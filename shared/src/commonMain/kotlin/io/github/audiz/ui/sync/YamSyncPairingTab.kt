package io.github.audiz.ui.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audiz.isPlatformQrScannerSupported
import io.github.audiz.launchPlatformQrScanner
import io.github.audiz.sync.YamSyncConnectionState
import io.github.audiz.sync.YamSyncManager
import kotlinx.coroutines.delay

/**
 * 🔗 Вкладка 1: Подключение через QR-код или ссылку
 */
@Composable
internal fun YamSyncPairingTab(
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
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    onClick = { subTab = 0 },
                    shape = RoundedCornerShape(9.dp),
                    color = if (subTab == 0) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (subTab == 0) 2.dp else 0.dp,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (subTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Показать QR",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (subTab == 0) FontWeight.Bold else FontWeight.Medium,
                                color = if (subTab == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Surface(
                    onClick = { subTab = 1 },
                    shape = RoundedCornerShape(9.dp),
                    color = if (subTab == 1) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (subTab == 1) 2.dp else 0.dp,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (subTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Подключиться",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (subTab == 1) FontWeight.Bold else FontWeight.Medium,
                                color = if (subTab == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF67C23A), modifier = Modifier.size(56.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Устройства успешно соединены!",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Партнёр: ${st.peer.name} (${st.peer.platform})",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { syncManager.disconnect() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                shape = RoundedCornerShape(23.dp)
                            ) {
                                Text("Разорвать связь", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                else -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Создать точку синхронизации",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Приложение запустит локальный сервер в вашей сети Wi-Fi и сгенерирует QR-код для подключения",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { syncManager.startHosting() },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Раздать (показать мой QR)", fontWeight = FontWeight.SemiBold)
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
