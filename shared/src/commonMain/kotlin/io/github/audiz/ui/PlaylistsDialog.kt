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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.audiz.SearchViewModel
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.PlaylistInfo

/**
 * 🗂️ Диалог медиатеки: управление персональными локальными плейлистами
 * и плейлистами из аккаунта Яндекс Музыки.
 */
@Composable
fun PlaylistsDialog(
    viewModel: SearchViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("💾 Локальные", "☁️ Яндекс", "✨ Подборки")

    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistTitle by remember { mutableStateOf("") }
    var newPlaylistDesc by remember { mutableStateOf("") }

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
                // Верхняя шапка
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
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть")
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

                Spacer(modifier = Modifier.height(14.dp))

                // Контент вкладок
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        0 -> LocalPlaylistsTab(
                            viewModel = viewModel,
                            onCreateClick = { showCreateDialog = true },
                            onRenameClick = {
                                playlistToRename = it
                                renameTitle = it.title
                            },
                            onOpenPlaylist = {
                                viewModel.openLocalPlaylist(it)
                                onDismiss()
                            }
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
 * 💾 Вкладка 1: Локальные плейлисты
 */
@Composable
private fun LocalPlaylistsTab(
    viewModel: SearchViewModel,
    onCreateClick: () -> Unit,
    onRenameClick: (LocalPlaylist) -> Unit,
    onOpenPlaylist: (LocalPlaylist) -> Unit
) {
    var expandedPlaylistId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Всего: ${viewModel.localPlaylists.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

        Spacer(modifier = Modifier.height(10.dp))

        if (viewModel.localPlaylists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "У вас пока нет локальных оффлайн-плейлистов",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Создайте свой первый плейлист или добавьте треки в него из поиска!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(viewModel.localPlaylists, key = { it.id }) { playlist ->
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
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "💾 Оффлайн: ${playlist.trackCount} треков",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
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
                                                        Text(
                                                            text = filename,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
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
