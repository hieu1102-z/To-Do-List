package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.repository.AuthRepository
import com.finalexam.to_do_list.model.repository.RegistrationResult
import com.finalexam.to_do_list.model.repository.RoomAccountProfileStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RegistrationStorageTest : DatabaseTestBase() {
    @Test fun registerKeepsGuest() {
        val gateway = RegistrationGateway()
        val accountUid = "registered-${UUID.randomUUID()}"
        val accountFile = AppDatabase.accountFileName(accountUid)
        val store = RoomAccountProfileStore { AppDatabase.accountBuilder(context, it).allowMainThreadQueries().build() }
        val repo = AuthRepository(gateway, store, Executor { it.run() }, { it() })
        task("guest-task")
        val oldTask = database.tasks().find("guest-task")
        val oldUid = uid
        try {
            var result: Result<RegistrationResult>? = null
            repo.register(" Họ tên ", " User@example.com ", "secret123", "secret123") { result = it }
            gateway.created(accountUid)
            assertTrue(result!!.isSuccess)
            var account = AppDatabase.accountBuilder(context, accountUid).allowMainThreadQueries().build()
            try {
                val user = account.users().find(accountUid)!!
                assertEquals("Họ tên", user.fullName)
                assertEquals("user@example.com", user.emailKey)
                assertFalse(user.emailVerified)
            } finally { account.close() }
            gateway.remoteVerified = true
            repo.reloadVerification { assertTrue(it.getOrThrow().emailVerified) }
            account = AppDatabase.accountBuilder(context, accountUid).allowMainThreadQueries().build()
            try { assertTrue(account.users().find(accountUid)!!.emailVerified) } finally { account.close() }
            repo.signOut()
            assertEquals(oldTask, database.tasks().find("guest-task"))
            assertEquals(oldUid, uid)
            assertEquals(0, count("sync_queue"))
            assertNull(database.users().find(accountUid))
        } finally { context.deleteDatabase(accountFile) }
    }
}
