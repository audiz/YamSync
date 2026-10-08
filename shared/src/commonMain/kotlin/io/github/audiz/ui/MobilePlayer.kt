package io.github.audiz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.PI
import kotlinx.coroutines.isActive
import io.github.audiz.dsp.AudioVisualizer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import io.github.audiz.getPlatform

/**
 * 📱 Компактный мини-плеер внизу экрана для мобильных устройств (iOS / Android)
 * - Высота: ~62 dp.
 * - Крупные зоны нажатия для пальцев (42-48 dp).
 * - Тонкий прогресс-бар в самом низу карточки.
 * - Клик по карточке раскрывает полноэкранный плеер.
 */
@Composable
fun MobileMiniPlayerBar(
    state: PlayerBarState,
    actions: PlayerBarActions,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentProgress = if (state.durationMs > 0) {
        (state.currentPositionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val coverBitmap = rememberCoverBitmap(state.coverUri, 200)

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 🔼 Индикатор свайпа вверх (Drag Handle капсула, как на большом плеере)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -12f) {
                                onExpand()
                            }
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onExpand
                    )
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp, bottom = 2.dp)
                        .size(width = 36.dp, height = 3.5.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            CircleShape
                        )
                )
            }

            // ⏱ Шкала прогресса над плеером (только индикация, без перемотки и перехвата касаний)
            MiniPlayerProgressBar(
                progress = currentProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .padding(top = 1.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 10.dp)
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 🎵 Информация о треке и обложка (нажатие или свайп вверх открывает полный плеер)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -12f) {
                                    onExpand()
                                }
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onExpand
                        )
                        .pointerHoverIcon(PointerIcon.Hand),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 🎨 Миниатюра / Иконка воспроизведения слева
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (coverBitmap != null) {
                            Image(
                                bitmap = coverBitmap,
                                contentDescription = "Обложка трека",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (state.isPlaying && !state.isPaused) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.28f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    NowPlayingIndicator(
                                        isPlaying = true,
                                        color = Color.White,
                                        maxHeight = 14.dp
                                    )
                                }
                            }
                        } else if (state.isPlaying && !state.isPaused) {
                            NowPlayingIndicator(
                                isPlaying = true,
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxHeight = 18.dp
                            )
                        } else if (state.isPaused) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = "Пауза",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = "Музыка",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 📝 Название трека и артист
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        val fullTitle = if (state.artistName.isNotBlank()) "${state.artistName} — ${state.trackTitle}" else state.trackTitle
                        BouncingMarqueeText(
                            text = fullTitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.fillMaxWidth(),
                            enableCopy = false
                        )
                        val isThematicWave = state.isWave && !state.waveTitle.isNullOrBlank() && !state.waveTitle.equals("Моя Волна", ignoreCase = true)
                        if (state.isWave) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val waveLabel = if (isThematicWave) "🌊 Моя Волна • ${state.waveTitle}" else "🌊 Моя Волна"
                                Text(
                                    text = waveLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isThematicWave && actions.onResetWave != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { actions.onResetWave.invoke() }
                                            )
                                            .pointerHoverIcon(PointerIcon.Hand),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Сбросить к обычной волне",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 🔘 Кнопки быстрого управления на ходу (42-48 dp): Назад, Play/Pause, Вперёд
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Предыдущий трек (42 dp) - заменил лайк
                    OutlineIconButton(
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = "Предыдущий",
                        size = 42.dp,
                        isLoading = state.isPrevLoading,
                        onClick = { actions.onPrev?.invoke() }
                    )

                    // Play / Pause (48 dp)
                    FilledIconButton(
                        icon = if (state.isPlaying && !state.isPaused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying && !state.isPaused) "Пауза" else "Воспроизведение",
                        size = 48.dp,
                        isLoading = state.isPlayLoading,
                        onClick = actions.onPlayPause
                    )

                    // Следующий трек (42 dp)
                    OutlineIconButton(
                        icon = Icons.Filled.SkipNext,
                        contentDescription = "Следующий",
                        size = 42.dp,
                        isLoading = state.isNextLoading,
                        onClick = { actions.onNext?.invoke() }
                    )
                }
            }
        }
    }
}

