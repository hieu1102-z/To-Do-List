package com.finalexam.to_do_list

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.SchemaSql
import com.finalexam.to_do_list.model.local.entity.UserEntity
import com.finalexam.to_do_list.model.repository.AccountWorkspace
import com.finalexam.to_do_list.model.repository.WorkspaceSnapshot
import com.finalexam.to_do_list.model.repository.defaultCategoryId
import com.finalexam.to_do_list.model.repository.long
import com.finalexam.to_do_list.model.repository.rows
import com.finalexam.to_do_list.model.repository.string
import com.finalexam.to_do_list.model.repository.uuid
import org.json.JSONObject
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

    @Test fun migrateV3() {
        val uid = uuid()
        val dbName = AppDatabase.accountFileName(uid)
        val path = context.getDatabasePath(dbName)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            SchemaSql.createStatements.forEach {
                old.execSQL(it.replace(", " + '`' + "predecessor_id" + '`' + " TEXT, " +
                    '`' + "dispatched" + '`' + " INTEGER NOT NULL DEFAULT 0, " +
                    '`' + "membership_revision" + '`' + " INTEGER NOT NULL DEFAULT 0", "").replace(", `icon` TEXT", ""))
            }
            old.execSQL("INSERT INTO users(uid,email,email_key,full_name) VALUES(?,?,?,'Account')",
                arrayOf(uid, "a@example.test", "a@example.test"))
            old.execSQL("INSERT INTO categories(id,owner_uid,name,name_key,created_at,client_updated_at,deleted_at) VALUES('old',?,'Old','old',1,1,3)", arrayOf(uid))
            old.version = 3
        }
        try {
            open(uid).use { store ->
                assertEquals(6, store.sql.version)
                assertEquals(3, store.category("old")!!.long("deleted_at").toInt())
                assertEquals("Account", store.database.users().find(uid)!!.fullName)
                assertTrue(store.sql.rows("SELECT name FROM sqlite_master WHERE name LIKE 'local_category_%'").isEmpty())
                assertTrue(store.sql.rows("PRAGMA foreign_key_check").isEmpty())
            }
        } finally { context.deleteDatabase(dbName) }
    }

    @Test fun categoryStyle() {
        val uid = uuid()
        val cid = uuid()
        try {
            open(uid).use { store ->
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                store.transaction { store.createCategory("Study", cid, "#287853", "book") }
            }
            open(uid).use { store ->
                val pending = store.categories().single()
                assertEquals("#287853", pending.color)
                assertEquals("book", pending.icon)
                val command = store.commands().single()
                val payload = store.envelope(command)["payload"] as Map<*, *>
                assertEquals("#287853", payload["color"])
                assertEquals("book", payload["icon"])
                store.acknowledge(command, mapOf("revision" to 1))
                val syncedCategory = checkNotNull(store.category(cid)) + mapOf("icon" to "star", "color" to "#7951B8")
                val member = mapOf("category_id" to cid, "user_uid" to uid, "role" to "OWNER", "joined_at" to 1)
                store.apply(WorkspaceSnapshot(syncedCategory, "OWNER", 1, mapOf("MEMBER" to listOf(member)), emptyList()))
                val synced = store.categories().single()
                assertEquals("#7951B8", synced.color)
                assertEquals("star", synced.icon)
            }
        } finally { context.deleteDatabase(AppDatabase.accountFileName(uid)) }
    }

    @Test fun editCategory() {
        val uid = uuid()
        try {
            open(uid).use { store ->
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                val cid = store.transaction { store.createCategory("Study", color = "#287853", icon = "book") }
                store.acknowledge(store.commands().single(), mapOf("revision" to 1))
                val original = store.categories().single()

                val updated = store.transaction { store.updateCategory(original, "  Plans  ", "#7951B8", null) }

                assertEquals(original.copy(name = "Plans", color = "#7951B8", icon = null), updated)
                assertEquals(updated, store.categories().single())
                assertEquals("plans", store.category(cid)?.get("name_key"))
                val command = store.commands().single()
                assertEquals(1L, command.long("base_revision"))
                val envelope = JSONObject(store.envelope(command))
                assertEquals("UPDATE", envelope.getString("kind"))
                val payload = envelope.getJSONObject("payload")
                assertEquals("Plans", payload.getString("name"))
                assertEquals("#7951B8", payload.getString("color"))
                assertTrue(payload.has("icon") && payload.isNull("icon"))

                assertThrows(IllegalStateException::class.java) {
                    store.transaction { store.updateCategory(original, "Stale draft", original.color, original.icon) }
                }
                assertEquals(listOf(command), store.commands())
            }
        } finally { context.deleteDatabase(AppDatabase.accountFileName(uid)) }
    }

    @Test fun editChecks() {
        val uid = uuid()
        val editorUid = uuid()
        try {
            open(uid).use { store ->
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                store.database.users().upsert(UserEntity(editorUid, "e@example.test", "e@example.test", "Editor"))
                val cid = store.transaction { store.createCategory("Study") }
                store.transaction { store.createCategory("Plans") }
                store.upsert("category_members", mapOf("category_id" to cid, "user_uid" to editorUid,
                    "role" to "EDITOR", "joined_at" to 1))
                val original = store.categories().first { it.ref.id == cid }
                val commands = store.commands()

                assertThrows(IllegalArgumentException::class.java) {
                    store.transaction { store.updateCategory(original, "  plans  ", null, "star") }
                }
                val editor = AccountWorkspace(editorUid, store.database)
                val editorView = editor.categories().single()
                assertThrows(IllegalStateException::class.java) {
                    editor.transaction { editor.updateCategory(editorView, "Renamed", null, "star") }
                }
                assertEquals(commands, store.commands())
                assertEquals(original, store.categories().first { it.ref.id == cid })
            }
        } finally { context.deleteDatabase(AppDatabase.accountFileName(uid)) }
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

    @Test fun sharedTask() {
        val uid = uuid()
        val viewer = uuid()
        try {
            open(uid).use { store ->
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                store.database.users().upsert(UserEntity(viewer, "v@example.test", "v@example.test", "Viewer"))
                val cid = store.createCategory("Project")
                store.upsert("tasks", mapOf("id" to uuid(), "category_id" to cid, "created_by_uid" to uid,
                    "title" to "Task", "created_at" to 1, "client_updated_at" to 1))
                assertFalse(store.home().single().isCategoryShared)

                val membership = mapOf("category_id" to cid, "user_uid" to viewer, "role" to "VIEWER", "joined_at" to 1)
                store.upsert("category_members", membership)
                assertTrue(store.home().single().isCategoryShared)
                assertTrue(store.database.tasks().homeTasks(viewer).single().isCategoryShared)

                store.upsert("category_members", membership + ("status" to "REMOVED"))
                assertFalse(store.home().single().isCategoryShared)
            }
        } finally { context.deleteDatabase(AppDatabase.accountFileName(uid)) }
    }

    @Test fun hiddenDefault() {
        val uid = "default-test"
        val cid = defaultCategoryId(uid)
        assertEquals("832ab5c3-6e65-80e9-b5f8-0d44aa1ba344", cid)
        val task = uuid()
        try {
            open(uid).use { store ->
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                store.transaction {
                    store.createCategory("to do")
                    assertEquals(cid, store.ensureDefaultCategory())
                    assertEquals(cid, store.ensureDefaultCategory())
                    store.upsert("tasks", mapOf("id" to task, "category_id" to cid, "created_by_uid" to uid,
                        "title" to "Draft", "created_at" to 1, "client_updated_at" to 1))
                    store.enqueue(cid, "TASK", task, "CREATE", 0, mapOf("title" to "Draft"))
                }
                assertEquals("to do", store.categories().single().name)
                assertEquals("", store.home().single().category)
                assertFalse(store.home().single().isCategoryShared)
                val ensure = store.commands().first { it["category_id"] == cid }
                assertEquals("ENSURE_DEFAULT_CATEGORY", store.envelope(ensure)["kind"])
                store.failed(ensure, "OBJECT_ID_REUSED", retry = false)
            }
            open(uid).use { store ->
                assertEquals(cid, store.ensureDefaultCategory())
                assertEquals("Draft", store.home().single().title)
                assertEquals("Lỗi đồng bộ", store.home().single().syncLabel)
                assertEquals(2, store.commands().count { it["category_id"] == cid })
                store.sql.execSQL("UPDATE tasks SET deleted_at=2 WHERE id=?", arrayOf(task))
                assertEquals("", store.trash().single().category)
            }
        } finally { context.deleteDatabase(AppDatabase.accountFileName(uid)) }
    }
}
