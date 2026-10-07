package io.github.audiz.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.isActive
import io.github.audiz.dsp.AudioVisualizer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =========================================================
// Core button primitives
// =========================================================

@Composable
fun FilledIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "filledBtnScale"
    )

    Row(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(shape)
            .background(containerColor, shape)
            .clickable(
                enabled = !isLoading,
                interactionSource = interactionSource,
                onClick = onClick
            )
            .let { if (!isLoading) it.pointerHoverIcon(PointerIcon.Hand) else it },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.45f),
                strokeWidth = if (size < 28.dp) 1.8.dp else 2.5.dp,
                color = contentColor
            )
        } else {
            Icon(icon, contentDescription = contentDescription, tint = contentColor, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun OutlineIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    shape: Shape = CircleShape,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "outlineBtnScale"
    )

    Row(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(shape)
            .border(0.5.dp, borderColor, shape)
            .clickable(
                enabled = !isLoading,
                interactionSource = interactionSource,
                onClick = onClick
            )
            .let { if (!isLoading) it.pointerHoverIcon(PointerIcon.Hand) else it },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.55f),
                strokeWidth = if (size < 24.dp) 1.5.dp else 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(icon, contentDescription = contentDescription, tint = contentColor, modifier = Modifier.size(size * 0.44f))
        }
    }
}

@Composable
fun GhostIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    shape: Shape = CircleShape,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "ghostBtnScale"
    )

    Row(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                onClick = onClick
            )
            .pointerHoverIcon(PointerIcon.Hand),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = contentDescription, tint = contentColor, modifier = Modifier.size(size * 0.55f))
    }
}

/** Toggle button whose background is a low-opacity accent tint when active (used for Repeat). */
@Composable
fun TintedToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onClick: () -> Unit
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent
    val tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "toggleBtnScale"
    )

    Row(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                onClick = onClick
            )
            .pointerHoverIcon(PointerIcon.Hand),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

// =========================================================
// Track-row actions
// =========================================================

@Composable
fun DownloadStateButton(
    isDownloaded: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onClick: () -> Unit
) {
    if (isDownloaded) {
        FilledIconButton(
            icon = Icons.Filled.Check,
            contentDescription = "Downloaded",
            containerColor = Color(0xFF1D9E75),
            contentColor = Color.White,
            size = size,
            modifier = modifier,
            onClick = onClick
        )
    } else {
        OutlineIconButton(
            icon = Icons.Filled.Download,
            contentDescription = "Download",
            size = size,
            modifier = modifier,
            onClick = onClick
        )
    }
}

@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    onClick: () -> Unit
) {
    if (isFavorite) {
        FilledIconButton(
            icon = Icons.Filled.Favorite,
            contentDescription = "Favorited",
            size = size,
            modifier = modifier,
            onClick = onClick
        )
    } else {
        OutlineIconButton(
            icon = Icons.Filled.FavoriteBorder,
            contentDescription = "Favorite",
            size = size,
            modifier = modifier,
            onClick = onClick
        )
    }
}

