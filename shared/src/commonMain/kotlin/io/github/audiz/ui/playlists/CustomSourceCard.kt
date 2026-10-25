package io.github.audiz.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import io.github.audiz.models.CustomMediaSource
import io.github.audiz.models.LocalSourceType

/**
 * 📁 Карточка добавленного источника медиа (папка, аудиофайл, M3U)
 */
@Composable
internal fun CustomSourceCard(
    source: CustomMediaSource,
    onOpen: () -> Unit,
    onBrowse: (() -> Unit)? = null,
    onDelete: () -> Unit,
    compactActions: Boolean = true
) {
    val icon = when (source.type) {
        LocalSourceType.FOLDER -> Icons.Filled.Folder
        LocalSourceType.SINGLE_FILE -> Icons.Filled.Audiotrack
        LocalSourceType.PLAYLIST_FILE -> Icons.AutoMirrored.Filled.QueueMusic
    }

    PlaylistCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (onBrowse != null) onBrowse() else onOpen() }
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            PlaylistCardHeader(
                title = source.name,
                subtitle = source.path,
                leadingIcon = icon,
                badges = {
                    PlaylistBadge(
                        text = when (source.type) {
                            LocalSourceType.FOLDER -> "📁 Папка · ${source.trackCount} треков"
                            LocalSourceType.SINGLE_FILE -> "🎵 Аудиофайл"
                            LocalSourceType.PLAYLIST_FILE -> "📋 M3U · ${source.trackCount} треков"
                        }
                    )
                },
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (onBrowse != null) {
                            IconButton(
                                onClick = onBrowse,
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = "Обзор папок",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        PlaylistPlayButton(
                            onClick = onOpen,
                            buttonSize = if (compactActions) 34.dp else 38.dp
                        )

                        PlaylistActionGroup(
                            onDelete = onDelete,
                            compact = compactActions
                        )
                    }
                }
            )
        }
    }
}
