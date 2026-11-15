package io.github.audiz

import platform.UIKit.UIApplication

actual fun acquirePlaybackWakeLock() {}
actual fun releasePlaybackWakeLock() {}

actual fun acquireSyncScreenWakeLock(): () -> Unit {
    val application = UIApplication.sharedApplication
    val previousIdleTimerDisabled = application.idleTimerDisabled
    application.idleTimerDisabled = true
    var released = false
    return {
        if (!released) {
            released = true
            application.idleTimerDisabled = previousIdleTimerDisabled
        }
    }
}
