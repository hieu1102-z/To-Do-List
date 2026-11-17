package com.finalexam.to_do_list.controller.auth

import androidx.lifecycle.ViewModel
import com.finalexam.to_do_list.model.repository.AuthFailure
import com.finalexam.to_do_list.model.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data object Success : LoginState
    data class Error(val failure: AuthFailure) : LoginState
}

/** Survives configuration changes without retaining an Activity, email or password. */
class LoginViewModel(private val auth: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<LoginState>(LoginState.Idle)
    val state = mutableState.asStateFlow()
    private var cancelAttempt: (() -> Unit)? = null

    fun submit(email: String, password: String) {
        if (mutableState.value == LoginState.Loading || mutableState.value == LoginState.Success) return
        mutableState.value = LoginState.Loading
        cancelAttempt = auth.signIn(email, password) { result ->
            // Cancellation when leaving must not cause a delayed navigation.
            if (mutableState.value != LoginState.Loading) return@signIn
            mutableState.value = result.fold({ LoginState.Success }, {
                LoginState.Error(it as? AuthFailure ?: AuthFailure.UNKNOWN)
            })
        }
    }

    fun leave() {
        if (mutableState.value == LoginState.Loading) {
            mutableState.value = LoginState.Idle
            cancelAttempt?.invoke()
        }
    }

    override fun onCleared() { leave(); super.onCleared() }
}
