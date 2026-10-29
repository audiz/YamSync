package io.github.audiz.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.audiz.models.YamSyncDiffState
import io.github.audiz.models.YamSyncPlaylistDiff
import io.github.audiz.models.YamSyncResolution
import io.github.audiz.sync.YamSyncManager
import io.github.audiz.sync.YamSyncPlaylistProgress

/**
 * 🔀 Вкладка «Плейлисты»: у каждого плейлиста своя синхронизация
 */
@Composable
internal fun YamSyncPlaylistsTab(syncManager: YamSyncManager) {
    if (!syncManager.isConnected) {
        SyncEmptyState(
            icon = Icons.Default.LinkOff,
            title = "Нет соединения",
            subtitle = "Сначала свяжите устройства на вкладке «Связь»"
        )
        return
    }

    if (syncManager.playlistDiffs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("Сравнение плейлистов…", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    val peerName = syncManager.connectedDevice?.name ?: "другого устройства"
    val pendingCount = syncManager.playlistDiffs.count {
        it.state != YamSyncDiffState.IDENTICAL && !syncManager.playlistSyncProgress.containsKey(it.playlistId)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (pendingCount > 0) "Отличаются: $pendingCount" else "Всё синхронизировано",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (pendingCount > 0) {
                    OutlinedButton(
                        onClick = { syncManager.syncAllPlaylists() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp).pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Синхронизировать всё ($pendingCount)", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
        items(syncManager.playlistDiffs, key = { it.playlistId }) { diff ->
            PlaylistSyncCard(
                diff = diff,
                peerName = peerName,
                missingCount = syncManager.playlistMissingCounts[diff.playlistId] ?: 0,
                progress = syncManager.playlistSyncProgress[diff.playlistId],
                onSync = { resolution -> syncManager.syncPlaylist(diff.playlistId, resolution) },
                onCancel = { syncManager.cancelPlaylistSync(diff.playlistId) }
            )
        }
    }
}

@Composable
internal fun SyncEmptyState(icon: ImageVector, title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(44.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class DiffBadge(val text: String, val color: Color)

@Composable
private fun diffBadge(state: YamSyncDiffState): DiffBadge = when (state) {
    YamSyncDiffState.NEW_REMOTE -> DiffBadge("Только там", Color(0xFFF5B14A))
    YamSyncDiffState.NEW_LOCAL -> DiffBadge("Только здесь", Color(0xFFE879F9))
    YamSyncDiffState.MODIFIED -> DiffBadge("Отличается", Color(0xFF38BDF8))
    YamSyncDiffState.IDENTICAL -> DiffBadge("Совпадает", Color(0xFF2DD881))
}

/** Подпись основной кнопки в зависимости от состояния плейлиста и выбранного действия. */
private fun primaryActionLabel(diff: YamSyncPlaylistDiff, peerName: String, missingCount: Int): String {
    val download = if (missingCount > 0) " и скачать $missingCount" else ""
    return when (diff.state) {
        YamSyncDiffState.NEW_REMOTE -> if (missingCount > 0) "Скачать $missingCount ${tracksWord(missingCount)}" else "Добавить плейлист"
        YamSyncDiffState.NEW_LOCAL -> "Отправить на $peerName"
        YamSyncDiffState.MODIFIED -> when (diff.resolution) {
            YamSyncResolution.MERGE_ALL -> "Объединить$download"
            YamSyncResolution.TAKE_REMOTE -> "Взять с $peerName$download"
            YamSyncResolution.KEEP_LOCAL -> "Отправить моё на $peerName"
        }
        YamSyncDiffState.IDENTICAL -> ""
    }
}

private fun tracksWord(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> "треков"
        mod10 == 1 -> "трек"
        mod10 in 2..4 -> "трека"
        else -> "треков"
    }
}

@Composable
private fun PlaylistSyncCard(
    diff: YamSyncPlaylistDiff,
    peerName: String,
    missingCount: Int,
    progress: YamSyncPlaylistProgress?,
    onSync: (YamSyncResolution?) -> Unit,
    onCancel: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val badge = diffBadge(diff.state)
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = diff.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = badge.text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = badge.color,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(badge.color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        val localCount = diff.localPlaylist?.trackCount ?: 0
        val remoteCount = diff.remotePlaylist?.trackCount ?: 0
        val summary = when (diff.state) {
            YamSyncDiffState.NEW_REMOTE -> "На $peerName $remoteCount ${tracksWord(remoteCount)} · здесь нет"
            YamSyncDiffState.NEW_LOCAL -> "$localCount ${tracksWord(localCount)} · на $peerName нет"
            YamSyncDiffState.IDENTICAL -> "$localCount ${tracksWord(localCount)}"
            YamSyncDiffState.MODIFIED ->
                "Здесь $localCount · там $remoteCount · +${diff.remoteOnlyTracks.size} / −${diff.localOnlyTracks.size}"
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )

        when {
            progress != null -> {
                Spacer(modifier = Modifier.height(8.dp))
                val statusText = when {
                    progress.isQueued -> "В очереди"
                    progress.total > 0 -> "Скачивается ${progress.done} из ${progress.total}"
                    else -> "Синхронизация…"
                }
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (!progress.isQueued) {
                    Spacer(modifier = Modifier.height(6.dp))
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.done.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth().height(5.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(36.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text(if (progress.isQueued) "Убрать из очереди" else "Остановить", style = MaterialTheme.typography.labelMedium)
                }
            }
            diff.state != YamSyncDiffState.IDENTICAL -> {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onSync(null) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.weight(1f).height(36.dp).pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text(
                            text = primaryActionLabel(diff, peerName, missingCount),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (diff.state == YamSyncDiffState.MODIFIED) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box {
                            OutlinedIconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Default.MoreHoriz, contentDescription = "Другие действия", modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Объединить") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null) },
                                    onClick = { menuOpen = false; onSync(YamSyncResolution.MERGE_ALL) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Взять с $peerName") },
                                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                    onClick = { menuOpen = false; onSync(YamSyncResolution.TAKE_REMOTE) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Отправить моё") },
                                    leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) },
                                    onClick = { menuOpen = false; onSync(YamSyncResolution.KEEP_LOCAL) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Список изменений по трекам
        val totalChanges = diff.remoteOnlyTracks.size + diff.localOnlyTracks.size
        if (diff.state == YamSyncDiffState.MODIFIED && totalChanges > 0) {
            TextButton(
                onClick = { isExpanded = !isExpanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isExpanded) "Скрыть изменения" else "Что изменилось ($totalChanges)",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            AnimatedVisibility(visible = isExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    diff.remoteOnlyTracks.forEach { t ->
                        Text(
                            text = "+ ${t.artist} — ${t.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2DD881),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    diff.localOnlyTracks.forEach { t ->
                        Text(
                            text = "− ${t.artist} — ${t.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFB7185),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
