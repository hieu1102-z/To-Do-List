package com.finalexam.to_do_list.controller.auth

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.controller.WorkspaceActivity
import com.finalexam.to_do_list.databinding.ActivityLoginBinding
import com.finalexam.to_do_list.model.repository.AuthRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException

class LoginActivity : WorkspaceActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: AuthRepository
    private var visiblePassword = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        auth = AuthRepository(this)
        binding.back.setOnClickListener { finish() }
        binding.continueOffline.setOnClickListener { finish() }
        binding.togglePassword.setOnClickListener {
            visiblePassword = !visiblePassword
            binding.password.transformationMethod = if (visiblePassword) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            binding.password.setSelection(binding.password.length())
            binding.togglePassword.contentDescription = if (visiblePassword) "Ẩn mật khẩu" else "Hiện mật khẩu"
            binding.togglePassword.setImageResource(if (visiblePassword) R.drawable.ic_eye else R.drawable.ic_eye_off)
        }
        binding.signIn.setOnClickListener { submit() }
        binding.password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        binding.forgotPassword.setOnClickListener { resetPassword() }
        if (!auth.configured) showError("Đăng nhập chưa sẵn sàng. Bạn vẫn có thể tiếp tục dùng offline.")
    }

    private fun submit() {
        if (busy) return
        binding.error.visibility = View.GONE
        val email = binding.email.text.toString().trim()
        val password = binding.password.text.toString()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.email.error = "Nhập địa chỉ email hợp lệ"
            binding.email.requestFocus()
            return
        }
        if (password.isBlank()) {
            binding.password.error = "Vui lòng nhập mật khẩu"
            binding.password.requestFocus()
            return
        }
        setBusy(true)
        auth.signIn(email, password) { result ->
            if (isFinishing || isDestroyed) return@signIn
            setBusy(false)
            result.fold({
                binding.password.text.clear()
                setResult(RESULT_OK)
                finish()
            }, { showError(message(it)) })
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.signIn.isEnabled = !value
        binding.email.isEnabled = !value
        binding.password.isEnabled = !value
        binding.forgotPassword.isEnabled = !value
        binding.loading.visibility = if (value) View.VISIBLE else View.GONE
        binding.signIn.text = if (value) "Đang đăng nhập…" else "Đăng nhập"
    }

    private fun resetPassword() {
        val input = EditText(this).apply {
            hint = "ban@email.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(binding.email.text)
            setPadding(32, 24, 32, 24)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Quên mật khẩu")
            .setMessage("Nhập email tài khoản để nhận hướng dẫn đặt lại mật khẩu.")
            .setView(input).setNegativeButton("Hủy", null).setPositiveButton("Gửi email", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = input.text.toString().trim()
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { input.error = "Email không hợp lệ"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                auth.resetPassword(email) { result ->
                    if (isFinishing || isDestroyed) return@resetPassword
                    dialog.dismiss()
                    result.fold({ AlertDialog.Builder(this).setTitle("Kiểm tra email").setMessage("Nếu tài khoản tồn tại, hướng dẫn đặt lại mật khẩu sẽ được gửi đến email của bạn.").setPositiveButton("Đã hiểu", null).show() }, { showError(message(it)) })
                }
            }
        }
        dialog.show()
    }

    private fun showError(text: String) { binding.error.text = text; binding.error.visibility = View.VISIBLE }
    private fun message(error: Throwable): String = when (error) {
        is FirebaseNetworkException -> "Không có kết nối mạng. Hãy kiểm tra mạng hoặc tiếp tục dùng offline."
        is FirebaseTooManyRequestsException -> "Có quá nhiều yêu cầu. Vui lòng thử lại sau."
        is FirebaseAuthException -> when (error.errorCode) {
            "ERROR_USER_DISABLED" -> "Tài khoản đã bị vô hiệu hóa."
            "ERROR_INVALID_EMAIL" -> "Địa chỉ email không hợp lệ."
            "ERROR_OPERATION_NOT_ALLOWED" -> "Đăng nhập email chưa được bật. Vui lòng liên hệ quản trị viên."
            else -> "Email hoặc mật khẩu không chính xác."
        }
        else -> "Đăng nhập chưa sẵn sàng. Bạn vẫn có thể dùng offline."
    }
}
