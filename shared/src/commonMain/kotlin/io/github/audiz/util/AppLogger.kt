package io.github.audiz.util

import io.github.audiz.appendLogToFile
import io.github.audiz.core.CoreLogger
import io.github.audiz.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppLogger {
    private val logsList = mutableListOf<String>()
    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow = _logsFlow.asStateFlow()
    private val lock = Any()

    init {
        // Подключаем слушатель из CoreLogger
        CoreLogger.listener = { level, tag, message, throwable ->
            log(level, tag, message, throwable)
        }
    }

    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun w(tag: String, message: String) = log("WARN", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log("ERROR", tag, message, throwable)

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        val entry = "[$level][$tag] $fullMsg"
        println(entry)

        synchronized(lock) {
            if (logsList.size > 500) {
                logsList.removeAt(0)
            }
            logsList.add(entry)
            _logsFlow.value = logsList.toList()
        }

        try {
            appendLogToFile(entry)
        } catch (_: Throwable) {}
    }

    fun getLogsText(): String {
        return synchronized(lock) {
            logsList.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) {
            logsList.clear()
            _logsFlow.value = emptyList()
        }
    }
}
