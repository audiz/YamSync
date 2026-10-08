package io.github.audiz.ui.sync

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.audiz.models.*
import io.github.audiz.sync.YamSyncConnectionState
import io.github.audiz.sync.YamSyncManager
import kotlinx.coroutines.delay

/**
 * ⚡ Главный диалог управления P2P Wi-Fi синхронизацией YamSync
 */
@Composable
fun YamSyncDialog(
    syncManager: YamSyncManager,
    onDismiss: () -> Unit
) {
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
            val dialogWidth = if (isCompact) maxWidth else 580.dp
            val dialogHeight = if (isCompact) maxHeight else 680.dp

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
                            text = { Text("Связь", fontSize = 13.sp) },
                            icon = { Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTabIndex == 1,
                            onClick = { selectedTabIndex = 1 },
                            text = {
                                val count = syncManager.playlistDiffs.count { it.state != YamSyncDiffState.IDENTICAL }
                                Text(if (count > 0) "Плейлисты ($count)" else "Плейлисты", fontSize = 13.sp)
                            },
                            icon = { Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTabIndex == 2,
                            onClick = { selectedTabIndex = 2 },
                            text = {
                                val count = syncManager.missingFiles.size
                                Text(if (count > 0) "Файлы ($count)" else "Файлы", fontSize = 13.sp)
                            },
                            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }

                    // 3. Содержимое активной вкладки
                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTabIndex) {
                            0 -> YamSyncPairingTab(syncManager = syncManager, isCompact = isCompact)
                            1 -> YamSyncPlaylistsTab(syncManager = syncManager, onNavigateToFiles = { selectedTabIndex = 2 })
                            2 -> YamSyncFilesTab(syncManager = syncManager)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YamSyncDialogHeader(
    syncManager: YamSyncManager,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "Синхронизация YamSync",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val subtitle = when (val s = syncManager.connectionState) {
                        is YamSyncConnectionState.Connected -> "🟢 Связано с ${s.peer.name}"
                        is YamSyncConnectionState.Hosting -> "📡 Раздача (ожидание)"
                        is YamSyncConnectionState.Connecting -> "⏳ Подключение..."
                        is YamSyncConnectionState.Syncing -> "🚀 Синхронизация..."
                        is YamSyncConnectionState.Disconnected -> "Оффлайн"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть")
            }
        }

        // Всплывающее статус-сообщение / ошибка
        AnimatedVisibility(visible = syncManager.errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = syncManager.errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        AnimatedVisibility(visible = syncManager.statusMessage != null && syncManager.errorMessage == null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(
                    text = syncManager.statusMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * 🔗 Вкладка 1: Подключение через QR-код или ссылку
 */
@Composable
private fun YamSyncPairingTab(
    syncManager: YamSyncManager,
    isCompact: Boolean
) {
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2500)
            isCopied = false
        }
    }

    var subTab by remember { mutableIntStateOf(0) }
    var inputUri by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Переключатель [Показать QR] | [Подключиться]
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text("Показать QR-код", fontSize = 12.sp)
            }
            SegmentedButton(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text("Подключиться", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (subTab == 0) {
            // Подрежим 1: Показать QR-код для сканирования
            when (val st = syncManager.connectionState) {
                is YamSyncConnectionState.Hosting -> {
                    Text(
                        text = "Наведите камеру другого устройства на QR-код:",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val qrSize = if (isCompact) 200.dp else 240.dp
                    QrCodeCanvas(
                        matrix = st.qrMatrix,
                        modifier = Modifier.size(qrSize)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                clipboardManager.setText(AnnotatedString(st.pairInfo.toUri()))
                                isCopied = true
                            }
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "IP: ${st.pairInfo.ip}:${st.pairInfo.port}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Токен: ${st.pairInfo.token} • Устройство: ${st.pairInfo.name}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(st.pairInfo.toUri()))
                            isCopied = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCopied) Color(0xFF67C23A) else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isCopied) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Icon(
                            if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (isCopied) "Ссылка скопирована в буфер!" else "Скопировать ссылку для подключения",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { syncManager.stopHosting() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Остановить раздачу", fontSize = 12.sp)
                    }
                }
                is YamSyncConnectionState.Connected -> {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF67C23A), modifier = Modifier.size(56.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Устройства успешно соединены!", fontWeight = FontWeight.Bold)
                            Text(
                                text = "Партнёр: ${st.peer.name} (${st.peer.platform})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { syncManager.disconnect() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Text("Разорвать связь")
                            }
                        }
                    }
                }
                else -> {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Создать точку синхронизации", fontWeight = FontWeight.Bold)
                            Text(
                                text = "Приложение запустит локальный сервер в вашей сети Wi-Fi и сгенерирует QR-код для подключения",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { syncManager.startHosting() }) {
                                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Раздать (показать мой QR)")
                            }
                        }
                    }
                }
            }
        } else {
            // Подрежим 2: Ввести URI или подключиться
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = inputUri,
                    onValueChange = { inputUri = it },
                    label = { Text("Ссылка yamsync://pair...") },
                    placeholder = { Text("Вставьте ссылку или отсканируйте код") },
                    singleLine = false,
                    maxLines = 3,
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                inputUri = clipText.trim()
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Вставить из буфера", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                inputUri = clipText.trim()
                            }
                        },
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Вставить из буфера", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { syncManager.connectToPeer(inputUri) },
                        enabled = inputUri.isNotBlank(),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Подключиться", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "💡 На ПК без камеры: скопируйте ссылку на раздающем устройстве и вставьте её здесь кнопкой «Вставить из буфера».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

