package io.github.audiz.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        isPopupOpen = !isPopupOpen
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Громкость",
                tint = if (isPopupOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(buttonSize * 0.85f)
            )
        }

        if (isPopupOpen) {
            val density = LocalDensity.current
            val popupOffsetY = with(density) { bottomPlayerPadding.roundToPx() }
            val cardWidth = maxOf(46.dp, buttonSize)

            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(x = 0, y = popupOffsetY),
                onDismissRequest = {
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
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 8.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val percent = (volume * 100).roundToInt()
                        Text(
                            text = "$percent%",
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
                                barWidth = 7.dp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
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
                                contentDescription = "Отключить/включить звук",
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