@Composable
fun DislikeButton(
    isDisliked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    onClick: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    val tint = if (isDisliked) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val icon = if (isDisliked) Icons.Filled.Delete else Icons.Filled.DeleteOutline

    Box(
        modifier = modifier
            .size(size)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showConfirmDialog = true }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (isDisliked) "Не нравится" else "Дизлайк",
            tint = tint,
            modifier = Modifier.size(size * 0.85f)
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text(
                    text = if (isDisliked) "Убрать из корзины?" else "Не рекомендовать этот трек?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isDisliked) {
                        "Вы уверены, что хотите продолжить и вернуть этот трек в рекомендации?"
                    } else {
                        "Вы уверены, что хотите продолжить? Этот трек больше не будет воспроизводиться в Моей Волне."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        onClick()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDisliked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        contentColor = if (isDisliked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Продолжить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

/** 3-state repeat cycle: off -> all -> one -> off. */
enum class RepeatMode3 { OFF, ALL, ONE }

@Composable
fun RepeatButton(
    mode: RepeatMode3,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onClick: () -> Unit
) {
    TintedToggleButton(
        icon = if (mode == RepeatMode3.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
        contentDescription = "Repeat: ${mode.name.lowercase()}",
        isActive = mode != RepeatMode3.OFF,
        size = size,
        modifier = modifier,
        onClick = onClick
    )
}

// =========================================================
// Content-type icons (artist / album / track / playlist)
// =========================================================

enum class ContentType { ARTIST, ALBUM, TRACK, PLAYLIST }

@Composable
fun ContentTypeIcon(
    type: ContentType,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
) {
    val icon = when (type) {
        ContentType.ARTIST -> Icons.Filled.Person
        ContentType.ALBUM -> Icons.Filled.Album
        ContentType.TRACK -> Icons.Filled.MusicNote
        ContentType.PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistPlay
    }
    Icon(icon, contentDescription = type.name.lowercase(), tint = tint, modifier = modifier.size(size))
}

// =========================================================
// Volume controls
// =========================================================

@Composable
fun VolumeIcon(
    volume: Float,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val icon = when {
        volume <= 0.001f -> Icons.AutoMirrored.Filled.VolumeMute
        volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }
    Icon(icon, contentDescription = "Volume", tint = tint, modifier = modifier.size(size))
}

@Composable
fun VolumeButton(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp
) {
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
        modifier = modifier
            .size(size)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (volume > 0.001f) {
                        onVolumeChange(0f)
                    } else {
                        onVolumeChange(lastNonZeroVolume)
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Volume",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(size * 0.85f)
        )
    }
}

// =========================================================
// "Now playing" indicator — animated equalizer bars
// =========================================================

@Composable
fun NowPlayingIndicator(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barWidth: Dp = 2.8.dp,
    barSpacing: Dp = 1.6.dp,
    maxHeight: Dp = 16.dp
) {
    var frame by remember { mutableStateOf(AudioVisualizer.advanceFrame(0.016f, isPlaying)) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            frame = AudioVisualizer.advanceFrame(0.016f, false)
        } else {
            var lastTimeNs = 0L
            while (isActive) {
                androidx.compose.runtime.withFrameNanos { nowNs ->
                    val dt = if (lastTimeNs > 0L) {
                        ((nowNs - lastTimeNs) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
                    } else 0.016f
                    lastTimeNs = nowNs
                    frame = AudioVisualizer.advanceFrame(dt, true)
                }
            }
        }
    }

    val totalWidth = (barWidth * 5) + (barSpacing * 4)

    Canvas(
        modifier = modifier.size(width = totalWidth, height = maxHeight)
    ) {
        val maxH = size.height
        val w = barWidth.toPx()
        val spacing = barSpacing.toPx()
        val peakH = 1.6.dp.toPx()

        val levels = frame.levels
        val peakCaps = frame.peakCaps

        for (i in 0 until 5) {
            val level = if (i < levels.size) levels[i] else AudioVisualizer.RESTING_LEVEL
            val h = (maxH * level.coerceIn(0.12f, 1f)).coerceAtLeast(w)
            val x = i * (w + spacing)
            val y = maxH - h

            // 1. Основной столбик частоты (прямоугольный)
            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(w, h)
            )

            // 2. Парящая пиковая засечка (Winamp Peak Cap, прямоугольная)
            if (i < peakCaps.size) {
                val peakLevel = peakCaps[i]
                val targetPeakY = (maxH - (maxH * peakLevel.coerceIn(0.12f, 1f))).coerceIn(0f, maxH - peakH)
                val peakY = targetPeakY.coerceAtMost(y - 1.2f).coerceAtLeast(0f)
                if (y - peakY >= 0.5f) {
                    drawRect(
                        color = color.copy(alpha = 0.95f),
                        topLeft = Offset(x, peakY),
                        size = Size(w, peakH)
                    )
                }
            }
        }
    }
}

// =========================================================
// Quality chip — HQ / LQ + bitrate в виде стильной единой капсулы
// =========================================================

@Composable
fun QualityChip(
    bitrateKbps: Int,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    height: Dp = size,
    hqThreshold: Int = 256
) {
    val isHq = bitrateKbps >= hqThreshold
    val containerColor = if (isHq) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val contentColor = if (isHq) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (isHq) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    val bitrateText = if (bitrateKbps >= 1000) "${bitrateKbps / 1000}M" else "${bitrateKbps}k"

    Box(
        modifier = modifier
            .height(height)
            .background(containerColor, CircleShape)
            .border(0.5.dp, borderColor, CircleShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (isHq) "HQ" else "LQ",
                color = contentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "•",
                color = contentColor.copy(alpha = 0.5f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = bitrateText,
                color = contentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// =========================================================
// Top bar icon button — rounded square (back / search / filter)
// =========================================================

@Composable
fun TopBarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    outlined: Boolean = false,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    if (outlined) {
        OutlineIconButton(
            icon = icon,
            contentDescription = contentDescription,
            size = size,
            shape = RoundedCornerShape(10.dp),
            modifier = modifier,
            isLoading = isLoading,
            onClick = onClick
        )
    } else {
        GhostIconButton(
            icon = icon,
            contentDescription = contentDescription,
            size = size,
            shape = RoundedCornerShape(10.dp),
            modifier = modifier,
            onClick = onClick
        )
    }
}

// =========================================================
// Transport bar
// =========================================================

@Composable
fun TransportBar(
    isPlaying: Boolean,
    onShuffleClick: () -> Unit,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    isShuffleActive: Boolean = false,
    spacing: Dp = 6.dp,
    secondaryButtonSize: Dp = 30.dp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GhostIconButton(
            icon = Icons.Filled.Shuffle,
            contentDescription = "Shuffle",
            size = secondaryButtonSize,
            contentColor = if (isShuffleActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            onClick = onShuffleClick
        )
        OutlineIconButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "Previous",
            size = 36.dp,
            onClick = onPrevClick
        )
        FilledIconButton(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            size = 52.dp,
            onClick = onPlayPauseClick
        )
        OutlineIconButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = "Next",
            size = 36.dp,
            onClick = onNextClick
        )
        OutlineIconButton(
            icon = Icons.Filled.Settings,
            contentDescription = "Settings",
            size = 36.dp,
            onClick = onSettingsClick
        )
    }
}

/**
 * Кнопка сохранения трека на диск в нижнем плеере (Вариант 1):
 * - Если трек не скачан: контурная дискета (клик -> скачивание в библиотеку).
 * - Если скачивается: круговой индикатор загрузки.
 * - Если сохранен: залитая дискета (клик -> удаление из библиотеки).
 */
@Composable
fun RecordToDiskButton(
    isSaved: Boolean,
    isSaving: Boolean = false,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    onClick: () -> Unit
) {
    if (isSaving) {
        Box(
            modifier = modifier
                .size(size)
                .border(0.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.55f),
                strokeWidth = if (size < 24.dp) 1.5.dp else 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    } else if (isSaved) {
        FilledIconButton(
            icon = Icons.Filled.Save,
            contentDescription = "Сохранено на диск (нажмите для удаления)",
            size = size,
            modifier = modifier,
            onClick = onClick
        )
    } else {
        OutlineIconButton(
            icon = Icons.Filled.Save,
            contentDescription = "Сохранить на диск",
            size = size,
            modifier = modifier,
            onClick = onClick
        )
    }
}

/**
 * Кнопка «Поделиться» треком в нижнем плеере:
 * - Элегантная круглая кнопка с мягким фоном и плавной анимацией подтверждения копирования.
 * - При клике кратковременно отображает иконку галочки (зеленый акцент).
 */
@Composable
fun ShareTrackButton(
    isSharing: Boolean = false,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    onClick: () -> Unit
) {
    var justCopied by remember { mutableStateOf(false) }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1500)
            justCopied = false
        }
    }

    val containerColor = if (justCopied) {
        Color(0xFF1D9E75).copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val contentColor = if (justCopied) {
        Color(0xFF1D9E75)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (justCopied) {
        Color(0xFF1D9E75).copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    Box(
        modifier = modifier
            .size(size)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(containerColor, CircleShape)
            .border(0.5.dp, borderColor, CircleShape)
            .clickable(
                enabled = !isSharing,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    justCopied = true
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSharing) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.55f),
                strokeWidth = if (size < 24.dp) 1.5.dp else 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (justCopied) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Ссылка скопирована",
                tint = contentColor,
                modifier = Modifier.size(size * 0.55f)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = "Поделиться треком",
                tint = contentColor,
                modifier = Modifier.size(size * 0.52f)
            )
        }
    }
}

/**
 * ➕ Кнопка «Добавить в плейлист» в нижнем плеере:
 * - Элегантная круглая кнопка в едином стиле с ShareTrackButton.
 */
@Composable
fun AddToPlaylistPlayerButton(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    enabled: Boolean = true,
    isInPlaylist: Boolean = false,
    playlistCount: Int = 0,
    onClick: () -> Unit
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        isInPlaylist -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        isInPlaylist -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        isInPlaylist -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    val description = when {
        !isInPlaylist -> "Добавить в плейлист"
        playlistCount > 1 -> "В плейлистах ($playlistCount)"
        else -> "В плейлисте"
    }

    Box(
        modifier = modifier
            .size(size)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .background(containerColor, CircleShape)
            .border(0.5.dp, borderColor, CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isInPlaylist) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
            contentDescription = description,
            tint = contentColor,
            modifier = Modifier.size(size * 0.58f)
        )
    }
}

