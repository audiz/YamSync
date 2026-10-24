package io.github.audiz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.audiz.SearchViewModel
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.PlaylistInfo
import io.github.audiz.ui.playlists.*

/**
 * 🗂️ Диалог медиатеки: управление персональными локальными плейлистами
 * и плейлистами из аккаунта Яндекс Музыки.
 */
@Composable
fun PlaylistsDialog(
    viewModel: SearchViewModel,
    onDismiss: () -> Unit,
    onOpenSync: (() -> Unit)? = null
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("💾 Локальные", "☁️ Яндекс", "✨ Подборки")

    var showCreateDialog by remember { mutableStateOf(false) }
    var showAddSourceDialog by remember { mutableStateOf(false) }
    var browsingFolderSource by remember(viewModel.activeBrowsedFolderSource) {
        mutableStateOf(viewModel.activeBrowsedFolderSource)
    }

    var playlistToRename by remember { mutableStateOf<LocalPlaylist?>(null) }
    var playlistToDelete by remember { mutableStateOf<LocalPlaylist?>(null) }
    var yandexPlaylistToRename by remember { mutableStateOf<PlaylistInfo?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 12.dp)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // Верхняя шапка: переключается в режим Проводника по папке
                if (browsingFolderSource != null) {
                    val src = browsingFolderSource!!
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    viewModel.resetBrowsedFolder()
                                    onDismiss()
                                },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.Home, contentDescription = "Домой")
                            }
                            IconButton(
                                onClick = {
                                    browsingFolderSource = null
                                    viewModel.activeBrowsedFolderSource = null
                                    viewModel.activeBrowsedFolderPath = null
                                },
                                modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад к медиатеке")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = src.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "📁 Проводник по папкам и трекам",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LibraryMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Медиатека",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Локальные коллекции и Яндекс Музыка",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (onOpenSync != null) {
                                IconButton(onClick = onOpenSync, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        imageVector = Icons.Filled.Wifi,
                                        contentDescription = "Синхронизация YamSync",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Вкладки
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(
                                        text = title,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Контент вкладок или Проводника
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (browsingFolderSource != null) {
                        LocalFolderBrowserView(
                            viewModel = viewModel,
                            source = browsingFolderSource!!,
                            onBack = {
                                browsingFolderSource = null
                                viewModel.activeBrowsedFolderSource = null
                                viewModel.activeBrowsedFolderPath = null
                            },
                            onDismiss = onDismiss
                        )
                    } else {
                        when (selectedTab) {
                            0 -> LocalPlaylistsTab(
                                viewModel = viewModel,
                                onAddSourceClick = { showAddSourceDialog = true },
                                onOpenSource = {
                                    viewModel.openCustomSource(it)
                                    onDismiss()
                                },
                                onBrowseFolder = { browsingFolderSource = it },
                                onCreateClick = { showCreateDialog = true },
                                onRenameClick = { playlistToRename = it },
                                onDeleteClick = { playlistToDelete = it },
                                onOpenPlaylist = {
                                    viewModel.openLocalPlaylist(it, autoPlayFirst = true)
                                    onDismiss()
                                },
                                onOpenSync = onOpenSync,
                                onDismiss = onDismiss
                            )
                            1 -> YandexUserPlaylistsTab(
                                viewModel = viewModel,
                                onOpenPlaylist = {
                                    viewModel.loadPlaylist(it)
                                    onDismiss()
                                },
                                onRenameClick = { yandexPlaylistToRename = it }
                            )
                            2 -> YandexCuratedTab(
                                viewModel = viewModel,
                                onOpenPlaylist = { uuid, title ->
                                    viewModel.loadPlaylistByUuid(uuid, title)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Вспомогательные диалоговые окна действий с плейлистами и источниками
    if (showAddSourceDialog) {
        AddSourceDialog(
            viewModel = viewModel,
            onDismiss = { showAddSourceDialog = false }
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, desc ->
                viewModel.createLocalPlaylist(title, desc)
                showCreateDialog = false
            }
        )
    }

    playlistToRename?.let { pl ->
        RenameLocalPlaylistDialog(
            playlist = pl,
            onDismiss = { playlistToRename = null },
            onConfirm = { newTitle ->
                viewModel.renameLocalPlaylist(pl.id, newTitle)
                playlistToRename = null
            }
        )
    }

    playlistToDelete?.let { target ->
        DeletePlaylistConfirmDialog(
            playlist = target,
            onDismiss = { playlistToDelete = null },
            onConfirm = {
                viewModel.deleteLocalPlaylist(target.id, deleteDownloadedTracks = true)
                playlistToDelete = null
            }
        )
    }

    yandexPlaylistToRename?.let { pl ->
        RenameYandexPlaylistDialog(
            playlist = pl,
            onDismiss = { yandexPlaylistToRename = null },
            onConfirm = { newTitle ->
                viewModel.renameYandexPlaylist(pl, newTitle) { success ->
                    if (success) {
                        yandexPlaylistToRename = null
                    }
                }
            }
        )
    }
}
