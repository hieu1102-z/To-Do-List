package com.finalexam.to_do_list

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finalexam.to_do_list.model.local.AppDatabase
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import java.util.UUID

/** JVM database tests only: no Activity, Firebase initialization or emulator. */
abstract class DatabaseTestBase {
    protected val context: Application get() = ApplicationProvider.getApplicationContext()
    protected lateinit var database: AppDatabase
    protected lateinit var sql: SupportSQLiteDatabase
    protected lateinit var name: String
    protected val uid = "test-owner"
    protected val category get() = scalar("SELECT id FROM categories WHERE name='Cá nhân'")!!
    protected val study get() = scalar("SELECT id FROM categories WHERE name='Học tập'")!!

    @Before fun openDatabase() {
        name = "jvm-${UUID.randomUUID()}.db"
        reopen()
        user(uid)
        listOf("Cá nhân", "Học tập", "Công việc", "Mua sắm").forEachIndexed { i, title ->
            val id = "category-$i"
            execute("INSERT INTO categories(id,owner_uid,name,name_key,created_at,client_updated_at) VALUES(?,?,?,?,1,1)", id, uid, title, title)
            member(uid, id, "OWNER")
        }
    }

    protected fun reopen() {
        if (::database.isInitialized) database.close()
        database = AppDatabase.builder(context, name).allowMainThreadQueries().build()
        sql = database.openHelper.writableDatabase
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) database.close()
        if (::name.isInitialized) context.deleteDatabase(name)
    }

    protected fun scalar(query: String): String? = sql.query(query).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
    protected fun count(table: String) = scalar("SELECT COUNT(*) FROM $table")!!.toInt()
    protected fun execute(statement: String, vararg args: Any?) { sql.execSQL(statement, args) }
    protected fun rejected(statement: String) {
        try { execute(statement); fail("SQLite accepted invalid data: $statement") }
        catch (expected: android.database.SQLException) { /* actual DB constraint, not a UI guard */ }
    }
    protected fun task(id: String = "task", categoryId: String = category) {
        execute("INSERT INTO tasks(id,category_id,created_by_uid,title,created_at,client_updated_at) VALUES(?,?,?,'Task',10,20)", id, categoryId, uid)
    }
    protected fun user(id: String) { execute("INSERT INTO users(uid,email,email_key,full_name) VALUES(?,'','','Member')", id) }
    protected fun member(id: String, categoryId: String = category, role: String = "EDITOR", status: String = "ACTIVE") {
        execute("INSERT INTO category_members(category_id,user_uid,role,status,joined_at) VALUES(?,?,?,?,10)", categoryId, id, role, status)
    }
    protected fun invite(id: String, status: String = "PENDING", email: String = "member@example.com") {
        execute("INSERT INTO category_invitations(id,category_id,inviter_uid,invitee_email_key,offered_role,status,created_at,expires_at) VALUES(?,?,?,?,'EDITOR',?,10,100)", id, category, uid, email, status)
    }
}
