package io.github.audiz.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audiz.models.YamSyncDiffState
import io.github.audiz.models.YamSyncPlaylistDiff
import io.github.audiz.models.YamSyncResolution
import io.github.audiz.sync.YamSyncManager

/**
 * 🔀 Вкладка 2: Разрешение конфликтов плейлистов (Merge Request)
 */
@Composable
internal fun YamSyncPlaylistsTab(
    syncManager: YamSyncManager,
    isCompact: Boolean,
    onNavigateToFiles: () -> Unit
) {
    if (!syncManager.isConnected) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Нет активного соединения", fontWeight = FontWeight.Bold)
                Text("Сначала свяжите устройства на вкладке «Связь»", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    if (syncManager.playlistDiffs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("Сравнение плейлистов...", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(syncManager.playlistDiffs, key = { index, diff -> "${diff.playlistId}_${diff.title}_$index" }) { _, diff ->
                PlaylistDiffCard(
                    diff = diff,
                    onResolutionChanged = { res ->
                        syncManager.setResolution(diff.playlistId, res)
                    }
                )
            }
        }

        // Нижняя панель применения изменений
        Surface(
            tonalElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { syncManager.refreshManifestAndDiff() },
                            enabled = !syncManager.isDownloadingFiles,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Обновить списки",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Плейлистов: ${syncManager.playlistDiffs.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (syncManager.missingFiles.isNotEmpty()) {
                        Text(
                            text = "Файлов к скачиванию: ${syncManager.missingFiles.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (syncManager.missingFiles.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { syncManager.applyPlaylistMerge(autoDownloadFiles = false) },
                            enabled = !syncManager.isDownloadingFiles,
                            modifier = Modifier.weight(1f).height(38.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Text("Только плейлисты", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Button(
                            onClick = {
                                syncManager.applyPlaylistMerge(autoDownloadFiles = true)
                                onNavigateToFiles()
                            },
                            enabled = !syncManager.isDownloadingFiles,
                            modifier = Modifier.weight(1.3f).height(38.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Применить и скачать (${syncManager.missingFiles.size})", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Button(
                            onClick = { syncManager.applyPlaylistMerge(autoDownloadFiles = false) },
                            modifier = Modifier.fillMaxWidth().height(38.dp)
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Применить слияние")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Карточка сравнения плейлиста в мобильном адаптивном стиле
 */
@Composable
internal fun PlaylistDiffCard(
    diff: YamSyncPlaylistDiff,
    onResolutionChanged: (YamSyncResolution) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Заголовок и бейдж статуса
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = diff.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                val (badgeText, badgeColor) = when (diff.state) {
                    YamSyncDiffState.IDENTICAL -> "ИДЕНТИЧЕН" to Color(0xFF67C23A)
                    YamSyncDiffState.NEW_LOCAL -> "ТОЛЬКО ЗДЕСЬ" to MaterialTheme.colorScheme.primary
                    YamSyncDiffState.NEW_REMOTE -> "НОВЫЙ" to Color(0xFFE6A23C)
                    YamSyncDiffState.MODIFIED -> "РАЗЛИЧАЕТСЯ" to Color(0xFFF56C6C)
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Статистика треков
            val localCount = diff.localPlaylist?.trackCount ?: 0
            val remoteCount = diff.remotePlaylist?.trackCount ?: 0
            Text(
                text = "Здесь: $localCount • На удалённом: $remoteCount (+${diff.remoteOnlyTracks.size} / -${diff.localOnlyTracks.size})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Переключатель резолюции: [Моё] | [Чужое] | [Объединить]
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ResolutionOption(
                        title = "Моё",
                        selected = diff.resolution == YamSyncResolution.KEEP_LOCAL,
                        modifier = Modifier.weight(1f)
                    ) {
                        onResolutionChanged(YamSyncResolution.KEEP_LOCAL)
                    }
                    ResolutionOption(
                        title = "Чужое",
                        selected = diff.resolution == YamSyncResolution.TAKE_REMOTE,
                        modifier = Modifier.weight(1f)
                    ) {
                        onResolutionChanged(YamSyncResolution.TAKE_REMOTE)
                    }
                    ResolutionOption(
                        title = "Объединить",
                        selected = diff.resolution == YamSyncResolution.MERGE_ALL,
                        modifier = Modifier.weight(1f)
                    ) {
                        onResolutionChanged(YamSyncResolution.MERGE_ALL)
                    }
                }
            }

            // Раскрывающийся аккордеон с деталями изменений
            val totalChanges = diff.remoteOnlyTracks.size + diff.localOnlyTracks.size
            if (totalChanges > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isExpanded) "Скрыть изменения" else "Подробнее об изменениях ($totalChanges)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        diff.remoteOnlyTracks.forEach { t ->
                            Text(
                                text = "+ ${t.artist} — ${t.title}",
                                fontSize = 11.sp,
                                color = Color(0xFF67C23A),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        diff.localOnlyTracks.forEach { t ->
                            Text(
                                text = "- ${t.artist} — ${t.title}",
                                fontSize = 11.sp,
                                color = Color(0xFFF56C6C),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
