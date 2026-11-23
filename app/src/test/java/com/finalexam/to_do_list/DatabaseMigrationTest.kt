package com.finalexam.to_do_list

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finalexam.to_do_list.model.local.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DatabaseMigrationTest {
    // Frozen DDL from the version-1 Room database, independent of the new schema.
    private val legacyDdl = """CREATE TABLE guest_tasks(id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,
        description TEXT NOT NULL,category TEXT NOT NULL,dueAt INTEGER,priority INTEGER NOT NULL,
        done INTEGER NOT NULL,completedAt INTEGER,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)"""
    private fun fixture(name: String, invalid: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL(legacyDdl)
            db.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42,'e411286ed4be63961fd30bedb15c51c6')")
            db.execSQL("INSERT INTO guest_tasks VALUES('stable-uuid','${if (invalid) "   " else "Original title"}','Notes','Học tập',1234,3,1,1500,100,200)")
            if (!invalid) db.execSQL("INSERT INTO guest_tasks VALUES('second-uuid','Second','','Cá nhân',NULL,1,0,NULL,110,210)")
            db.version = 1
        }
    }

    @Test fun migrationPreservesDataUuidsAndCreatesTheFullConstrainedSchema() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-${UUID.randomUUID()}.db"
        fixture(name)
        var database = AppDatabase.guestBuilder(context, name).allowMainThreadQueries().build()
        try {
            val sql = database.openHelper.writableDatabase
            assertEquals(4, sql.version)
            val task = database.tasks().find("stable-uuid")!!
            assertEquals("Original title", task.title)
            assertEquals("Notes", task.description)
            assertEquals(1234L, task.dueAt)
            assertEquals(3, task.priority)
            assertTrue(task.isDone)
            assertEquals(1500L, task.completedAt)
            assertEquals(100L, task.createdAt)
            assertEquals(200L, task.clientUpdatedAt)
            assertEquals("NONE", task.repeatType)
            assertEquals("Asia/Ho_Chi_Minh", task.timezone)
            assertEquals(0L, task.revision)
            assertNull(task.serverUpdatedAt)
            assertTrue(task.createdByUid.startsWith("local:"))
            assertEquals("Học tập", database.tasks().homeTasks(task.createdByUid).first { it.id == task.id }.category)
            sql.query("SELECT name FROM sqlite_master WHERE name='guest_tasks'").use { assertFalse(it.moveToFirst()) }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            sql.query("SELECT COUNT(*) FROM tasks").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            val uid = database.tasks().guestUid()
            database.close()
            database = AppDatabase.guestBuilder(context, name).allowMainThreadQueries().build()
            assertEquals(uid, database.tasks().guestUid())
            assertEquals(task, database.tasks().find(task.id))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun invalidLegacyDataRollsBackWithoutDeletingOriginalRows() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "invalid-migration-${UUID.randomUUID()}.db"
        fixture(name, invalid = true)
        val database = AppDatabase.guestBuilder(context, name).allowMainThreadQueries().build()
        try {
            try { database.openHelper.writableDatabase; fail("Expected CHECK failure") }
            catch (expected: android.database.SQLException) { /* transaction must roll back */ }
            database.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(1, old.version)
                old.rawQuery("SELECT id,title FROM guest_tasks", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("stable-uuid", it.getString(0)); assertEquals("   ", it.getString(1))
                }
                old.rawQuery("SELECT name FROM sqlite_master WHERE name='users'", null).use { assertFalse(it.moveToFirst()) }
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
