package com.finalexam.to_do_list.controller.auth

import androidx.lifecycle.ViewModel
import com.finalexam.to_do_list.model.repository.AuthFailure
import com.finalexam.to_do_list.model.repository.AuthRepository
import com.finalexam.to_do_list.model.repository.RegistrationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VerificationNotice { SENT, VERIFIED, NOT_VERIFIED }
sealed interface RegisterState {
    data object Idle : RegisterState
    data object Creating : RegisterState
    data class Error(val failure: AuthFailure) : RegisterState
    data class Created(val account: RegistrationResult, val busy: Boolean = false,
        val notice: VerificationNotice? = null, val failure: AuthFailure? = null) : RegisterState
}

/** Only account/result state survives configuration changes; never passwords. */
class RegisterViewModel(private val auth: AuthRepository, verificationOnly: Boolean = false) : ViewModel() {
    private val mutableState = MutableStateFlow<RegisterState>(
        auth.user?.takeIf { verificationOnly }?.let { RegisterState.Created(RegistrationResult(it, true, false)) }
            ?: RegisterState.Idle)
    val state = mutableState.asStateFlow()
    private var cancelAttempt: (() -> Unit)? = null

    fun submit(name: String, email: String, password: String, confirmation: String) {
        if (mutableState.value is RegisterState.Created || mutableState.value == RegisterState.Creating) return
        mutableState.value = RegisterState.Creating
        cancelAttempt = auth.register(name, email, password, confirmation) { result ->
            if (mutableState.value != RegisterState.Creating) return@register
            mutableState.value = result.fold({ RegisterState.Created(it) }, {
                RegisterState.Error(it as? AuthFailure ?: AuthFailure.UNKNOWN)
            })
        }
    }

    fun resend() {
        val current = mutableState.value as? RegisterState.Created ?: return
        if (current.busy || current.account.user.emailVerified) return
        mutableState.value = current.copy(busy = true, failure = null, notice = null)
        auth.sendVerification { result ->
            val now = mutableState.value as? RegisterState.Created ?: return@sendVerification
            mutableState.value = now.copy(busy = false,
                account = now.account.copy(verificationSent = result.isSuccess || now.account.verificationSent),
                notice = if (result.isSuccess) VerificationNotice.SENT else null,
                failure = result.exceptionOrNull() as? AuthFailure)
        }
    }

    fun refreshVerification() {
        val current = mutableState.value as? RegisterState.Created ?: return
        if (current.busy || current.account.user.emailVerified) return
        mutableState.value = current.copy(busy = true, failure = null, notice = null)
        auth.reloadVerification { result ->
            val now = mutableState.value as? RegisterState.Created ?: return@reloadVerification
            mutableState.value = result.fold({ updated -> now.copy(busy = false,
                account = now.account.copy(user = updated),
                notice = if (updated.emailVerified) VerificationNotice.VERIFIED else VerificationNotice.NOT_VERIFIED)
            }, { now.copy(busy = false, failure = it as? AuthFailure ?: AuthFailure.UNKNOWN) })
        }
    }

    fun leave() {
        if (mutableState.value == RegisterState.Creating) {
            mutableState.value = RegisterState.Idle
            cancelAttempt?.invoke()
        }
    }
    override fun onCleared() { leave(); super.onCleared() }
}
