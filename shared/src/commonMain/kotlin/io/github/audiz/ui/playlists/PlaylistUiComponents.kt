package io.github.audiz.ui.playlists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audiz.ui.rememberCoverBitmap

/**
 * 🎨 Квадратная обложка плейлиста со стильным скруглением, градиентом-заглушкой и мини-бейджем источника.
 */
@Composable
internal fun PlaylistArtwork(
    coverUri: String? = null,
    fallbackIcon: ImageVector = Icons.AutoMirrored.Filled.QueueMusic,
    badgeText: String? = null,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val coverBitmap = rememberCoverBitmap(coverUri, size = 120)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (coverBitmap != null) {
            Image(
                bitmap = coverBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.size(size * 0.50f)
            )
        }

        // Мини-бейдж источника в правом нижнем углу (💾 или ☁️)
        if (!badgeText.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Text(
                    text = badgeText,
                    fontSize = 8.5.sp,
                    lineHeight = 10.sp,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/**
 * 🏷 Компактная кнопка-чип с количеством треков и шевроном разворачивания.
 */
@Composable
internal fun PlaylistTracksPill(
    trackCount: Int,
    isExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        modifier = modifier
            .height(28.dp)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "$trackCount",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "Свернуть треки" else "Развернуть треки",
                tint = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * ⋮ Компактное контекстное меню дополнительных действий карточки.
 */
@Composable
internal fun PlaylistMoreMenu(
    onRename: (() -> Unit)? = null,
    onExportM3u: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (onRename == null && onExportM3u == null && onDelete == null) return

    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Дополнительные действия",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text("Переименовать", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    onClick = {
                        expanded = false
                        onRename()
                    }
                )
            }

            if (onExportM3u != null) {
                DropdownMenuItem(
                    text = { Text("Экспорт в M3U", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    onClick = {
                        expanded = false
                        onExportM3u()
                    }
                )
            }

            if (onDelete != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                DropdownMenuItem(
                    text = {
                        Text(
                            "Удалить плейлист",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    onClick = {
                        expanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/**
 * 🏷 Компактный бейдж-подпись с метаданными плейлиста.
 */
@Composable
internal fun PlaylistBadge(
    text: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    containerAlpha: Float = 0.14f,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = containerAlpha)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * ▶️ Аккуратная круглая кнопка запуска плейлиста в сочном акцентном стиле.
 */
@Composable
internal fun PlaylistPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 34.dp
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(buttonSize)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            )
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Слушать",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(buttonSize * 0.58f)
        )
    }
}

/**
 * 🎴 Единый контейнер карточки плейлиста: мягкая подложка + тонкая рамка.
 */
@Composable
internal fun PlaylistCardSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
        ),
        content = content
    )
}

/**
 * Шапка карточки плейлиста для обратной совместимости (например, CustomSourceCard).
 */
@Composable
internal fun PlaylistCardHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    badges: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        modifier = Modifier.size(15.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (badges != null) {
                Spacer(modifier = Modifier.height(3.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    badges()
                }
            }
        }

        trailing?.invoke()
    }
}
