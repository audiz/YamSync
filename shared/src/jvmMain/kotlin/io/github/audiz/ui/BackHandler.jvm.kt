package io.github.audiz.ui

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // В Desktop нет системной кнопки Назад
}
