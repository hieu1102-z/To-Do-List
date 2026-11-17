package com.finalexam.to_do_list.model.repository

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

/** Firebase alone authenticates accounts; guest data is never uploaded on login. */
class AuthRepository(context: Context) {
    private val auth: FirebaseAuth? = (FirebaseApp.getApps(context).firstOrNull()
        ?: FirebaseApp.initializeApp(context))?.let { FirebaseAuth.getInstance(it) }

    val configured get() = auth != null
    val user get() = auth?.currentUser
    fun signOut() { auth?.signOut() }

    fun signIn(email: String, password: String, callback: (Result<Unit>) -> Unit) {
        val firebase = auth ?: return callback(Result.failure(IllegalStateException("Đăng nhập chưa sẵn sàng. Bạn vẫn có thể dùng ứng dụng offline.")))
        firebase.signInWithEmailAndPassword(email, password).addOnCompleteListener {
            callback(if (it.isSuccessful) Result.success(Unit) else Result.failure(it.exception ?: IllegalStateException("Không thể đăng nhập.")))
        }
    }

    fun resetPassword(email: String, callback: (Result<Unit>) -> Unit) {
        val firebase = auth ?: return callback(Result.failure(IllegalStateException("Chức năng tài khoản chưa được cấu hình.")))
        firebase.sendPasswordResetEmail(email).addOnCompleteListener {
            callback(if (it.isSuccessful) Result.success(Unit) else Result.failure(it.exception ?: IllegalStateException("Không thể gửi email.")))
        }
    }
}
