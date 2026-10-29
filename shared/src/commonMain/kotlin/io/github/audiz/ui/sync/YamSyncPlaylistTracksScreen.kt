package io.github.audiz.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.audiz.models.YamSyncPlaylistDiff
import io.github.audiz.models.YamSyncTransferProgress
import io.github.audiz.sync.YamSyncManager
import io.github.audiz.sync.YamSyncPlaylistTrackEntry
import io.github.audiz.ui.BackHandler

private enum class TrackFilter { MISSING, LOCAL, ALL }

/** Состояние строки трека с учётом идущих загрузок. */
private enum class TrackRowState { LOCAL, DOWNLOADING, QUEUED, FAILED, DOWNLOADABLE, UNAVAILABLE }

private fun rowState(
    entry: YamSyncPlaylistTrackEntry,
    transfer: YamSyncTransferProgress?,
    isQueued: Boolean
): TrackRowState = when {
    entry.isLocal -> TrackRowState.LOCAL
    transfer != null && transfer.isCompleted && transfer.error == null -> TrackRowState.LOCAL
    transfer != null && !transfer.isCompleted -> TrackRowState.DOWNLOADING
    isQueued -> TrackRowState.QUEUED
    transfer?.error != null -> TrackRowState.FAILED
    entry.isDownloadable -> TrackRowState.DOWNLOADABLE
    else -> TrackRowState.UNAVAILABLE
}

/**
 * 📃 Экран плейлиста: полный список треков с поиском, фильтром и скачиванием по одному
 */
@Composable
internal fun YamSyncPlaylistTracksScreen(
    syncManager: YamSyncManager,
    diff: YamSyncPlaylistDiff,
    onBack: () -> Unit
) {
    BackHandler(enabled = true, onBack = onBack)
    val peerName = syncManager.connectedDevice?.name ?: "другом устройстве"

    val entries by produceState<List<YamSyncPlaylistTrackEntry>?>(null, diff.playlistId, syncManager.diffVersion) {
        value = syncManager.loadPlaylistTracks(diff.playlistId)
    }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<TrackFilter?>(null) }

    val rows = entries.orEmpty().distinctBy { it.track.matchKey }.map { entry ->
        entry to rowState(entry, syncManager.fileTransfers[entry.track.matchKey], entry.track.matchKey in syncManager.queuedTrackKeys)
    }
    val localCount = rows.count { it.second == TrackRowState.LOCAL }
    val missingCount = rows.size - localCount
    // По умолчанию показываем то, чего здесь нет; если всё есть — весь список
    val activeFilter = filter ?: if (missingCount > 0) TrackFilter.MISSING else TrackFilter.ALL

    val visible = rows.filter { (entry, state) ->
        val passesFilter = when (activeFilter) {
            TrackFilter.MISSING -> state != TrackRowState.LOCAL
            TrackFilter.LOCAL -> state == TrackRowState.LOCAL
            TrackFilter.ALL -> true
        }
        passesFilter && (query.isBlank() ||
            entry.track.title.contains(query, ignoreCase = true) ||
            entry.track.artist.contains(query, ignoreCase = true))
    }
    val downloadable = rows.filter { it.second == TrackRowState.DOWNLOADABLE || it.second == TrackRowState.FAILED }.map { it.first.track }
    val downloadableBytes = downloadable.sumOf { it.fileSize }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад к плейлистам")
            }
            Text(
                text = diff.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // Компактная строка поиска в стиле поиска на главной
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        "Поиск в плейлисте",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { query = "" },
                    modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Очистить", modifier = Modifier.size(16.dp))
                }
            }
        }

        // Фильтры — компактные капсулы, помещаются в одну строку на узком экране
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                TrackFilter.MISSING to "Нет здесь · $missingCount",
                TrackFilter.LOCAL to "Есть · $localCount",
                TrackFilter.ALL to "Все · ${rows.size}"
            ).forEach { (f, label) ->
                val selected = activeFilter == f
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { filter = f }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                entries == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(32.dp))
                visible.isEmpty() -> Text(
                    text = if (query.isNotBlank()) "Ничего не найдено" else "Здесь пусто",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    items(visible, key = { it.first.track.matchKey }) { (entry, state) ->
                        TrackRow(
                            entry = entry,
                            state = state,
                            transfer = syncManager.fileTransfers[entry.track.matchKey],
                            peerName = peerName,
                            onDownload = { syncManager.downloadPlaylistTracks(diff.playlistId, listOf(entry.track)) },
                            onCancel = { syncManager.cancelTrackDownload(entry.track.matchKey) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }

        if (downloadable.isNotEmpty()) {
            Button(
                onClick = { syncManager.downloadPlaylistTracks(diff.playlistId, downloadable) },
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .height(44.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                val size = if (downloadableBytes > 0) " · ${formatMb(downloadableBytes)}" else ""
                Text("Скачать все ${downloadable.size}$size", fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TrackRow(
    entry: YamSyncPlaylistTrackEntry,
    state: TrackRowState,
    transfer: YamSyncTransferProgress?,
    peerName: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    val track = entry.track
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" — "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = when (state) {
                TrackRowState.LOCAL -> "есть здесь"
                TrackRowState.DOWNLOADING -> "скачивается ${transfer?.progressPercent ?: 0}%"
                TrackRowState.QUEUED -> "в очереди"
                TrackRowState.FAILED -> "не удалось скачать"
                TrackRowState.DOWNLOADABLE -> if (track.fileSize > 0) formatMb(track.fileSize) else "можно скачать"
                TrackRowState.UNAVAILABLE -> "файла нет на $peerName"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    TrackRowState.FAILED -> MaterialTheme.colorScheme.error
                    TrackRowState.DOWNLOADING, TrackRowState.QUEUED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            when (state) {
                TrackRowState.LOCAL -> Icon(
                    Icons.Default.Check,
                    contentDescription = "Есть здесь",
                    tint = Color(0xFF2DD881),
                    modifier = Modifier.size(20.dp)
                )
                TrackRowState.DOWNLOADING -> CircularProgressIndicator(
                    progress = { transfer?.progressFraction ?: 0f },
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(24.dp)
                )
                TrackRowState.QUEUED -> IconButton(onClick = onCancel, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Icon(Icons.Default.Close, contentDescription = "Убрать из очереди", modifier = Modifier.size(18.dp))
                }
                TrackRowState.DOWNLOADABLE, TrackRowState.FAILED -> OutlinedIconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = if (state == TrackRowState.FAILED) Icons.Default.Refresh else Icons.Default.Download,
                        contentDescription = if (state == TrackRowState.FAILED) "Повторить" else "Скачать",
                        modifier = Modifier.size(16.dp)
                    )
                }
                TrackRowState.UNAVAILABLE -> Icon(
                    Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
