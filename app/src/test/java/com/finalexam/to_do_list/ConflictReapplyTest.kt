package com.finalexam.to_do_list

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.entity.UserEntity
import com.finalexam.to_do_list.model.repository.AccountWorkspace
import com.finalexam.to_do_list.model.repository.Row
import com.finalexam.to_do_list.model.repository.WorkspaceSnapshot
import com.finalexam.to_do_list.model.repository.asRow
import com.finalexam.to_do_list.model.repository.defaultCategoryId
import com.finalexam.to_do_list.model.repository.long
import com.finalexam.to_do_list.model.repository.reapplyRejectedChange
import com.finalexam.to_do_list.model.repository.rows
import com.finalexam.to_do_list.model.repository.string
import com.finalexam.to_do_list.model.repository.syncConflictRows
import com.finalexam.to_do_list.model.repository.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConflictReapplyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val earlierDue = 1_793_000_000_000L
    private val serverDue = 1_794_000_000_000L

    @Test fun reappliesOnlyTitle() = withConflict { conflict ->
        val current = task(conflict.taskId)
        assertTrue("title" in (syncConflictRows().single()["reapply_fields"] as Set<*>))

        reapplyRejectedChange(conflict.operationId, 2, setOf("title"))

        val replacement = commands().single()
        assertNotEquals(conflict.operationId, replacement["operation_id"])
        assertEquals("PENDING", replacement["status"])
        assertEquals(2L, replacement["base_revision"])
        assertEquals(1L, replacement["membership_revision"])
        assertNull(replacement["predecessor_id"])
        assertEquals("UPDATE", envelope(replacement)["kind"])
        assertEquals(mapOf("title" to "Bản nháp A"), envelope(replacement)["payload"])
        val updated = task(conflict.taskId)
        assertEquals("Bản nháp A", updated["title"])
        assertEquals(current["due_at"], updated["due_at"])
        assertEquals(current["priority"], updated["priority"])
        assertTrue(syncConflictRows().isEmpty())
    }

    @Test fun staleViewKeepsDraft() = withConflict { conflict ->
        val next = conflict.snapshot.copy(seq = 3, groups = conflict.snapshot.groups + (
            "TASK" to listOf(task(conflict.taskId) + mapOf("revision" to 3, "title" to "Bản mới hơn"))
        ))
        assertTrue(apply(next))
        val draft = commands().single()

        assertTrue(runCatching {
            reapplyRejectedChange(conflict.operationId, 2, setOf("title"))
        }.isFailure)

        assertEquals(draft, commands().single())
        assertEquals("Bản mới hơn", task(conflict.taskId)["title"])
    }

    @Test fun deleteFailureRollsBack() = withConflict { conflict ->
        val current = task(conflict.taskId)
        val draft = commands().single()
        sql.execSQL("""
            CREATE TRIGGER block_draft_delete BEFORE DELETE ON sync_queue
            WHEN OLD.status = 'REJECTED'
            BEGIN
                SELECT RAISE(ABORT, 'Draft deletion failed');
            END
        """.trimIndent())

        assertTrue(runCatching {
            reapplyRejectedChange(conflict.operationId, 2, setOf("title"))
        }.isFailure)

        assertEquals(draft, commands().single())
        assertEquals(current, task(conflict.taskId))
    }

    @Test fun newConflictKeepsPatch() = withConflict { conflict ->
        reapplyRejectedChange(conflict.operationId, 2, setOf("title"))
        val replacement = commands().single()
        sending(replacement.string("operation_id"))
        failed(replacement, "REVISION_CONFLICT", retry = false)

        val newer = conflict.snapshot.copy(seq = 3, groups = conflict.snapshot.groups + (
            "TASK" to listOf(task(conflict.taskId) + mapOf("revision" to 3, "title" to "Bản mới nhất"))
        ))
        assertTrue(apply(newer))
        val rejected = syncConflictRows().single()
        assertEquals(replacement["operation_id"], rejected["operation_id"])
        assertEquals("REVISION_CONFLICT", rejected["last_error"])
        assertEquals(mapOf("title" to "Bản nháp A"), rejected["draft"])
        assertEquals("Bản mới nhất", (rejected["current"] as Map<*, *>)["title"])
    }

    @Test fun dueDateUpdatesMonthlyAnchor() = withConflict { conflict ->
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val day = Instant.ofEpochMilli(earlierDue).atZone(zone).dayOfMonth
        val monthly = task(conflict.taskId) + mapOf("revision" to 3, "repeat_type" to "MONTHLY",
            "repeat_anchor_day" to if (day == 1) 2 else 1, "timezone" to zone.id)
        assertTrue(apply(conflict.snapshot.copy(seq = 3, groups = conflict.snapshot.groups + ("TASK" to listOf(monthly)))))

        reapplyRejectedChange(conflict.operationId, 3, setOf("due_at"))

        val patch = envelope(commands().single()).getValue("payload").asRow()
        assertEquals(setOf("due_at", "repeat_anchor_day"), patch.keys)
        assertEquals(earlierDue, patch.long("due_at"))
        assertEquals(day.toLong(), patch.long("repeat_anchor_day"))
        val updated = task(conflict.taskId)
        assertEquals("MONTHLY", updated["repeat_type"])
        assertEquals(zone.id, updated["timezone"])
        assertEquals(day.toLong(), updated["repeat_anchor_day"])
    }

    @Test fun cardIdentifiesCategoryOwner() = withConflict { conflict ->
        val local = syncConflictRows().single()
        assertEquals("Bản B", local["target_label"])
        assertEquals("Shared", local["category_label"])

        val cid = conflict.snapshot.category.string("id")
        val otherUid = uuid()
        database.users().upsert(UserEntity(otherUid, "b@example.test", "b@example.test", "Minh"))
        sql.execSQL("UPDATE categories SET owner_uid=? WHERE id=?", arrayOf(otherUid, cid))
        sql.execSQL("UPDATE category_members SET role='EDITOR' WHERE category_id=? AND user_uid=?", arrayOf(cid, uid))
        upsert("category_members", mapOf("category_id" to cid, "user_uid" to otherUid, "role" to "OWNER", "joined_at" to 1))

        val shared = syncConflictRows().single()
        assertEquals("Bản B", shared["target_label"])
        assertEquals("Shared · Minh", shared["category_label"])
    }

    @Test fun cardHidesDefaultCategory() = withConflict(useDefault = true) {
        assertEquals("Không có danh mục", syncConflictRows().single()["category_label"])
    }

    @Test fun cardShowsParentTask() = withConflict { conflict ->
        val cid = conflict.snapshot.category.string("id")
        val subtaskId = uuid()
        upsert("subtasks", mapOf("id" to subtaskId, "task_id" to conflict.taskId,
            "title" to "Công việc con", "client_updated_at" to 1, "revision" to 1))
        enqueue(cid, "SUBTASK", "${conflict.taskId}/$subtaskId", "UPDATE", 1, mapOf("title" to "Tên mới"))
        failed(commands().last(), "REVISION_CONFLICT", retry = false)

        val card = syncConflictRows().single { it["entity_type"] == "SUBTASK" }
        assertEquals("Bản B", card["target_label"])
        assertEquals("Shared", card["category_label"])
    }

    private fun withConflict(useDefault: Boolean = false, test: AccountWorkspace.(TestConflict) -> Unit) {
        val accountId = uuid()
        val dbName = AppDatabase.accountFileName(accountId)
        try {
            AccountWorkspace(accountId, AppDatabase.accountBuilder(context, accountId).allowMainThreadQueries().build()).use { store ->
                store.database.users().upsert(UserEntity(accountId, "a@example.test", "a@example.test", "A"))
                val cid = if (useDefault) defaultCategoryId(accountId) else uuid()
                val taskId = uuid()
                val category = mapOf("id" to cid, "owner_uid" to accountId,
                    "name" to if (useDefault) "Mặc định" else "Shared", "name_key" to if (useDefault) "" else "shared",
                    "created_at" to 1, "client_updated_at" to 1, "revision" to 1)
                val member = mapOf("category_id" to cid, "user_uid" to accountId, "role" to "OWNER",
                    "joined_at" to 1, "revision" to 1)
                val task = mapOf("id" to taskId, "category_id" to cid, "created_by_uid" to accountId, "title" to "Bản gốc",
                    "created_at" to 1, "client_updated_at" to 1, "revision" to 1, "due_at" to earlierDue, "priority" to 2)
                val initial = WorkspaceSnapshot(category, "OWNER", 1,
                    mapOf("MEMBER" to listOf(member), "TASK" to listOf(task)), emptyList())
                assertTrue(store.apply(initial))

                val draft = mapOf("title" to "Bản nháp A", "due_at" to earlierDue, "priority" to 2)
                store.transaction {
                    store.upsert("tasks", task + draft)
                    store.enqueue(cid, "TASK", taskId, "UPDATE", 1, draft)
                }
                val rejected = store.commands().single()
                store.sending(rejected.string("operation_id"))
                store.failed(rejected, "REVISION_CONFLICT", retry = false)

                val serverTask = task + mapOf("title" to "Bản B", "due_at" to serverDue, "priority" to 3, "revision" to 2)
                val server = initial.copy(seq = 2, groups = initial.groups + ("TASK" to listOf(serverTask)))
                assertTrue(store.apply(server))
                assertEquals(serverDue, store.task(taskId).long("due_at"))
                assertEquals(3L, store.task(taskId).long("priority"))
                store.test(TestConflict(taskId, rejected.string("operation_id"), server))
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun AccountWorkspace.task(id: String): Row = sql.rows("SELECT * FROM tasks WHERE id=?", id).single()

    private data class TestConflict(val taskId: String, val operationId: String, val snapshot: WorkspaceSnapshot)
}
