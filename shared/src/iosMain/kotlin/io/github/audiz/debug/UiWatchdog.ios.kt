package io.github.audiz.debug

import io.github.audiz.util.AppLogger
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlinx.coroutines.*
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.Volatile
import io.ktor.util.date.getTimeMillis

actual object UiWatchdog {
    @Volatile
    private var isRunning = false
    private var watchdogJob: Job? = null
    private val watchdogScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isPingPending = AtomicInt(0)
    private val pingStartTime = AtomicLong(0L)
    @Volatile
    private var thresholdMs: Long = 100L
    @Volatile
    private var lastReportTime: Long = 0L

    actual fun isRunning(): Boolean = isRunning

    actual fun start(thresholdMs: Long) {
        if (isRunning) return
        this.thresholdMs = thresholdMs
        isRunning = true
        isPingPending.value = 0

        AppLogger.i("UiWatchdog", "⚡ [UI-Watchdog iOS] Сторожевой таймер интерфейса запущен (порог: ${thresholdMs}мс)")

        watchdogJob = watchdogScope.launch {
            while (isRunning) {
                try {
                    val now = getTimeMillis()
                    if (isPingPending.value == 1) {
                        val hungTime = now - pingStartTime.value
                        if (hungTime >= this@UiWatchdog.thresholdMs) {
                            if (now - lastReportTime > 1000L) {
                                lastReportTime = now
                                AppLogger.w(
                                    "UiWatchdog",
                                    "⚠️ [UI-FREEZE DETECTED iOS] Зависание главного потока интерфейса (Main Queue): ${hungTime}мс!"
                                )
                            }
                        }
                    } else {
                        isPingPending.value = 1
                        pingStartTime.value = now
                        dispatch_async(dispatch_get_main_queue()) {
                            val elapsed = getTimeMillis() - pingStartTime.value
                            if (elapsed >= this@UiWatchdog.thresholdMs && isRunning) {
                                AppLogger.i(
                                    "UiWatchdog",
                                    "✅ [UI-Watchdog iOS] UI-поток возобновил работу. Задержка кадра: ${elapsed}мс"
                                )
                            }
                            isPingPending.value = 0
                        }
                    }
                    delay(30L)
                } catch (_: CancellationException) {
                    break
                } catch (e: Throwable) {
                    AppLogger.w("UiWatchdog", "Ошибка в цикле watchdog iOS: ${e.message}")
                }
            }
            AppLogger.i("UiWatchdog", "⚡ [UI-Watchdog iOS] Сторожевой таймер интерфейса остановлен")
        }
    }

    actual fun stop() {
        if (!isRunning) return
        isRunning = false
        watchdogJob?.cancel()
        watchdogJob = null
        isPingPending.value = 0
    }
}
