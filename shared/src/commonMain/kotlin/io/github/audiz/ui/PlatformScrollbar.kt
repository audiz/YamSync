package io.github.audiz.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun PlatformScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
)

@Composable
expect fun PlatformScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier
)