package io.github.audiz.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.audiz.models.FullTrackInfo

/**
 * 🌊 Карточка «Моя Волна» для главного экрана.
 * Показывает ТОЛЬКО один текущий активный трек из загруженной пачки в 5 треков.
 */
@Composable
fun WaveTrackCard(
    currentTrack: FullTrackInfo?,
    currentIndex: Int,
    totalTracks: Int,
    isLoading: Boolean,
    isPlaying: Boolean,
    isPaused: Boolean,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onDownload: (trackId: String, trackTitle: String, artistName: String) -> Unit,
    isTrackDownloaded: Boolean,
    isDownloading: Boolean,
    onSeek: (Long) -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 🏷️ Верхняя строка: Заголовок "Моя Волна" + индикатор трека (N из 5) + кнопка обновления
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("🌊", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Text(
                        text = "Моя Волна",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (totalTracks > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = "Трек ${currentIndex + 1} из $totalTracks",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onReload,
                        enabled = !isLoading,
                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Обновить Волну",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (currentTrack != null) {
                val artistsString = currentTrack.artists.joinToString { it.name }.ifBlank { "Неизвестный исполнитель" }
                val albumTitle = currentTrack.albums.firstOrNull()?.title

                // 🎵 Текущий трек (ОДИН трек из пачки)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Иконка обложки / трека
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Название трека и артист
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTrack.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = artistsString,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!albumTitle.isNullOrBlank()) {
                            Text(
                                text = "💿 $albumTitle",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 🎛️ Кнопки управления треком
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ⬇️ Скачивание
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp).padding(6.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            DownloadStateButton(
                                isDownloaded = isTrackDownloaded,
                                size = 36.dp,
                                onClick = { onDownload(currentTrack.id, currentTrack.title, artistsString) }
                            )
                        }

                        // ▶️ / ⏸ Play / Pause
                        FilledIconButton(
                            icon = if (isPlaying && !isPaused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying && !isPaused) "Пауза" else "Воспроизвести",
                            size = 40.dp,
                            onClick = onPlayPause
                        )

                        // ⏭ Кнопка Next (пропуск трека и перезагрузка списка из 5 треков)
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp).padding(6.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            OutlineIconButton(
                                icon = Icons.Filled.SkipNext,
                                contentDescription = "Следующий (обновить пачку треков)",
                                size = 36.dp,
                                onClick = onNext
                            )
                        }
                    }
                }

                // 📊 Прогресс-бар воспроизведения для активного трека Волны
                AnimatedVisibility(visible = isPlaying || isPaused) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        ThinTrackProgressBar(
                            playbackPositionMs = playbackPositionMs,
                            playbackDurationMs = playbackDurationMs,
                            onSeek = onSeek
                        )
                    }
                }
            } else {
                // Если сессия еще не загружена или пуста
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onReload,
                        enabled = !isLoading,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isLoading) "⏳ Настраиваем волну..." else "▶️ Запустить Мою Волну")
                    }
                }
            }
        }
    }
}
