package io.github.audiz.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audiz.sync.YamSyncConnectionState
import io.github.audiz.sync.YamSyncKnownDevice
import io.github.audiz.sync.YamSyncManager
import io.github.audiz.currentTimeMillis
import kotlinx.coroutines.delay

internal fun sanitizeErrorMessage(err: String): String {
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

internal fun formatMb(bytes: Long): String {
    val mbTimesTen = (bytes * 10) / (1024 * 1024)
    val whole = mbTimesTen / 10
    val fraction = mbTimesTen % 10
    return "$whole.$fraction МБ"
}

internal fun isMobilePlatform(platform: String): Boolean =
    platform.contains("Android", ignoreCase = true) || platform.contains("iOS", ignoreCase = true)

/** «сегодня», «вчера», «3 дн. назад» — когда устройство было на связи последний раз. */
internal fun formatLastSeen(lastSeenMs: Long): String {
    if (lastSeenMs <= 0L) return ""
    val days = ((currentTimeMillis() - lastSeenMs) / 86_400_000L).coerceAtLeast(0L)
    return when (days) {
        0L -> "был сегодня"
        1L -> "был вчера"
        else -> "был $days дн. назад"
    }
}

/** Сегментный переключатель на всю ширину: используется для вкладок и подрежимов окна синхронизации. */
@Composable
internal fun SyncSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth().height(40.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(3.dp)) {
            options.forEachIndexed { index, title ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        // Ширина пропорциональна длине подписи, чтобы «Плейлисты · 4» не обрезались рядом с коротким «Связь»
                        .weight(title.length.coerceAtLeast(4).toFloat())
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun YamSyncDialogHeader(
    syncManager: YamSyncManager,
    onDismiss: () -> Unit
) {
    val state = syncManager.connectionState
    val connectedPeer = (state as? YamSyncConnectionState.Connected)?.peer

    // Информационные сообщения исчезают сами; прогресс загрузки показывается в карточках
    val status = syncManager.statusMessage
    LaunchedEffect(status, syncManager.isDownloadingFiles) {
        if (status != null && !syncManager.isDownloadingFiles) {
            delay(3500)
            if (syncManager.statusMessage == status) syncManager.statusMessage = null
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (statusText, statusColor) = when (state) {
                is YamSyncConnectionState.Connected -> "Связано" to Color(0xFF2DD881)
                is YamSyncConnectionState.Syncing -> "Синхронизация…" to Color(0xFF2DD881)
                is YamSyncConnectionState.Hosting -> "Ожидание партнёра…" to Color(0xFFF59E0B)
                is YamSyncConnectionState.Connecting -> "Подключение…" to Color(0xFFF59E0B)
                is YamSyncConnectionState.Disconnected -> "Нет соединения" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(statusColor.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        connectedPeer != null && isMobilePlatform(connectedPeer.platform) -> Icons.Default.PhoneIphone
                        connectedPeer != null -> Icons.Default.Laptop
                        else -> Icons.Default.Devices
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connectedPeer?.name ?: "Устройства",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).background(statusColor, CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (syncManager.isConnected) {
                IconButton(
                    onClick = { syncManager.refreshManifestAndDiff() },
                    enabled = !syncManager.isDownloadingFiles,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить", modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть")
            }
        }

        // Ошибка остаётся до явного закрытия: обычно она требует действия пользователя
        AnimatedVisibility(visible = syncManager.errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                    IconButton(onClick = { syncManager.errorMessage = null }, modifier = Modifier.size(28.dp)) {
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

        AnimatedVisibility(visible = status != null && syncManager.errorMessage == null) {
            Text(
                text = status ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 46.dp, end = 8.dp)
            )
        }
    }
}

@Composable
internal fun KnownDeviceCard(
    device: YamSyncKnownDevice,
    onConnect: () -> Unit,
    onRemove: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isMobilePlatform(device.platform)) Icons.Default.PhoneIphone else Icons.Default.Laptop,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(device.ip, formatLastSeen(device.lastSeenMs)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Button(
            onClick = onConnect,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp).pointerHoverIcon(PointerIcon.Hand),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Связать", style = MaterialTheme.typography.labelMedium)
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Ещё",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Забыть устройство") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    }
                )
            }
        }
    }
}

/**
 * Отрисовка матрицы QR-кода на Canvas
 */
@Composable
internal fun QrCodeCanvas(
    matrix: Array<BooleanArray>,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            val count = matrix.size
            if (count == 0) return@Canvas
            val cellSize = size.width / count

            for (r in 0 until count) {
                for (c in 0 until count) {
                    if (matrix[r][c]) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(c * cellSize, r * cellSize),
                            size = Size(cellSize + 0.5f, cellSize + 0.5f)
                        )
                    }
                }
            }
        }
    }
}
