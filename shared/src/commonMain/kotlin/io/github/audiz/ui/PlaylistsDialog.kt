package io.github.audiz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import io.github.audiz.models.CustomMediaSource
import io.github.audiz.models.LocalSourceType
import io.github.audiz.models.FolderListing
import io.github.audiz.models.FolderItem
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.isPlatformPickerSupported
import io.github.audiz.pickDirectory
import io.github.audiz.pickAudioOrPlaylistFile
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.audiz.DispatcherIO
import io.github.audiz.localFileExists
import io.github.audiz.resolveLocalPath

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
    var newPlaylistTitle by remember { mutableStateOf("") }
    var newPlaylistDesc by remember { mutableStateOf("") }

    var showAddSourceDialog by remember { mutableStateOf(false) }
    var sourcePathInput by remember { mutableStateOf("") }
    var sourceCustomName by remember { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    var browsingFolderSource by remember(viewModel.activeBrowsedFolderSource) {
        mutableStateOf(viewModel.activeBrowsedFolderSource)
    }

    var playlistToRename by remember { mutableStateOf<LocalPlaylist?>(null) }
    var yandexPlaylistToRename by remember { mutableStateOf<PlaylistInfo?>(null) }
    var isRenamingYandex by remember { mutableStateOf(false) }
    var renameTitle by remember { mutableStateOf("") }

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
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        edgePadding = 4.dp,
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
                                onRenameClick = {
                                    playlistToRename = it
                                    renameTitle = it.title
                                },
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
                                onRenameClick = {
                                    yandexPlaylistToRename = it
                                    renameTitle = it.title
                                }
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

    // Всплывающее окно добавления папки, аудиофайла или плейлиста M3U
    if (showAddSourceDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddSourceDialog = false
                sourceError = null
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Добавить источник музыки")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Укажите абсолютный путь к папке с музыкой, отдельному аудиофайлу (.mp3, .flac, .m4a и др.) или плейлисту (.m3u / .m3u8):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isPlatformPickerSupported) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val picked = pickDirectory()
                                    if (!picked.isNullOrBlank()) {
                                        sourcePathInput = picked
                                        val folderName = picked.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
                                        sourceCustomName = folderName
                                        sourceError = null
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Выбрать папку", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }

                            OutlinedButton(
                                onClick = {
                                    val picked = pickAudioOrPlaylistFile()
                                    if (!picked.isNullOrBlank()) {
                                        sourcePathInput = picked
                                        val fileName = picked.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                                        sourceCustomName = fileName
                                        sourceError = null
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Выбрать файл", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = sourcePathInput,
                        onValueChange = {
                            sourcePathInput = it
                            sourceError = null
                        },
                        label = { Text("Путь к папке или файлу") },
                        placeholder = { Text("/home/user/Music или C:\\Music\\song.mp3") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text
                                    if (!clipText.isNullOrBlank()) {
                                        sourcePathInput = clipText.trim()
                                        sourceError = null
                                    }
                                },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = "Вставить из буфера")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = sourceCustomName,
                        onValueChange = { sourceCustomName = it },
                        label = { Text("Название источника (необязательно)") },
                        placeholder = { Text("Моя папка, Любимый альбом...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val error = sourceError ?: viewModel.localMediaStatusMessage
                    if (!error.isNullOrBlank() && (error.startsWith("❌") || error.startsWith("⚠️"))) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = sourcePathInput.trim()
                        if (path.isNotBlank()) {
                            val added = viewModel.addCustomSource(
                                path = path,
                                customName = sourceCustomName.trim().ifBlank { null }
                            )
                            if (added != null) {
                                sourcePathInput = ""
                                sourceCustomName = ""
                                sourceError = null
                                showAddSourceDialog = false
                            } else {
                                sourceError = viewModel.localMediaStatusMessage ?: "Не удалось добавить источник"
                            }
                        }
                    },
                    enabled = sourcePathInput.isNotBlank()
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddSourceDialog = false
                    sourceError = null
                }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Всплывающее окно создания нового локального плейлиста
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Новый локальный плейлист") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newPlaylistTitle,
                        onValueChange = { newPlaylistTitle = it },
                        label = { Text("Название") },
                        placeholder = { Text("Например: В дорогу, Любимый рок...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPlaylistDesc,
                        onValueChange = { newPlaylistDesc = it },
                        label = { Text("Описание (необязательно)") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistTitle.isNotBlank()) {
                            viewModel.createLocalPlaylist(newPlaylistTitle, newPlaylistDesc)
                            newPlaylistTitle = ""
                            newPlaylistDesc = ""
                            showCreateDialog = false
                        }
                    },
                    enabled = newPlaylistTitle.isNotBlank()
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Всплывающее окно переименования локального плейлиста
    if (playlistToRename != null) {
        AlertDialog(
            onDismissRequest = { playlistToRename = null },
            title = { Text("Переименовать плейлист") },
            text = {
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it },
                    label = { Text("Новое название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pl = playlistToRename
                        if (pl != null && renameTitle.isNotBlank()) {
                            viewModel.renameLocalPlaylist(pl.id, renameTitle)
                            playlistToRename = null
                        }
                    },
                    enabled = renameTitle.isNotBlank()
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRename = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Всплывающее окно переименования плейлиста Яндекс Музыки
    if (yandexPlaylistToRename != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isRenamingYandex) {
                    yandexPlaylistToRename = null
                }
            },
            title = { Text("Переименовать плейлист Яндекс") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameTitle,
                        onValueChange = { renameTitle = it },
                        label = { Text("Новое название") },
                        singleLine = true,
                        enabled = !isRenamingYandex,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isRenamingYandex) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Сохранение в Яндекс Музыке...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pl = yandexPlaylistToRename
                        if (pl != null && renameTitle.isNotBlank()) {
                            isRenamingYandex = true
                            viewModel.renameYandexPlaylist(pl, renameTitle) { success ->
                                isRenamingYandex = false
                                if (success) {
                                    yandexPlaylistToRename = null
                                }
                            }
                        }
                    },
                    enabled = renameTitle.isNotBlank() && !isRenamingYandex
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { yandexPlaylistToRename = null },
                    enabled = !isRenamingYandex
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

/**
 * 💾 Вкладка 1: Локальные источники (папки, файлы, M3U) и оффлайн-плейлисты
 */
@Composable
private fun LocalPlaylistsTab(
    viewModel: SearchViewModel,
    onAddSourceClick: () -> Unit,
    onOpenSource: (CustomMediaSource) -> Unit,
    onBrowseFolder: (CustomMediaSource) -> Unit,
    onCreateClick: () -> Unit,
    onRenameClick: (LocalPlaylist) -> Unit,
    onOpenPlaylist: (LocalPlaylist) -> Unit,
    onOpenSync: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var expandedPlaylistId by remember { mutableStateOf<String?>(null) }

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
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${viewModel.customMediaSources.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Button(
                    onClick = onAddSourceClick,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Добавить", style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
            items(viewModel.customMediaSources, key = { "source_${it.id}" }) { source ->
                CustomSourceCard(
                    source = source,
                    onOpen = { onOpenSource(source) },
                    onBrowse = if (source.type == LocalSourceType.FOLDER) { { onBrowseFolder(source) } } else null,
                    onDelete = { viewModel.removeCustomSource(source.id) }
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Оффлайн-плейлисты",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${viewModel.localPlaylists.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Button(
                    onClick = onCreateClick,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Создать", style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
                            imageVector = Icons.Filled.QueueMusic,
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
            items(viewModel.localPlaylists, key = { "playlist_${it.id}" }) { playlist ->
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
                            val availableCount = remember(playlist.trackPaths) {
                                playlist.trackPaths.count { path ->
                                    localFileExists(resolveLocalPath(path)) || run {
                                        val fn = path.substringAfterLast('/').substringAfterLast('\\')
                                        fn.isNotBlank() && localFileExists(resolveLocalPath(fn))
                                    }
                                }
                            }
                            val isFullyAvailable = availableCount == playlist.trackCount
                            val isNoneAvailable = availableCount == 0 && playlist.trackCount > 0

                            // Верхний ряд: Название + бэдж + кнопка Play
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
                                    if (playlist.description.isNotBlank()) {
                                        Text(
                                            text = playlist.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = when {
                                            isFullyAvailable -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                        }
                                    ) {
                                        Text(
                                            text = when {
                                                isFullyAvailable -> "💾 Оффлайн: ${playlist.trackCount} треков"
                                                isNoneAvailable -> "☁️ Файлы не скачаны (${playlist.trackCount})"
                                                else -> "💾 Оффлайн: $availableCount/${playlist.trackCount}"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when {
                                                isFullyAvailable -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.error
                                            },
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
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

                            Spacer(modifier = Modifier.height(6.dp))

                            // Нижний ряд: Экспорт .m3u8, Переименовать, Удалить
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.exportLocalPlaylist(playlist.id) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(28.dp).pointerHoverIcon(PointerIcon.Hand)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.FileDownload,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "M3U8",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    }

                                    if (!isFullyAvailable && onOpenSync != null) {
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

                                    TextButton(
                                        onClick = {
                                            expandedPlaylistId = if (expandedPlaylistId == playlist.id) null else playlist.id
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp).pointerHoverIcon(PointerIcon.Hand)
                                    ) {
                                        Icon(
                                            imageVector = if (expandedPlaylistId == playlist.id) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = "Треки (${playlist.trackCount})",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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

                                    IconButton(
                                        onClick = { viewModel.deleteLocalPlaylist(playlist.id) },
                                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Удалить плейлист",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
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
                                                localFileExists(resolveLocalPath(trackPath)) || run {
                                                    val fn = trackPath.substringAfterLast('/').substringAfterLast('\\')
                                                    fn.isNotBlank() && localFileExists(resolveLocalPath(fn))
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

/**
 * 📁 Карточка добавленного источника медиа (папка, аудиофайл, M3U)
 */
@Composable
private fun CustomSourceCard(
    source: CustomMediaSource,
    onOpen: () -> Unit,
    onBrowse: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (onBrowse != null) onBrowse() else onOpen()
            }
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val icon = when (source.type) {
                    LocalSourceType.FOLDER -> Icons.Filled.Folder
                    LocalSourceType.SINGLE_FILE -> Icons.Filled.Audiotrack
                    LocalSourceType.PLAYLIST_FILE -> Icons.Filled.QueueMusic
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = source.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        val countText = when (source.type) {
                            LocalSourceType.FOLDER -> "📁 Папка: ${source.trackCount} треков"
                            LocalSourceType.SINGLE_FILE -> "🎵 Аудиофайл"
                            LocalSourceType.PLAYLIST_FILE -> "📋 M3U: ${source.trackCount} треков"
                        }
                        Text(
                            text = countText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

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
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onOpen,
                    modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayCircle,
                        contentDescription = "Слушать всё",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Удалить источник",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 📂 Встроенный проводник по папкам источника
 */
@Composable
private fun LocalFolderBrowserView(
    viewModel: SearchViewModel,
    source: CustomMediaSource,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    var currentPath by remember(source, viewModel.activeBrowsedFolderPath) {
        mutableStateOf(
            viewModel.activeBrowsedFolderPath?.takeIf { it.startsWith(source.path) } ?: source.path
        )
    }
    var folderListing by remember { mutableStateOf<FolderListing?>(null) }
    var isFolderLoading by remember { mutableStateOf(true) }

    LaunchedEffect(currentPath) {
        viewModel.activeBrowsedFolderSource = source
        viewModel.activeBrowsedFolderPath = currentPath
        isFolderLoading = true
        folderListing = withContext(DispatcherIO) {
            viewModel.browseFolder(currentPath, source.path)
        }
        isFolderLoading = false
    }

    val listing = folderListing

    Column(modifier = Modifier.fillMaxSize()) {
        // Навигационная плашка / Хлебные крошки
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (currentPath != source.path && listing?.parentPath != null) {
                        IconButton(
                            onClick = { listing.parentPath?.let { currentPath = it } },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = "Вверх на уровень",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    val folderLabel = if (currentPath == source.path) {
                        "Корневая папка: ${source.name}"
                    } else {
                        val folderName = currentPath.substringAfterLast('/')
                        "… / $folderName"
                    }
                    Text(
                        text = folderLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Кнопки: Играть папку (только файлы папки или со всеми подпапками)
                if (listing != null && (listing.tracks.isNotEmpty() || listing.subfolders.isNotEmpty())) {
                    val folderName = currentPath.substringAfterLast('/').ifBlank { source.name }
                    val currentTracksCount = listing.tracks.size
                    val totalRecursiveCount = currentTracksCount + listing.subfolders.sumOf { it.trackCount }
                    val hasSubfolders = listing.subfolders.isNotEmpty()
                    val hasCurrentTracks = currentTracksCount > 0

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasCurrentTracks && hasSubfolders) {
                            FilledTonalButton(
                                onClick = {
                                    viewModel.playFolder(
                                        folderPath = currentPath,
                                        folderName = folderName,
                                        source = source,
                                        isRecursive = false
                                    )
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Только эта папка ($currentTracksCount)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.playFolder(
                                        folderPath = currentPath,
                                        folderName = folderName,
                                        source = source,
                                        isRecursive = true
                                    )
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "С подпапками ($totalRecursiveCount)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        } else if (hasCurrentTracks) {
                            FilledTonalButton(
                                onClick = {
                                    viewModel.playFolder(
                                        folderPath = currentPath,
                                        folderName = folderName,
                                        source = source,
                                        isRecursive = false
                                    )
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Играть папку ($currentTracksCount)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        } else if (hasSubfolders) {
                            FilledTonalButton(
                                onClick = {
                                    viewModel.playFolder(
                                        folderPath = currentPath,
                                        folderName = folderName,
                                        source = source,
                                        isRecursive = true
                                    )
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Все треки из подпапок ($totalRecursiveCount)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isFolderLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        } else if (listing == null || (listing.subfolders.isEmpty() && listing.tracks.isEmpty())) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "В этой папке нет аудиофайлов или подпапок",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Секция 1: Подпапки (если есть)
                if (listing.subfolders.isNotEmpty()) {
                    item(key = "subfolders_header") {
                        Text(
                            text = "📁 Папки (${listing.subfolders.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    items(listing.subfolders, key = { "sub_${it.path}" }) { sub ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentPath = sub.path }
                                .pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = sub.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (sub.trackCount > 0) {
                                            Text(
                                                text = "${sub.trackCount} треков внутри",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    // Кнопка: играть эту конкретную подпапку
                                    if (sub.trackCount > 0) {
                                        IconButton(
                                            onClick = {
                                                viewModel.playFolder(folderPath = sub.path, folderName = sub.name, source = source)
                                                onDismiss()
                                            },
                                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.PlayCircle,
                                                contentDescription = "Слушать папку",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Войти",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Секция 2: Треки в текущей папке (если есть)
                if (listing.tracks.isNotEmpty()) {
                    item(key = "tracks_header") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "🎵 Треки (${listing.tracks.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    items(listing.tracks, key = { "trk_${it.id}" }) { track ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val folderName = currentPath.substringAfterLast('/').ifBlank { source.name }
                                    viewModel.playSingleLocalTrack(
                                        track = track,
                                        folderTracks = listing.tracks,
                                        folderName = folderName,
                                        folderPath = currentPath,
                                        source = source
                                    )
                                    onDismiss()
                                }
                                .pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val artist = track.artists.firstOrNull()?.name ?: "Unknown Artist"
                                        Text(
                                            text = artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        val folderName = currentPath.substringAfterLast('/').ifBlank { source.name }
                                        viewModel.playSingleLocalTrack(
                                            track = track,
                                            folderTracks = listing.tracks,
                                            folderName = folderName,
                                            folderPath = currentPath,
                                            source = source
                                        )
                                        onDismiss()
                                    },
                                    modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayCircle,
                                        contentDescription = "Слушать трек",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
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

/**
 * ☁️ Вкладка 2: Плейлисты пользователя из Яндекс Музыки
 */
@Composable
private fun YandexUserPlaylistsTab(
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
    val yandexPlaylistTracksCache = remember { mutableStateMapOf<Long, List<io.github.audiz.models.YandexPlaylistTrackItem>>() }
    val isDeletingTrackMap = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(viewModel.userPlaylists) { playlist ->
            val kind = playlist.kind
            val isExpanded = expandedPlaylistKind == kind

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

                            OutlinedButton(
                                onClick = { viewModel.downloadYandexPlaylistToLocal(playlist) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp).pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Скачать в оффлайн", style = MaterialTheme.typography.labelSmall, maxLines = 1)
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

/**
 * ✨ Вкладка 3: Персональные подборки Яндекса (Плейлист дня, Дежавю и др.)
 */
@Composable
private fun YandexCuratedTab(
    viewModel: SearchViewModel,
    onOpenPlaylist: (uuid: String, title: String) -> Unit
) {
    if (viewModel.personalPlaylists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Подборки подгружаются из аккаунта Яндекс Музыки...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(viewModel.personalPlaylists) { item ->
            val playlist = item.playlist ?: return@items
            val title = playlist.title.ifBlank {
                when (item.playlistType) {
                    "playlistOfTheDay" -> "Плейлист дня"
                    "recentTracks" -> "Премьера"
                    "neverHeard" -> "Дежавю"
                    else -> "Персональный плейлист"
                }
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Персональная подборка алгоритмов Яндекса",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = { onOpenPlaylist(playlist.playlistUuid, title) },
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
            }
        }
    }
}
