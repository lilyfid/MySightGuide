package com.lilianaisuan.mysightguide.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<AppState>(AppState.Ready)
    val uiState = _uiState.asStateFlow()

    fun setListeningState() { _uiState.value = AppState.Listening }
    fun setReadyState() { _uiState.value = AppState.Ready }
    fun setBusy(message: String) { _uiState.value = AppState.Busy(message) }
    fun setResult(text: String) { _uiState.value = AppState.Result(text) }
    fun setError(message: String) { _uiState.value = AppState.Error(message) }
}
