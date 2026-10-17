package io.github.audiz.ui

import androidx.compose.runtime.Composable
import io.github.audiz.util.AppLogger

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled) {
        try {
            onBack()
        } catch (e: Throwable) {
            AppLogger.e("BackHandler", "Перехвачено исключение в onBack(): ${e.message}")
        }
    }
}
