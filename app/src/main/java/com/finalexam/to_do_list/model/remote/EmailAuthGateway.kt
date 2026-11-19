package com.finalexam.to_do_list.model.remote

/** Snapshot of Firebase identity, never credentials or authentication tokens. */
data class AuthUser(
    val uid: String,
    val email: String,
    val displayName: String? = null,
    val emailVerified: Boolean = false,
    val avatarUrl: String? = null
)

interface EmailAuthGateway {
    val configured: Boolean
    val currentUser: AuthUser?
    fun signIn(email: String, password: String, callback: (Result<AuthUser>) -> Unit)
    fun createUser(email: String, password: String, callback: (Result<AuthUser>) -> Unit)
    fun updateDisplayName(uid: String, name: String, callback: (Result<AuthUser>) -> Unit)
    fun sendVerification(uid: String, callback: (Result<Unit>) -> Unit)
    fun reloadUser(uid: String, callback: (Result<AuthUser>) -> Unit)
    fun resetPassword(email: String, callback: (Result<Unit>) -> Unit)
    fun signOut()
}
