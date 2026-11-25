package com.finalexam.to_do_list

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.entity.UserEntity
import com.finalexam.to_do_list.model.repository.AccountWorkspace
import com.finalexam.to_do_list.model.repository.Row
import com.finalexam.to_do_list.model.repository.WorkspaceSnapshot
import com.finalexam.to_do_list.model.repository.string
import com.finalexam.to_do_list.model.repository.syncConflictRows
import com.finalexam.to_do_list.model.repository.uuid
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RestoreDisplayContextTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val taskTitle = "Nộp bài"
    private val categoryLabel = "Việc chung · Minh"

    @Test fun restoreKeepsLocalLabels() = withRestore { uid, taskId, snapshot ->
        open(uid).use { store ->
            val command = store.commands().single()
            val body = JSONObject(command.string("payload_json"))
            val display = body.getJSONObject("display_context")
            assertEquals(taskTitle, display.getString("task_title"))
            assertEquals(categoryLabel, display.getString("category_label"))

            body.remove("display_context")
            val legacyCommand = command + ("payload_json" to body.toString())
            val request = store.envelope(command)
            assertEquals(store.envelope(legacyCommand), request)
            assertEquals("RESTORE", request["kind"])
            assertEquals(taskId, request["entity_id"])
            assertEquals(emptyMap<String, Any?>(), request["payload"])
            assertEquals(taskTitle, store.database.tasks().find(taskId)?.title)

            store.sending(command.string("operation_id"))
            store.failed(command, "NOT_FOUND", retry = false)
            val afterPurge = snapshot.copy(seq = 3, groups = snapshot.groups + ("TASK" to emptyList<Row>()))
            assertTrue(store.apply(afterPurge))
            assertNull(store.database.tasks().findIncludingDeleted(taskId))

            val issue = store.syncConflictRows().single()
            assertEquals("NOT_FOUND", issue["last_error"])
            assertEquals("RESTORE", issue["kind"])
            assertEquals(taskTitle, issue["target_label"])
            assertEquals(taskTitle, issue["restore_title"])
            assertEquals(categoryLabel, issue["category_label"])
            assertNull(issue["current"])
        }
    }

    @Test fun restoreAckRemovesContext() = withRestore { uid, taskId, _ ->
        open(uid).use { store ->
            val command = store.commands().single()
            store.acknowledge(command, mapOf("revision" to 3L, "seq" to 3L))

            assertTrue(store.commands().isEmpty())
            assertTrue(store.syncConflictRows().isEmpty())
            assertEquals(3L, store.database.tasks().find(taskId)?.revision)
        }
    }

    @Test fun legacyWaitsForSnapshot() = withRestore { uid, taskId, snapshot ->
        open(uid).use { store ->
            val command = store.commands().single()
            store.failed(command, "NOT_IN_TRASH", retry = false)
            assertNull(store.syncConflictRows().single()["current"])

            val restored = snapshot.groups.getValue("TASK").single() + mapOf("revision" to 3, "deleted_at" to null)
            val newSnapshot = snapshot.copy(seq = 3, groups = snapshot.groups + ("TASK" to listOf(restored)))
            assertTrue(store.apply(newSnapshot))
            assertEquals(taskId, (store.syncConflictRows().single()["current"] as Map<*, *>)["id"])
        }
    }

    private fun withRestore(verify: (String, String, WorkspaceSnapshot) -> Unit) {
        val uid = uuid()
        val ownerUid = uuid()
        val categoryId = uuid()
        val taskId = uuid()
        val category = mapOf("id" to categoryId, "owner_uid" to ownerUid, "name" to "Việc chung",
            "name_key" to "việc chung", "revision" to 1, "created_at" to 1, "client_updated_at" to 1)
        val members = listOf(
            mapOf("category_id" to categoryId, "user_uid" to ownerUid, "role" to "OWNER", "joined_at" to 1, "revision" to 1),
            mapOf("category_id" to categoryId, "user_uid" to uid, "role" to "EDITOR", "joined_at" to 1, "revision" to 1)
        )
        val task = mapOf("id" to taskId, "category_id" to categoryId, "created_by_uid" to ownerUid,
            "title" to taskTitle, "revision" to 2, "created_at" to 1, "client_updated_at" to 2, "deleted_at" to 2)
        val snapshot = WorkspaceSnapshot(category, "EDITOR", 2,
            mapOf("MEMBER" to members, "TASK" to listOf(task)), emptyList())

        try {
            open(uid).use { store ->
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                store.database.users().upsert(UserEntity(ownerUid, "b@example.test", "b@example.test", "Minh"))
                assertTrue(store.apply(snapshot))
                store.transaction { store.changeTrash(store.trash().single(), purge = false, name = null) }
            }
            verify(uid, taskId, snapshot)
        } finally {
            context.deleteDatabase(AppDatabase.accountFileName(uid))
        }
    }

    private fun open(uid: String) =
        AccountWorkspace(uid, AppDatabase.accountBuilder(context, uid).allowMainThreadQueries().build())
}
