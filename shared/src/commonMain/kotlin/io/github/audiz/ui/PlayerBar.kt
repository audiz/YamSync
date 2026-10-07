package io.github.audiz.ui

import io.github.audiz.getPlatform
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 📦 Данные состояния нижнего плеера
 */
data class PlayerBarState(
    val trackTitle: String = "Выберите трек",
    val artistName: String = "",
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bitrate: Int? = null,
    val volume: Float = 1f,
    val isShuffle: Boolean = false,
    val isNextLoading: Boolean = false,
    val isPrevLoading: Boolean = false,
    val isPlayLoading: Boolean = false,
    val isRecordToDisk: Boolean = false,
    val isSavingToDisk: Boolean = false,
    val isSharing: Boolean = false,
    val isFavorite: Boolean = false,
    val isDisliked: Boolean = false,
    val isInPlaylist: Boolean = false,
    val playlistCount: Int = 0,
    val isConfigActive: Boolean = false,
    val isEqualizerActive: Boolean = false,
    val hasTrackCandidate: Boolean = false,
    val isWave: Boolean = false,
    val waveTitle: String? = null,
    val coverUri: String? = null,
    val isMobile: Boolean = false,
)

/**
 * ⚡ Действия и колбэки нижнего плеера
 */
data class PlayerBarActions(
    val onVolumeChange: (Float) -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onToggleShuffle: (() -> Unit)? = null,
    val onNext: (() -> Unit)? = null,
    val onPrev: (() -> Unit)? = null,
    val onConfigClick: (() -> Unit)? = null,
    val onToggleRecordToDisk: (() -> Unit)? = null,
    val onShareTrack: (() -> Unit)? = null,
    val onAddToPlaylist: (() -> Unit)? = null,
    val onToggleFavorite: (() -> Unit)? = null,
    val onToggleDislike: (() -> Unit)? = null,
    val onOpenEqualizer: (() -> Unit)? = null,
    val onExpandMobilePlayer: (() -> Unit)? = null,
    val onResetWave: (() -> Unit)? = null,
)

/**
 * 🎵 Панель аудиоплеера внизу экрана:
 * - На мобильных (iOS / Android): компактный мини-плеер с крупными кнопками 44-48 dp.
 * - На десктопе (Linux, macOS, Windows): полноразмерная панель со всеми регуляторами и возможностью ультракомпактного окна.
 */
@Composable
fun PlayerBar(
    state: PlayerBarState,
    actions: PlayerBarActions,
    modifier: Modifier = Modifier
) {
    if (state.isMobile) {
        MobileMiniPlayerBar(
            state = state,
            actions = actions,
            onExpand = { actions.onExpandMobilePlayer?.invoke() },
            modifier = modifier
        )
    } else {
        DesktopPlayerBar(
            state = state,
            actions = actions,
            modifier = modifier
        )
    }
}

/**
 * 🖥️ Десктопная панель аудиоплеера (сохраняет 100% функционала и компактный вид на ПК)
 */
