package io.github.audiz.ui.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audiz.sync.YamSyncManager

/**
 * 📥 Вкладка 3: Выборочная загрузка аудиофайлов по требованию
 */
@Composable
internal fun YamSyncFilesTab(syncManager: YamSyncManager) {
    if (!syncManager.isConnected) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Нет активного соединения", fontWeight = FontWeight.Bold)
                Text("Свяжите устройства на вкладке «Связь»", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    if (syncManager.missingFiles.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF67C23A), modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Все файлы уже на месте!", fontWeight = FontWeight.Bold)
                Text("Все треки из плейлистов присутствуют на диске устройства", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Панель массового выбора
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = syncManager.selectedFiles.size == syncManager.missingFiles.size,
                    onCheckedChange = { syncManager.toggleSelectAllFiles() }
                )
                Text(
                    text = "Выбрать все (${syncManager.missingFiles.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            val totalBytes = syncManager.missingFiles
                .filter { it.matchKey in syncManager.selectedFiles }
                .sumOf { it.fileSize }
            Text(
                text = formatMb(totalBytes),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(syncManager.missingFiles, key = { index, track -> "${track.matchKey}_$index" }) { _, track ->
                val isSelected = syncManager.selectedFiles.contains(track.matchKey)
                val transfer = syncManager.fileTransfers[track.matchKey]

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isSelected) 0.5f else 0.2f),
                    modifier = Modifier.fillMaxWidth().clickable { syncManager.toggleFileSelection(track.matchKey) }
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { syncManager.toggleFileSelection(track.matchKey) },
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${track.artist} • ${track.fileName}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (track.fileSize > 0) {
                                Text(
                                    text = formatMb(track.fileSize),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Прогресс загрузки трека
                        if (transfer != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            if (transfer.isCompleted) {
                                if (transfer.error != null) {
                                    Text(
                                        text = "Ошибка: ${transfer.error}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Text(
                                        text = "✓ Загружено",
                                        fontSize = 10.sp,
                                        color = Color(0xFF67C23A),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                LinearProgressIndicator(
                                    progress = { transfer.progressFraction },
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                )
                                val speedKb = transfer.speedBytesPerSec / 1024
                                Text(
                                    text = "${transfer.progressPercent}% • $speedKb КБ/с",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Нижняя кнопка скачивания
        Surface(
            tonalElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                Button(
                    onClick = { syncManager.downloadSelectedFiles() },
                    enabled = syncManager.selectedFiles.isNotEmpty() && !syncManager.isDownloadingFiles,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (syncManager.isDownloadingFiles) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Идёт скачивание...")
                    } else {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Скачать выбранные файлы (${syncManager.selectedFiles.size})")
                    }
                }
            }
        }
    }
}
