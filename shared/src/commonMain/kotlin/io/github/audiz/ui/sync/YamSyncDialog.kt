package io.github.audiz.ui.sync

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.audiz.isPlatformQrScannerSupported
import io.github.audiz.launchPlatformQrScanner
import io.github.audiz.models.*
import io.github.audiz.sync.YamSyncConnectionState
import io.github.audiz.sync.YamSyncKnownDevice
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

private fun sanitizeErrorMessage(err: String): String {
    val lower = err.lowercase()
    if (lower.contains("local network prohibited") || lower.contains("kcfstreamerrorcodekey=50") || lower.contains("unsatisfied") || lower.contains("code=-1009")) {
        return "Доступ к локальной сети ограничен iOS. Разрешите «Локальная сеть» для YamSync в Настройках iPhone (Настройки → YamSync → Локальная сеть)."
    }
    if (lower.contains("connection refused") || lower.contains("econnrefused")) {
        return "Устройство отклонило подключение. Убедитесь, что на втором устройстве открыт YamSync и запущена раздача."
    }
    if (lower.contains("timed out") || lower.contains("etimedout")) {
        return "Превышено время ожидания. Проверьте, что оба устройства подключены к одной сети Wi-Fi."
    }
    return err.take(250)
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
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Синхронизация YamSync",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (syncManager.isConnected) {
                    IconButton(
                        onClick = { syncManager.refreshManifestAndDiff() },
                        enabled = !syncManager.isDownloadingFiles
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Обновить списки и манифест",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
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
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    val displayError = remember(syncManager.errorMessage) {
                        sanitizeErrorMessage(syncManager.errorMessage ?: "")
                    }
                    Text(
                        text = displayError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { syncManager.errorMessage = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Скрыть ошибку",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = syncManager.statusMessage != null && syncManager.errorMessage == null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = syncManager.statusMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { syncManager.statusMessage = null },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Скрыть",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
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
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
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
                if (isPlatformQrScannerSupported) {
                    FilledTonalButton(
                        onClick = {
                            launchPlatformQrScanner { scanned ->
                                inputUri = scanned.trim()
                                syncManager.connectToPeer(inputUri)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сканировать QR-код камерой", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = inputUri,
                    onValueChange = { inputUri = it },
                    label = { Text("Ссылка yamsync://pair...") },
                    placeholder = { Text("Вставьте ссылку или отсканируйте код") },
                    singleLine = false,
                    maxLines = 3,
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isPlatformQrScannerSupported) {
                                IconButton(onClick = {
                                    launchPlatformQrScanner { scanned ->
                                        inputUri = scanned.trim()
                                        syncManager.connectToPeer(inputUri)
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.QrCodeScanner,
                                        contentDescription = "Сканировать QR-код камерой",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = {
                                val clipText = clipboardManager.getText()?.text
                                if (!clipText.isNullOrBlank()) {
                                    inputUri = clipText.trim()
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Вставить из буфера", tint = MaterialTheme.colorScheme.primary)
                            }
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

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "💡 Как подключиться:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "1. На втором устройстве откройте вкладку «Показать QR-код» и нажмите «Раздать».\n" +
                                   "2. Со смартфона: нажмите «Сканировать QR-код камерой» выше или наведите штатную камеру iOS на экран другого устройства.\n" +
                                   "3. Либо скопируйте ссылку на раздающем устройстве и вставьте её сюда кнопкой «Вставить из буфера».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        // 3. Сохранённые устройства (Known Devices)
        if (syncManager.connectionState !is YamSyncConnectionState.Connected && syncManager.knownDevices.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Сохранённые устройства",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${syncManager.knownDevices.size}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (dev in syncManager.knownDevices) {
                    KnownDeviceCard(
                        device = dev,
                        onConnect = { syncManager.connectToKnownDevice(dev) },
                        onRemove = { syncManager.removeKnownDevice(dev) }
                    )
                }
            }
        }
    }
}

@Composable
private fun KnownDeviceCard(
    device: YamSyncKnownDevice,
    onConnect: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val isMobile = device.platform.contains("Android", ignoreCase = true) ||
                                device.platform.contains("iOS", ignoreCase = true)
                        val icon = if (isMobile) Icons.Default.PhoneAndroid else Icons.Default.Laptop
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${device.ip} • ${device.platform.ifBlank { "YamSync" }}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onConnect,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Связать", fontSize = 12.sp)
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Удалить устройство",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
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
            items(syncManager.playlistDiffs, key = { "${it.playlistId}_${it.title}" }) { diff ->
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

@Composable
private fun ResolutionOption(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
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