@Composable
fun DesktopPlayerBar(
    state: PlayerBarState,
    actions: PlayerBarActions,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }
    val displayPosition = if (isDragging && state.durationMs > 0) (dragProgress * state.durationMs).toLong() else state.currentPositionMs

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)
        ) {
            // 🎵 1. Название трека и исполнитель выше всего остального (на 100% ширины)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isPaused) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = "Пауза",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (state.isPlaying) {
                    NowPlayingIndicator(isPlaying = true)
                } else {
                    ContentTypeIcon(ContentType.TRACK, modifier = Modifier.size(16.dp))
                }
                val fullTitle = if (state.artistName.isNotBlank()) "${state.artistName} — ${state.trackTitle}" else state.trackTitle
                BouncingMarqueeText(
                    text = fullTitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // ⏱ 2. Таймер воспроизведения слева, сохранение на диск и качество справа
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Таймер воспроизведения и кнопка эквалайзера справа от него
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${formatTime(displayPosition)} / ${formatTime(state.durationMs)}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )

                    if (actions.onOpenEqualizer != null) {
                        IconButton(
                            onClick = { actions.onOpenEqualizer.invoke() },
                            modifier = Modifier
                                .size(24.dp)
                                .pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Эквалайзер и срезы частот",
                                tint = if (state.isEqualizerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Справа: кнопка поделиться + кнопка сохранения на диск (слева от качества) + качество воспроизведения
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (actions.onShareTrack != null) {
                        ShareTrackButton(
                            isSharing = state.isSharing,
                            size = 28.dp,
                            onClick = { actions.onShareTrack.invoke() }
                        )
                    }

                    if (actions.onAddToPlaylist != null) {
                        val canAddToPlaylist = state.hasTrackCandidate || (state.trackTitle.isNotBlank() && state.trackTitle != "Выберите трек")
                        AddToPlaylistPlayerButton(
                            size = 28.dp,
                            enabled = canAddToPlaylist,
                            isInPlaylist = state.isInPlaylist,
                            playlistCount = state.playlistCount,
                            onClick = { actions.onAddToPlaylist.invoke() }
                        )
                    }

                    if (actions.onToggleRecordToDisk != null) {
                        RecordToDiskButton(
                            isSaved = state.isRecordToDisk,
                            isSaving = state.isSavingToDisk,
                            size = 28.dp,
                            onClick = { actions.onToggleRecordToDisk.invoke() }
                        )
                    }

                    if (state.bitrate != null && state.bitrate > 0) {
                        QualityChip(bitrateKbps = state.bitrate, size = 28.dp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 🎚 3. Прогресс-бар (Slider с поддержкой плавного перетаскивания)
            val progress = if (isDragging) {
                dragProgress
            } else if (state.durationMs > 0) {
                (state.currentPositionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            ThinSlider(
                value = progress,
                onValueChange = { newProgress ->
                    isDragging = true
                    dragProgress = newProgress
                },
                onValueChangeFinished = { finalFraction ->
                    if (state.durationMs > 0) {
                        actions.onSeek((finalFraction * state.durationMs).toLong())
                    }
                    isDragging = false
                },
                modifier = Modifier.fillMaxWidth(),
                barHeight = 5.dp,
                touchTargetHeight = 14.dp
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 🔘 4. Нижняя строка: Кнопка дизлайка (слева), Кнопки управления (по центру), Громкость / Лайк (в правом углу)
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth()
            ) {
                val centerSpacing = if (maxWidth < 360.dp) 3.dp else 6.dp
                val standardButtonSize = 36.dp

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Слева: Кнопка дизлайка
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        DislikeButton(
                            isDisliked = state.isDisliked,
                            size = standardButtonSize,
                            onClick = { actions.onToggleDislike?.invoke() }
                        )
                    }

                    // По центру: Кнопки управления (Shuffle, Prev, Play/Pause, Next, Settings, Лайк на Desktop)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(centerSpacing)
                    ) {
                        GhostIconButton(
                            icon = Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            size = standardButtonSize,
                            contentColor = if (state.isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            onClick = { actions.onToggleShuffle?.invoke() }
                        )

                        OutlineIconButton(
                            icon = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            size = standardButtonSize,
                            isLoading = state.isPrevLoading,
                            onClick = { actions.onPrev?.invoke() }
                        )

                        FilledIconButton(
                            icon = if (state.isPlaying && !state.isPaused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying && !state.isPaused) "Pause" else "Play",
                            size = 52.dp,
                            isLoading = state.isPlayLoading,
                            onClick = actions.onPlayPause
                        )

                        OutlineIconButton(
                            icon = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            size = standardButtonSize,
                            isLoading = state.isNextLoading,
                            onClick = { actions.onNext?.invoke() }
                        )

                        OutlineIconButton(
                            icon = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            size = standardButtonSize,
                            borderColor = if (state.isConfigActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            contentColor = if (state.isConfigActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (state.isConfigActive) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape) else Modifier,
                            onClick = { actions.onConfigClick?.invoke() }
                        )

                        if (!state.isMobile) {
                            FavoriteButton(
                                isFavorite = state.isFavorite,
                                size = standardButtonSize,
                                onClick = { actions.onToggleFavorite?.invoke() }
                            )
                        }
                    }

                    // В правом углу: Регулятор громкости (Desktop) или кнопка Лайк (на мобильных)
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (!state.isMobile) {
                            DesktopVolumeControl(
                                volume = state.volume,
                                onVolumeChange = actions.onVolumeChange,
                                buttonSize = standardButtonSize
                            )
                        } else {
                            FavoriteButton(
                                isFavorite = state.isFavorite,
                                size = standardButtonSize,
                                onClick = { actions.onToggleFavorite?.invoke() }
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return "$minutes:$seconds"
}
