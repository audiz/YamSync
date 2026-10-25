package io.github.audiz.ui.playlists

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.audiz.SearchViewModel
import io.github.audiz.models.PlaylistInfo
import io.github.audiz.models.YandexPlaylistTrackItem

/**
 * ☁️ Вкладка 2: Плейлисты пользователя из Яндекс Музыки
 */
@Composable
internal fun YandexUserPlaylistsTab(
    viewModel: SearchViewModel,
    onOpenPlaylist: (PlaylistInfo) -> Unit,
    onRenameClick: (PlaylistInfo) -> Unit = {}
) {
    if (viewModel.currentAccessToken.isBlank()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "🔑 Для доступа к плейлистам из Яндекс Музыки авторизуйтесь в Настройках ⚙️",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (viewModel.isUserPlaylistsLoading && viewModel.userPlaylists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 3.dp)
        }
        return
    }

    if (viewModel.userPlaylists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "В вашем аккаунте Яндекс Музыки пока нет созданных плейлистов.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var expandedPlaylistKind by remember { mutableStateOf<Long?>(null) }
    val yandexPlaylistTracksCache = remember { mutableStateMapOf<Long, List<YandexPlaylistTrackItem>>() }
    val isDeletingTrackMap = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(viewModel.userPlaylists, key = { index, playlist -> "yandex_pl_${playlist.kind ?: playlist.uid}_$index" }) { index, playlist ->
            val kind = playlist.kind
            val isExpanded = expandedPlaylistKind == kind
            val playlistKey = playlist.kind?.toString() ?: playlist.playlistUuid ?: playlist.title
            val downloadProgress = viewModel.activePlaylistDownloadProgress
            val isThisDownloading = downloadProgress != null && downloadProgress.playlistId == playlistKey
            val isAnyDownloading = downloadProgress != null

            LaunchedEffect(isExpanded, kind) {
                if (isExpanded && kind != null && !yandexPlaylistTracksCache.containsKey(kind)) {
                    val details = viewModel.loadYandexPlaylistDetails(playlist)
                    if (details != null) {
                        yandexPlaylistTracksCache[kind] = details.tracks
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = playlist.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val desc = playlist.description
                            if (!desc.isNullOrBlank()) {
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "☁️ Яндекс • ${playlist.trackCount} треков",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        IconButton(
                            onClick = { onOpenPlaylist(playlist) },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayCircle,
                                contentDescription = "Слушать",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    // 📥 Статус-бар скачивания плейлиста в оффлайн
                    if (downloadProgress != null && isThisDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val progressTitle = if (downloadProgress.totalCount > 0) {
                                        val skippedInfo = if (downloadProgress.skippedCount > 0) " (пропущено ${downloadProgress.skippedCount})" else ""
                                        "Скачивание: ${downloadProgress.downloadedCount}/${downloadProgress.totalCount}$skippedInfo"
                                    } else {
                                        "Подготовка списка треков..."
                                    }

                                    Text(
                                        text = progressTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )

                                    IconButton(
                                        onClick = { viewModel.cancelPlaylistDownload() },
                                        modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Отменить скачивание",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                if (downloadProgress.isIndeterminate || downloadProgress.totalCount <= 0) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    val fraction = (downloadProgress.downloadedCount + downloadProgress.skippedCount).toFloat() / downloadProgress.totalCount.toFloat()
                                    LinearProgressIndicator(
                                        progress = { fraction.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }

                                if (downloadProgress.currentTrackTitle.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = downloadProgress.currentTrackTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                expandedPlaylistKind = if (isExpanded) null else kind
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp).pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "Треки (${playlist.trackCount})",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (kind != null) {
                                IconButton(
                                    onClick = { onRenameClick(playlist) },
                                    modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = "Переименовать",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (isThisDownloading) {
                                OutlinedButton(
                                    onClick = { viewModel.cancelPlaylistDownload() },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.height(28.dp).pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Отмена", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.downloadYandexPlaylistToLocal(playlist) },
                                    enabled = !isAnyDownloading,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp).then(
                                        if (!isAnyDownloading) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier
                                    )
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Скачать в оффлайн", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                    }

                    // 📜 Разворачивающийся список треков облачного плейлиста
                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val cachedTracks = kind?.let { yandexPlaylistTracksCache[it] }

                        if (cachedTracks == null) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else if (cachedTracks.isEmpty()) {
                            Text(
                                text = "В плейлисте пока нет треков. Добавьте их из поиска или плеера!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                cachedTracks.forEach { item ->
                                    val isDeleting = isDeletingTrackMap[item.id] == true
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.MusicNote,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Column {
                                                    Text(
                                                        text = item.title.ifBlank { "Трек #${item.id}" },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (item.artist.isNotBlank()) {
                                                        Text(
                                                            text = item.artist,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }

                                            if (isDeleting) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp).padding(2.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                IconButton(
                                                    onClick = {
                                                        isDeletingTrackMap[item.id] = true
                                                        val fullTrack = io.github.audiz.models.FullTrackInfo(
                                                            id = item.id,
                                                            realId = item.id,
                                                            title = item.title
                                                        )
                                                        viewModel.removeTrackFromYandexPlaylist(playlist, fullTrack) { success ->
                                                            isDeletingTrackMap[item.id] = false
                                                            if (success && kind != null) {
                                                                val currentList = yandexPlaylistTracksCache[kind]?.toMutableList()
                                                                currentList?.removeAll { it.id == item.id }
                                                                if (currentList != null) {
                                                                    yandexPlaylistTracksCache[kind] = currentList
                                                                }
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.size(26.dp).pointerHoverIcon(PointerIcon.Hand)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Close,
                                                        contentDescription = "Убрать из плейлиста",
                                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
