package com.finalexam.to_do_list

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.SchemaSql
import com.finalexam.to_do_list.model.local.entity.UserEntity
import com.finalexam.to_do_list.model.repository.AccountWorkspace
import com.finalexam.to_do_list.model.repository.GuestCategoryTransfers
import com.finalexam.to_do_list.model.repository.WorkspaceSnapshot
import com.finalexam.to_do_list.model.repository.long
import com.finalexam.to_do_list.model.repository.rows
import com.finalexam.to_do_list.model.repository.string
import com.finalexam.to_do_list.model.repository.uuid
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AccountWorkspaceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun open(uid: String) =
        AccountWorkspace(uid, AppDatabase.accountBuilder(context, uid).allowMainThreadQueries().build())

    private fun <T> useGuest(name: String, action: (AppDatabase) -> T): T {
        val database = AppDatabase.guestBuilder(context, name).allowMainThreadQueries().build()
        return try { action(database) } finally { database.close() }
    }

    @Test fun migrateV3() {
        val uid = uuid()
        val dbName = AppDatabase.accountFileName(uid)
        val path = context.getDatabasePath(dbName)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            SchemaSql.createStatements.forEach {
                old.execSQL(it.replace(", " + '`' + "predecessor_id" + '`' + " TEXT, " +
                    '`' + "dispatched" + '`' + " INTEGER NOT NULL DEFAULT 0, " +
                    '`' + "membership_revision" + '`' + " INTEGER NOT NULL DEFAULT 0", ""))
            }
            old.execSQL("INSERT INTO users(uid,email,email_key,full_name) VALUES(?,?,?,'Account')",
                arrayOf(uid, "a@example.test", "a@example.test"))
            old.execSQL("INSERT INTO categories(id,owner_uid,name,name_key,created_at,client_updated_at,deleted_at) VALUES('old',?,'Old','old',1,1,3)", arrayOf(uid))
            old.version = 3
        }
        try {
            open(uid).use { store ->
                assertEquals(4, store.sql.version)
                assertEquals(3, store.category("old")!!.long("deleted_at").toInt())
                assertEquals("Account", store.database.users().find(uid)!!.fullName)
                assertTrue(store.sql.rows("SELECT * FROM local_category_copies").isEmpty())
                assertTrue(store.sql.rows("PRAGMA foreign_key_check").isEmpty())
            }
        } finally { context.deleteDatabase(dbName) }
    }

    @Test fun retryAfterReopen() {
        val uid = uuid()
        val id = uuid()
        try {
            open(uid).use { store ->
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                store.transaction {
                    store.createCategory("Học tập", id)
                    store.enqueue(id, "CATEGORY", id, "UPDATE", 0, mapOf("name" to "Nháp 1"))
                    val rows = store.commands()
                    store.sending(rows.last().string("operation_id"))
                    store.enqueue(id, "CATEGORY", id, "UPDATE", 0, mapOf("name" to "Nháp 2"))
                }
            }
            open(uid).use { store ->
                val rows = store.commands()
                assertEquals(3, rows.size)
                assertEquals("SENDING", rows[1]["status"])
                assertTrue(rows[1].string("payload_json").contains("Nháp 1"))
                assertEquals(rows[1]["operation_id"], rows[2]["predecessor_id"])
                store.acknowledge(rows[0], mapOf("revision" to 1))
                assertEquals(1L, store.commands()[0]["base_revision"])
                assertEquals(0L, store.commands()[1]["base_revision"])
                store.acknowledge(store.commands().first(), mapOf("revision" to 2))
                assertEquals(2L, store.commands().single()["base_revision"])
            }
        } finally { context.deleteDatabase(AppDatabase.accountFileName(uid)) }
    }

    @Test fun pendingPurge() {
        val uid = uuid()
        val cid = uuid()
        val tid = uuid()
        try {
            open(uid).use { store ->
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                val category = mapOf("id" to cid, "owner_uid" to uid, "name" to "Shared", "name_key" to "shared",
                    "created_at" to 1, "client_updated_at" to 1, "revision" to 1)
                val member = mapOf("category_id" to cid, "user_uid" to uid, "role" to "OWNER", "joined_at" to 1)
                val task = mapOf("id" to tid, "category_id" to cid, "created_by_uid" to uid, "title" to "Trashed",
                    "created_at" to 1, "client_updated_at" to 1, "deleted_at" to 2, "revision" to 2)
                val snapshot = WorkspaceSnapshot(category, "OWNER", 5,
                    mapOf("MEMBER" to listOf(member), "TASK" to listOf(task)), emptyList())
                store.apply(snapshot)
                assertEquals(1, store.trash().size)
                store.transaction {
                    store.sql.execSQL("DELETE FROM tasks WHERE id=?", arrayOf(tid))
                    store.enqueue(cid, "TASK", tid, "PURGE", 2)
                }
                store.apply(snapshot)
                assertNull(store.database.tasks().findIncludingDeleted(tid))
                assertNull(store.commands().single()["payload_json"])
            }
        } finally { context.deleteDatabase(AppDatabase.accountFileName(uid)) }
    }

    @Test fun transferAfterReopen() {
        val uid = uuid()
        val guestName = "transfer.db"
        val sourceTask = uuid()
        val sourceSubtask = uuid()
        try {
            val request = useGuest(guestName) { guest ->
                val guestUid = guest.tasks().guestUid()
                val sourceId = requireNotNull(guest.tasks().categoryId(guestUid, "học tập"))
                val sql = guest.openHelper.writableDatabase
                sql.execSQL("""INSERT INTO tasks(id,category_id,created_by_uid,title,created_at,client_updated_at,is_done,
                    completed_at,repeat_type,due_at,deleted_at) VALUES(?,?,?,'Task',144,158,1,155,'WEEKLY',200,160)""",
                    arrayOf(sourceTask, sourceId, guestUid))
                sql.execSQL("""INSERT INTO subtasks(id,task_id,title,is_done,client_updated_at,deleted_at)
                    VALUES(?,?,'Subtask',1,158,159)""", arrayOf(sourceSubtask, sourceTask))
                sql.execSQL("""INSERT INTO task_reminders(task_id,user_uid,remind_at,is_enabled,client_updated_at)
                    VALUES(?,?,156,1,158)""", arrayOf(sourceTask, guestUid))
                GuestCategoryTransfers(context, guest).begin(sourceId, uid, "Học tập").request
            }
            useGuest(guestName) { guest -> open(uid).use { store ->
                val guestTransfers = GuestCategoryTransfers(context, guest)
                assertEquals(request, guestTransfers.pending(uid).single())
                assertTrue(guestTransfers.pending("other").isEmpty())
                assertNull(guest.tasks().role(request.id, guest.tasks().guestUid()))
                assertNotNull(guest.tasks().findIncludingDeleted(sourceTask))
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                store.transferIn(guestTransfers.read(request))
            } }
            useGuest(guestName) { guest -> open(uid).use { store ->
                val guestTransfers = GuestCategoryTransfers(context, guest)
                store.transferIn(guestTransfers.read(request))
                assertEquals(uid, store.category(request.id)!!["owner_uid"])
                assertEquals("OWNER", store.role(request.id))
                val moved = store.sql.rows("SELECT * FROM tasks").single()
                assertEquals(sourceTask, moved["id"])
                assertEquals(uid, moved["created_by_uid"])
                assertEquals(155L, moved["completed_at"])
                assertEquals(160L, moved["deleted_at"])
                assertEquals("WEEKLY", moved["repeat_type"])
                assertEquals(sourceSubtask, store.sql.rows("SELECT id FROM subtasks").single()["id"])
                assertEquals(uid, store.sql.rows("SELECT user_uid FROM task_reminders").single()["user_uid"])
                val envelope = store.commands().map(store::envelope)
                assertFalse(envelope.toString().contains("local:"))
                assertEquals(listOf("CREATE", "CREATE", "CREATE", "TRASH", "CREATE", "TRASH"), envelope.map { it["kind"] })
                assertEquals(request.id, envelope.first()["category_id"])
                assertEquals(sourceTask, envelope.last()["entity_id"])
                assertTrue(store.categories().single().transferring)
                assertTrue(runCatching { store.requireRole(request.id, "OWNER") }.isFailure)
                assertNotNull(guest.tasks().findIncludingDeleted(sourceTask))
                while (store.commands().isNotEmpty()) store.acknowledge(store.commands().first(), mapOf("revision" to 1))
                guestTransfers.complete(request)
                store.clearTransfer(request.id)
                assertFalse(store.categories().single().transferring)
            } }
            useGuest(guestName) { guest ->
                assertNull(guest.tasks().findIncludingDeleted(sourceTask))
                assertNull(guest.tasks().categoryId(guest.tasks().guestUid(), "học tập"))
                assertTrue(GuestCategoryTransfers(context, guest).pending(uid).isEmpty())
            }
        } finally {
            context.deleteDatabase(AppDatabase.accountFileName(uid))
            context.deleteDatabase(guestName)
        }
    }

    @Test fun rejectedTransferRestoresLocalSource() {
        val uid = uuid()
        val guestName = "reject.db"
        try {
            useGuest(guestName) { guest ->
                val guestUid = guest.tasks().guestUid()
                val id = requireNotNull(guest.tasks().categoryId(guestUid, "học tập"))
                val transfers = GuestCategoryTransfers(context, guest)
                val source = transfers.begin(id, uid, "Học tập")
                open(uid).use { store ->
                    store.database.users().upsert(UserEntity(uid, "", "", "A"))
                    store.transferIn(source)
                    val create = store.commands().single()
                    store.failed(create, "NAME_ALREADY_EXISTS", retry = false)
                    store.releaseRejectedTransfer(id)
                    transfers.release(source.request)
                    assertEquals("OWNER", guest.tasks().role(id, guestUid))
                    assertTrue(transfers.pending(uid).isEmpty())
                    assertNull(store.category(id))
                    assertEquals("NAME_ALREADY_EXISTS", store.commands().single()["last_error"])
                    store.transferIn(transfers.begin(id, uid, "Tên khác"))
                    assertEquals("Tên khác", store.category(id)!!["name"])
                    assertEquals("PENDING", store.commands().single()["status"])
                }
            }
        } finally {
            context.deleteDatabase(AppDatabase.accountFileName(uid))
            context.deleteDatabase(guestName)
        }
    }
}
