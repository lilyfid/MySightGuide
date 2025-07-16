package com.lilianaisuan.mysightguide.viewmodel // ✅ Corrected Package

// This sealed class defines all the possible states for your UI.
sealed class AppState {
    object Ready : AppState()
    object Listening : AppState()
    data class Busy(val message: String) : AppState()
    data class Result(val textToSpeak: String) : AppState()
    data class Error(val message: String) : AppState()
}