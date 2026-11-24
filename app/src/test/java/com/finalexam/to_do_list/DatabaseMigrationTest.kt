package com.finalexam.to_do_list

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.SchemaSql
import com.finalexam.to_do_list.model.repository.AccountCache
import com.finalexam.to_do_list.model.repository.AccountWorkspace
import com.finalexam.to_do_list.model.repository.defaultCategoryId
import com.finalexam.to_do_list.model.repository.rows
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun upgradeDeletesGuestDatabaseAndProfile() {
        val name = "guest-workspace.db"
        context.openOrCreateDatabase(name, 0, null).use {
            it.execSQL("CREATE TABLE guest_tasks(id TEXT PRIMARY KEY)")
            it.execSQL("INSERT INTO guest_tasks VALUES('unsynced-guest')")
            it.version = 1
        }
        val profile = context.getSharedPreferences("guest_profile", 0)
        profile.edit().putString("display_name", "Guest").commit()
        AccountCache(context).deleteGuestData()

        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            assertFalse(context.getDatabasePath(name + suffix).exists())
        }
        assertTrue(profile.all.isEmpty())
    }

    @Test fun upgradeRoom() {
        val uid = "upgrade-account"
        val name = AppDatabase.accountFileName(uid)
        try {
            context.openOrCreateDatabase(name, 0, null).use { old ->
                SchemaSql.createStatements.forEach { old.execSQL(it.replace(", `icon` TEXT", "")) }
                old.execSQL("INSERT INTO users(uid,email,email_key,full_name) VALUES(?,'test@example.test','test@example.test','Owner')", arrayOf(uid))
                old.execSQL("INSERT INTO categories(id,owner_uid,name,name_key,color,created_at,client_updated_at) VALUES('old',?,'to do','to do','#5064EE',1,1)", arrayOf(uid))
                old.execSQL("INSERT INTO category_members(category_id,user_uid,role,joined_at) VALUES('old',?,'OWNER',1)", arrayOf(uid))
                old.execSQL("""INSERT INTO sync_queue(operation_id,actor_uid,category_id,entity_type,entity_id,action,payload_json,client_occurred_at)
                    VALUES('op',?,'old','CATEGORY','old','CREATE','{"command_type":"CREATE","name":"to do"}',1)""", arrayOf(uid))
                old.execSQL("CREATE TABLE local_category_copies (id TEXT PRIMARY KEY)")
                old.execSQL("CREATE TABLE local_category_transfers (id TEXT PRIMARY KEY)")
                old.version = 4
            }
            open(uid).use {
                assertEquals(6, it.sql.version)
                val old = it.categories().single()
                assertEquals("to do", old.name)
                assertEquals("#5064EE", old.color)
                assertNull(old.icon)
                assertEquals("CREATE", it.envelope(it.commands().single())["kind"])
                assertTrue(it.sql.rows("SELECT name FROM sqlite_master WHERE name LIKE 'local_category_%'").isEmpty())
                assertTrue(it.sql.rows("PRAGMA foreign_key_check").isEmpty())
                assertEquals(defaultCategoryId(uid), it.ensureDefaultCategory())
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun open(uid: String) = AccountWorkspace(uid,
        AppDatabase.accountBuilder(context, uid).allowMainThreadQueries().build())
}
