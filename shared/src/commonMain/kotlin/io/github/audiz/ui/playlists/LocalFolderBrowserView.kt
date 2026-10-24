package io.github.audiz.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.audiz.DispatcherIO
import io.github.audiz.SearchViewModel
import io.github.audiz.models.CustomMediaSource
import io.github.audiz.models.FolderListing
import kotlinx.coroutines.withContext

/**
 * 📂 Встроенный проводник по папкам источника
 */
@Composable
internal fun LocalFolderBrowserView(
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

                    itemsIndexed(listing.subfolders, key = { index, sub -> "sub_${sub.path}_$index" }) { index, sub ->
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

                    itemsIndexed(listing.tracks, key = { index, track -> "trk_${track.id}_$index" }) { index, track ->
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
                                        val artist = track.artists.firstOrNull()?.name
                                        if (!artist.isNullOrBlank()) {
                                            Text(
                                                text = artist,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
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
