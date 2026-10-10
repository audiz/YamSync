package io.github.audiz.ui

//import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
//import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.audiz.models.FullTrackInfo

@Composable
private fun LazyListState.OnBottomReached(buffer: Int = 2, onLoadMore: () -> Unit) {
    val shouldLoadMore = remember {
        derivedStateOf {
            val layoutInfo = layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
            totalItemsNumber > 1 && lastVisibleItemIndex >= (totalItemsNumber - buffer)
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) onLoadMore()
    }
}

@Composable
fun ArtistTracksList(
    tracks: List<FullTrackInfo>,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onBackClick: () -> Unit,
    onHomeClick: (() -> Unit)? = null,
    isTrackDownloading: Boolean,
    downloadingTrackId: String?,
    onDownloadTrack: (trackId: String, trackTitle: String, artistName: String) -> Unit,
    onPlayTrack: (trackId: String, trackTitle: String, artistName: String) -> Unit,
    playingTrackId: String? = null,
    isPlaying: Boolean = false,
    isPaused: Boolean = false,
    playbackPositionMs: Long = 0L,
    playbackDurationMs: Long = 0L,
    onTogglePlayPause: () -> Unit = {},
    onStopPlayback: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    totalTracksCount: Int = tracks.size,
    isLoadingAllPages: Boolean = false,
    onLoadAllClick: () -> Unit = {},
    isTrackDownloaded: (artistName: String, trackTitle: String) -> Boolean = { _, _ -> false },
    title: String = "Загружено треков",
    onRefresh: (() -> Unit)? = null,
    onDeleteTrack: ((trackId: String, trackTitle: String, artistName: String) -> Unit)? = null,
    onAddToPlaylist: ((FullTrackInfo) -> Unit)? = null,
    onRemoveFromPlaylist: ((FullTrackInfo) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    listState.OnBottomReached { if (canLoadMore) onLoadMore() }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onHomeClick != null) {
                TopBarIconButton(
                    icon = Icons.Filled.Home,
                    contentDescription = "Главная страница",
                    outlined = true,
                    onClick = onHomeClick
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBackClick
                ).pointerHoverIcon(PointerIcon.Hand)
            ) {
                TopBarIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    outlined = true,
                    onClick = onBackClick
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Назад", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(modifier = Modifier.width(16.dp))
            val total = if (totalTracksCount > tracks.size) totalTracksCount else tracks.size
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Показано: ${tracks.size}" + if (total > tracks.size) " из $total" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onRefresh != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TopBarIconButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "Обновить список",
                    outlined = true,
                    onClick = onRefresh
                )
            }

            if (canLoadMore && total > tracks.size) {
                Spacer(modifier = Modifier.width(8.dp))
                TopBarIconButton(
                    icon = Icons.Filled.UnfoldMore,
                    contentDescription = "Прогрузить весь список треков",
                    outlined = true,
                    isLoading = isLoadingAllPages,
                    onClick = onLoadAllClick
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(tracks, key = { index, track -> "${track.id}_$index" }) { index, track ->
                    val artistsString = track.artists.joinToString { it.name }
                    val isCurrentTrackDownloading = isTrackDownloading && downloadingTrackId == track.id
                    val isCurrentTrackActive = (
                        track.id == playingTrackId ||
                        track.realId == playingTrackId ||
                        (playingTrackId != null && track.id.removePrefix("local:") == playingTrackId.removePrefix("local:")) ||
                        (playingTrackId != null && track.realId?.removePrefix("local:") == playingTrackId.removePrefix("local:"))
                    ) && (isPlaying || isPaused)
                    val isCurrentTrackPlaying = isCurrentTrackActive && isPlaying && !isPaused
                    val rId = track.realId
                    val isDownloaded = track.id.startsWith("local:") ||
                        (rId != null && (rId.startsWith("local:") || rId.startsWith("/") || (rId.length > 2 && rId[1] == ':'))) ||
                        isTrackDownloaded(artistsString, track.title)

                    TrackItemCard(
                        track = track,
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
                                { onDeleteTrack(track.realId ?: track.id, track.title, artistsString) }
                            } else null,
                            onAddToPlaylist = if (onAddToPlaylist != null) {
                                { onAddToPlaylist(track) }
                            } else null,
                            onRemoveFromPlaylist = if (onRemoveFromPlaylist != null) {
                                { onRemoveFromPlaylist(track) }
                            } else null
                        ),
                        showAlbum = true
                    )
                }
                if (canLoadMore) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }

            if (tracks.isNotEmpty()) {
                PlatformScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    state = listState
                )
            }
        }
    }
}

