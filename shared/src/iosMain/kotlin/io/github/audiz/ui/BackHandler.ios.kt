package io.github.audiz.ui

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // В iOS нет системной кнопки Назад для закрытия оверлеев
}
