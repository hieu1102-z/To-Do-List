package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.controller.auth.RegisterState
import com.finalexam.to_do_list.controller.auth.RegisterViewModel
import com.finalexam.to_do_list.controller.auth.VerificationNotice
import com.finalexam.to_do_list.model.remote.AuthUser
import com.finalexam.to_do_list.model.remote.EmailAuthGateway
import com.finalexam.to_do_list.model.remote.FirebaseEmailAuthGateway
import com.finalexam.to_do_list.model.repository.*
import com.google.firebase.auth.FirebaseAuthException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

internal class RegistrationGateway : EmailAuthGateway {
    override var configured = true
    override var currentUser: AuthUser? = null
    var creates = 0
    var verifications = 0
    var reloads = 0
    var submittedEmail = ""
    var submittedPassword = ""
    var submittedName = ""
    var profileFails = false
    var verificationFails = false
    var reloadFails = false
    var remoteVerified = false
    var delayProfile = false
    var delayVerification = false
    lateinit var pendingCreate: (Result<AuthUser>) -> Unit
    lateinit var pendingProfile: (Result<AuthUser>) -> Unit
    lateinit var pendingVerification: (Result<Unit>) -> Unit
    override fun createUser(email: String, password: String, callback: (Result<AuthUser>) -> Unit) {
        creates++; submittedEmail = email; submittedPassword = password; pendingCreate = callback
    }
    fun created(uid: String = "firebase-new-user") {
        val account = AuthUser(uid, submittedEmail)
        currentUser = account
        pendingCreate(Result.success(account))
    }
    override fun updateDisplayName(uid: String, name: String, callback: (Result<AuthUser>) -> Unit) {
        submittedName = name
        if (delayProfile) { pendingProfile = callback; return }
        if (profileFails) callback(Result.failure(AuthFailure.NETWORK))
        else {
            currentUser = currentUser!!.copy(displayName = name)
            callback(Result.success(currentUser!!))
        }
    }
    override fun sendVerification(uid: String, callback: (Result<Unit>) -> Unit) {
        verifications++
        if (delayVerification) pendingVerification = callback
        else callback(if (verificationFails) Result.failure(AuthFailure.NETWORK) else Result.success(Unit))
    }
    override fun reloadUser(uid: String, callback: (Result<AuthUser>) -> Unit) {
        reloads++
        if (reloadFails) callback(Result.failure(AuthFailure.NETWORK))
        else {
            currentUser = currentUser!!.copy(emailVerified = remoteVerified)
            callback(Result.success(currentUser!!))
        }
    }
    override fun signOut() { currentUser = null }
    override fun signIn(email: String, password: String, callback: (Result<AuthUser>) -> Unit) { error("Not used") }
    override fun resetPassword(email: String, callback: (Result<Unit>) -> Unit) { error("Not used") }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RegistrationTest {
    private val gateway = RegistrationGateway()
    private val cached = mutableListOf<AuthUser>()
    private var clock = 1000L
    private val repo get() = repository
    private val repository = AuthRepository(gateway, AccountProfileStore { cached.add(it) },
        Executor { it.run() }, { it() }, { clock })

    @Test fun validatesAllInputsBeforeCreation() {
        var result: Result<RegistrationResult>? = null
        fun submit(name: String = "Name", email: String = "user@example.com", password: String = "secret123", confirm: String = password) {
            repo.register(name, email, password, confirm) { result = it }
        }
        submit(name = "  "); assertSame(AuthFailure.NAME_REQUIRED, result!!.exceptionOrNull())
        submit(email = "invalid"); assertSame(AuthFailure.INVALID_EMAIL, result!!.exceptionOrNull())
        submit(password = "12345"); assertSame(AuthFailure.WEAK_PASSWORD, result!!.exceptionOrNull())
        submit(confirm = "different"); assertSame(AuthFailure.PASSWORD_MISMATCH, result!!.exceptionOrNull())
        gateway.configured = false
        submit(); assertSame(AuthFailure.NOT_CONFIGURED, result!!.exceptionOrNull())
        assertEquals(0, gateway.creates)
        assertTrue(cached.isEmpty())
    }

    @Test fun createsProfileAndSendsVerification() {
        var result: Result<RegistrationResult>? = null
        repo.register(" Full Name ", " User@example.com ", " secret123 ", " secret123 ") { result = it }
        assertNull(result)
        assertNull(repo.user)
        assertEquals("User@example.com", gateway.submittedEmail)
        assertEquals(" secret123 ", gateway.submittedPassword)
        gateway.created()
        val account = result!!.getOrThrow()
        assertEquals("Full Name", gateway.submittedName)
        assertEquals("Full Name", cached.single().displayName)
        assertFalse(cached.single().emailVerified)
        assertTrue(account.profileUpdated)
        assertTrue(account.verificationSent)
        assertEquals(1, gateway.creates)
        assertEquals(1, gateway.verifications)
        assertEquals("firebase-new-user", repo.user?.uid)
    }

    @Test fun duplicateCreationAndLoginAreBlocked() {
        var second: Result<RegistrationResult>? = null
        var login: Result<Unit>? = null
        repo.register("Name", "user@example.com", "secret123", "secret123") { }
        repo.register("Name", "user@example.com", "secret123", "secret123") { second = it }
        repo.signIn("user@example.com", "secret123") { login = it }
        assertSame(AuthFailure.BUSY, second!!.exceptionOrNull())
        assertSame(AuthFailure.BUSY, login!!.exceptionOrNull())
        assertEquals(1, gateway.creates)
        gateway.created()
    }

    @Test fun existingEmailDoesNotCreateProfile() {
        var result: Result<RegistrationResult>? = null
        repo.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        gateway.pendingCreate(Result.failure(AuthFailure.EMAIL_IN_USE))
        assertSame(AuthFailure.EMAIL_IN_USE, result!!.exceptionOrNull())
        assertTrue(cached.isEmpty())
        assertEquals(0, gateway.verifications)
        assertNull(repo.user)
    }

    @Test fun networkFailureCanBeRetried() {
        var result: Result<RegistrationResult>? = null
        repo.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        gateway.pendingCreate(Result.failure(AuthFailure.NETWORK))
        assertSame(AuthFailure.NETWORK, result!!.exceptionOrNull())
        repo.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        gateway.created()
        assertTrue(result!!.isSuccess)
        assertEquals(2, gateway.creates)
    }

    @Test fun failedDeliveryDoesNotRetryAccountCreation() {
        gateway.verificationFails = true
        var result: Result<RegistrationResult>? = null
        repo.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        gateway.created()
        assertTrue(result!!.isSuccess)
        assertFalse(result!!.getOrThrow().verificationSent)
        assertEquals(1, gateway.creates)
        assertNotNull(repo.user)
        assertEquals(1, cached.size)
    }

    @Test fun failedNameUpdateStillReportsCreatedAccount() {
        gateway.profileFails = true
        var result: Result<RegistrationResult>? = null
        repo.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        gateway.created()
        assertFalse(result!!.getOrThrow().profileUpdated)
        assertTrue(result!!.getOrThrow().verificationSent)
        assertEquals(1, gateway.creates)
        assertNull(cached.single().displayName)
    }

    @Test fun cacheFailureExplainsAccountAlreadyExists() {
        val failing = AuthRepository(gateway, AccountProfileStore { error("disk") }, Executor { it.run() }, { it() })
        var result: Result<RegistrationResult>? = null
        failing.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        gateway.created()
        assertSame(AuthFailure.REGISTRATION_LOCAL_STORAGE, result!!.exceptionOrNull())
        assertEquals(1, gateway.creates)
        assertEquals(0, gateway.verifications)
        assertNull(failing.user)
    }

    @Test fun cancelledCreationCannotRestoreSession() {
        var result: Result<RegistrationResult>? = null
        val cancel = repo.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        cancel()
        gateway.created()
        assertSame(AuthFailure.CANCELLED, result!!.exceptionOrNull())
        assertNull(repo.user)
        assertTrue(cached.isEmpty())
    }

    @Test fun cancelWhileUpdatingNameStopsFollowup() {
        gateway.delayProfile = true
        var result: Result<RegistrationResult>? = null
        val cancel = repo.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        gateway.created()
        cancel()
        gateway.pendingProfile(Result.failure(AuthFailure.CANCELLED))
        assertSame(AuthFailure.CANCELLED, result!!.exceptionOrNull())
        assertTrue(cached.isEmpty())
        assertEquals(0, gateway.verifications)
    }

    @Test fun signOutWhileSendingCannotRestoreSession() {
        gateway.delayVerification = true
        var result: Result<RegistrationResult>? = null
        repo.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        gateway.created()
        repo.signOut()
        gateway.pendingVerification(Result.success(Unit))
        assertSame(AuthFailure.CANCELLED, result!!.exceptionOrNull())
        assertNull(repo.user)
    }

    @Test fun resendHonorsCooldownAndNeverCreatesAgain() {
        repo.register("Name", "user@example.com", "secret123", "secret123") { it.getOrThrow() }
        gateway.created()
        var sent: Result<Unit>? = null
        repo.sendVerification { sent = it }
        assertSame(AuthFailure.VERIFICATION_COOLDOWN, sent!!.exceptionOrNull())
        assertEquals(60_000L, repo.verificationDelay("firebase-new-user"))
        clock += 60_000L
        repo.sendVerification { sent = it }
        assertTrue(sent!!.isSuccess)
        assertEquals(2, gateway.verifications)
        assertEquals(1, gateway.creates)
    }

    @Test fun reloadCachesVerifiedStateFromSdk() {
        gateway.currentUser = AuthUser("uid", "user@example.com")
        var result: Result<AuthUser>? = null
        repo.reloadVerification { result = it }
        assertFalse(result!!.getOrThrow().emailVerified)
        gateway.remoteVerified = true
        repo.reloadVerification { result = it }
        assertTrue(result!!.getOrThrow().emailVerified)
        assertTrue(cached.last().emailVerified)
        assertEquals(2, gateway.reloads)
        var sent: Result<Unit>? = null
        repo.sendVerification { sent = it }
        assertTrue(sent!!.isSuccess)
        assertEquals(0, gateway.verifications)
    }

    @Test fun verificationRequiresSessionAndReportsNetworkFailure() {
        var result: Result<AuthUser>? = null
        var sent: Result<Unit>? = null
        repo.reloadVerification { result = it }
        assertSame(AuthFailure.SESSION_REQUIRED, result!!.exceptionOrNull())
        repo.sendVerification { sent = it }
        assertSame(AuthFailure.SESSION_REQUIRED, sent!!.exceptionOrNull())
        gateway.currentUser = AuthUser("uid", "user@example.com")
        gateway.reloadFails = true
        repo.reloadVerification { result = it }
        assertSame(AuthFailure.NETWORK, result!!.exceptionOrNull())
        assertTrue(cached.isEmpty())
    }

    @Test fun modelHandlesCreationAndVerificationWithoutActivity() {
        val model = RegisterViewModel(repo)
        model.submit("Name", "user@example.com", "secret123", "secret123")
        assertEquals(RegisterState.Creating, model.state.value)
        model.submit("Name", "user@example.com", "secret123", "secret123")
        assertEquals(1, gateway.creates)
        gateway.created()
        assertTrue(model.state.value is RegisterState.Created)
        assertFalse(model.state.value.toString().contains("secret123"))
        model.refreshVerification()
        assertEquals(VerificationNotice.NOT_VERIFIED, (model.state.value as RegisterState.Created).notice)
        gateway.remoteVerified = true
        model.refreshVerification()
        assertEquals(VerificationNotice.VERIFIED, (model.state.value as RegisterState.Created).notice)
    }

    @Test fun modelCanCancelAndSurfaceValidation() {
        val model = RegisterViewModel(repo)
        model.submit("", "user@example.com", "secret123", "secret123")
        assertEquals(RegisterState.Error(AuthFailure.NAME_REQUIRED), model.state.value)
        model.submit("Name", "user@example.com", "secret123", "secret123")
        model.leave()
        gateway.created()
        assertEquals(RegisterState.Idle, model.state.value)
        assertNull(repo.user)
    }

    @Test fun firebaseRegistrationErrorsAreMapped() {
        assertSame(AuthFailure.EMAIL_IN_USE, FirebaseEmailAuthGateway.mapFailure(FirebaseAuthException("ERROR_EMAIL_ALREADY_IN_USE", "detail")))
        assertSame(AuthFailure.WEAK_PASSWORD, FirebaseEmailAuthGateway.mapFailure(FirebaseAuthException("ERROR_WEAK_PASSWORD", "detail")))
    }

    @Test fun changedSessionDuringCacheIsCancelled() {
        val work = mutableListOf<Runnable>()
        val queued = AuthRepository(gateway, AccountProfileStore { cached.add(it) }, Executor { work.add(it) }, { it() })
        var result: Result<RegistrationResult>? = null
        queued.register("Name", "user@example.com", "secret123", "secret123") { result = it }
        gateway.created()
        gateway.currentUser = AuthUser("account-B", "b@example.com")
        work.single().run()
        assertSame(AuthFailure.CANCELLED, result!!.exceptionOrNull())
        assertEquals("account-B", queued.user?.uid)
        assertEquals(0, gateway.verifications)
    }

    @Test fun verificationPanelSupportsResendForExistingUser() {
        gateway.currentUser = AuthUser("existing-uid", "user@example.com")
        val model = RegisterViewModel(repo, verificationOnly = true)
        assertTrue(model.state.value is RegisterState.Created)
        model.resend()
        assertEquals(VerificationNotice.SENT, (model.state.value as RegisterState.Created).notice)
        model.resend()
        assertSame(AuthFailure.VERIFICATION_COOLDOWN, (model.state.value as RegisterState.Created).failure)
        clock += 60_000L
        model.resend()
        assertEquals(2, gateway.verifications)
        assertEquals(0, gateway.creates)
    }
}
