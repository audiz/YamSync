package io.github.audiz

import android.content.Context
import android.os.PowerManager

private var transitionWakeLock: PowerManager.WakeLock? = null
private val wakeLockSync = Any()

actual fun acquirePlaybackWakeLock() {
    try {
        val context = AppContextHolder.appContext
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        synchronized(wakeLockSync) {
            if (transitionWakeLock == null) {
                transitionWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "YandexMusic:PlaybackWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            transitionWakeLock?.acquire(45_000L) // 45 секунд максимальный таймаут
            println("PlatformWakeLock Android: WakeLock захвачен (45s max timeout)")
        }
    } catch (e: Exception) {
        println("PlatformWakeLock Android: Ошибка захвата WakeLock: ${e.message}")
    }
}

actual fun releasePlaybackWakeLock() {
    try {
        synchronized(wakeLockSync) {
            if (transitionWakeLock?.isHeld == true) {
                transitionWakeLock?.release()
                println("PlatformWakeLock Android: WakeLock освобожден")
            }
        }
    } catch (e: Exception) {
        println("PlatformWakeLock Android: Ошибка освобождения WakeLock: ${e.message}")
    }
}
