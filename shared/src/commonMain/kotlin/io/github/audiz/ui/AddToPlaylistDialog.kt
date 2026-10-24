package io.github.audiz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import io.github.audiz.AppConfigKeys
import io.github.audiz.SearchViewModel
import io.github.audiz.isPlatformPickerSupported
import io.github.audiz.loadAppConfig
import io.github.audiz.pickSaveFile
import io.github.audiz.sanitizeKeepSpaces
import io.github.audiz.saveAppConfig
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.PlaylistInfo

/**
 * 📂 Диалог добавления трека в персональный плейлист:
 * - 💾 Локальные (Оффлайн) — добавление на диск (с автоскачиванием)
 * - ☁️ Яндекс Музыка — добавление в облачный плейлист аккаунта
 * - 📁 Сохранить как... — сохранение файла в любую папку через системное окно
 */
@Composable
fun AddToPlaylistDialog(
    track: FullTrackInfo,
    viewModel: SearchViewModel,
    onDismiss: () -> Unit
) {
    val tabs = remember {
        if (isPlatformPickerSupported) {
            listOf("💾 Локальные", "☁️ Яндекс", "📁 Сохранить как...")
        } else {
            listOf("💾 Локальные", "☁️ Яндекс")
        }
    }
    val initialTab = remember {
        val saved = loadAppConfig(AppConfigKeys.LAST_ADD_TO_PLAYLIST_TAB)?.toIntOrNull() ?: 0
        saved.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    }
    var selectedTab by remember { mutableStateOf(initialTab) }

    var isCreatingLocal by remember { mutableStateOf(false) }
    var newLocalTitle by remember { mutableStateOf("") }

    var isCreatingYandex by remember { mutableStateOf(false) }
    var newYandexTitle by remember { mutableStateOf("") }

    var isSavingToFile by remember { mutableStateOf(false) }
    val defaultFileName = remember(track) {
        val cleanArtist = track.artists.joinToString(", ") { it.name }.trim()
        val cleanTitle = track.title.trim()
        val raw = if (cleanArtist.isNotEmpty()) "$cleanArtist — $cleanTitle" else cleanTitle
        val clean = sanitizeKeepSpaces(raw)
        if (clean.endsWith(".mp3", ignoreCase = true)) clean else "$clean.mp3"
    }

    var isActionInProgress by remember { mutableStateOf(false) }
    val yandexPlaylistsWithTrack = remember { mutableStateMapOf<Long, Boolean>() }

    val coroutineScope = rememberCoroutineScope()
    var dragOffsetY by remember { mutableStateOf(0f) }
    val animatedOffsetY = remember { Animatable(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val isCompact = maxWidth < 380.dp
            val horizontalPadding = if (isCompact) 10.dp else 16.dp
            val cardPadding = if (isCompact) 4.dp else 6.dp

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (dragOffsetY + animatedOffsetY.value).roundToInt()) }
                    .padding(horizontal = cardPadding, vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontalPadding)
                ) {
                    // 🔝 Шапка с Drag Handle, кнопкой закрытия и жестом свайпа вниз
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        if (dragAmount > 0 || dragOffsetY > 0) {
                                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                                            change.consume()
                                        }
                                    },
                                    onDragEnd = {
                                        if (dragOffsetY > 80f) {
                                            onDismiss()
                                        } else {
                                            coroutineScope.launch {
                                                animatedOffsetY.snapTo(dragOffsetY)
                                                dragOffsetY = 0f
                                                animatedOffsetY.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        dragOffsetY = 0f
                                    }
                                )
                            }
                    ) {
                        // Полоска-индикатор (Drag Handle)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }

                        // Заголовок
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = "Добавить в плейлист",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(28.dp)
                                    .pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Информация о треке
                        Text(
                            text = "${track.artists.joinToString { it.name }} — ${track.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                // Переключатель вкладок: Локальные / Яндекс Музыка
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = {
                                selectedTab = index
                                saveAppConfig(AppConfigKeys.LAST_ADD_TO_PLAYLIST_TAB, index.toString())
                            },
                            text = {
                                Text(
                                    text = title,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    // ==================== 💾 ВКЛАДКА: ЛОКАЛЬНЫЕ ПЛЕЙЛИСТЫ ====================
                    if (!isCreatingLocal) {
                        if (isPlatformPickerSupported) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { isCreatingLocal = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Новый плейлист", maxLines = 1)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val savePath = pickSaveFile(defaultFileName)
                                        if (!savePath.isNullOrBlank()) {
                                            viewModel.saveTrackToFilePath(track, savePath) {
                                                onDismiss()
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Сохранить как...", maxLines = 1)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { isCreatingLocal = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerHoverIcon(PointerIcon.Hand),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Создать новый плейлист", maxLines = 1)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newLocalTitle,
                                onValueChange = { newLocalTitle = it },
                                placeholder = { Text("Название плейлиста") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            IconButton(
                                onClick = {
                                    if (newLocalTitle.isNotBlank()) {
                                        val created = viewModel.createLocalPlaylist(newLocalTitle)
                                        viewModel.addTrackToLocalPlaylist(created.id, track)
                                        onDismiss()
                                    }
                                },
                                enabled = newLocalTitle.isNotBlank(),
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = "Создать", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                Spacer(modifier = Modifier.height(12.dp))

                // Список существующих локальных плейлистов
                Text(
                    text = "Ваши локальные плейлисты:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                if (viewModel.localPlaylists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "У вас пока нет локальных плейлистов.\nСоздайте первый!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(viewModel.localPlaylists, key = { index, playlist -> "local_${playlist.id}_$index" }) { index, playlist ->
                            val isAlreadyIn = viewModel.isTrackInLocalPlaylist(playlist, track)

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isAlreadyIn) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable {
                                        if (isAlreadyIn) {
                                            viewModel.removeTrackFromLocalPlaylist(playlist.id, track)
                                        } else {
                                            viewModel.addTrackToLocalPlaylist(playlist.id, track)
                                            onDismiss()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.LibraryMusic,
                                            contentDescription = null,
                                            tint = if (isAlreadyIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column {
                                            Text(
                                                text = playlist.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${playlist.trackCount} треков на диске",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (isAlreadyIn) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "В плейлисте",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            IconButton(
                                                onClick = {
                                                    viewModel.removeTrackFromLocalPlaylist(playlist.id, track)
                                                },
                                                modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.DeleteOutline,
                                                    contentDescription = "Убрать из плейлиста",
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Подсказка об автоскачивании
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.DownloadDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Если трека нет на диске, он автоматически скачается",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
                } else if (selectedTab == 1) {
                    // ==================== ☁️ ВКЛАДКА: ЯНДЕКС МУЗЫКА ====================
                    if (!isCreatingYandex) {
                        OutlinedButton(
                            onClick = { isCreatingYandex = true },
                            enabled = !isActionInProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerHoverIcon(PointerIcon.Hand),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Создать в Яндекс Музыке", maxLines = 1)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newYandexTitle,
                                onValueChange = { newYandexTitle = it },
                                placeholder = { Text("Название плейлиста") },
                                singleLine = true,
                                enabled = !isActionInProgress,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            if (isActionInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp).padding(4.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                IconButton(
                                    onClick = {
                                        if (newYandexTitle.isNotBlank()) {
                                            isActionInProgress = true
                                            viewModel.createYandexPlaylist(newYandexTitle) { created ->
                                                if (created != null) {
                                                    viewModel.addTrackToYandexPlaylist(created, track) {
                                                        isActionInProgress = false
                                                        onDismiss()
                                                    }
                                                } else {
                                                    isActionInProgress = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = newYandexTitle.isNotBlank() && !isActionInProgress,
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = "Создать", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Ваши плейлисты Яндекс Музыки:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    if (viewModel.userPlaylists.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Плейлисты в аккаунте не найдены.\nСоздайте новый выше!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(viewModel.userPlaylists, key = { index, playlist -> "ya_${playlist.kind ?: playlist.uid}_$index" }) { index, playlist ->
                                val kind = playlist.kind
                                val cleanTrackId = (track.realId?.ifBlank { null } ?: track.id).removePrefix("local:").substringBefore(":")
                                val rawTrackId = track.id.removePrefix("local:").substringBefore(":")
                                val isCachedInPlaylist = kind != null && viewModel.userPlaylistsTrackIds[kind]?.let { ids ->
                                    ids.contains(cleanTrackId) || ids.contains(rawTrackId) || ids.contains(track.id)
                                } == true
                                val isAlreadyIn = when (yandexPlaylistsWithTrack[kind]) {
                                    true -> true
                                    false -> false
                                    null -> isCachedInPlaylist
                                }

                                val isCleanIdNumeric = cleanTrackId.toLongOrNull() != null

                                LaunchedEffect(kind, cleanTrackId) {
                                    if (kind != null && !isCachedInPlaylist && !yandexPlaylistsWithTrack.containsKey(kind)) {
                                        val details = viewModel.loadYandexPlaylistDetails(playlist)
                                        val hasTrack = details?.tracks?.any { item ->
                                            val itemCleanId = item.id.substringBefore(":")
                                            if (isCleanIdNumeric) {
                                                itemCleanId == cleanTrackId
                                            } else {
                                                item.title.isNotBlank() &&
                                                item.title.equals(track.title, ignoreCase = true) &&
                                                (track.artists.isEmpty() || track.artists.any { a ->
                                                    item.artist.contains(a.name, ignoreCase = true) || a.name.contains(item.artist, ignoreCase = true)
                                                })
                                            }
                                        } == true
                                        yandexPlaylistsWithTrack[kind] = hasTrack
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isAlreadyIn) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pointerHoverIcon(if (isActionInProgress) PointerIcon.Default else PointerIcon.Hand)
                                        .clickable(enabled = !isActionInProgress) {
                                            isActionInProgress = true
                                            if (isAlreadyIn) {
                                                viewModel.removeTrackFromYandexPlaylist(playlist, track) { success ->
                                                    isActionInProgress = false
                                                    if (success && kind != null) {
                                                        yandexPlaylistsWithTrack[kind] = false
                                                    }
                                                }
                                            } else {
                                                viewModel.addTrackToYandexPlaylist(playlist, track) { success ->
                                                    isActionInProgress = false
                                                    if (success && kind != null) {
                                                        yandexPlaylistsWithTrack[kind] = true
                                                        onDismiss()
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Cloud,
                                                contentDescription = null,
                                                tint = if (isAlreadyIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = playlist.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${playlist.trackCount} треков в облаке",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        if (isAlreadyIn) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "В плейлисте",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                IconButton(
                                                    onClick = {
                                                        isActionInProgress = true
                                                        viewModel.removeTrackFromYandexPlaylist(playlist, track) { success ->
                                                            isActionInProgress = false
                                                            if (success && kind != null) {
                                                                yandexPlaylistsWithTrack[kind] = false
                                                            }
                                                        }
                                                    },
                                                    enabled = !isActionInProgress,
                                                    modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.DeleteOutline,
                                                        contentDescription = "Убрать из плейлиста",
                                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Подсказка об облачной синхронизации
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Трек мгновенно появится в вашем аккаунте Яндекс Музыки",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                } else if (selectedTab == 2) {
                    // ==================== 📁 ВКЛАДКА: СОХРАНИТЬ КАК (СИСТЕМНЫЙ ДИАЛОГ) ====================
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Column {
                                    Text(
                                        text = "Системное окно сохранения файла",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Откроется окно проводника. Вы сможете выбрать любую папку и при необходимости изменить имя файла прямо в нём.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = "Имя файла по умолчанию:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = defaultFileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val savePath = pickSaveFile(defaultFileName)
                                if (!savePath.isNullOrBlank()) {
                                    isSavingToFile = true
                                    viewModel.saveTrackToFilePath(track, savePath) {
                                        isSavingToFile = false
                                        onDismiss()
                                    }
                                }
                            },
                            enabled = !isSavingToFile,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isSavingToFile) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Сохранение...")
                            } else {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Выбрать папку и сохранить", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
}
