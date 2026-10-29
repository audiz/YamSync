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
import io.github.audiz.models.isLocal

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
    isTrackInPlaylist: ((FullTrackInfo) -> Boolean)? = null,
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
                Column {
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
                        isTrackInPlaylist = isTrackInPlaylist,
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
    isTrackInPlaylist: ((FullTrackInfo) -> Boolean)? = null,
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
        val isDownloaded = fullTrack.isLocal || isTrackDownloaded(artistsString, fullTrack.title)
        val isInPlaylist = isTrackInPlaylist?.invoke(fullTrack) ?: false

        CompactTrackRow(
            track = fullTrack,
            state = TrackItemState(
                isDownloading = isCurrentTrackDownloading,
                isActive = isCurrentTrackActive,
                isPlaying = isCurrentTrackPlaying,
                isDownloaded = isDownloaded,
                isInPlaylist = isInPlaylist,
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
            )
        )
        return
    }

    when (item.type) {
        "artist" -> item.artist?.let { artist ->
            val tracksCount = artist.counts?.tracks?.takeIf { it > 0 }
            CompactEntityRow(
                title = artist.name,
                typeLabel = "Исполнитель",
                typeColor = searchTypeColor("artist"),
                meta = listOf(
                    artist.genres.take(2).joinToString(", ").replaceFirstChar { it.uppercase() },
                    tracksCount?.let { "$it треков" } ?: "",
                    artist.likesCount?.takeIf { it > 0 }?.let { "${formatCompactCount(it)} лайков" } ?: ""
                ),
                onClick = { onArtistClick(artist.id) }
            )
        }
        "playlist" -> item.playlist?.let { playlist ->
            CompactEntityRow(
                title = playlist.title.ifBlank { "Без названия" },
                typeLabel = "Плейлист",
                typeColor = searchTypeColor("playlist"),
                meta = listOf(if (playlist.trackCount > 0) "${playlist.trackCount} треков" else ""),
                onClick = { onPlaylistClick(playlist) }
            )
        }
        "album" -> item.album?.let { album ->
            CompactEntityRow(
                title = album.title.ifBlank { "Без названия" },
                typeLabel = listOfNotNull("Альбом", album.year?.takeIf { it > 0 }?.toString()).joinToString(" · "),
                typeColor = searchTypeColor("album"),
                meta = listOf(
                    if (album.trackCount > 0) "${album.trackCount} треков" else ""
                ),
                onClick = { onAlbumClick?.invoke(album.id, album.title) }
            )
        }
    }
    if (item.type != "wave") return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { item.wave?.let { onWaveClick?.invoke(it) } },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
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
            }
        }
    }
}