/**
 * 🔀 Вкладка 2: Разрешение конфликтов плейлистов (Merge Request)
 */
@Composable
private fun YamSyncPlaylistsTab(
    syncManager: YamSyncManager,
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
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(syncManager.playlistDiffs, key = { it.playlistId }) { diff ->
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
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Плейлистов: ${syncManager.playlistDiffs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (syncManager.missingFiles.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { syncManager.applyPlaylistMerge(autoDownloadFiles = false) },
                            enabled = !syncManager.isDownloadingFiles,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Только плейлисты", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                syncManager.applyPlaylistMerge(autoDownloadFiles = true)
                                onNavigateToFiles()
                            },
                            enabled = !syncManager.isDownloadingFiles,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Применить и скачать (${syncManager.missingFiles.size})", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = { syncManager.applyPlaylistMerge(autoDownloadFiles = false) }
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
private fun PlaylistDiffCard(
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
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = diff.resolution == YamSyncResolution.KEEP_LOCAL,
                    onClick = { onResolutionChanged(YamSyncResolution.KEEP_LOCAL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                ) {
                    Text("Моё", fontSize = 11.sp)
                }
                SegmentedButton(
                    selected = diff.resolution == YamSyncResolution.TAKE_REMOTE,
                    onClick = { onResolutionChanged(YamSyncResolution.TAKE_REMOTE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                ) {
                    Text("Чужое", fontSize = 11.sp)
                }
                SegmentedButton(
                    selected = diff.resolution == YamSyncResolution.MERGE_ALL,
                    onClick = { onResolutionChanged(YamSyncResolution.MERGE_ALL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                ) {
                    Text("Объединить", fontSize = 11.sp)
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

/**
 * 📥 Вкладка 3: Выборочная загрузка аудиофайлов по требованию
 */
@Composable
private fun YamSyncFilesTab(syncManager: YamSyncManager) {
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
            items(syncManager.missingFiles, key = { it.matchKey }) { track ->
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

/**
 * Отрисовка матрицы QR-кода на Canvas
 */
@Composable
private fun QrCodeCanvas(
    matrix: Array<BooleanArray>,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val count = matrix.size
            if (count == 0) return@Canvas
            val cellSize = size.width / count

            for (r in 0 until count) {
                for (c in 0 until count) {
                    if (matrix[r][c]) {
                        drawRoundRect(
                            color = Color.Black,
                            topLeft = Offset(c * cellSize, r * cellSize),
                            size = Size(cellSize, cellSize),
                            cornerRadius = CornerRadius(cellSize * 0.2f, cellSize * 0.2f)
                        )
                    }
                }
            }
        }
    }
}

private fun formatMb(bytes: Long): String {
    val mbTimesTen = (bytes * 10) / (1024 * 1024)
    val whole = mbTimesTen / 10
    val fraction = mbTimesTen % 10
    return "$whole.$fraction МБ"
}
