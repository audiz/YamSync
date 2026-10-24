package io.github.audiz.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.audiz.models.YamSyncDiffState
import io.github.audiz.sync.YamSyncManager
import io.github.audiz.ui.BackHandler

/**
 * ⚡ Главный диалог управления P2P Wi-Fi синхронизацией YamSync
 */
@Composable
fun YamSyncDialog(
    syncManager: YamSyncManager,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = true, onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            val isCompact = maxWidth < 600.dp
            val dialogWidth = if (isCompact) maxWidth else minOf(maxWidth - 24.dp, 580.dp)
            val dialogHeight = if (isCompact) maxHeight else minOf(maxHeight - 24.dp, 680.dp)

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                var selectedTabIndex by remember { mutableIntStateOf(0) }

                Column(modifier = Modifier.fillMaxSize()) {
                    // 1. Верхний заголовок и статус-сообщения
                    YamSyncDialogHeader(
                        syncManager = syncManager,
                        isCompact = isCompact,
                        onDismiss = onDismiss
                    )

                    // 2. Вкладки (Связь • Плейлисты • Файлы)
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = selectedTabIndex == 0,
                            onClick = { selectedTabIndex = 0 },
                            text = { Text("Связь", fontSize = 12.sp, maxLines = 1, softWrap = false) },
                            icon = { Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTabIndex == 1,
                            onClick = { selectedTabIndex = 1 },
                            text = {
                                val count = syncManager.playlistDiffs.count { it.state != YamSyncDiffState.IDENTICAL }
                                Text(
                                    text = if (count > 0) "Плейлисты ($count)" else "Плейлисты",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            icon = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTabIndex == 2,
                            onClick = { selectedTabIndex = 2 },
                            text = {
                                val count = syncManager.missingFiles.size
                                Text(
                                    text = if (count > 0) "Файлы ($count)" else "Файлы",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }

                    // 3. Содержимое активной вкладки
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (selectedTabIndex) {
                            0 -> YamSyncPairingTab(syncManager = syncManager, isCompact = isCompact)
                            1 -> YamSyncPlaylistsTab(syncManager = syncManager, isCompact = isCompact, onNavigateToFiles = { selectedTabIndex = 2 })
                            2 -> YamSyncFilesTab(syncManager = syncManager)
                        }
                    }
                }
            }
        }
    }
}
