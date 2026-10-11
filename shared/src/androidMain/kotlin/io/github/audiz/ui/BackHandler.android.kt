package io.github.audiz.ui

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled) {
        try {
            onBack()
        } catch (e: Throwable) {
            println("BackHandler: Перехвачено исключение в onBack(): ${e.message}")
            e.printStackTrace()
        }
    }
}
