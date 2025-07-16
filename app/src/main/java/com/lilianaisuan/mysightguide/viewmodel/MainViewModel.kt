package com.lilianaisuan.mysightguide.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lilianaisuan.mysightguide.ai.GemmaServiceProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AppState {
    object Listening : AppState()
    object Ready : AppState()
    data class Busy(val message: String) : AppState()
    data class Result(val textToSpeak: String) : AppState()
    data class Error(val message: String) : AppState()
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val gemmaServiceProvider: GemmaServiceProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppState>(AppState.Ready)
    val uiState = _uiState.asStateFlow()

    fun setListeningState() { _uiState.value = AppState.Listening }
    fun setReadyState() { _uiState.value = AppState.Ready }
    fun setBusy(message: String) { _uiState.value = AppState.Busy(message) }
    fun setResult(text: String) { _uiState.value = AppState.Result(text) }
    fun setError(message: String) { _uiState.value = AppState.Error(message) }

    // This function is now much simpler. It takes the bitmap and passes it along.
    fun generateSceneDescription(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.value = AppState.Busy("Analyzing the scene...")

            // The prompt for our multimodal model
            val prompt = "You are MySightGuide, an AI assistant for a visually impaired user. Describe this scene in a short, natural, and helpful sentence."

            val gemmaResponse = gemmaServiceProvider.generateSceneDescription(prompt, bitmap)
            _uiState.value = AppState.Result(gemmaResponse)
        }
    }
}