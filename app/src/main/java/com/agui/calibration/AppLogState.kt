package com.agui.calibration

import androidx.compose.runtime.mutableStateOf

internal class AppLogState {

    val logs = mutableStateOf(listOf<String>())

    fun append(message: String) {
        logs.value = (logs.value + "${System.currentTimeMillis()}: $message").takeLast(80)
    }

    fun appendMultiLine(message: String) {
        message.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach(::append)
    }

    fun clear() {
        logs.value = emptyList()
    }
}