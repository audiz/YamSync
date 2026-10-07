package io.github.audiz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
 * Тонкий вертикальный слайдер громкости на всю высоту контейнера.
 */
@Composable
fun ThinVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    barWidth: Dp = 6.dp,
    onDraggingChange: ((Boolean) -> Unit)? = null
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnDraggingChange by rememberUpdatedState(onDraggingChange)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val height = size.height
                    if (height > 0) {
                        currentOnDraggingChange?.invoke(true)
                        var currentFraction = (1f - (down.position.y / height)).coerceIn(0f, 1f)
                        currentOnValueChange(currentFraction)
                        val pointerId = down.id

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null || !change.pressed) {
                                    break
                                }
                                change.consume()
                                currentFraction = (1f - (change.position.y / height)).coerceIn(0f, 1f)
                                currentOnValueChange(currentFraction)
                            }
                        } finally {
                            currentOnDraggingChange?.invoke(false)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Фоновая полоса (трек)
        Box(
            modifier = Modifier
                .width(barWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(barWidth / 2))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // Активная заполненная часть (снизу вверх)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(value.coerceIn(0f, 1f))
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(barWidth / 2))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/**
 * Круглая кнопка громкости с вертикальным регулятором для Desktop.
 */
@Composable
expect fun DesktopVolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 28.dp,
    bottomPlayerPadding: Dp = 0.dp,
    hoverDelayMs: Long = 120L
)
