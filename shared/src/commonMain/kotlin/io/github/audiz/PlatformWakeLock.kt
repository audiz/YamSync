package io.github.audiz

/**
 * 🔋 Управление системным WakeLock для предотвращения засыпания процессора
 * во время переключения и загрузки следующего трека при выключенном экране.
 */
expect fun acquirePlaybackWakeLock()
expect fun releasePlaybackWakeLock()

/** Удерживать экран во время открытой сессии синхронизации. Захват и освобождение — в UI-потоке. */
expect fun acquireSyncScreenWakeLock(): () -> Unit
