package io.github.audiz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.audiz.models.FullTrackInfo

/**
 * 📦 Состояние отображения трека в списке
 */
data class TrackItemState(
    val isDownloading: Boolean = false,
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val isDownloaded: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
)

/**
 * ⚡ Обработчики действий для трека
 */
data class TrackItemActions(
    val onPlay: () -> Unit,
    val onTogglePlayPause: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onDownload: () -> Unit,
    val onDelete: (() -> Unit)? = null,
    val onAddToPlaylist: (() -> Unit)? = null,
    val onRemoveFromPlaylist: (() -> Unit)? = null,
)

/**
 * 🎵 Единая карточка трека для экранов артиста, плейлистов и результатов поиска.
 */
@Composable
fun TrackItemCard(
    track: FullTrackInfo,
    state: TrackItemState,
    actions: TrackItemActions,
    modifier: Modifier = Modifier,
    showAlbum: Boolean = true,
) {
    val artistsString = track.artists.joinToString { it.name }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            // 🎵 Верхняя строка: Название трека слева + Время трека СПРАВА
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.isPlaying) {
                        NowPlayingIndicator(isPlaying = true)
                    } else {
                        ContentTypeIcon(ContentType.TRACK, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // ⏱ Длительность трека
                if (track.durationMs > 0) {
                    val min = (track.durationMs / 1000) / 60
                    val sec = (track.durationMs / 1000) % 60
                    Text(
                        text = "${min}:${sec.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            // 👤 Нижняя строка: Артист и Альбом слева + Кнопочки СПРАВА (под временем)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (track.artists.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ContentTypeIcon(ContentType.ARTIST, modifier = Modifier.size(14.dp))
                            Text(
                                text = artistsString,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (showAlbum && track.albums.isNotEmpty()) {
                        val albumTitle = track.albums.firstOrNull()?.title ?: "Single"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ContentTypeIcon(ContentType.ALBUM, modifier = Modifier.size(14.dp))
                            Text(
                                text = "Альбом: $albumTitle",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 🔘 Кнопки управления под временем
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isActive) {
                        // ⏸ / ▶️ Кнопка паузы / возобновления
                        FilledIconButton(
                            icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Пауза" else "Воспроизвести",
                            size = 36.dp,
                            onClick = actions.onTogglePlayPause
                        )
                    } else {
                        // ▶️ Кнопка воспроизведения
                        OutlineIconButton(
                            icon = Icons.Filled.PlayArrow,
                            contentDescription = "Воспроизвести",
                            size = 36.dp,
                            onClick = actions.onPlay
                        )
                    }

                    // ➕ Добавить в локальный плейлист
                    if (actions.onAddToPlaylist != null) {
                        OutlineIconButton(
                            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = "Добавить в плейлист",
                            size = 36.dp,
                            onClick = actions.onAddToPlaylist
                        )
                    }

                    // ⬇️ Кнопка скачивания / Скачан
                    if (state.isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp).padding(6.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        DownloadStateButton(
                            isDownloaded = state.isDownloaded,
                            size = 36.dp,
                            onClick = actions.onDownload
                        )
                        if (actions.onRemoveFromPlaylist != null) {
                            OutlineIconButton(
                                icon = Icons.Filled.DeleteOutline,
                                contentDescription = "Убрать из плейлиста",
                                size = 36.dp,
                                borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.error,
                                onClick = actions.onRemoveFromPlaylist
                            )
                        } else if (state.isDownloaded && actions.onDelete != null) {
                            OutlineIconButton(
                                icon = Icons.Filled.DeleteOutline,
                                contentDescription = "Удалить с диска",
                                size = 36.dp,
                                borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.error,
                                onClick = actions.onDelete
                            )
                        }
                    }
                }
            }

            // 📊 Тонкий прогресс-бар для активного трека
            if (state.isActive) {
                Spacer(modifier = Modifier.height(6.dp))
                ThinTrackProgressBar(
                    playbackPositionMs = state.playbackPositionMs,
                    playbackDurationMs = state.playbackDurationMs,
                    onSeek = actions.onSeek
                )
            }
        }
    }
}
