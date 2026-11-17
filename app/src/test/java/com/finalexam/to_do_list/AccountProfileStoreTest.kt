package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.remote.AuthUser
import com.finalexam.to_do_list.model.repository.RoomAccountProfileStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AccountProfileStoreTest : DatabaseTestBase() {
    private inline fun <T> AppDatabase.withDatabase(block: (AppDatabase) -> T): T = try { block(this) } finally { close() }
    // Robolectric includes method names in native SQLite temp paths (Windows MAX_PATH).
    @Test fun cacheKeepsGuestWork() {
        val accountUid = "firebase-${UUID.randomUUID()}"
        val accountFile = AppDatabase.accountFileName(accountUid)
        val guestUid = uid
        task("guest-task")
        val original = database.tasks().find("guest-task")
        val store = RoomAccountProfileStore { AppDatabase.accountBuilder(context, it).allowMainThreadQueries().build() }
        try {
            store.cache(AuthUser(accountUid, " User@Example.COM ", "Full Name", true, "https://example.com/avatar.png"))
            AppDatabase.accountBuilder(context, accountUid).allowMainThreadQueries().build().withDatabase { account ->
                val profile = account.users().find(accountUid)!!
                assertEquals("User@Example.COM", profile.email)
                assertEquals("user@example.com", profile.emailKey)
                assertEquals("Full Name", profile.fullName)
                assertTrue(profile.emailVerified)
                assertEquals("https://example.com/avatar.png", profile.avatarUrl)
                assertNull(profile.serverUpdatedAt)
                account.openHelper.writableDatabase.query("SELECT COUNT(*) FROM users WHERE uid LIKE 'local:%'").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
            assertEquals(guestUid, uid)
            assertEquals(original, database.tasks().find("guest-task"))
            assertEquals(4, count("categories"))
            assertEquals(0, count("sync_queue"))
            assertNull(database.users().find(accountUid))
        } finally { context.deleteDatabase(accountFile) }
    }

    @Test fun upsertKeepsChildren() {
        val accountUid = "firebase-${UUID.randomUUID()}"
        val accountFile = AppDatabase.accountFileName(accountUid)
        val store = RoomAccountProfileStore { AppDatabase.accountBuilder(context, it).allowMainThreadQueries().build() }
        try {
            store.cache(AuthUser(accountUid, "user@example.com", "Old name"))
            AppDatabase.accountBuilder(context, accountUid).allowMainThreadQueries().build().withDatabase { account ->
                account.users().upsert(account.users().find(accountUid)!!.copy(serverUpdatedAt = 123L))
                val sql = account.openHelper.writableDatabase
                sql.execSQL("INSERT INTO categories(id,owner_uid,name,name_key,created_at,client_updated_at) VALUES('account-category',?,'Personal','personal',10,10)", arrayOf(accountUid))
                sql.execSQL("INSERT INTO tasks(id,category_id,created_by_uid,title,created_at,client_updated_at) VALUES('account-task','account-category',?,'Task',10,10)", arrayOf(accountUid))
            }
            store.cache(AuthUser(accountUid, "user@example.com", "Updated name", true))
            AppDatabase.accountBuilder(context, accountUid).allowMainThreadQueries().build().withDatabase { account ->
                assertEquals("Updated name", account.users().find(accountUid)!!.fullName)
                assertEquals(123L, account.users().find(accountUid)!!.serverUpdatedAt)
                assertEquals("account-task", account.tasks().find("account-task")!!.id)
            }
        } finally { context.deleteDatabase(accountFile) }
    }

    @Test fun accountFileNamesAreIsolatedSafeAndRejectLocalIdentities() {
        val first = AppDatabase.accountFileName("firebase:with/slash")
        assertTrue(first.matches(Regex("account-[0-9a-f]{64}\\.db")))
        assertEquals(first, AppDatabase.accountFileName("firebase:with/slash"))
        assertNotEquals(first, AppDatabase.accountFileName("another-user"))
        try { AppDatabase.accountFileName("local:guest"); fail("Local UID accepted") }
        catch (expected: IllegalArgumentException) { }
        try { AppDatabase.accountFileName(""); fail("Empty UID accepted") }
        catch (expected: IllegalArgumentException) { }
    }
}
