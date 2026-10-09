package io.github.audiz.debug

actual object UiWatchdog {
    actual fun isRunning(): Boolean = false
    actual fun start(thresholdMs: Long) {}
    actual fun stop() {}
}
