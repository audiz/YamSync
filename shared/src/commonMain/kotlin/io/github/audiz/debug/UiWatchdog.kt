package io.github.audiz.debug

/**
 * 🐕 Сторожевой таймер (Watchdog) потока пользовательского интерфейса.
 * Отслеживает зависания главного потока и рендеринга (> 100 мс) и сохраняет
 * диагностический срез стека вызовов (thread stack traces) в лог.
 */
expect object UiWatchdog {
    fun isRunning(): Boolean
    fun start(thresholdMs: Long = 100L)
    fun stop()
}
