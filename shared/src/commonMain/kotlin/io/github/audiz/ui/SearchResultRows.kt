package io.github.audiz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.audiz.models.FullTrackInfo

private val CompactButtonSize = 30.dp

/** 1 234 → «1,2 тыс.», 2 811 657 → «2,8 млн». */
internal fun formatCompactCount(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000},${(value % 1_000_000) / 100_000} млн"
    value >= 10_000 -> "${value / 1_000} тыс."
    value >= 1_000 -> "${value / 1_000},${(value % 1_000) / 100} тыс."
    else -> value.toString()
}

/** Строка артиста, плейлиста или альбома: название на всю ширину, ниже тип и мета-информация. */
@Composable
internal fun CompactEntityRow(
    title: String,
    typeLabel: String,
    typeColor: Color,
    meta: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = typeColor, fontWeight = FontWeight.SemiBold)) { append(typeLabel) }
                    meta.filter { it.isNotBlank() }.forEach { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Компактная строка трека: название и длительность, ниже исполнитель и кнопки действий. */
@Composable
internal fun CompactTrackRow(
    track: FullTrackInfo,
    state: TrackItemState,
    actions: TrackItemActions,
    modifier: Modifier = Modifier
) {
    val artists = track.artists.joinToString { it.name }
    val album = track.albums.firstOrNull()?.title
    val subtitle = listOfNotNull(artists.takeIf { it.isNotBlank() }, album).joinToString(" · ")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (state.isActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                else Modifier
            )
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.isPlaying) {
                NowPlayingIndicator(isPlaying = true)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (track.durationMs > 0) {
                val sec = track.durationMs / 1000
                Text(
                    text = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.isActive) {
                    FilledIconButton(
                        icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Пауза" else "Воспроизвести",
                        size = CompactButtonSize,
                        onClick = actions.onTogglePlayPause
                    )
                } else {
                    OutlineIconButton(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = "Воспроизвести",
                        size = CompactButtonSize,
                        onClick = actions.onPlay
                    )
                }
                actions.onAddToPlaylist?.let { add ->
                    OutlineIconButton(
                        icon = if (state.isInPlaylist) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = if (state.isInPlaylist) "В плейлисте" else "Добавить в плейлист",
                        size = CompactButtonSize,
                        contentColor = if (state.isInPlaylist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        borderColor = if (state.isInPlaylist) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
                        onClick = add
                    )
                }
                if (state.isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(CompactButtonSize).padding(5.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    DownloadStateButton(
                        isDownloaded = state.isDownloaded,
                        size = CompactButtonSize,
                        onClick = actions.onDownload
                    )
                    if (state.isDownloaded && actions.onDelete != null) {
                        OutlineIconButton(
                            icon = Icons.Filled.DeleteOutline,
                            contentDescription = "Удалить с диска",
                            size = CompactButtonSize,
                            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = actions.onDelete
                        )
                    }
                }
            }
        }
        if (state.isActive) {
            Spacer(modifier = Modifier.height(4.dp))
            ThinTrackProgressBar(
                playbackPositionMs = state.playbackPositionMs,
                playbackDurationMs = state.playbackDurationMs,
                onSeek = actions.onSeek
            )
        }
    }
}
