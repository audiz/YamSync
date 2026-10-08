package io.github.audiz.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audiz.sync.YamSyncConnectionState
import io.github.audiz.sync.YamSyncManager

/**
 * 🟢 Компактный статус-индикатор активного соединения YamSync в панели навигации
 */
@Composable
fun YamSyncPill(
    syncManager: YamSyncManager,
    isCompact: Boolean,
    onOpenSyncDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = syncManager.connectionState
    if (state is YamSyncConnectionState.Disconnected) return

    var isMenuOpen by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val (badgeColor, statusText, deviceIcon) = when (state) {
        is YamSyncConnectionState.Hosting -> Triple(
            MaterialTheme.colorScheme.primary,
            "Ожидание...",
            Icons.Default.Wifi
        )
        is YamSyncConnectionState.Connecting -> Triple(
            Color(0xFFE6A23C),
            "Связывание...",
            Icons.Default.Sync
        )
        is YamSyncConnectionState.Connected -> {
            val isRemoteMobile = state.peer.platform.contains("Android", ignoreCase = true) ||
                    state.peer.platform.contains("iOS", ignoreCase = true)
            Triple(
                Color(0xFF67C23A),
                state.peer.name,
                if (isRemoteMobile) Icons.Default.PhoneAndroid else Icons.Default.Laptop
            )
        }
        is YamSyncConnectionState.Syncing -> Triple(
            Color(0xFF409EFF),
            "Синхронизация...",
            Icons.Default.Sync
        )
        is YamSyncConnectionState.Disconnected -> Triple(Color.Gray, "", Icons.Default.Wifi)
    }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            tonalElevation = 2.dp,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable { isMenuOpen = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // Пульсирующая / яркая цветная точка статуса
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                )

                // Пиктограмма устройства
                Icon(
                    imageVector = deviceIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )

                // Имя устройства (на широких экранах или при наличии места)
                if (!isCompact && statusText.isNotBlank()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        DropdownMenu(
            expanded = isMenuOpen,
            onDismissRequest = { isMenuOpen = false }
        ) {
            when (state) {
                is YamSyncConnectionState.Connected -> {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = state.peer.name,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "IP: ${state.pairInfo.ip} • ${state.peer.platform}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF67C23A))
                        },
                        onClick = {}
                    )

                    if (syncManager.playlistDiffs.isNotEmpty()) {
                        val modifiedCount = syncManager.playlistDiffs.count { it.state != io.github.audiz.models.YamSyncDiffState.IDENTICAL }
                        if (modifiedCount > 0) {
                            DropdownMenuItem(
                                text = { Text("Плейлистов для слияния: $modifiedCount") },
                                leadingIcon = { Icon(Icons.Default.MergeType, contentDescription = null) },
                                onClick = {
                                    isMenuOpen = false
                                    onOpenSyncDialog()
                                }
                            )
                        }
                    }

                    if (syncManager.missingFiles.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Файлов для загрузки: ${syncManager.missingFiles.size}") },
                            leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                            onClick = {
                                isMenuOpen = false
                                onOpenSyncDialog()
                            }
                        )
                    }

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text("Открыть синхронизацию") },
                        leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                        onClick = {
                            isMenuOpen = false
                            onOpenSyncDialog()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Отключить", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            isMenuOpen = false
                            syncManager.disconnect()
                        }
                    )
                }
                is YamSyncConnectionState.Hosting -> {
                    DropdownMenuItem(
                        text = { Text("Ожидание подключения...") },
                        leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) },
                        onClick = {
                            isMenuOpen = false
                            onOpenSyncDialog()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Копировать ссылку") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            isMenuOpen = false
                            clipboardManager.setText(AnnotatedString(state.pairInfo.toUri()))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Остановить раздачу") },
                        leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null) },
                        onClick = {
                            isMenuOpen = false
                            syncManager.stopHosting()
                        }
                    )
                }
                else -> {
                    DropdownMenuItem(
                        text = { Text("Открыть диалог") },
                        leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                        onClick = {
                            isMenuOpen = false
                            onOpenSyncDialog()
                        }
                    )
                }
            }
        }
    }
}
