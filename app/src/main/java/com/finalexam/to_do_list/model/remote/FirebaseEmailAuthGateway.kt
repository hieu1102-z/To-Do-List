package com.finalexam.to_do_list.model.remote

import android.content.Context
import com.finalexam.to_do_list.model.repository.AuthFailure
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest

class FirebaseEmailAuthGateway(context: Context) : EmailAuthGateway {
    private val auth = (FirebaseApp.getApps(context).find { it.name == FirebaseApp.DEFAULT_APP_NAME }
        ?: FirebaseApp.initializeApp(context))?.let { FirebaseAuth.getInstance(it) }
    override val configured get() = auth != null
    override val currentUser get() = auth?.currentUser?.snapshot()

    override fun signIn(email: String, password: String, callback: (Result<AuthUser>) -> Unit) {
        val firebase = auth ?: return callback(Result.failure(AuthFailure.NOT_CONFIGURED))
        firebase.signInWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            val user = if (task.isSuccessful) task.result?.user else null
            if (user != null) callback(Result.success(user.snapshot()))
            else callback(Result.failure(mapFailure(task.exception)))
        }
    }

    override fun resetPassword(email: String, callback: (Result<Unit>) -> Unit) {
        val firebase = auth ?: return callback(Result.failure(AuthFailure.NOT_CONFIGURED))
        firebase.sendPasswordResetEmail(email).addOnCompleteListener { task ->
            val missingAccount = (task.exception as? FirebaseAuthException)?.errorCode == "ERROR_USER_NOT_FOUND"
            callback(if (task.isSuccessful || missingAccount) Result.success(Unit) else Result.failure(mapFailure(task.exception)))
        }
    }

    override fun createUser(email: String, password: String, callback: (Result<AuthUser>) -> Unit) {
        val firebase = auth ?: return callback(Result.failure(AuthFailure.NOT_CONFIGURED))
        firebase.createUserWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            val user = if (task.isSuccessful) task.result?.user else null
            callback(if (user != null) Result.success(user.snapshot()) else Result.failure(mapFailure(task.exception)))
        }
    }

    override fun updateDisplayName(uid: String, name: String, callback: (Result<AuthUser>) -> Unit) {
        val user = auth?.currentUser?.takeIf { it.uid == uid }
            ?: return callback(Result.failure(AuthFailure.CANCELLED))
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build()).addOnCompleteListener { task ->
            val updated = currentUser
            callback(when {
                updated?.uid != uid -> Result.failure(AuthFailure.CANCELLED)
                task.isSuccessful -> Result.success(updated)
                else -> Result.failure(mapFailure(task.exception))
            })
        }
    }

    override fun sendVerification(uid: String, callback: (Result<Unit>) -> Unit) {
        val user = auth?.currentUser?.takeIf { it.uid == uid }
            ?: return callback(Result.failure(AuthFailure.CANCELLED))
        user.sendEmailVerification().addOnCompleteListener { task ->
            callback(when {
                currentUser?.uid != uid -> Result.failure(AuthFailure.CANCELLED)
                task.isSuccessful -> Result.success(Unit)
                else -> Result.failure(mapFailure(task.exception))
            })
        }
    }

    override fun reloadUser(uid: String, callback: (Result<AuthUser>) -> Unit) {
        val user = auth?.currentUser?.takeIf { it.uid == uid }
            ?: return callback(Result.failure(AuthFailure.SESSION_REQUIRED))
        user.reload().addOnCompleteListener { reloaded ->
            if (!reloaded.isSuccessful) callback(Result.failure(mapFailure(reloaded.exception)))
            else if (currentUser?.uid != uid) callback(Result.failure(AuthFailure.CANCELLED))
            else user.getIdToken(true).addOnCompleteListener { refreshed ->
                val updated = currentUser
                callback(when {
                    updated?.uid != uid -> Result.failure(AuthFailure.CANCELLED)
                    refreshed.isSuccessful -> Result.success(updated)
                    else -> Result.failure(mapFailure(refreshed.exception))
                })
            }
        }
    }

    override fun signOut() { auth?.signOut() }

    private fun FirebaseUser.snapshot() = AuthUser(uid, email.orEmpty(), displayName, isEmailVerified, photoUrl?.toString())

    companion object {
        internal fun mapFailure(error: Exception?): AuthFailure = when (error) {
            is FirebaseNetworkException -> AuthFailure.NETWORK
            is FirebaseTooManyRequestsException -> AuthFailure.TOO_MANY_REQUESTS
            is FirebaseAuthException -> when (error.errorCode) {
                "ERROR_USER_DISABLED" -> AuthFailure.USER_DISABLED
                "ERROR_INVALID_EMAIL" -> AuthFailure.INVALID_EMAIL
                "ERROR_EMAIL_ALREADY_IN_USE" -> AuthFailure.EMAIL_IN_USE
                "ERROR_WEAK_PASSWORD", "ERROR_PASSWORD_DOES_NOT_MEET_REQUIREMENTS" -> AuthFailure.WEAK_PASSWORD
                "ERROR_OPERATION_NOT_ALLOWED" -> AuthFailure.PROVIDER_DISABLED
                "ERROR_INVALID_API_KEY", "ERROR_APP_NOT_AUTHORIZED", "ERROR_API_NOT_AVAILABLE" -> AuthFailure.NOT_CONFIGURED
                "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> AuthFailure.INVALID_CREDENTIALS
                else -> AuthFailure.UNKNOWN
            }
            else -> AuthFailure.UNKNOWN
        }
    }
}
