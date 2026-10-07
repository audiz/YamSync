package io.github.audiz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun DesktopVolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier,
    buttonSize: Dp,
    bottomPlayerPadding: Dp,
    hoverDelayMs: Long
) {
    var isPopupOpen by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isHoveredOnCard by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var hoverJob by remember { mutableStateOf<Job?>(null) }
    var lastNonZeroVolume by remember { mutableStateOf(0.7f) }

    if (volume > 0.001f) {
        lastNonZeroVolume = volume
    }

    val icon = when {
        volume <= 0.001f -> Icons.AutoMirrored.Filled.VolumeMute
        volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .pointerHoverIcon(PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val deltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (deltaY != 0f) {
                        event.changes.forEach { it.consume() }
                        // Скролл вверх (deltaY < 0) прибавляет громкость
                        val step = -deltaY.coerceIn(-5f, 5f) * 0.02f
                        onVolumeChange((volume + step).coerceIn(0f, 1f))
                        isPopupOpen = true
                    }
                }
                .onPointerEvent(PointerEventType.Enter) {
                    hoverJob?.cancel()
                    hoverJob = coroutineScope.launch {
                        delay(hoverDelayMs)
                        isPopupOpen = true
                    }
                }
                .onPointerEvent(PointerEventType.Exit) {
                    hoverJob?.cancel()
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        hoverJob?.cancel()
                        isPopupOpen = !isPopupOpen
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Volume",
                tint = if (isPopupOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(buttonSize * 0.85f)
            )
        }

        if (isPopupOpen) {
            val density = LocalDensity.current
            val popupOffsetY = with(density) { bottomPlayerPadding.roundToPx() }
            val cardWidth = maxOf(44.dp, buttonSize)

            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(x = 0, y = popupOffsetY),
                onDismissRequest = {
                    hoverJob?.cancel()
                    isPopupOpen = false
                },
                properties = PopupProperties(focusable = false)
            ) {
                ElevatedCard(
                    shape = RoundedCornerShape(22.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
                    ),
                    modifier = Modifier
                        .width(cardWidth)
                        .height(185.dp)
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(22.dp)
                        )
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val deltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (deltaY != 0f) {
                                event.changes.forEach { it.consume() }
                                val step = -deltaY.coerceIn(-5f, 5f) * 0.02f
                                onVolumeChange((volume + step).coerceIn(0f, 1f))
                            }
                        }
                        .onPointerEvent(PointerEventType.Enter) {
                            isHoveredOnCard = true
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            isHoveredOnCard = false
                            hoverJob?.cancel()
                            if (!isDragging) {
                                isPopupOpen = false
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 8.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val percent = (volume * 100).roundToInt()
                        Text(
                            text = "$percent",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            ThinVerticalSlider(
                                value = volume,
                                onValueChange = onVolumeChange,
                                modifier = Modifier.fillMaxSize(),
                                barWidth = 6.dp,
                                onDraggingChange = { dragging ->
                                    isDragging = dragging
                                    if (!dragging && !isHoveredOnCard) {
                                        isPopupOpen = false
                                    }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // Иконка громкости внизу карточки (перекрывает исходную кнопку)
                        Box(
                            modifier = Modifier
                                .size(buttonSize)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (volume > 0.001f) {
                                            lastNonZeroVolume = volume
                                            onVolumeChange(0f)
                                        } else {
                                            onVolumeChange(lastNonZeroVolume)
                                        }
                                    }
                                )
                                .pointerHoverIcon(PointerIcon.Hand),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = "Volume",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(buttonSize * 0.75f)
                            )
                        }
                    }
                }
            }
        }
    }
}