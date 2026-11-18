package io.github.audiz.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
    val isQueueOpen: Boolean = false,
    val isDownloadedOrLocal: Boolean = false,
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
    val onOpenQueue: (() -> Unit)? = null,
    val onOpenArtist: (() -> Unit)? = null,
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

/** Компактный десктопный плеер с симметричными группами действий вокруг управления. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopPlayerBar(
    state: PlayerBarState,
    actions: PlayerBarActions,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }
    var justCopiedShare by remember { mutableStateOf(false) }
    val displayPosition = if (isDragging && state.durationMs > 0) {
        (dragProgress * state.durationMs).toLong()
    } else state.currentPositionMs
    val coverBitmap = rememberCoverBitmap(state.coverUri, 120)

    LaunchedEffect(justCopiedShare) {
        if (justCopiedShare) {
            delay(1500)
            justCopiedShare = false
        }
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            val buttonSize = 28.dp
            val controlsInOneRow = maxWidth >= 248.dp
            val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            val primary = MaterialTheme.colorScheme.primary

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f).widthIn(min = 96.dp).heightIn(min = buttonSize),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverBitmap != null) {
                                Image(
                                    bitmap = coverBitmap,
                                    contentDescription = "Обложка трека",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else Icon(Icons.Filled.MusicNote, null, modifier = Modifier.size(16.dp), tint = tint)
                        }
                        NowPlayingIndicator(
                            isPlaying = state.isPlaying && !state.isPaused,
                            barWidth = 2.dp,
                            barSpacing = 1.dp,
                            maxHeight = 14.dp
                        )
                        Text(
                            text = if (state.isWave) state.waveTitle?.let { "Моя Волна • $it" } ?: "Моя Волна" else "Сейчас играет",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isWave) primary else tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (state.isWave && !state.waveTitle.isNullOrBlank()) {
                            CompactPlayerAction(
                                icon = Icons.Filled.Close,
                                description = "Сбросить к персональной Волне",
                                size = 20.dp,
                                onClick = actions.onResetWave
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${formatTime(displayPosition)} / ${formatTime(state.durationMs)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", fontSize = 10.sp),
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.bitrate?.takeIf { it > 0 }?.let { bitrate ->
                            val bitrateText = if (bitrate >= 1000) "${bitrate / 1000}M" else "${bitrate}k"
                            Text(
                                text = "${if (bitrate >= 256) "HQ" else "LQ"} · $bitrateText",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DesktopVolumeControl(volume = state.volume, onVolumeChange = actions.onVolumeChange, buttonSize = buttonSize)
                    }
                }

                val fullTitle = if (state.artistName.isNotBlank()) "${state.artistName} — ${state.trackTitle}" else state.trackTitle
                BouncingMarqueeText(
                    text = fullTitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
                    modifier = Modifier.fillMaxWidth()
                )

                ThinSlider(
                    value = if (isDragging) dragProgress else if (state.durationMs > 0) {
                        (state.currentPositionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else 0f,
                    onValueChange = { dragProgress = it; isDragging = true },
                    onValueChangeFinished = {
                        if (state.durationMs > 0) actions.onSeek((it * state.durationMs).toLong())
                        isDragging = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    barHeight = 5.dp,
                    touchTargetHeight = 14.dp
                )

                val leftActions: @Composable () -> Unit = {
                    FlowRow(
                        modifier = Modifier.widthIn(max = buttonSize * 2 + 4.dp),
                        maxItemsInEachRow = 2,
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        DislikeButton(
                            isDisliked = state.isDisliked,
                            isDownloadedOrLocal = state.isDownloadedOrLocal,
                            size = buttonSize,
                            onClick = { actions.onToggleDislike?.invoke() }
                        )
                        CompactPlayerAction(
                            Icons.Filled.Shuffle, "Случайный порядок", actions.onToggleShuffle,
                            active = state.isShuffle
                        )
                        CompactPlayerAction(
                            Icons.Filled.Tune, "Эквалайзер и срезы частот", actions.onOpenEqualizer,
                            active = state.isEqualizerActive
                        )
                        CompactPlayerAction(
                            Icons.AutoMirrored.Filled.FormatListBulleted, "Очередь воспроизведения", actions.onOpenQueue,
                            active = state.isQueueOpen
                        )
                    }
                }
                val rightActions: @Composable () -> Unit = {
                    FlowRow(
                        modifier = Modifier.widthIn(max = buttonSize * 2 + 4.dp),
                        maxItemsInEachRow = 2,
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        CompactPlayerAction(
                            if (state.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            if (state.isFavorite) "Убрать из любимых" else "Добавить в любимые",
                            actions.onToggleFavorite,
                            active = state.isFavorite
                        )
                        CompactPlayerAction(
                            Icons.Filled.Save,
                            if (state.isRecordToDisk) "Сохранено на диск (нажмите для удаления)" else "Сохранить на диск",
                            actions.onToggleRecordToDisk,
                            active = state.isRecordToDisk,
                            isLoading = state.isSavingToDisk
                        )
                        CompactPlayerAction(
                            if (state.isInPlaylist) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
                            if (state.isInPlaylist) "В плейлистах (${state.playlistCount})" else "Добавить в плейлист",
                            actions.onAddToPlaylist,
                            active = state.isInPlaylist,
                            enabled = state.hasTrackCandidate || (state.trackTitle.isNotBlank() && state.trackTitle != "Выберите трек")
                        )
                        CompactPlayerAction(
                            if (justCopiedShare) Icons.Filled.Check else Icons.Filled.Share,
                            if (justCopiedShare) "Ссылка скопирована" else "Поделиться треком",
                            actions.onShareTrack?.let { share -> { share(); justCopiedShare = true } },
                            active = justCopiedShare,
                            isLoading = state.isSharing
                        )
                    }
                }
                val transport: @Composable () -> Unit = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlineIconButton(
                            Icons.Filled.SkipPrevious, "Предыдущий трек", size = buttonSize,
                            isLoading = state.isPrevLoading, onClick = { actions.onPrev?.invoke() }
                        )
                        FilledIconButton(
                            if (state.isPlaying && !state.isPaused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (state.isPlaying && !state.isPaused) "Пауза" else "Воспроизвести",
                            size = 44.dp, isLoading = state.isPlayLoading, onClick = actions.onPlayPause
                        )
                        OutlineIconButton(
                            Icons.Filled.SkipNext, "Следующий трек", size = buttonSize,
                            isLoading = state.isNextLoading, onClick = { actions.onNext?.invoke() }
                        )
                    }
                }

                if (controlsInOneRow) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { leftActions() }
                        transport()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { rightActions() }
                    }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { transport() }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        leftActions()
                        rightActions()
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactPlayerAction(
    icon: ImageVector,
    description: String,
    onClick: (() -> Unit)?,
    size: Dp = 28.dp,
    active: Boolean = false,
    isLoading: Boolean = false,
    enabled: Boolean = true
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp))
            .clickable(
                enabled = enabled && onClick != null && !isLoading,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick?.invoke() }
            )
            .pointerHoverIcon(if (enabled && onClick != null && !isLoading) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(
                icon, contentDescription = description,
                tint = if (enabled && onClick != null) tint else tint.copy(alpha = 0.3f),
                modifier = Modifier.size(minOf(17.dp, size * 0.65f))
            )
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return "$minutes:$seconds"
}
