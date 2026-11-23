package com.finalexam.to_do_list.controller.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Retains the in-flight write across rotations; never submits the same form twice. */
class AddTaskViewModel : ViewModel() {
    enum class SaveState { IDLE, SAVING, SAVED, ERROR }
    private val mutableState = MutableStateFlow(SaveState.IDLE)
    val state = mutableState.asStateFlow()
    fun save(write: ((Result<Unit>) -> Unit) -> Unit) {
        if (mutableState.value == SaveState.SAVING || mutableState.value == SaveState.SAVED) return
        mutableState.value = SaveState.SAVING
        write {
            mutableState.value = if (it.isSuccess) SaveState.SAVED else SaveState.ERROR
        }
    }
}
