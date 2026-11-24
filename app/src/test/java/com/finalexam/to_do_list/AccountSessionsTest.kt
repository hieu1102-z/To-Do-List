package com.finalexam.to_do_list

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.remote.AuthUser
import com.finalexam.to_do_list.model.repository.AccountCache
import com.finalexam.to_do_list.model.repository.AccountSessions
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AccountSessionsTest {
    @Test fun switchUid() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val first = AuthUser("first-account", "first@example.test", "First", true)
        val second = AuthUser("next-account", "next@example.test", "Next", true)
        var user: AuthUser? = first
        val cancelled = mutableListOf<String>()
        val cache = AccountCache(context) { cancelled += it }
        val sessions = AccountSessions({ user }, { cache })
        val old = AppDatabase.accountFileName(first.uid)
        val new = AppDatabase.accountFileName(second.uid)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                assertNull(sessions.prepare(context, first.uid, false))
                val original = requireNotNull(sessions.current(context))
                sessions.access(context, original) { transaction { createCategory("Offline draft") } }
                user = null
                sessions.revoke(context, first.uid)
                assertNull(sessions.current(context))
                assertTrue(context.getDatabasePath(old).isFile)

                user = first
                assertNull(sessions.prepare(context, first.uid, false))
                val refreshed = requireNotNull(sessions.current(context))
                assertNotSame(original, refreshed)
                assertThrows(IllegalStateException::class.java) {
                    sessions.access(context, original) { error("Stale session accessed Room") }
                }
                assertEquals("Offline draft", sessions.access(context, refreshed) { categories().single().name })
                sessions.revoke(context, first.uid)

                user = second
                assertEquals(1L, sessions.prepare(context, second.uid, false)?.count)
                assertNull(sessions.current(context))
                assertFalse(context.getDatabasePath(new).exists())
                user = null
                sessions.revoke(context, second.uid)
                assertTrue(context.getDatabasePath(old).isFile)

                user = second
                assertEquals(1L, sessions.prepare(context, second.uid, false)?.count)
                val oldJournal = context.getDatabasePath(old + "-journal")
                oldJournal.writeText("legacy journal")
                val avatar = cache.avatar(first.uid)
                avatar.parentFile!!.mkdirs()
                avatar.writeText("old avatar")
                assertNull(sessions.prepare(context, second.uid, true))
                assertEquals(listOf(old), cancelled)
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    assertFalse(context.getDatabasePath(old + suffix).exists())
                }
                assertFalse(avatar.exists())
                assertTrue(context.getDatabasePath(new).isFile)
                assertEquals(second.uid, sessions.current(context)?.uid)
                assertThrows(IllegalStateException::class.java) {
                    sessions.access(context, refreshed) { error("Old account accessed Room") }
                }
            }.get()
        } finally {
            executor.shutdownNow()
            context.deleteDatabase(old)
            context.deleteDatabase(new)
        }
    }
}
