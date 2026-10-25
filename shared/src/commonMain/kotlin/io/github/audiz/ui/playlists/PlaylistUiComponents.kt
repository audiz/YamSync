package io.github.audiz.ui.playlists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * 🖼 Обложка плейлиста: одна картинка или мозаика 2×2 из первых треков.
 * Если обложек нет — мягкий градиент с тематической иконкой.
 */
@Composable
internal fun PlaylistCover(
    coverUris: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    corner: Dp = 12.dp,
    fallbackIcon: ImageVector = Icons.AutoMirrored.Filled.QueueMusic
) {
    val uris = coverUris.filter { it.isNotBlank() }.take(4)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            uris.isEmpty() -> Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.size(size * 0.42f)
            )

            uris.size == 1 -> CoverCell(uris[0], Modifier.fillMaxSize())

            uris.size == 2 -> Row(modifier = Modifier.fillMaxSize()) {
                CoverCell(uris[0], Modifier.weight(1f).fillMaxSize())
                CoverCell(uris[1], Modifier.weight(1f).fillMaxSize())
            }

            uris.size == 3 -> Row(modifier = Modifier.fillMaxSize()) {
                CoverCell(uris[0], Modifier.weight(1f).fillMaxSize())
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    CoverCell(uris[1], Modifier.weight(1f).fillMaxSize())
                    CoverCell(uris[2], Modifier.weight(1f).fillMaxSize())
                }
            }

            else -> Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f).fillMaxSize()) {
                    CoverCell(uris.getOrNull(0), Modifier.weight(1f).fillMaxSize())
                    CoverCell(uris.getOrNull(1), Modifier.weight(1f).fillMaxSize())
                }
                Row(modifier = Modifier.weight(1f).fillMaxSize()) {
                    CoverCell(uris.getOrNull(2), Modifier.weight(1f).fillMaxSize())
                    CoverCell(uris.getOrNull(3), Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun CoverCell(uri: String?, modifier: Modifier = Modifier) {
    val bitmap = rememberCoverBitmap(uri, size = 160)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * 🏷 Компактный бейдж-подпись под названием плейлиста.
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
 * ▶️ Аккуратная круглая кнопка запуска плейлиста.
 */
@Composable
internal fun PlaylistPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 38.dp
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(buttonSize)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(percent = 50)
            )
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Слушать",
            tint = MaterialTheme.colorScheme.primary,
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
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        content = content
    )
}

/**
 * Шапка карточки плейлиста: обложка + название + описание + бейджи + действия.
 */
@Composable
internal fun PlaylistCardHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    coverUris: List<String> = emptyList(),
    coverSize: Dp = 56.dp,
    coverIcon: ImageVector = Icons.AutoMirrored.Filled.QueueMusic,
    trailing: @Composable (() -> Unit)? = null,
    badges: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PlaylistCover(
            coverUris = coverUris,
            size = coverSize,
            corner = 10.dp,
            fallbackIcon = coverIcon
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
                Spacer(modifier = Modifier.height(4.dp))
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
