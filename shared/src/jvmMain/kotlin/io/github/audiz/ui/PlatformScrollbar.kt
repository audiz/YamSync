package io.github.audiz.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PlatformScrollbar(state: LazyListState, modifier: Modifier) {
    // Визуальное отображение полоски скролла отключено
}

@Composable
actual fun PlatformScrollbar(state: ScrollState, modifier: Modifier) {
    // Визуальное отображение полоски скролла отключено
}