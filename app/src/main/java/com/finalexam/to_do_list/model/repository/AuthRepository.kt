package com.finalexam.to_do_list.model.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Patterns
import com.finalexam.to_do_list.model.remote.AuthUser
import com.finalexam.to_do_list.model.remote.EmailAuthGateway
import com.finalexam.to_do_list.model.remote.FirebaseEmailAuthGateway
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Firebase authenticates accounts before the application prepares their workspace. */
class AuthRepository internal constructor(
    private val gateway: EmailAuthGateway,
    private val profiles: AccountProfileStore,
    private val executor: Executor,
    private val dispatch: (() -> Unit) -> Unit,
    private val elapsedTime: () -> Long = { SystemClock.elapsedRealtime() },
    private val onSignOut: (String) -> Unit = {}
) {
    private class Attempt {
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val authenticatedUid = AtomicReference<String?>(null)
    }
    private val active = AtomicReference<Attempt?>(null)
    private val verificationRequests = ConcurrentHashMap<String, Long>()

    val configured get() = gateway.configured
    val user: AuthUser? get() = if (active.get() == null) gateway.currentUser else null
    fun signOut() {
        val previousUid = user?.uid
        active.get()?.cancelled?.set(true)
        gateway.signOut()
        previousUid?.let(onSignOut)
    }

    /** Returns cancellation for this attempt only; passwords are never stored by the repository. */
    fun signIn(email: String, password: String, callback: (Result<Unit>) -> Unit): () -> Unit {
        val normalizedEmail = email.trim()
        val error = when {
            !configured -> AuthFailure.NOT_CONFIGURED
            !Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches() -> AuthFailure.INVALID_EMAIL
            password.isEmpty() -> AuthFailure.PASSWORD_REQUIRED
            else -> null
        }
        if (error != null) { callback(Result.failure(error)); return {} }
        val attempt = Attempt()
        if (!active.compareAndSet(null, attempt)) { callback(Result.failure(AuthFailure.BUSY)); return {} }
        gateway.signIn(normalizedEmail, password) { result ->
            result.fold({ signedIn ->
                attempt.authenticatedUid.set(signedIn.uid)
                if (attempt.cancelled.get() || gateway.currentUser?.uid != signedIn.uid) {
                    rejectOwnSession(attempt)
                    complete(attempt, Result.failure(AuthFailure.CANCELLED), callback)
                } else if (signedIn.uid.isBlank() || signedIn.uid.startsWith("local:") || signedIn.email.isBlank()) {
                    rejectOwnSession(attempt)
                    complete(attempt, Result.failure(AuthFailure.UNKNOWN), callback)
                } else {
                    cacheProfile(attempt, signedIn) { complete(attempt, it, callback) }
                }
            }, { complete(attempt, Result.failure(if (attempt.cancelled.get()) AuthFailure.CANCELLED else it), callback) })
        }
        return { cancel(attempt) }
    }

    fun register(fullName: String, email: String, password: String, confirmation: String,
        callback: (Result<RegistrationResult>) -> Unit): () -> Unit {
        val name = fullName.trim()
        val address = email.trim()
        val error = when {
            !configured -> AuthFailure.NOT_CONFIGURED
            name.isEmpty() -> AuthFailure.NAME_REQUIRED
            !Patterns.EMAIL_ADDRESS.matcher(address).matches() -> AuthFailure.INVALID_EMAIL
            password.length < 6 -> AuthFailure.WEAK_PASSWORD
            password != confirmation -> AuthFailure.PASSWORD_MISMATCH
            else -> null
        }
        if (error != null) { callback(Result.failure(error)); return {} }
        val attempt = Attempt()
        if (!active.compareAndSet(null, attempt)) { callback(Result.failure(AuthFailure.BUSY)); return {} }
        gateway.createUser(address, password) { created ->
            created.fold({ account ->
                attempt.authenticatedUid.set(account.uid)
                if (!validAttempt(attempt, account)) {
                    rejectOwnSession(attempt)
                    complete(attempt, Result.failure(AuthFailure.CANCELLED), callback)
                } else gateway.updateDisplayName(account.uid, name) { updated ->
                    val current = gateway.currentUser
                    if (current == null || !validAttempt(attempt, current)) {
                        rejectOwnSession(attempt)
                        complete(attempt, Result.failure(AuthFailure.CANCELLED), callback)
                    } else cacheProfile(attempt, current) { cached ->
                        if (cached.isFailure) {
                            complete(attempt, Result.failure(if (attempt.cancelled.get() || cached.exceptionOrNull() == AuthFailure.CANCELLED) AuthFailure.CANCELLED
                                else AuthFailure.REGISTRATION_LOCAL_STORAGE), callback)
                        } else sendVerificationFor(account.uid) { sent ->
                            complete(attempt, Result.success(RegistrationResult(current,
                                profileUpdated = updated.isSuccess, verificationSent = sent.isSuccess)), callback)
                        }
                    }
                }
            }, { complete(attempt, Result.failure(it as? AuthFailure ?: AuthFailure.UNKNOWN), callback) })
        }
        return { cancel(attempt) }
    }

    fun verificationDelay(uid: String): Long = verificationRequests[uid]?.let {
        (60_000L - (elapsedTime() - it)).coerceAtLeast(0L)
    } ?: 0L

    fun sendVerification(callback: (Result<Unit>) -> Unit) {
        val current = user ?: return callback(Result.failure(AuthFailure.SESSION_REQUIRED))
        if (current.emailVerified) return callback(Result.success(Unit))
        sendVerificationFor(current.uid, callback)
    }

    private fun sendVerificationFor(uid: String, callback: (Result<Unit>) -> Unit) {
        if (gateway.currentUser?.uid != uid) return callback(Result.failure(AuthFailure.CANCELLED))
        var allowed = false
        val now = elapsedTime()
        verificationRequests.compute(uid) { _, last ->
            if (last == null || now - last >= 60_000L) { allowed = true; now } else last
        }
        if (!allowed) return callback(Result.failure(AuthFailure.VERIFICATION_COOLDOWN))
        gateway.sendVerification(uid, callback)
    }

    /** Reload Firebase state AND refresh the ID token before caching verification status. */
    fun reloadVerification(callback: (Result<AuthUser>) -> Unit) {
        val current = user ?: return callback(Result.failure(AuthFailure.SESSION_REQUIRED))
        gateway.reloadUser(current.uid) { result ->
            result.fold({ updated ->
                if (gateway.currentUser?.uid != current.uid || updated.uid != current.uid) {
                    callback(Result.failure(AuthFailure.CANCELLED))
                } else executor.execute {
                    val saved = runCatching { profiles.cache(updated) }
                    dispatch {
                        callback(when {
                            gateway.currentUser?.uid != current.uid -> Result.failure(AuthFailure.CANCELLED)
                            saved.isFailure -> Result.failure(AuthFailure.LOCAL_STORAGE)
                            else -> Result.success(updated)
                        })
                    }
                }
            }, { callback(Result.failure(it as? AuthFailure ?: AuthFailure.UNKNOWN)) })
        }
    }

    private fun validAttempt(attempt: Attempt, account: AuthUser): Boolean =
        !attempt.cancelled.get() && account.uid == attempt.authenticatedUid.get() &&
            gateway.currentUser?.uid == account.uid && account.uid.isNotBlank() &&
            !account.uid.startsWith("local:") && account.email.isNotBlank()

    private fun cacheProfile(attempt: Attempt, account: AuthUser, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            val saved = runCatching { profiles.cache(account) }
            dispatch {
                if (saved.isFailure) rejectOwnSession(attempt)
                callback(when {
                    attempt.cancelled.get() -> Result.failure(AuthFailure.CANCELLED)
                    saved.isFailure -> Result.failure(AuthFailure.LOCAL_STORAGE)
                    gateway.currentUser?.uid != account.uid -> Result.failure(AuthFailure.CANCELLED)
                    else -> Result.success(Unit)
                })
            }
        }
    }

    private fun cancel(attempt: Attempt) {
        if (!attempt.finished.get()) {
            attempt.cancelled.set(true)
            rejectOwnSession(attempt)
        }
    }

    fun resetPassword(email: String, callback: (Result<Unit>) -> Unit) {
        if (!configured) return callback(Result.failure(AuthFailure.NOT_CONFIGURED))
        if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) return callback(Result.failure(AuthFailure.INVALID_EMAIL))
        gateway.resetPassword(email.trim(), callback)
    }

    fun updateDisplayName(name: String, callback: (Result<AuthUser>) -> Unit) {
        val current = user ?: return callback(Result.failure(AuthFailure.SESSION_REQUIRED))
        val normalized = name.trim()
        if (normalized.isEmpty()) return callback(Result.failure(AuthFailure.NAME_REQUIRED))
        gateway.updateDisplayName(current.uid, normalized) { result ->
            result.fold({ updated ->
                if (gateway.currentUser?.uid != current.uid || updated.uid != current.uid) {
                    callback(Result.failure(AuthFailure.CANCELLED))
                } else executor.execute {
                    val cached = runCatching { profiles.cache(updated) }
                    dispatch {
                        callback(when {
                            gateway.currentUser?.uid != current.uid -> Result.failure(AuthFailure.CANCELLED)
                            cached.isFailure -> Result.failure(AuthFailure.LOCAL_STORAGE)
                            else -> Result.success(updated)
                        })
                    }
                }
            }, { callback(Result.failure(it as? AuthFailure ?: AuthFailure.UNKNOWN)) })
        }
    }

    /** Cache SDK-restored identity without forcing a new online sign-in. */
    fun refreshProfile(callback: (Result<Unit>) -> Unit = {}) {
        val current = user ?: return callback(Result.success(Unit))
        executor.execute {
            val result = runCatching { profiles.cache(current) }
            dispatch {
                callback(when {
                    gateway.currentUser?.uid != current.uid -> Result.failure(AuthFailure.CANCELLED)
                    result.isFailure -> Result.failure(AuthFailure.LOCAL_STORAGE)
                    else -> Result.success(Unit)
                })
            }
        }
    }

    private fun rejectOwnSession(attempt: Attempt) {
        val uid = attempt.authenticatedUid.get()
        if (uid != null && gateway.currentUser?.uid == uid) gateway.signOut()
    }

    private fun <T> complete(attempt: Attempt, result: Result<T>, callback: (Result<T>) -> Unit) {
        dispatch {
            if (attempt.finished.compareAndSet(false, true)) {
                active.compareAndSet(attempt, null)
                callback(if (attempt.cancelled.get() || (result.isSuccess && gateway.currentUser?.uid != attempt.authenticatedUid.get()))
                    Result.failure(AuthFailure.CANCELLED) else result)
            }
        }
    }

    companion object {
        @Volatile private var instance: AuthRepository? = null
        fun get(context: Context): AuthRepository = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                val main = Handler(Looper.getMainLooper())
                AuthRepository(FirebaseEmailAuthGateway(app),
                    AccountProfileStore { user ->
                        AccountSession.current(app)?.takeIf { it.uid == user.uid }?.let { session ->
                            AccountSession.access(app, session) { database.cacheProfile(user) }
                        }
                    },
                    Executors.newSingleThreadExecutor(), { action -> main.post { action() }; Unit },
                    onSignOut = { uid ->
                        AccountSession.revoke(app, uid)
                        WorkspaceSync.signOut(app, uid)
                    })
            }.also { instance = it }
        }
    }
}
