package com.finalexam.to_do_list.controller.auth

import android.os.Bundle
import android.os.CountDownTimer
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.controller.WorkspaceActivity
import com.finalexam.to_do_list.databinding.ActivityRegisterBinding
import com.finalexam.to_do_list.model.repository.AuthFailure
import com.finalexam.to_do_list.model.repository.AuthRepository
import kotlinx.coroutines.launch

class RegisterActivity : WorkspaceActivity() {
    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: AuthRepository
    private lateinit var model: RegisterViewModel
    private var countdown: CountDownTimer? = null
    private var verificationOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = AuthRepository.get(this)
        verificationOnly = intent.getBooleanExtra(EXTRA_VERIFY_ONLY, false)
        if (verificationOnly && auth.user == null) { finish(); return }
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RegisterViewModel::class.java))
                return RegisterViewModel(auth, verificationOnly) as T
            }
        })[RegisterViewModel::class.java]
        binding.back.setOnClickListener { leave() }
        binding.toLogin.setOnClickListener { leave() }
        binding.continueOffline.setOnClickListener { leave() }
        binding.goHome.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { leave() }
        binding.createAccount.setOnClickListener { submit() }
        binding.confirmPassword.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        passwordToggle(binding.password, binding.togglePassword)
        passwordToggle(binding.confirmPassword, binding.toggleConfirm)
        binding.resend.setOnClickListener { model.resend() }
        binding.checkVerified.setOnClickListener { model.refreshVerification() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refreshVerification()
    }

    override fun onStop() { countdown?.cancel(); super.onStop() }

    private fun submit() {
        model.submit(binding.fullName.text.toString(), binding.email.text.toString(),
            binding.password.text.toString(), binding.confirmPassword.text.toString())
    }

    private fun render(state: RegisterState) {
        countdown?.cancel()
        val creating = state == RegisterState.Creating
        val created = state as? RegisterState.Created
        binding.form.visibility = if (created == null) View.VISIBLE else View.GONE
        binding.successPanel.visibility = if (created != null) View.VISIBLE else View.GONE
        binding.loading.visibility = if (creating || created?.busy == true) View.VISIBLE else View.GONE
        binding.createAccount.isEnabled = !creating && auth.configured
        binding.createAccount.setText(if (creating) R.string.register_loading else R.string.register_action)
        listOf(binding.fullName, binding.email, binding.password, binding.confirmPassword).forEach { it.isEnabled = !creating; it.error = null }
        binding.error.visibility = View.GONE
        if (!auth.configured) showError(AuthFailure.NOT_CONFIGURED.message!!)
        if (state is RegisterState.Error) {
            if (state.failure == AuthFailure.REGISTRATION_LOCAL_STORAGE) binding.createAccount.isEnabled = false
            val field = when (state.failure) {
                AuthFailure.NAME_REQUIRED -> binding.fullName
                AuthFailure.INVALID_EMAIL, AuthFailure.EMAIL_IN_USE -> binding.email
                AuthFailure.WEAK_PASSWORD -> binding.password
                AuthFailure.PASSWORD_MISMATCH -> binding.confirmPassword
                else -> null
            }
            field?.error = state.failure.message
            field?.requestFocus()
            showError(state.failure.message!!)
        }
        if (created != null) {
            binding.password.text.clear()
            binding.confirmPassword.text.clear()
            val verified = created.account.user.emailVerified
            binding.successTitle.setText(if (verified) R.string.email_verified else if (verificationOnly) R.string.verify_email_title else R.string.register_created)
            binding.verificationMessage.text = getString(if (verified) R.string.verification_complete else R.string.verification_instructions, created.account.user.email)
            binding.profileWarning.visibility = if (created.account.profileUpdated) View.GONE else View.VISIBLE
            binding.checkVerified.visibility = if (verified) View.GONE else View.VISIBLE
            binding.checkVerified.isEnabled = !created.busy
            binding.resend.visibility = if (verified) View.GONE else View.VISIBLE
            val wait = auth.verificationDelay(created.account.user.uid)
            binding.resend.isEnabled = !created.busy && wait == 0L
            binding.resend.setText(R.string.verification_resend)
            if (!verified && wait > 0L) {
                fun showWait(ms: Long) { binding.resend.text = getString(R.string.verification_wait, (ms + 999L) / 1000L) }
                showWait(wait)
                countdown = object : CountDownTimer(wait, 1000L) {
                    override fun onTick(millisUntilFinished: Long) { showWait(millisUntilFinished) }
                    override fun onFinish() {
                        binding.resend.setText(R.string.verification_resend)
                        binding.resend.isEnabled = (model.state.value as? RegisterState.Created)?.busy == false
                    }
                }.start()
            }
            binding.notice.text = created.failure?.message ?: when (created.notice) {
                VerificationNotice.SENT -> getString(R.string.verification_sent)
                VerificationNotice.VERIFIED -> getString(R.string.email_verified)
                VerificationNotice.NOT_VERIFIED -> getString(R.string.verification_pending)
                null -> if (!created.account.verificationSent && !verified) getString(R.string.verification_not_sent) else ""
            }
        }
    }

    private fun passwordToggle(field: EditText, button: ImageButton) {
        button.setOnClickListener {
            val hidden = field.transformationMethod is PasswordTransformationMethod
            field.transformationMethod = if (hidden) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            field.setSelection(field.length())
            button.setImageResource(if (hidden) R.drawable.ic_eye else R.drawable.ic_eye_off)
            button.contentDescription = getString(if (hidden) R.string.password_hide else R.string.password_show)
        }
    }

    private fun leave() {
        model.leave()
        binding.password.text.clear()
        binding.confirmPassword.text.clear()
        if (model.state.value is RegisterState.Created && auth.user != null) setResult(RESULT_OK)
        finish()
    }
    private fun showError(message: String) { binding.error.text = message; binding.error.visibility = View.VISIBLE }
    companion object { const val EXTRA_VERIFY_ONLY = "verification_only" }
}
