package com.lilianaisuan.mysightguide.viewmodel

sealed class AppState {
    object Listening : AppState()
    object Ready : AppState()
    data class Busy(val message: String) : AppState()
    data class Result(val textToSpeak: String) : AppState()
    data class Error(val message: String) : AppState()
}