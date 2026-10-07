package io.github.audiz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Универсальный тонкий интерактивный слайдер без кругляшка/точки/ползунка (просто чистая полоска).
 */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 2.5.dp,
    touchTargetHeight: Dp = 16.dp,
    onValueChangeFinished: ((Float) -> Unit)? = null
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    BoxWithConstraints(
        modifier = modifier
            .height(touchTargetHeight)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width
                    if (width > 0) {
                        var currentFraction = (down.position.x / width).coerceIn(0f, 1f)
                        currentOnValueChange(currentFraction)
                        val pointerId = down.id

                        var finished = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null || !change.pressed) {
                                    finished = true
                                    currentOnValueChangeFinished?.invoke(currentFraction)
                                    break
                                }
                                change.consume()
                                currentFraction = (change.position.x / width).coerceIn(0f, 1f)
                                currentOnValueChange(currentFraction)
                            }
                        } finally {
                            if (!finished) {
                                currentOnValueChangeFinished?.invoke(currentFraction)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Фоновая полоска (неактивная часть)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        // Заполненная полоска (активная часть)
        Box(
            modifier = Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * Тонкий интерактивный прогресс-бар для списка треков (без кругляшка/точки, просто тонкая полоска).
 */
@Composable
fun ThinTrackProgressBar(
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }

    val progress = if (isDragging) {
        dragProgress
    } else if (playbackDurationMs > 0) {
        (playbackPositionMs.toFloat() / playbackDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    ThinSlider(
        value = progress,
        onValueChange = {
            isDragging = true
            dragProgress = it
        },
        onValueChangeFinished = { finalFraction ->
            if (playbackDurationMs > 0) {
                onSeek((finalFraction * playbackDurationMs).toLong())
            }
            isDragging = false
        },
        modifier = modifier.fillMaxWidth(),
        barHeight = 2.dp,
        touchTargetHeight = 12.dp
    )
}
