package io.github.audiz.ui.playlists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * Шапка карточки плейлиста: иконка, название и описание слева, действия справа.
 * Метаданные (бейджи) идут третьей строкой в той же колонке, чтобы ничего не обрезалось.
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
