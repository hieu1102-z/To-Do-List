package com.finalexam.to_do_list.controller.auth

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.controller.WorkspaceActivity
import com.finalexam.to_do_list.databinding.ActivityLoginBinding
import com.finalexam.to_do_list.model.repository.AuthRepository
import com.finalexam.to_do_list.model.repository.AuthFailure
import kotlinx.coroutines.launch

class LoginActivity : WorkspaceActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: AuthRepository
    private lateinit var viewModel: LoginViewModel
    private var visiblePassword = false
    private var busy = false
    private val registration = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) enterWorkspace()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        auth = AuthRepository.get(this)
        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(LoginViewModel::class.java))
                return LoginViewModel(auth) as T
            }
        })[LoginViewModel::class.java]
        binding.back.setOnClickListener { leave() }

        onBackPressedDispatcher.addCallback(this) { leave() }
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
        binding.register.setOnClickListener {
            if (viewModel.state.value != LoginState.Loading)
                registration.launch(Intent(this, RegisterActivity::class.java))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    setBusy(state == LoginState.Loading)
                    when (state) {
                        LoginState.Success -> {
                            binding.password.text.clear()

                            Toast.makeText(
                                this@LoginActivity,
                                R.string.login_success,
                                Toast.LENGTH_SHORT
                            ).show()

                            enterWorkspace()
                        }
                        is LoginState.Error -> showError(message(state.failure))
                        LoginState.Loading -> binding.error.visibility = View.GONE
                        LoginState.Idle -> Unit
                    }
                }
            }
        }
        if (!auth.configured) showError("Đăng nhập chưa sẵn sàng.")
    }

    private fun enterWorkspace() {
        startActivity(Intent(this, com.finalexam.to_do_list.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun leave() { viewModel.leave(); binding.password.text.clear(); finish() }

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
        if (password.isEmpty()) {
            binding.password.error = "Vui lòng nhập mật khẩu"
            binding.password.requestFocus()
            return
        }
        viewModel.submit(email, password)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.signIn.isEnabled = !value && auth.configured
        binding.email.isEnabled = !value
        binding.password.isEnabled = !value
        binding.forgotPassword.isEnabled = !value && auth.configured
        binding.register.isEnabled = !value
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
    private fun message(error: Throwable): String = (error as? AuthFailure)?.message ?: AuthFailure.UNKNOWN.message!!
}
