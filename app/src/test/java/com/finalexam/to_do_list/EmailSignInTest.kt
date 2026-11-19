package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.controller.auth.LoginState
import com.finalexam.to_do_list.controller.auth.LoginViewModel
import com.finalexam.to_do_list.model.remote.AuthUser
import com.finalexam.to_do_list.model.remote.EmailAuthGateway
import com.finalexam.to_do_list.model.remote.FirebaseEmailAuthGateway
import com.finalexam.to_do_list.model.repository.AccountProfileStore
import com.finalexam.to_do_list.model.repository.AuthFailure
import com.finalexam.to_do_list.model.repository.AuthRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class EmailSignInTest {
    private class Gateway : EmailAuthGateway {
        override var configured = true
        override var currentUser: AuthUser? = null
        var calls = 0
        var email = ""
        var password = ""
        var resetEmail: String? = null
        lateinit var pending: (Result<AuthUser>) -> Unit
        override fun signIn(email: String, password: String, callback: (Result<AuthUser>) -> Unit) {
            calls++; this.email = email; this.password = password; pending = callback
        }
        fun success(user: AuthUser = AuthUser("firebase-uid", "User@example.com")) {
            currentUser = user
            pending(Result.success(user))
        }
        fun failure(error: AuthFailure) { pending(Result.failure(error)) }
        override fun resetPassword(email: String, callback: (Result<Unit>) -> Unit) {
            resetEmail = email; callback(Result.success(Unit))
        }
        override fun signOut() { currentUser = null }
        override fun createUser(email: String, password: String, callback: (Result<AuthUser>) -> Unit) { error("Not used by sign-in tests") }
        override fun updateDisplayName(uid: String, name: String, callback: (Result<AuthUser>) -> Unit) { error("Not used by sign-in tests") }
        override fun sendVerification(uid: String, callback: (Result<Unit>) -> Unit) { error("Not used by sign-in tests") }
        override fun reloadUser(uid: String, callback: (Result<AuthUser>) -> Unit) { error("Not used by sign-in tests") }
    }
    private val gateway = Gateway()
    private val saved = mutableListOf<AuthUser>()
    private val direct = Executor { it.run() }
    private fun repository(store: AccountProfileStore = AccountProfileStore { saved.add(it) }, executor: Executor = direct,
        dispatch: (() -> Unit) -> Unit = { it() }) = AuthRepository(gateway, store, executor, dispatch)

    @Test fun validatesConfigurationEmailAndPasswordBeforeCallingFirebase() {
        val repo = repository()
        var result: Result<Unit>? = null
        repo.signIn("invalid", "password") { result = it }
        assertSame(AuthFailure.INVALID_EMAIL, result!!.exceptionOrNull())
        repo.signIn("user@example.com", "") { result = it }
        assertSame(AuthFailure.PASSWORD_REQUIRED, result!!.exceptionOrNull())
        gateway.configured = false
        repo.signIn("user@example.com", "password") { result = it }
        assertSame(AuthFailure.NOT_CONFIGURED, result!!.exceptionOrNull())
        assertEquals(0, gateway.calls)
        assertTrue(saved.isEmpty())
    }

    @Test fun successWaitsForProfileStorageAndPreservesPasswordExactly() {
        val work = mutableListOf<Runnable>()
        val repo = repository(executor = Executor { work.add(it) })
        var result: Result<Unit>? = null
        repo.signIn(" User@example.com ", "  password  ") { result = it }
        assertEquals("User@example.com", gateway.email)
        assertEquals("  password  ", gateway.password)
        gateway.success()
        assertNull(result)
        assertNull(repo.user)
        assertTrue(saved.isEmpty())
        work.removeAt(0).run()
        assertTrue(result!!.isSuccess)
        assertEquals("firebase-uid", saved.single().uid)
        assertEquals("firebase-uid", repo.user?.uid)
    }

    @Test fun wrongCredentialsAndNetworkFailureNeverWriteProfilesAndCanRetry() {
        val repo = repository()
        var result: Result<Unit>? = null
        repo.signIn("user@example.com", "wrong") { result = it }
        gateway.failure(AuthFailure.INVALID_CREDENTIALS)
        assertSame(AuthFailure.INVALID_CREDENTIALS, result!!.exceptionOrNull())
        repo.signIn("user@example.com", "password") { result = it }
        gateway.failure(AuthFailure.NETWORK)
        assertSame(AuthFailure.NETWORK, result!!.exceptionOrNull())
        assertNull(repo.user)
        assertTrue(saved.isEmpty())
        repo.signIn("user@example.com", "password") { result = it }
        gateway.success()
        assertTrue(result!!.isSuccess)
        assertEquals(3, gateway.calls)
    }

    @Test fun duplicateSubmissionsAreRejectedWithoutStartingAnotherSdkRequest() {
        val repo = repository()
        var first: Result<Unit>? = null
        var duplicate: Result<Unit>? = null
        repo.signIn("user@example.com", "password") { first = it }
        repo.signIn("user@example.com", "password") { duplicate = it }
        assertSame(AuthFailure.BUSY, duplicate!!.exceptionOrNull())
        assertEquals(1, gateway.calls)
        gateway.success()
        assertTrue(first!!.isSuccess)
    }

    @Test fun failedProfileWriteDoesNotLeaveASuccessfulSession() {
        val repo = repository(AccountProfileStore { error("disk error with sensitive diagnostic") })
        var result: Result<Unit>? = null
        repo.signIn("user@example.com", "password") { result = it }
        gateway.success()
        assertSame(AuthFailure.LOCAL_STORAGE, result!!.exceptionOrNull())
        assertFalse(result!!.exceptionOrNull()!!.message!!.contains("sensitive"))
        assertNull(repo.user)
        assertNull(gateway.currentUser)
    }

    @Test fun cancellationBeforeSdkCompletionRejectsLateSuccess() {
        val repo = repository()
        var result: Result<Unit>? = null
        val cancel = repo.signIn("user@example.com", "password") { result = it }
        cancel()
        gateway.success()
        assertSame(AuthFailure.CANCELLED, result!!.exceptionOrNull())
        assertNull(gateway.currentUser)
        assertTrue(saved.isEmpty())
    }

    @Test fun signOutDuringProfileWriteCannotRestoreAnOldSession() {
        val work = mutableListOf<Runnable>()
        val repo = repository(executor = Executor { work.add(it) })
        var result: Result<Unit>? = null
        repo.signIn("user@example.com", "password") { result = it }
        gateway.success()
        repo.signOut()
        work.single().run()
        assertSame(AuthFailure.CANCELLED, result!!.exceptionOrNull())
        assertNull(repo.user)
    }

    @Test fun changingAccountBeforeCacheCompletionNeverReportsTheWrongAccountAsSuccess() {
        val work = mutableListOf<Runnable>()
        val repo = repository(executor = Executor { work.add(it) })
        var result: Result<Unit>? = null
        repo.signIn("a@example.com", "password") { result = it }
        gateway.success(AuthUser("account-A", "a@example.com"))
        gateway.currentUser = AuthUser("account-B", "b@example.com")
        work.single().run()
        assertSame(AuthFailure.CANCELLED, result!!.exceptionOrNull())
        assertEquals("account-B", repo.user?.uid)
        assertEquals("account-A", saved.single().uid)
    }

    @Test fun restoresSdkSessionWithoutPasswordOrNetworkAndLogoutKeepsTheCache() {
        gateway.currentUser = AuthUser("existing-account", "existing@example.com", emailVerified = true)
        val repo = repository()
        var result: Result<Unit>? = null
        repo.refreshProfile { result = it }
        assertTrue(result!!.isSuccess)
        assertEquals("existing-account", saved.single().uid)
        assertEquals(0, gateway.calls)
        repo.signOut()
        assertNull(repo.user)
        assertEquals(1, saved.size)
    }

    @Test fun resetPasswordValidatesAndTrimsEmailWithoutAuthenticating() {
        val repo = repository()
        var result: Result<Unit>? = null
        repo.resetPassword("invalid") { result = it }
        assertSame(AuthFailure.INVALID_EMAIL, result!!.exceptionOrNull())
        assertNull(gateway.resetEmail)
        repo.resetPassword(" user@example.com ") { result = it }
        assertTrue(result!!.isSuccess)
        assertEquals("user@example.com", gateway.resetEmail)
        assertNull(repo.user)
        assertTrue(saved.isEmpty())
    }

    @Test fun localUidCannotBeMistakenForAFirebaseAccount() {
        val repo = repository()
        var result: Result<Unit>? = null
        repo.signIn("user@example.com", "password") { result = it }
        gateway.success(AuthUser("local:guest", "user@example.com"))
        assertSame(AuthFailure.UNKNOWN, result!!.exceptionOrNull())
        assertNull(repo.user)
        assertTrue(saved.isEmpty())
    }

    @Test fun viewModelTracksSuccessErrorRetryAndCancellationWithoutAnyActivity() {
        val model = LoginViewModel(repository())
        model.submit("user@example.com", "password")
        assertEquals(LoginState.Loading, model.state.value)
        model.submit("user@example.com", "password")
        assertEquals(1, gateway.calls)
        gateway.failure(AuthFailure.NETWORK)
        assertEquals(LoginState.Error(AuthFailure.NETWORK), model.state.value)
        model.submit("user@example.com", "password")
        model.leave()
        gateway.success()
        assertEquals(LoginState.Idle, model.state.value)
        assertNull(gateway.currentUser)
        model.submit("user@example.com", "password")
        gateway.success()
        assertEquals(LoginState.Success, model.state.value)
        assertFalse(model.state.value.toString().contains("password"))
    }

    @Test fun firebaseFailuresAreMappedToSafeVietnameseMessages() {
        assertSame(AuthFailure.NETWORK, FirebaseEmailAuthGateway.mapFailure(FirebaseNetworkException("diagnostic")))
        assertSame(AuthFailure.TOO_MANY_REQUESTS, FirebaseEmailAuthGateway.mapFailure(FirebaseTooManyRequestsException("diagnostic")))
        mapOf("ERROR_INVALID_LOGIN_CREDENTIALS" to AuthFailure.INVALID_CREDENTIALS,
            "ERROR_WRONG_PASSWORD" to AuthFailure.INVALID_CREDENTIALS,
            "ERROR_USER_NOT_FOUND" to AuthFailure.INVALID_CREDENTIALS,
            "ERROR_USER_DISABLED" to AuthFailure.USER_DISABLED,
            "ERROR_OPERATION_NOT_ALLOWED" to AuthFailure.PROVIDER_DISABLED,
            "ERROR_INVALID_API_KEY" to AuthFailure.NOT_CONFIGURED).forEach { (code, expected) ->
            val actual = FirebaseEmailAuthGateway.mapFailure(FirebaseAuthException(code, "raw sensitive detail"))
            assertSame(expected, actual)
            assertFalse(actual.message!!.contains("sensitive"))
        }
    }
}
