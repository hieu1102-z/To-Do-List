package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.repository.GuestTaskRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GuestTaskRepositoryTest : DatabaseTestBase() {
    private fun repository() = GuestTaskRepository(context, database, Executor { it.run() }, { it() })

    @Test fun editReminderOptions() {
        val repo = repository()
        val due = System.currentTimeMillis() + 172_800_000L
        success { repo.create("Before", "", "to do", 2, due, null, it) }
        val task = rows(repo).single()
        success { repo.toggle(task.id, it) }
        execute("UPDATE tasks SET repeat_type='WEEKLY' WHERE id=?", task.id)
        success { repo.updateWithReminder(task.id, "After", "New notes", "Học tập", 3, due, 60L, it) }
        val changed = rows(repo).single()
        assertEquals(task.id, changed.id)
        assertEquals(task.createdAt, changed.createdAt)
        assertTrue(changed.done)
        assertEquals("WEEKLY", changed.repeatType)
        assertEquals("New notes", changed.description)
        assertEquals("Học tập", changed.category)
        assertEquals(due - 3_600_000L, changed.remindAt)
        assertEquals(false, changed.reminderEnabled)
        success { repo.toggle(task.id, it) }
        success { repo.updateWithReminder(task.id, "After", "New notes", "to do", 1, due, null, it) }
        reopen()
        assertNull(rows(repository()).single().remindAt)
        assertEquals(1, count("tasks"))
    }

    @Test fun detailProjection() {
        val repo = repository()
        val due = System.currentTimeMillis() + 172_800_000L
        success { repo.create("Detail", "Description", "Cá nhân", 3, due, 60L, it) }
        val saved = rows(repo).single()
        execute("UPDATE tasks SET repeat_type='WEEKLY' WHERE id=?", saved.id)
        user("participant"); member("participant")
        execute("UPDATE users SET full_name='Người nhận thật' WHERE uid='participant'")
        execute("INSERT INTO task_participants(category_id,task_id,user_uid,joined_at) VALUES(?,?,?,10)", category, saved.id, "participant")
        val detail = rows(repo).single()
        assertEquals("WEEKLY", detail.repeatType)
        assertEquals(due - 3_600_000L, detail.remindAt)
        assertEquals("Người nhận thật", detail.participantNames)
        assertEquals("Description", detail.description)
        success { repo.setNotificationsEnabled(false, it) }
        assertEquals(false, rows(repo).single().reminderEnabled)
        reopen()
        assertEquals("Người nhận thật", rows(repository()).single().participantNames)
        execute("UPDATE category_members SET status='REMOVED' WHERE user_uid='participant'")
        assertNull(rows(repository()).single().participantNames)
    }

    @Test fun unclassifiedPersists() {
        val repo = repository()
        success { repo.create("Unclassified", "", "to do", 2, null, null, it) }
        success { repo.create("Another", "", "to do", 2, null, null, it) }
        assertTrue(rows(repo).all { it.category == "to do" })
        assertEquals("1", scalar("SELECT COUNT(*) FROM categories WHERE name_key='to do'"))
        assertEquals("1", scalar("SELECT COUNT(*) FROM category_members m JOIN categories c ON c.id=m.category_id WHERE c.name_key='to do' AND m.role='OWNER' AND m.status='ACTIVE'"))
        val saved = rows(repo).first()
        reopen()
        val reopened = repository()
        assertEquals(2, rows(reopened).size)
        success { reopened.save(saved.id, "Moved", "", "Học tập", 2, null, it) }
        assertEquals("Học tập", rows(reopened).first { it.id == saved.id }.category)
        success { reopened.save(saved.id, "Back", "", "to do", 2, null, it) }
        assertEquals("to do", rows(reopened).first { it.id == saved.id }.category)
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun createOptions() {
        val repo = repository()
        val due = System.currentTimeMillis() + 172_800_000L
        success { repo.create("New task", "Notes", "Học tập", 3, due, 60L, it) }
        val saved = rows(repo).single()
        assertEquals("Học tập", saved.category)
        assertEquals(3, saved.priority)
        assertEquals(due, saved.dueAt)
        assertEquals((due - 3_600_000L).toString(), scalar("SELECT remind_at FROM task_reminders"))
        reopen()
        assertEquals(saved.id, rows(repository()).single().id)
        assertEquals(1, count("task_reminders"))
    }

    @Test fun createWithoutReminder() {
        val repo = repository()
        success { repo.create("No reminder", "", "Mua sắm", 1, System.currentTimeMillis() + 86_400_000L, null, it) }
        assertEquals(1, count("tasks"))
        assertEquals(0, count("task_reminders"))
        val id = rows(repo).single().id
        success { repo.toggle(id, it) }
        success { repo.toggle(id, it) }
        assertEquals(0, count("task_reminders"))
    }

    @Test fun reminderSurvivesEdits() {
        val repo = repository()
        val due = System.currentTimeMillis() + 172_800_000L
        success { repo.create("Reminder", "", "Cá nhân", 2, due, 60L, it) }
        val id = rows(repo).single().id
        success { repo.toggle(id, it) }
        success { repo.toggle(id, it) }
        assertEquals((due - 3_600_000L).toString(), scalar("SELECT remind_at FROM task_reminders"))
        success { repo.save(id, "Renamed", "", "Cá nhân", 2, due + 60_000L, it) }
        assertEquals((due - 3_540_000L).toString(), scalar("SELECT remind_at FROM task_reminders"))
        success { repo.setNotificationsEnabled(false, it) }
        success { repo.setNotificationsEnabled(true, it) }
        assertEquals((due - 3_540_000L).toString(), scalar("SELECT remind_at FROM task_reminders"))
    }

    @Test fun saveOnceWhileBusy() {
        val pending = java.util.ArrayDeque<Runnable>()
        val repo = GuestTaskRepository(context, database, Executor { pending.add(it) }, { it() })
        val model = com.finalexam.to_do_list.controller.home.AddTaskViewModel()
        repeat(2) { model.save { saved -> repo.create("One task", "", "Cá nhân", 2, null, null, saved) } }
        assertEquals(1, pending.size)
        assertEquals(com.finalexam.to_do_list.controller.home.AddTaskViewModel.SaveState.SAVING, model.state.value)
        pending.remove().run()
        assertEquals(1, count("tasks"))
        assertEquals(com.finalexam.to_do_list.controller.home.AddTaskViewModel.SaveState.SAVED, model.state.value)
    }

    @Test fun restoreKeepsPreviouslyTrashedChildren() {
        val repo = GuestTaskRepository(context, database, Executor { it.run() }, { it() })
        task("active-task")
        execute("INSERT INTO subtasks(id,task_id,title,client_updated_at,deleted_at) VALUES('gone','active-task','Subtask',10,11)")
        success { repo.delete("active-task", it) }
        success { repo.restoreTask("active-task", it) }
        assertEquals("11", scalar("SELECT deleted_at FROM subtasks WHERE id='gone'"))

        task("trashed-task")
        execute("UPDATE tasks SET deleted_at=12 WHERE id='trashed-task'")
        success { repo.deleteCategory("Cá nhân", callback = it) }
        assertNull(scalar("SELECT deleted_at FROM tasks WHERE id='active-task'"))
        var categories = emptyList<com.finalexam.to_do_list.model.local.relation.TrashTask>()
        repo.trash { categories = it.getOrThrow() }
        val categoryItem = categories.single { it.entityType == "CATEGORY" }
        success { repo.changeCategoryTrash(categoryItem, false, null, it) }
        assertEquals("12", scalar("SELECT deleted_at FROM tasks WHERE id='trashed-task'"))
        assertEquals("11", scalar("SELECT deleted_at FROM subtasks WHERE id='gone'"))
    }
    private fun rows(repo: GuestTaskRepository): List<HomeTask> {
        var result: Result<List<HomeTask>>? = null
        repo.load { result = it }
        return result!!.getOrThrow()
    }
    private fun success(operation: ((Result<Unit>) -> Unit) -> Unit) {
        var result: Result<Unit>? = null
        operation { result = it }
        result!!.getOrThrow()
    }

    @Test fun guestCrudUsesNormalizedTablesAndSurvivesReopenWithoutAnAccount() {
        var repo = repository()
        success { repo.save(null, "  Offline work  ", " Notes ", "Học tập", 3, 500L, it) }
        val saved = rows(repo).single()
        assertEquals("Offline work", saved.title)
        assertEquals("Notes", saved.description)
        assertEquals("Học tập", saved.category)
        assertTrue(database.tasks().find(saved.id)!!.createdByUid.startsWith("local:"))
        success { repo.save(saved.id, "Edited", "", "Học tập", 1, null, it) }
        assertEquals(saved.id, rows(repo).single().id)
        assertEquals(saved.createdAt, rows(repo).single().createdAt)
        success { repo.toggle(saved.id, it) }
        assertTrue(rows(repo).single().done)
        assertNotNull(rows(repo).single().completedAt)
        reopen(); repo = repository()
        assertTrue(rows(repo).single().done)
        success { repo.toggle(saved.id, it) }
        assertFalse(rows(repo).single().done)
        assertNull(rows(repo).single().completedAt)
        success { repo.delete(saved.id, it) }
        assertTrue(rows(repo).isEmpty())
        assertEquals(0, count("sync_queue"))
        assertEquals(0, count("sync_state"))
    }

    @Test fun invalidOrUnauthorizedWritesFailAndNeverCreateOrResurrectRows() {
        val repo = repository()
        var result: Result<Unit>? = null
        repo.save(null, "  ", "", "Cá nhân", 2, null) { result = it }
        assertTrue(result!!.isFailure)
        repo.save("missing", "Task", "", "Cá nhân", 2, null) { result = it }
        assertTrue(result!!.isFailure)
        repo.save(null, "Task", "", "Cá nhân", 4, null) { result = it }
        assertTrue(result!!.isFailure)
        execute("UPDATE category_members SET role='VIEWER' WHERE category_id=?", category)
        repo.save(null, "Task", "", "Cá nhân", 2, null) { result = it }
        assertTrue(result!!.isFailure)
        assertEquals(0, count("tasks"))
    }
}