/**
 * ⏱ Неинтерактивная шкала прогресса для мини-плеера (только индикация).
 * Не перехватывает клики и жесты, позволяя свободно нажимать и свайпать мини-плеер вверх.
 */
@Composable
private fun MiniPlayerProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.5.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.90f))
        )
    }
}

/**
 * 📲 Полноэкранный раскрывающийся экран плеера (Full-screen Sheet) для iOS / Android
 * - Открывается по тапу на мини-плеер.
 * - Огромные кнопки Play (68 dp), Prev/Next (52 dp), комфортный скруббер (36 dp touch target).
 * - Свайп вниз и кнопка «Свернуть» для закрытия.
 * - Полный доступ ко всем функциям: качество, эквалайзер, сохранение на диск, плейлист, шаринг.
 */
@Composable
fun MobileFullPlayerSheet(
    state: PlayerBarState,
    actions: PlayerBarActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Поддержка системной кнопки "Назад" (Android)
    BackHandler(enabled = true, onBack = onDismiss)

    val isDesktop = remember { !getPlatform().isMobile }

    val coroutineScope = rememberCoroutineScope()
    var dragOffsetY by remember { mutableStateOf(0f) }
    val animatedOffsetY = remember { Animatable(0f) }

    var isDraggingScrubber by remember { mutableStateOf(false) }
    var scrubberProgress by remember { mutableStateOf(0f) }

    val currentMs = if (isDraggingScrubber && state.durationMs > 0) {
        (scrubberProgress * state.durationMs).toLong()
    } else {
        state.currentPositionMs
    }

    val sliderValue = if (isDraggingScrubber) {
        scrubberProgress
    } else if (state.durationMs > 0) {
        (state.currentPositionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val coverBitmap = rememberCoverBitmap(state.coverUri, 600)
    val ambilightPalette = remember(coverBitmap) { extractAmbilightPalette(coverBitmap) }

    val infiniteTransition = rememberInfiniteTransition()
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = if (state.isPlaying && !state.isPaused) 0.65f else 0.50f,
        targetValue = if (state.isPlaying && !state.isPaused) 0.90f else 0.50f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = if (state.isPlaying && !state.isPaused) 1.00f else 1.00f,
        targetValue = if (state.isPlaying && !state.isPaused) 1.04f else 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val dismissDragModifier = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
                if (dragAmount > 0 || dragOffsetY > 0) {
                    dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                    change.consume()
                }
            },
            onDragEnd = {
                if (dragOffsetY > 120f) {
                    onDismiss()
                } else {
                    coroutineScope.launch {
                        animatedOffsetY.snapTo(dragOffsetY)
                        dragOffsetY = 0f
                        animatedOffsetY.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
                    }
                }
            },
            onDragCancel = {
                dragOffsetY = 0f
            }
        )
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .offset { IntOffset(0, (dragOffsetY + animatedOffsetY.value).roundToInt()) },
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==========================================
            // 1. ВЕРХНЯЯ ШАПКА: Drag Handle + Навигация
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dismissDragModifier),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Полоска-индикатор для свайпа (Drag Handle)
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp, bottom = 12.dp)
                        .size(width = 40.dp, height = 4.5.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            CircleShape
                        )
                )

                // Строка навигации: Свернуть (слева), Режим (по центру)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Свернуть плеер",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    val isThematicWave = state.isWave && !state.waveTitle.isNullOrBlank() && !state.waveTitle.equals("Моя Волна", ignoreCase = true)
                    val contextLabel = when {
                        isThematicWave -> "МОЯ ВОЛНА • ${state.waveTitle}"
                        state.isWave -> "МОЯ ВОЛНА"
                        else -> "СЕЙЧАС ИГРАЕТ"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = contextLabel.uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isThematicWave && actions.onResetWave != null) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { actions.onResetWave.invoke() }
                                    )
                                    .pointerHoverIcon(PointerIcon.Hand),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Сбросить к обычной волне",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    // Кнопка настроек в правом верхнем углу плеера
                    if (actions.onConfigClick != null) {
                        IconButton(
                            onClick = { actions.onConfigClick.invoke() },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Настройки",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        // Распорка справа для симметрии
                        Spacer(modifier = Modifier.size(44.dp))
                    }
                }
            }

            // ==========================================
            // 2. ЦЕНТР: Большая обложка с мягким Ambilight-свечением (как на референсе с iOS)
            // ==========================================
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 14.dp)
                    .aspectRatio(1f)
                    .fillMaxWidth(0.80f)
                    .then(dismissDragModifier),
                contentAlignment = Alignment.Center
            ) {
                // 🌟 1. Фоновый Ambilight-ореол за обложкой (как на референсе с iOS)
                val glowLeft = ambilightPalette?.left ?: MaterialTheme.colorScheme.primary
                val glowRight = ambilightPalette?.right ?: MaterialTheme.colorScheme.secondary

                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@Canvas

                    val maxDistance = 46.dp.toPx()
                    val baseCornerRadius = 24.dp.toPx()
                    val steps = 24

                    for (i in steps downTo 1) {
                        val d = (i.toFloat() / steps) * maxDistance
                        val factor = d / maxDistance
                        // Гауссов колоколообразный спад e^(-2.2 * factor^2)
                        val alpha = (breathingAlpha * 0.13f * exp(-2.2f * factor * factor)).coerceIn(0f, 1f)

                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    glowLeft.copy(alpha = alpha),
                                    glowRight.copy(alpha = alpha)
                                ),
                                startX = -d,
                                endX = w + d
                            ),
                            topLeft = Offset(-d, -d),
                            size = Size(w + 2 * d, h + 2 * d),
                            cornerRadius = CornerRadius(baseCornerRadius + d, baseCornerRadius + d)
                        )
                    }
                }

                // 🖼️ 2. Сама чёткая карточка с обложкой альбома поверх ореола
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            RoundedCornerShape(24.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = actions.onPlayPause
                        )
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = "Обложка альбома",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            if (state.isPaused) {
                                Icon(
                                    imageVector = Icons.Filled.Pause,
                                    contentDescription = "Пауза",
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    modifier = Modifier.size(64.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.MusicNote,
                                    contentDescription = "Музыка",
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(72.dp)
                                )
                            }
                        }
                    }

                    // 🎛️ Вариант 1: Неоновый спектральный эквалайзер вдоль нижнего края обложки
                    CoverSpectrumVisualizer(
                        isPlaying = state.isPlaying && !state.isPaused,
                        glowLeft = glowLeft,
                        glowRight = glowRight,
                        onOpenEqualizer = actions.onOpenEqualizer,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }

            // ==========================================
            // 3. ИНФОРМАЦИЯ О ТРЕКЕ: Заголовок + Артист + Лайк
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                ) {
                    BouncingMarqueeText(
                        text = state.trackTitle,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (state.artistName.isNotBlank()) state.artistName else "Яндекс Музыка",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Кнопка Лайк (48 dp)
                FavoriteButton(
                    isFavorite = state.isFavorite,
                    size = 48.dp,
                    onClick = { actions.onToggleFavorite?.invoke() }
                )
            }

            // ==========================================
            // 4. СКРУББЕР / СЛАЙДЕР ВРЕМЕНИ
            // ==========================================
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                ThinSlider(
                    value = sliderValue,
                    onValueChange = { newProgress ->
                        isDraggingScrubber = true
                        scrubberProgress = newProgress
                    },
                    onValueChangeFinished = { finalFraction ->
                        if (state.durationMs > 0) {
                            actions.onSeek((finalFraction * state.durationMs).toLong())
                        }
                        isDraggingScrubber = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    barHeight = 6.dp,
                    touchTargetHeight = 36.dp
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentMs),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTime(state.durationMs),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ==========================================
            // 5. ОСНОВНЫЕ КНОПКИ ВОСПРОИЗВЕДЕНИЯ (44-68 dp)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Перемешать (44 dp)
                GhostIconButton(
                    icon = Icons.Filled.Shuffle,
                    contentDescription = "Случайный порядок",
                    size = 44.dp,
                    contentColor = if (state.isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    onClick = { actions.onToggleShuffle?.invoke() }
                )

                // Предыдущий трек (52 dp)
                OutlineIconButton(
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = "Предыдущий",
                    size = 52.dp,
                    isLoading = state.isPrevLoading,
                    onClick = { actions.onPrev?.invoke() }
                )

                // Play / Pause гигантская кнопка (68 dp)
                FilledIconButton(
                    icon = if (state.isPlaying && !state.isPaused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying && !state.isPaused) "Пауза" else "Воспроизведение",
                    size = 68.dp,
                    isLoading = state.isPlayLoading,
                    onClick = actions.onPlayPause
                )

                // Следующий трек (52 dp)
                OutlineIconButton(
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "Следующий",
                    size = 52.dp,
                    isLoading = state.isNextLoading,
                    onClick = { actions.onNext?.invoke() }
                )

                if (isDesktop) {
                    // Регулятор громкости на ПК вместо корзинки (44 dp)
                    DesktopVolumeControl(
                        volume = state.volume,
                        onVolumeChange = actions.onVolumeChange,
                        buttonSize = 44.dp,
                        bottomPlayerPadding = 0.dp
                    )
                } else {
                    // Дизлайк на мобильных устройствах (44 dp)
                    DislikeButton(
                        isDisliked = state.isDisliked,
                        isDownloadedOrLocal = state.isDownloadedOrLocal,
                        size = 44.dp,
                        onClick = { actions.onToggleDislike?.invoke() }
                    )
                    if (actions.onOpenQueue != null) {
                        IconButton(
                            onClick = {
                                actions.onOpenQueue.invoke()
                                onDismiss()
                            },
                            modifier = Modifier.size(44.dp).pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Очередь воспроизведения",
                                tint = if (state.isQueueOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // ==========================================
            // 6. СТРОКА ДЕЙСТВИЙ (Качество, EQ, Диск, Плейлист, Шаринг)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Слева: Качество битрейта (капсула) + на ПК в режиме мобильного корзинка справа от битрейта
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.bitrate != null && state.bitrate > 0) {
                        QualityChip(bitrateKbps = state.bitrate, size = 32.dp)
                    } else if (!isDesktop) {
                        Spacer(modifier = Modifier.width(32.dp))
                    }

                    if (isDesktop) {
                        DislikeButton(
                            isDisliked = state.isDisliked,
                            isDownloadedOrLocal = state.isDownloadedOrLocal,
                            size = 36.dp,
                            onClick = { actions.onToggleDislike?.invoke() }
                        )
                        if (actions.onOpenQueue != null) {
                            IconButton(
                                onClick = {
                                    actions.onOpenQueue.invoke()
                                    onDismiss()
                                },
                                modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "Очередь воспроизведения",
                                    tint = if (state.isQueueOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Эквалайзер (40 dp)
                    if (actions.onOpenEqualizer != null) {
                        IconButton(
                            onClick = { actions.onOpenEqualizer.invoke() },
                            modifier = Modifier
                                .size(40.dp)
                                .pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Эквалайзер",
                                tint = if (state.isEqualizerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Сохранить на диск / Скачать (40 dp)
                    if (actions.onToggleRecordToDisk != null) {
                        RecordToDiskButton(
                            isSaved = state.isRecordToDisk,
                            isSaving = state.isSavingToDisk,
                            size = 40.dp,
                            onClick = { actions.onToggleRecordToDisk.invoke() }
                        )
                    }

                    // Добавить в плейлист (40 dp)
                    if (actions.onAddToPlaylist != null) {
                        val canAddToPlaylist = state.hasTrackCandidate || (state.trackTitle.isNotBlank() && state.trackTitle != "Выберите трек")
                        AddToPlaylistPlayerButton(
                            size = 40.dp,
                            enabled = canAddToPlaylist,
                            isInPlaylist = state.isInPlaylist,
                            playlistCount = state.playlistCount,
                            onClick = { actions.onAddToPlaylist.invoke() }
                        )
                    }

                    // Поделиться треком (40 dp)
                    if (actions.onShareTrack != null) {
                        ShareTrackButton(
                            isSharing = state.isSharing,
                            size = 40.dp,
                            onClick = { actions.onShareTrack.invoke() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 💡 Цветовая палитра для фоновой Ambilight-подсветки вокруг обложки
 */
data class AmbilightPalette(
    val left: Color,
    val right: Color,
    val center: Color
)

/**
 * 🎨 Безопасное извлечение насыщенных (vibrant) цветов из обложки альбома.
 * Игнорирует белые/черные/серые рамки и находит истинные акцентные цвета арта.
 */
fun extractAmbilightPalette(bitmap: androidx.compose.ui.graphics.ImageBitmap?): AmbilightPalette? {
    if (bitmap == null) return null
    return runCatching {
        val pixelMap = bitmap.toPixelMap()
        val w = pixelMap.width
        val h = pixelMap.height
        if (w <= 0 || h <= 0) return null

        fun findVibrantInRegion(minX: Int, maxX: Int): Color {
            var bestScore = 0f
            var bestColor = Color.Transparent
            val stepX = ((maxX - minX) / 12).coerceAtLeast(1)
            val stepY = (h / 12).coerceAtLeast(1)

            for (x in minX until maxX step stepX) {
                for (y in 0 until h step stepY) {
                    val c = pixelMap[x, y]
                    val rf = c.red
                    val gf = c.green
                    val bf = c.blue

                    val maxVal = maxOf(rf, gf, bf)
                    val minVal = minOf(rf, gf, bf)
                    val delta = maxVal - minVal
                    val saturation = if (maxVal > 0.05f) delta / maxVal else 0f
                    val brightness = maxVal

                    // Отсеиваем слишком тёмные или блеклые пиксели (рамки/фон)
                    if (brightness in 0.15f..0.98f && saturation > 0.20f) {
                        val score = saturation * brightness
                        if (score > bestScore) {
                            bestScore = score
                            bestColor = c
                        }
                    }
                }
            }
            return if (bestScore > 0f) bestColor else pixelMap[minX + (maxX - minX) / 2, h / 2]
        }

        val leftColor = findVibrantInRegion(0, w / 2)
        val rightColor = findVibrantInRegion(w / 2, w)
        val centerColor = pixelMap[w / 2, h / 2]

        AmbilightPalette(
            left = leftColor,
            right = rightColor,
            center = centerColor
        )
    }.getOrNull()
}

/**
 * 🎛️ Неоновый спектральный визуализатор Winamp Pro вдоль нижнего края обложки альбома (Вариант 1)
 *
 * Особенности:
 * - 19 честных аппаратных частотных полос Winamp Pro (20 Гц .. 20 кГц), рассчитанных прямо из аудиопотока.
 * - Парящие пиковые засечки (Peak Caps) с физикой свободного падения Winamp Pro.
 * - Цветовой градиент от левого к правому цвету текущей палитры обложки (Ambilight glowLeft -> glowRight).
 * - Полупрозрачный темный градиент-подложка снизу карточки для четкой видимости на любых обложках.
 * - Клик по любой точке спектра открывает 10-полосный эквалайзер.
 */
@Composable
fun CoverSpectrumVisualizer(
    isPlaying: Boolean,
    glowLeft: Color,
    glowRight: Color,
    onOpenEqualizer: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var frame by remember {
        mutableStateOf(AudioVisualizer.advanceSpectrum19Frame(0.016f, isPlaying))
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            frame = AudioVisualizer.advanceSpectrum19Frame(0.016f, false)
        } else {
            var lastTimeNs = 0L
            while (isActive) {
                androidx.compose.runtime.withFrameNanos { nowNs ->
                    val dt = if (lastTimeNs > 0L) {
                        ((nowNs - lastTimeNs) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
                    } else 0.016f
                    lastTimeNs = nowNs
                    frame = AudioVisualizer.advanceSpectrum19Frame(dt, true)
                }
            }
        }
    }

    val numBars = AudioVisualizer.SPECTRUM_BAND_COUNT
    val barLevels = frame.levels
    val capLevels = frame.peakCaps

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0x33080C16),
                        Color(0x99080C16),
                        Color(0xDE080C16)
                    )
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = onOpenEqualizer != null,
                onClick = { onOpenEqualizer?.invoke() }
            )
            .pointerHoverIcon(if (onOpenEqualizer != null) PointerIcon.Hand else PointerIcon.Default)
    ) {
        // 📊 Спектральные столбики с парящими пиками (19 полос Winamp Pro)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        ) {
            val totalW = size.width
            val maxH = size.height
            if (totalW <= 0f || maxH <= 0f) return@Canvas

            val spacing = 4.dp.toPx()
            val availableW = totalW - 20.dp.toPx()
            val barW = ((availableW - (numBars - 1) * spacing) / numBars).coerceIn(4.dp.toPx(), 14.dp.toPx())
            val totalBarsW = numBars * barW + (numBars - 1) * spacing
            val startX = (totalW - totalBarsW) / 2f
            val peakH = 2.5.dp.toPx()

            val barBrush = Brush.horizontalGradient(
                colors = listOf(
                    glowLeft.copy(alpha = 0.92f),
                    glowRight.copy(alpha = 0.92f)
                ),
                startX = startX,
                endX = startX + totalBarsW
            )

            for (i in 0 until numBars) {
                val level = if (i < barLevels.size) barLevels[i] else AudioVisualizer.RESTING_LEVEL
                val h = (maxH * level.coerceIn(0.08f, 1f)).coerceAtLeast(barW)
                val x = startX + i * (barW + spacing)
                val y = maxH - h

                // 1. Основной частотный столбик (прямоугольный, с закруглением нижнего внешнего уголка для крайних полос)
                if (i == 0 || i == numBars - 1) {
                    val cornerR = (barW * 0.75f).coerceAtMost(h)
                    val roundRect = RoundRect(
                        x,
                        y,
                        x + barW,
                        y + h,
                        CornerRadius.Zero,
                        CornerRadius.Zero,
                        if (i == numBars - 1) CornerRadius(cornerR, cornerR) else CornerRadius.Zero,
                        if (i == 0) CornerRadius(cornerR, cornerR) else CornerRadius.Zero
                    )
                    val barPath = Path().apply {
                        addRoundRect(roundRect)
                    }
                    drawPath(
                        path = barPath,
                        brush = barBrush
                    )
                } else {
                    drawRect(
                        brush = barBrush,
                        topLeft = Offset(x, y),
                        size = Size(barW, h)
                    )
                }

                // 2. Парящая пиковая засечка (Winamp Peak Cap, прямоугольная)
                val capLevel = if (i < capLevels.size) capLevels[i].coerceAtLeast(level) else level
                val targetPeakY = (maxH - (maxH * capLevel.coerceIn(0.08f, 1f))).coerceIn(0f, maxH - peakH)
                val peakY = targetPeakY.coerceAtMost(y - 2.dp.toPx()).coerceAtLeast(0f)
                drawRect(
                    color = Color.White.copy(alpha = 0.88f),
                    topLeft = Offset(x, peakY),
                    size = Size(barW, peakH)
                )
            }
        }
    }
}