// =========================================================
// Usage example
// =========================================================

@Composable
fun PlayerButtonsDemo() {
    var isPlaying by remember { mutableStateOf(true) }
    var isDownloaded by remember { mutableStateOf(false) }
    var isFavorite by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(RepeatMode3.OFF) }
    var recordToDisk by remember { mutableStateOf(true) }

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TopBarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = {})
        TopBarIconButton(Icons.Filled.Search, "Search", outlined = true, onClick = {})
        TopBarIconButton(Icons.Filled.FilterList, "Filter", outlined = true, onClick = {})
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ContentTypeIcon(ContentType.ARTIST)
        ContentTypeIcon(ContentType.ALBUM)
        ContentTypeIcon(ContentType.TRACK)
        QualityChip(bitrateKbps = 320)
        QualityChip(bitrateKbps = 128)
        NowPlayingIndicator(isPlaying = isPlaying)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        DownloadStateButton(isDownloaded, onClick = { isDownloaded = !isDownloaded })
        FavoriteButton(isFavorite, onClick = { isFavorite = !isFavorite })
        RepeatButton(repeatMode, onClick = {
            repeatMode = when (repeatMode) {
                RepeatMode3.OFF -> RepeatMode3.ALL
                RepeatMode3.ALL -> RepeatMode3.ONE
                RepeatMode3.ONE -> RepeatMode3.OFF
            }
        })
        RecordToDiskButton(isSaved = recordToDisk, onClick = { recordToDisk = !recordToDisk })
    }

    TransportBar(
        isPlaying = isPlaying,
        onShuffleClick = {},
        onPrevClick = {},
        onPlayPauseClick = { isPlaying = !isPlaying },
        onNextClick = {},
        onSettingsClick = {}
    )
}
