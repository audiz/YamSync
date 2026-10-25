package io.github.audiz.ui.playlists

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 🎛 Компактная группа действий карточки плейлиста: «Редактировать» + «Удалить».
 *
 * Оформлена как единый пилюлевидный чип с тонкой рамкой и разделителем,
 * чтобы визуально не конкурировать с основной кнопкой воспроизведения.
 * При наведении/нажатии иконка получает мягкую круглую подложку акцентного цвета.
 *
 * @param onEdit действие редактирования (если `null` — кнопка не показывается)
 * @param onDelete действие удаления (если `null` — кнопка не показывается)
 * @param compact компактный размер для Desktop; на мобильных лучше передавать `false`
 */
@Composable
internal fun PlaylistActionGroup(
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = true
) {
    if (onEdit == null && onDelete == null) return

    val buttonSize = if (compact) 32.dp else 40.dp
    val iconSize = if (compact) 17.dp else 20.dp

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onEdit != null) {
                PlaylistActionButton(
                    icon = Icons.Filled.Edit,
                    contentDescription = "Редактировать плейлист",
                    tint = MaterialTheme.colorScheme.primary,
                    buttonSize = buttonSize,
                    iconSize = iconSize,
                    onClick = onEdit
                )
            }

            if (onEdit != null && onDelete != null) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(if (compact) 14.dp else 18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                )
            }

            if (onDelete != null) {
                PlaylistActionButton(
                    icon = Icons.Filled.DeleteOutline,
                    contentDescription = "Удалить плейлист",
                    tint = MaterialTheme.colorScheme.error,
                    buttonSize = buttonSize,
                    iconSize = iconSize,
                    onClick = onDelete
                )
            }
        }
    }
}

/**
 * Круглая иконка-действие с анимированной hover-подложкой.
 */
@Composable
private fun PlaylistActionButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundAlpha by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.26f
            isHovered -> 0.18f
            else -> 0f
        },
        label = "playlistActionBackground"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        label = "playlistActionScale"
    )

    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(tint.copy(alpha = backgroundAlpha))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
    }
}
