package io.github.audiz.debug

import io.github.audiz.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

actual object UiWatchdog {
    @Volatile private var isRunning = false
    private var watchdogThread: Thread? = null
    private val isPingPending = AtomicBoolean(false)
    private val pingStartTime = AtomicLong(0L)
    @Volatile private var thresholdMs: Long = 100L
    @Volatile private var lastReportTime: Long = 0L

    actual fun isRunning(): Boolean = isRunning

    actual fun start(thresholdMs: Long) {
        if (isRunning) return
        this.thresholdMs = thresholdMs
        isRunning = true
        isPingPending.set(false)

        watchdogThread = Thread({
            AppLogger.i("UiWatchdog", "⚡ [UI-Watchdog] Сторожевой таймер интерфейса запущен (порог: ${thresholdMs}мс)")
            while (isRunning) {
                try {
                    val now = System.currentTimeMillis()
                    if (isPingPending.get()) {
                        val hungTime = now - pingStartTime.get()
                        if (hungTime >= this.thresholdMs) {
                            if (now - lastReportTime > 1000L) {
                                lastReportTime = now
                                dumpFreezeReport(hungTime)
                            }
                        }
                    } else {
                        isPingPending.set(true)
                        pingStartTime.set(now)
                        SwingUtilities.invokeLater {
                            val elapsed = System.currentTimeMillis() - pingStartTime.get()
                            if (elapsed >= this.thresholdMs && isRunning) {
                                AppLogger.i("UiWatchdog", "✅ [UI-Watchdog] UI-поток возобновил работу. Задержка кадра: ${elapsed}мс")
                            }
                            isPingPending.set(false)
                        }
                    }
                    Thread.sleep(30L)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    AppLogger.w("UiWatchdog", "Ошибка в цикле watchdog: ${e.message}")
                }
            }
            AppLogger.i("UiWatchdog", "⚡ [UI-Watchdog] Сторожевой таймер интерфейса остановлен")
        }, "YamSync-UI-Watchdog").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    actual fun stop() {
        if (!isRunning) return
        isRunning = false
        watchdogThread?.interrupt()
        watchdogThread = null
        isPingPending.set(false)
    }

    private fun dumpFreezeReport(hungTimeMs: Long) {
        val allThreads = Thread.getAllStackTraces()
        val edtThread = allThreads.keys.firstOrNull { it.name.startsWith("AWT-EventQueue") }
        val skikoThreads = allThreads.keys.filter { it.name.contains("Skiko", ignoreCase = true) }
        val relevantBackground = allThreads.keys.filter {
            it.name.contains("Ktor", ignoreCase = true) ||
            it.name.contains("LocalStreamProxy", ignoreCase = true) ||
            it.name.contains("JavaFX", ignoreCase = true)
        }

        val sb = StringBuilder()
        sb.append("⚠️ [UI-FREEZE DETECTED] Зависание потока интерфейса: ${hungTimeMs}мс!\n")

        if (edtThread != null) {
            sb.append("=== Главный поток UI: ${edtThread.name} (Состояние: ${edtThread.state}) ===\n")
            val stack = allThreads[edtThread] ?: emptyArray()
            stack.take(15).forEach { elem ->
                sb.append("    at $elem\n")
            }
        }

        for (skiko in skikoThreads) {
            sb.append("=== Поток Skiko: ${skiko.name} (Состояние: ${skiko.state}) ===\n")
            val stack = allThreads[skiko] ?: emptyArray()
            stack.take(10).forEach { elem ->
                sb.append("    at $elem\n")
            }
        }

        for (bg in relevantBackground.take(3)) {
            sb.append("=== Поток фоновой задачи: ${bg.name} (Состояние: ${bg.state}) ===\n")
            val stack = allThreads[bg] ?: emptyArray()
            stack.take(8).forEach { elem ->
                sb.append("    at $elem\n")
            }
        }

        AppLogger.w("UiWatchdog", sb.toString())
    }
}
