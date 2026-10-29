package io.github.audiz.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.audiz.isPlatformQrScannerSupported
import io.github.audiz.launchPlatformQrScanner
import io.github.audiz.sync.YamSyncConnectionState
import io.github.audiz.sync.YamSyncManager
import kotlinx.coroutines.delay

/**
 * 🔗 Вкладка «Связь»: раздача QR-кода, подключение по ссылке и сохранённые устройства
 */
@Composable
internal fun YamSyncPairingTab(
    syncManager: YamSyncManager,
    isCompact: Boolean
) {
    var subTab by remember { mutableIntStateOf(0) }
    val state = syncManager.connectionState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state is YamSyncConnectionState.Connected) {
            ConnectedBlock(state = state, onDisconnect = { syncManager.disconnect() })
            return@Column
        }

        SyncSegmentedControl(
            options = listOf("Показать QR", "Подключиться"),
            selectedIndex = subTab,
            onSelect = { subTab = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (subTab == 0) {
            if (state is YamSyncConnectionState.Hosting) {
                HostingBlock(state = state, isCompact = isCompact, onStop = { syncManager.stopHosting() })
            } else {
                StartHostingBlock(onStart = { syncManager.startHosting() })
            }
        } else {
            JoinBlock(onConnect = { syncManager.connectToPeer(it) })
        }

        if (syncManager.knownDevices.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Сохранённые устройства",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${syncManager.knownDevices.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            syncManager.knownDevices.forEachIndexed { index, dev ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                KnownDeviceCard(
                    device = dev,
                    onConnect = { syncManager.connectToKnownDevice(dev) },
                    onRemove = { syncManager.removeKnownDevice(dev) }
                )
            }
        }
    }
}

@Composable
private fun HostingBlock(
    state: YamSyncConnectionState.Hosting,
    isCompact: Boolean,
    onStop: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }
    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    QrCodeCanvas(matrix = state.qrMatrix, modifier = Modifier.size(if (isCompact) 200.dp else 230.dp))
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Отсканируйте камерой другого устройства",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(14.dp))

    // Адрес в одну строку + круглая кнопка копирования ссылки
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${state.pairInfo.ip}:${state.pairInfo.port}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${state.pairInfo.name} · токен ${state.pairInfo.token}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        FilledIconButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(state.pairInfo.toUri()))
                isCopied = true
            },
            modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = "Скопировать ссылку для подключения",
                modifier = Modifier.size(18.dp)
            )
        }
    }
    if (isCopied) {
        Text(
            text = "Ссылка скопирована",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }

    Spacer(modifier = Modifier.height(6.dp))
    TextButton(
        onClick = onStop,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
    ) {
        Text("Остановить раздачу", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StartHostingBlock(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.QrCode2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Связать с другим устройством",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Покажем QR-код, который нужно отсканировать на втором устройстве в той же сети Wi-Fi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(46.dp).pointerHoverIcon(PointerIcon.Hand),
            shape = RoundedCornerShape(23.dp)
        ) {
            Text("Показать QR-код", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun JoinBlock(onConnect: (String) -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    var inputUri by remember { mutableStateOf("") }

    if (isPlatformQrScannerSupported) {
        Button(
            onClick = {
                launchPlatformQrScanner { scanned ->
                    inputUri = scanned.trim()
                    onConnect(inputUri)
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp).pointerHoverIcon(PointerIcon.Hand),
            shape = RoundedCornerShape(23.dp)
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Сканировать QR-код", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "или вставьте ссылку",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    OutlinedTextField(
        value = inputUri,
        onValueChange = { inputUri = it },
        placeholder = { Text("yamsync://pair…", maxLines = 1) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        trailingIcon = {
            IconButton(
                onClick = {
                    clipboardManager.getText()?.text?.takeIf { it.isNotBlank() }?.let { inputUri = it.trim() }
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = "Вставить из буфера")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(10.dp))
    Button(
        onClick = { onConnect(inputUri) },
        enabled = inputUri.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(44.dp).pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(22.dp)
    ) {
        Text("Подключиться", fontWeight = FontWeight.SemiBold)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "На втором устройстве откройте «Устройства» → «Показать QR». Ссылку можно скопировать там же кнопкой рядом с адресом.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ConnectedBlock(
    state: YamSyncConnectionState.Connected,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.peer.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(state.peer.platform, "${state.pairInfo.ip}:${state.pairInfo.port}")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(
            onClick = onDisconnect,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.height(34.dp).pointerHoverIcon(PointerIcon.Hand)
        ) {
            Text("Отключиться", style = MaterialTheme.typography.labelMedium)
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Устройство останется в сохранённых, повторно связаться можно одной кнопкой",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}
