package io.github.audiz.ui

//import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
//import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.audiz.models.YandexMusicResponse
import io.github.audiz.models.TypedResult
import io.github.audiz.models.WaveSearchResultInfo
import io.github.audiz.models.PlaylistInfo
import io.github.audiz.models.FullTrackInfo

@Composable
fun SearchResultsList(
    result: YandexMusicResponse,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (PlaylistInfo) -> Unit,
    onAlbumClick: ((albumId: Long, albumTitle: String) -> Unit)? = null,
    isTrackDownloading: Boolean,
    downloadingTrackId: String?,
    onDownloadTrack: (trackId: String, trackTitle: String, artistName: String) -> Unit,
    onPlayTrack: (trackId: String, trackTitle: String, artistName: String) -> Unit,
    onWaveClick: ((WaveSearchResultInfo) -> Unit)? = null,
    playingTrackId: String? = null,
    isPlaying: Boolean = false,
    isPaused: Boolean = false,
    playbackPositionMs: Long = 0L,
    playbackDurationMs: Long = 0L,
    onTogglePlayPause: () -> Unit = {},
    onStopPlayback: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    isTrackDownloaded: (artistName: String, trackTitle: String) -> Boolean = { _, _ -> false },
    onDeleteTrack: ((trackId: String, trackTitle: String, artistName: String) -> Unit)? = null,
    onAddToPlaylist: ((FullTrackInfo) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val dismissKeyboard: () -> Unit = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            dismissKeyboard()
        }
    }

    val searchItems = remember(result) {
        result.result?.results?.filter { item ->
            when (item.type) {
                "track" -> item.track != null
                "artist" -> item.artist != null
                "playlist" -> item.playlist != null
                "album" -> item.album != null
                "wave" -> item.wave != null
                else -> false
            }
        } ?: emptyList()
    }

    LaunchedEffect(searchItems.size) {
        if (searchItems.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(searchItems) { item ->
                ResultCard(
                    item = item,
                    onArtistClick = {
                        dismissKeyboard()
                        onArtistClick(it)
                    },
                    onPlaylistClick = {
                        dismissKeyboard()
                        onPlaylistClick(it)
                    },
                    onAlbumClick = onAlbumClick?.let { callback ->
                        { albumId, albumTitle ->
                            dismissKeyboard()
                            callback(albumId, albumTitle)
                        }
                    },
                    isTrackDownloading = isTrackDownloading,
                    downloadingTrackId = downloadingTrackId,
                    onDownloadTrack = { trackId, trackTitle, artistName ->
                        dismissKeyboard()
                        onDownloadTrack(trackId, trackTitle, artistName)
                    },
                    onPlayTrack = { trackId, trackTitle, artistName ->
                        dismissKeyboard()
                        onPlayTrack(trackId, trackTitle, artistName)
                    },
                    playingTrackId = playingTrackId,
                    isPlaying = isPlaying,
                    isPaused = isPaused,
                    playbackPositionMs = playbackPositionMs,
                    playbackDurationMs = playbackDurationMs,
                    onTogglePlayPause = onTogglePlayPause,
                    onStopPlayback = onStopPlayback,
                    onSeek = onSeek,
                    isTrackDownloaded = isTrackDownloaded,
                    onDeleteTrack = onDeleteTrack,
                    onWaveClick = onWaveClick?.let { callback ->
                        { wave ->
                            dismissKeyboard()
                            callback(wave)
                        }
                    },
                    onAddToPlaylist = onAddToPlaylist
                )
            }
        }

        if (searchItems.isNotEmpty()) {
            /*VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(scrollState = listState)
            )*/
            PlatformScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ResultCard(
    item: TypedResult,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (PlaylistInfo) -> Unit,
    onAlbumClick: ((Long, String) -> Unit)? = null,
    isTrackDownloading: Boolean,
    downloadingTrackId: String?,
    onDownloadTrack: (trackId: String, trackTitle: String, artistName: String) -> Unit,
    onPlayTrack: (trackId: String, trackTitle: String, artistName: String) -> Unit,
    onWaveClick: ((WaveSearchResultInfo) -> Unit)? = null,
    playingTrackId: String? = null,
    isPlaying: Boolean = false,
    isPaused: Boolean = false,
    playbackPositionMs: Long = 0L,
    playbackDurationMs: Long = 0L,
    onTogglePlayPause: () -> Unit = {},
    onStopPlayback: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    isTrackDownloaded: (artistName: String, trackTitle: String) -> Boolean = { _, _ -> false },
    onDeleteTrack: ((trackId: String, trackTitle: String, artistName: String) -> Unit)? = null,
    onAddToPlaylist: ((FullTrackInfo) -> Unit)? = null
) {
    val track = item.track
    if (item.type == "track" && track != null) {
        val fullTrack = track.toFullTrackInfo()
        val artistsString = fullTrack.artists.joinToString { it.name }
        val isCurrentTrackDownloading = isTrackDownloading && downloadingTrackId == fullTrack.id
        val isCurrentTrackActive = (
            fullTrack.id == playingTrackId ||
            fullTrack.realId == playingTrackId ||
            (playingTrackId != null && fullTrack.id.removePrefix("local:") == playingTrackId.removePrefix("local:")) ||
            (playingTrackId != null && fullTrack.realId?.removePrefix("local:") == playingTrackId.removePrefix("local:"))
        ) && (isPlaying || isPaused)
        val isCurrentTrackPlaying = isCurrentTrackActive && isPlaying && !isPaused
        val rId = fullTrack.realId
        val isDownloaded = fullTrack.id.startsWith("local:") ||
            (rId != null && (rId.startsWith("local:") || rId.startsWith("/") || (rId.length > 2 && rId[1] == ':'))) ||
            isTrackDownloaded(artistsString, fullTrack.title)

        TrackItemCard(
            track = fullTrack,
            state = TrackItemState(
                isDownloading = isCurrentTrackDownloading,
                isActive = isCurrentTrackActive,
                isPlaying = isCurrentTrackPlaying,
                isDownloaded = isDownloaded,
                playbackPositionMs = playbackPositionMs,
                playbackDurationMs = playbackDurationMs,
            ),
            actions = TrackItemActions(
                onPlay = { onPlayTrack(track.id, track.title, artistsString) },
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
                onDownload = { onDownloadTrack(track.id, track.title, artistsString) },
                onDelete = if (onDeleteTrack != null) {
                    { onDeleteTrack(fullTrack.realId ?: track.id, track.title, artistsString) }
                } else null,
                onAddToPlaylist = if (onAddToPlaylist != null) {
                    { onAddToPlaylist(fullTrack) }
                } else null
            ),
            showAlbum = true
        )
        return
    }

    val isArtist = item.type == "artist"
    val isPlaylist = item.type == "playlist"
    val isAlbum = item.type == "album"
    val isWave = item.type == "wave"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    isArtist -> Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { item.artist?.id?.let { onArtistClick(it) } }
                    isPlaylist -> Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { item.playlist?.let { onPlaylistClick(it) } }
                    isAlbum -> Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { item.album?.let { onAlbumClick?.invoke(it.id, it.title) } }
                    isWave -> Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { item.wave?.let { onWaveClick?.invoke(it) } }
                    else -> Modifier
                }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isArtist -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                isPlaylist -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                isAlbum -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                isWave -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (item.type) {
                "wave" -> {
                    val wave = item.wave
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Waves,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = wave?.title ?: "Моя Волна",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val sub = wave?.subTitle ?: "Моя волна по жанру"
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilledTonalButton(
                            onClick = { wave?.let { onWaveClick?.invoke(it) } },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Слушать", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                "artist" -> {
                    val likes = item.artist?.likesCount
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            ContentTypeIcon(ContentType.ARTIST, modifier = Modifier.size(18.dp))
                            Text(
                                text = item.artist?.name ?: "Артист",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (likes != null && likes > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = "$likes лайков",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    Text(
                        text = "Нажмите, чтобы открыть все треки исполнителя",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                "playlist" -> {
                    val count = item.playlist?.trackCount ?: 0
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            ContentTypeIcon(ContentType.PLAYLIST, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Плейлист: ${item.playlist?.title ?: "Без названия"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (count > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = "$count треков",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    Text(
                        text = "Нажмите, чтобы открыть треки плейлиста",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                "album" -> {
                    val count = item.album?.trackCount ?: 0
                    val year = item.album?.year
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            ContentTypeIcon(ContentType.ALBUM, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Альбом: ${item.album?.title ?: "Без названия"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (year != null && year > 0) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        text = "$year",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            if (count > 0) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        text = "$count треков",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "Нажмите, чтобы открыть треки альбома",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
