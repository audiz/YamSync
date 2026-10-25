package io.github.audiz.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audiz.AppConfigKeys
import io.github.audiz.DispatcherIO
import io.github.audiz.SearchViewModel
import io.github.audiz.loadAppConfig
import io.github.audiz.localFileExists
import io.github.audiz.models.CustomMediaSource
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.LocalSourceType
import io.github.audiz.resolveLocalPath
import io.github.audiz.saveAppConfig

/**
 * 💾 Вкладка 1: Локальные источники (папки, файлы, M3U) и оффлайн-плейлисты
 */
@Composable
internal fun LocalPlaylistsTab(
    viewModel: SearchViewModel,
    onAddSourceClick: () -> Unit,
    onOpenSource: (CustomMediaSource) -> Unit,
    onBrowseFolder: (CustomMediaSource) -> Unit,
    onCreateClick: () -> Unit,
    onRenameClick: (LocalPlaylist) -> Unit,
    onDeleteClick: (LocalPlaylist) -> Unit,
    onOpenPlaylist: (LocalPlaylist) -> Unit,
    onOpenSync: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var expandedPlaylistId by remember {
        mutableStateOf(loadAppConfig(AppConfigKeys.EXPANDED_PLAYLIST_ID)?.takeIf { it.isNotBlank() })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ==========================================
        // 📁 СЕКЦИЯ 1: Папки и файлы устройства
        // ==========================================
        item(key = "custom_sources_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Папки и файлы устройства",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${viewModel.customMediaSources.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Button(
                    onClick = onAddSourceClick,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.wrapContentWidth().pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Добавить", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }
        }

        if (viewModel.customMediaSources.isEmpty()) {
            item(key = "custom_sources_empty") {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(32.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Своя музыка с устройства",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Добавьте папки, отдельные треки или плейлисты M3U для оффлайн-прослушивания в плеере.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            itemsIndexed(viewModel.customMediaSources, key = { index, source -> "source_${source.id}_$index" }) { index, source ->
                CustomSourceCard(
                    source = source,
                    onOpen = { onOpenSource(source) },
                    onBrowse = if (source.type == LocalSourceType.FOLDER) { { onBrowseFolder(source) } } else null,
                    onDelete = { viewModel.removeCustomSource(source.id) },
                    compactActions = !viewModel.isMobileUi
                )
            }
        }

        // ==========================================
        // 💾 СЕКЦИЯ 2: Оффлайн-плейлисты
        // ==========================================
        item(key = "local_playlists_header") {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 1.dp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Оффлайн-плейлисты",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${viewModel.localPlaylists.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Button(
                    onClick = onCreateClick,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.wrapContentWidth().pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Создать", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }
        }

        if (viewModel.localPlaylists.isEmpty()) {
            item(key = "local_playlists_empty") {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(32.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Нет созданных оффлайн-плейлистов",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Создайте плейлист и добавляйте в него скачанные треки.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            itemsIndexed(viewModel.localPlaylists, key = { index, playlist -> "playlist_${playlist.id}_$index" }) { index, playlist ->
                // Проверка наличия файлов и путей к обложкам — вне UI-потока
                val availability = produceState(initialValue = null as Int?, playlist.trackPaths) {
                    value = withContext(DispatcherIO) {
                        try {
                            playlist.trackPaths.count { path ->
                                localFileExists(resolveLocalPath(path)) || run {
                                    val fn = path.substringAfterLast('/').substringAfterLast('\\')
                                    fn.isNotBlank() && localFileExists(resolveLocalPath(fn))
                                }
                            }
                        } catch (_: Throwable) {
                            0
                        }
                    }
                }.value
                val availableCount = availability
                val isFullyAvailable = availableCount != null && availableCount == playlist.trackCount
                val isNoneAvailable = availableCount == 0 && playlist.trackCount > 0
                val isExpanded = expandedPlaylistId == playlist.id

                PlaylistCardSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    ) {
                        PlaylistCardHeader(
                            title = playlist.title,
                            subtitle = playlist.description.takeIf { it.isNotBlank() },
                            badges = {
                                PlaylistBadge(
                                    text = "${playlist.trackCount} треков",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    containerAlpha = 0.10f
                                )
                                if (availableCount != null) {
                                    PlaylistBadge(
                                        text = when {
                                            isFullyAvailable -> "💾 Оффлайн"
                                            isNoneAvailable -> "☁️ Нет файлов"
                                            else -> "💾 $availableCount/${playlist.trackCount}"
                                        },
                                        tint = if (isFullyAvailable) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    PlaylistActionGroup(
                                        onEdit = { onRenameClick(playlist) },
                                        onDelete = { onDeleteClick(playlist) },
                                        compact = !viewModel.isMobileUi
                                    )
                                    PlaylistPlayButton(
                                        onClick = { onOpenPlaylist(playlist) },
                                        buttonSize = if (viewModel.isMobileUi) 42.dp else 38.dp
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Нижний ряд: действия слева, разворачивание треков справа
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FlowRow(
                                modifier = Modifier.weight(1f, fill = false),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (availableCount != null && !isFullyAvailable && onOpenSync != null) {
                                    OutlinedButton(
                                        onClick = {
                                            onDismiss()
                                            onOpenSync()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(28.dp).pointerHoverIcon(PointerIcon.Hand)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CloudDownload,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "YamSync",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            TextButton(
                                onClick = {
                                    val next = if (isExpanded) null else playlist.id
                                    expandedPlaylistId = next
                                    saveAppConfig(AppConfigKeys.EXPANDED_PLAYLIST_ID, next ?: "")
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
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
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }


                        // 📜 Разворачивающийся список треков с возможностью удаления
                        if (expandedPlaylistId == playlist.id) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                thickness = 1.dp
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            if (playlist.trackPaths.isEmpty()) {
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
                                    playlist.trackPaths.forEach { trackPath ->
                                        val filename = trackPath
                                            .substringAfterLast('/')
                                            .substringAfterLast('\\')
                                            .substringBeforeLast('.')
                                        val isFileAvailable = remember(trackPath) {
                                            try {
                                                localFileExists(resolveLocalPath(trackPath)) || run {
                                                    val fn = trackPath.substringAfterLast('/').substringAfterLast('\\')
                                                    fn.isNotBlank() && localFileExists(resolveLocalPath(fn))
                                                }
                                            } catch (_: Throwable) {
                                                false
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = if (isFileAvailable) 0.6f else 0.35f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (isFileAvailable) {
                                                        val resolved = resolveLocalPath(trackPath).takeIf { localFileExists(it) }
                                                            ?: run {
                                                                val fn = trackPath.substringAfterLast('/').substringAfterLast('\\')
                                                                resolveLocalPath(fn).takeIf { localFileExists(it) }
                                                            } ?: trackPath
                                                        viewModel.openLocalPlaylist(playlist, restoreTrackId = resolved, autoPlayFirst = true)
                                                        onDismiss()
                                                    } else if (onOpenSync != null) {
                                                        onDismiss()
                                                        onOpenSync()
                                                    } else {
                                                        viewModel.errorMessage = "Файл '$filename' не найден на устройстве. Синхронизируйте его через YamSync."
                                                    }
                                                }
                                                .pointerHoverIcon(PointerIcon.Hand)
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
                                                        imageVector = if (isFileAvailable) Icons.Filled.MusicNote else Icons.Filled.CloudOff,
                                                        contentDescription = null,
                                                        tint = if (isFileAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        text = filename,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (isFileAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (!isFileAvailable) {
                                                        Text(
                                                            text = "(не скачан)",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.error,
                                                            fontSize = 10.sp
                                                        )
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        viewModel.removeTrackFromLocalPlaylist(playlist.id, trackPath)
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
