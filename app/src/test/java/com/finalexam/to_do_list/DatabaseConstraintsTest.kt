package com.finalexam.to_do_list

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DatabaseConstraintsTest : DatabaseTestBase() {
    @Test fun taskDefaultsAndEveryTaskCheckAreEnforcedBySqlite() {
        task()
        assertEquals("2", scalar("SELECT priority FROM tasks"))
        assertEquals("0", scalar("SELECT is_done FROM tasks"))
        assertEquals("NONE", scalar("SELECT repeat_type FROM tasks"))
        assertEquals("Asia/Ho_Chi_Minh", scalar("SELECT timezone FROM tasks"))
        assertNull(scalar("SELECT repeat_anchor_day FROM tasks"))
        listOf("title=''", "title='   '", "title='${"x".repeat(201)}'", "priority=0", "priority=4", "is_done=2",
            "repeat_type='YEARLY'", "repeat_anchor_day=0", "repeat_anchor_day=32", "is_done=1", "completed_at=10")
            .forEach { rejected("UPDATE tasks SET $it WHERE id='task'") }
        execute("UPDATE tasks SET is_done=1,completed_at=30 WHERE id='task'")
        rejected("UPDATE tasks SET completed_at=NULL WHERE id='task'")
        execute("UPDATE tasks SET is_done=0,completed_at=NULL WHERE id='task'")
        rejected("UPDATE tasks SET category_id='missing' WHERE id='task'")
        rejected("UPDATE tasks SET created_by_uid='missing' WHERE id='task'")
        rejected("UPDATE tasks SET title=NULL WHERE id='task'")
        assertEquals("0", scalar("SELECT revision FROM tasks"))
        assertEquals("0", scalar("SELECT server_seq FROM tasks"))
    }

    @Test fun checksForUsersMembersInvitesSubtasksAndRemindersRejectInvalidValues() {
        rejected("UPDATE users SET email_verified=2")
        rejected("UPDATE category_members SET role='ADMIN'")
        rejected("UPDATE category_members SET status='PENDING'")
        invite("invite")
        rejected("UPDATE category_invitations SET offered_role='OWNER'")
        rejected("UPDATE category_invitations SET status='UNKNOWN'")
        rejected("UPDATE category_invitations SET invitee_uid='missing'")
        task()
        execute("INSERT INTO subtasks(id,task_id,title,client_updated_at) VALUES('sub','task','Sub',10)")
        listOf("title=''", "title='   '", "title='${"x".repeat(201)}'", "is_done=2").forEach { rejected("UPDATE subtasks SET $it") }
        execute("INSERT INTO task_reminders(task_id,user_uid,remind_at,client_updated_at) VALUES('task',?,50,10)", uid)
        assertEquals("1", scalar("SELECT is_enabled FROM task_reminders"))
        rejected("UPDATE task_reminders SET is_enabled=2")
        rejected("UPDATE task_reminders SET user_uid='missing'")
        rejected("DELETE FROM users WHERE uid='$uid'")
    }

    @Test fun uniquenessAllowsHistoricalInvitationsAndInactiveOwners() {
        user("other")
        member("other", role = "OWNER", status = "REMOVED")
        rejected("UPDATE category_members SET status='ACTIVE' WHERE user_uid='other'")
        execute("UPDATE category_members SET role='EDITOR',status='ACTIVE' WHERE user_uid='other'")
        invite("old", "ACCEPTED")
        invite("older", "REJECTED")
        invite("new")
        rejected("UPDATE category_invitations SET status='PENDING' WHERE id='old'")
        execute("UPDATE category_invitations SET status='EXPIRED' WHERE id='new'")
        invite("replacement")
        assertEquals(4, count("category_invitations"))
        rejected("UPDATE categories SET name_key=(SELECT name_key FROM categories WHERE id='$category') WHERE id='$study'")
        rejected("INSERT INTO category_members(category_id,user_uid,role,joined_at) VALUES('$category','$uid','EDITOR',10)")
        rejected("INSERT INTO category_invitations SELECT * FROM category_invitations WHERE id='old'")
        reopen()
        rejected("UPDATE category_members SET role='OWNER' WHERE user_uid='other'")
        rejected("UPDATE category_invitations SET status='PENDING' WHERE id='old'")
    }

    @Test fun participantsMustReferenceTaskAndMembershipInTheSameCategory() {
        task()
        user("member")
        member("member", categoryId = study)
        rejected("INSERT INTO task_participants(category_id,task_id,user_uid,joined_at) VALUES('$category','task','member',10)")
        rejected("INSERT INTO task_participants(category_id,task_id,user_uid,joined_at) VALUES('$study','task','member',10)")
        member("member")
        execute("INSERT INTO task_participants(category_id,task_id,user_uid,joined_at) VALUES(?,'task','member',10)", category)
        execute("INSERT INTO task_participants(category_id,task_id,user_uid,joined_at) VALUES(?,'task',?,10)", category, uid)
        assertEquals(2, count("task_participants"))
        rejected("INSERT INTO task_participants(category_id,task_id,user_uid,joined_at) VALUES('$category','task','member',10)")
        execute("DELETE FROM category_members WHERE category_id=? AND user_uid='member'", category)
        assertEquals(1, count("task_participants"))
        assertEquals(1, count("tasks"))
    }

    private fun addTaskChildren() {
        task()
        execute("INSERT INTO subtasks(id,task_id,title,client_updated_at) VALUES('sub','task','Sub',10)")
        execute("INSERT INTO task_reminders(task_id,user_uid,remind_at,client_updated_at) VALUES('task',?,50,10)", uid)
        execute("INSERT INTO task_participants(category_id,task_id,user_uid,joined_at) VALUES(?,'task',?,10)", category, uid)
    }

    @Test fun taskDeletePhysicallyCascadesAllTaskChildren() {
        addTaskChildren()
        execute("DELETE FROM tasks WHERE id='task'")
        listOf("tasks", "subtasks", "task_reminders", "task_participants").forEach { assertEquals(it, 0, count(it)) }
        assertEquals(4, count("categories"))
    }

    @Test fun categoryDeleteCascadesBusinessRowsButRetainsRoutingMetadata() {
        addTaskChildren()
        invite("invite")
        execute("INSERT INTO sync_queue(operation_id,actor_uid,category_id,entity_type,entity_id,action,client_occurred_at) VALUES('operation',?,?,'CATEGORY',?,'DELETE',10)", uid, category, category)
        execute("INSERT INTO sync_state(user_uid,category_id) VALUES(?,?)", uid, category)
        execute("DELETE FROM categories WHERE id=?", category)
        listOf("tasks", "subtasks", "task_reminders", "task_participants", "category_invitations").forEach { assertEquals(it, 0, count(it)) }
        assertEquals(3, count("category_members"))
        assertEquals(1, count("sync_queue"))
        assertEquals(1, count("sync_state"))
        assertNull(scalar("SELECT payload_json FROM sync_queue"))
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun outboxChecksAndCompositeMetadataKeysMatchProtocol() {
        execute("INSERT INTO sync_queue(operation_id,actor_uid,category_id,entity_type,entity_id,action,client_occurred_at) VALUES('op',?,'absent','TASK','task','DELETE',10)", uid)
        listOf("payload_json='{}'", "action='UPDATE'", "action='CREATE'", "action='UPSERT'", "status='UNKNOWN'")
            .forEach { rejected("UPDATE sync_queue SET $it") }
        execute("UPDATE sync_queue SET action='UPDATE',payload_json='{}'")
        rejected("UPDATE sync_queue SET payload_json=NULL")
        execute("UPDATE sync_queue SET action='CREATE'")
        execute("UPDATE sync_queue SET action='DELETE',payload_json=NULL,status='ACKED'")
        rejected("UPDATE sync_queue SET actor_uid='missing'")
        execute("INSERT INTO sync_state(user_uid,category_id) VALUES(?,'absent')", uid)
        assertEquals("0", scalar("SELECT last_server_seq FROM sync_state"))
        rejected("INSERT INTO sync_state(user_uid,category_id) VALUES('$uid','absent')")
        rejected("UPDATE sync_state SET user_uid='missing'")
    }
}
