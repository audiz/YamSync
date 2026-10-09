package io.github.audiz

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Waves
import androidx.compose.ui.text.font.FontWeight
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.ui.SearchInputField
import io.github.audiz.ui.SearchResultsList
import io.github.audiz.ui.ArtistTracksList
import io.github.audiz.ui.HomeHub
import io.github.audiz.ui.PlaylistsDialog
import io.github.audiz.ui.AddToPlaylistDialog
import io.github.audiz.ui.LogsDialog
import io.github.audiz.ui.PlayerBar
import io.github.audiz.ui.PlayerBarState
import io.github.audiz.ui.PlayerBarActions
import io.github.audiz.ui.SettingsDialog
import io.github.audiz.ui.SettingsState
import io.github.audiz.ui.SettingsActions
import io.github.audiz.ui.AppAccentColor
import io.github.audiz.ui.buildAppColorScheme
import io.github.audiz.ui.EqualizerDialog
import io.github.audiz.ui.MobileFullPlayerSheet
import io.github.audiz.ui.BackHandler
import io.github.audiz.dsp.EqualizerEngine

private data class TrackDeleteConfirmInfo(
    val trackId: String,
    val title: String,
    val artist: String
)

@Composable
fun App() {
    // Инициализируем вьюмодель реактивного состояния приложения
    val searchViewModel: SearchViewModel = viewModel { SearchViewModel() }

    // 🔗 Обработка входящих Deep Link ссылок
    LaunchedEffect(Unit) {
        DeepLinkHandler.pendingTrackId.collect { trackId ->
            if (trackId != null) {
                DeepLinkHandler.consumeTrackId()
                searchViewModel.playTrackByExternalId(trackId)
            }
        }
    }
    
    val isDarkTheme = when (searchViewModel.appTheme) {
        "Light" -> false
        "Dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme() // fallback to system
    }
    
    val selectedAccent = remember(searchViewModel.accentColor) {
        AppAccentColor.fromKey(searchViewModel.accentColor)
    }

    val colors = remember(isDarkTheme, selectedAccent) {
        buildAppColorScheme(isDarkTheme, selectedAccent)
    }

    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {


            // Навигация: если список загруженных треков активен и не пуст — переключаемся на Экран Б
            val showTracksScreen = searchViewModel.isTracksListVisible && (searchViewModel.loadedTracks.isNotEmpty() || searchViewModel.isLoading)
            var settingsExpanded by remember { mutableStateOf(false) }
            var isMobilePlayerExpanded by remember { mutableStateOf(false) }
            var showEqualizerDialog by remember { mutableStateOf(false) }
            var equalizerInitialTab by remember { mutableStateOf(0) }
            var showPlaylistsDialog by remember { mutableStateOf(false) }
            var showYamSyncDialog by remember { mutableStateOf(false) }
            var trackForPlaylistDialog by remember { mutableStateOf<FullTrackInfo?>(null) }
            var showLogsDialog by remember { mutableStateOf(false) }
            var trackToDelete by remember { mutableStateOf<TrackDeleteConfirmInfo?>(null) }
            val eqState by EqualizerEngine.state.collectAsState()
            val isMobileUi = searchViewModel.isMobileUi

            val isFirstPage = !showTracksScreen
            val currentWave = searchViewModel.currentWaveTrack
            val hasTrackCandidate = searchViewModel.playerTrackId != null || currentWave != null || searchViewModel.plannedNextTrack != null
            val isWaveActiveOrPreloaded = searchViewModel.isWaveMode || (isFirstPage && searchViewModel.playerTrackId == null && currentWave != null)

            val displayTitle = when {
                searchViewModel.playerTrackTitle.isNotEmpty() -> searchViewModel.playerTrackTitle
                isFirstPage && currentWave != null -> currentWave.title
                else -> "Выберите трек"
            }

            val displayArtist = when {
                searchViewModel.playerArtistName.isNotEmpty() -> searchViewModel.playerArtistName
                isFirstPage && currentWave != null -> currentWave.artists.joinToString { it.name }
                else -> ""
            }

            val isCurrentTrackDownloaded = remember(displayArtist, displayTitle, searchViewModel.isCurrentTrackSavedToDisk) {
                searchViewModel.isCurrentTrackSavedToDisk ||
                    (displayTitle.isNotBlank() && searchViewModel.isTrackDownloaded(displayArtist, displayTitle))
            }
            val isCurrentTrackDownloadedOrLocal = searchViewModel.isCurrentTrackLocal || isCurrentTrackDownloaded

            val playerBarState = PlayerBarState(
                trackTitle = displayTitle,
                artistName = displayArtist,
                isPlaying = searchViewModel.playerIsPlaying,
                isPaused = searchViewModel.playerIsPaused,
                currentPositionMs = searchViewModel.playerPositionMs,
                durationMs = searchViewModel.playerDurationMs,
                bitrate = searchViewModel.playerBitrate,
                volume = searchViewModel.playerVolume,
                isShuffle = searchViewModel.isShuffleEnabled,
                isNextLoading = searchViewModel.isNextTrackLoading,
                isPrevLoading = searchViewModel.isPrevTrackLoading,
                isPlayLoading = searchViewModel.isPlayTrackLoading,
                isRecordToDisk = if (searchViewModel.isCurrentTrackLocal) false else searchViewModel.isCurrentTrackSavedToDisk,
                isSavingToDisk = if (searchViewModel.isCurrentTrackLocal) false else searchViewModel.isCurrentTrackSavingToDisk,
                isSharing = searchViewModel.isSharingTrack,
                isFavorite = if (searchViewModel.isCurrentTrackLocal) false else searchViewModel.isCurrentTrackLiked,
                isDisliked = if (searchViewModel.isCurrentTrackLocal) false else searchViewModel.isCurrentTrackDisliked,
                isInPlaylist = searchViewModel.isCurrentTrackInPlaylist,
                playlistCount = searchViewModel.currentTrackPlaylistsCount,
                isConfigActive = settingsExpanded,
                isEqualizerActive = eqState.isEnabled,
                hasTrackCandidate = hasTrackCandidate,
                isWave = isWaveActiveOrPreloaded,
                waveTitle = if (isWaveActiveOrPreloaded) {
                    searchViewModel.currentWaveTitle?.takeIf { it.isNotBlank() && !it.equals("Моя Волна", ignoreCase = true) }
                } else null,
                coverUri = searchViewModel.playerCoverUri ?: if (isWaveActiveOrPreloaded) currentWave?.coverUri else null,
                isMobile = isMobileUi,
                isQueueOpen = showTracksScreen,
                isDownloadedOrLocal = isCurrentTrackDownloadedOrLocal
            )

            val playerBarActions = PlayerBarActions(
                onVolumeChange = { searchViewModel.changeVolume(it) },
                onPlayPause = { searchViewModel.togglePlayPause() },
                onStop = { searchViewModel.stopPlayback() },
                onSeek = { searchViewModel.seekPlayer(it) },
                onToggleShuffle = { searchViewModel.toggleShuffle() },
                onNext = { searchViewModel.playNextTrack() },
                onPrev = { searchViewModel.playPrevTrack() },
                onConfigClick = { settingsExpanded = !settingsExpanded },
                onToggleRecordToDisk = if (searchViewModel.isCurrentTrackLocal) null else {
                    {
                        if (searchViewModel.isCurrentTrackSavedToDisk) {
                            val trackId = searchViewModel.playerTrackId ?: currentWave?.id
                            val title = searchViewModel.playerTrackTitle.ifBlank { currentWave?.title ?: "" }
                            val artist = searchViewModel.playerArtistName.ifBlank { currentWave?.artists?.joinToString { it.name } ?: "" }
                            if (trackId != null) {
                                trackToDelete = TrackDeleteConfirmInfo(
                                    trackId = trackId,
                                    title = title,
                                    artist = artist
                                )
                            }
                        } else {
                            searchViewModel.toggleCurrentTrackSaveOrDelete()
                        }
                    }
                },
                onShareTrack = { searchViewModel.shareCurrentTrack() },
                onAddToPlaylist = {
                    val track = searchViewModel.getCurrentPlayingTrackInfo()
                        ?: currentWave
                        ?: searchViewModel.plannedNextTrack
                    if (track != null) {
                        trackForPlaylistDialog = track
                    }
                },
                onToggleFavorite = if (searchViewModel.isCurrentTrackLocal) null else { { searchViewModel.toggleLikeCurrentTrack() } },
                onToggleDislike = {
                    if (isCurrentTrackDownloadedOrLocal) {
                        val trackId = searchViewModel.playerTrackId ?: searchViewModel.currentPlayingFilePath ?: currentWave?.id
                        val title = searchViewModel.playerTrackTitle.ifBlank { currentWave?.title ?: "" }
                        val artist = searchViewModel.playerArtistName.ifBlank { currentWave?.artists?.joinToString { it.name } ?: "" }
                        if (trackId != null) {
                            trackToDelete = TrackDeleteConfirmInfo(
                                trackId = trackId,
                                title = title,
                                artist = artist
                            )
                        }
                    } else {
                        searchViewModel.toggleDislikeCurrentTrack()
                    }
                },
                onOpenEqualizer = {
                    equalizerInitialTab = 0
                    showEqualizerDialog = true
                },
                onOpenQueue = if (searchViewModel.loadedTracks.isNotEmpty()) {
                    {
                        if (isMobileUi) {
                            searchViewModel.openQueueView(TracksListOrigin.MOBILE_PLAYER)
                            isMobilePlayerExpanded = false
                        } else {
                            searchViewModel.toggleQueueView()
                        }
                    }
                } else null,
                onExpandMobilePlayer = { isMobilePlayerExpanded = true },
                onResetWave = { searchViewModel.resetToDefaultWave() }
            )

            Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isMobileUi) 10.dp else 16.dp)
                            .padding(bottom = if (isMobileUi) 6.dp else 16.dp)
                    ) {
                        // 🌊 Надпись над плеером: Моя Волна (на десктопе сохраняем прежнее поведение, на мобильных название уже внутри мини-плеера)
                        if (!isMobileUi && (searchViewModel.isWaveMode || (isFirstPage && searchViewModel.playerTrackId == null))) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Waves,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                val thematicWaveTitle = searchViewModel.currentWaveTitle?.takeIf {
                                    it.isNotBlank() && !it.equals("Моя Волна", ignoreCase = true)
                                }
                                Text(
                                    text = if (thematicWaveTitle != null) {
                                        "Моя Волна • $thematicWaveTitle"
                                    } else {
                                        "Моя Волна"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (thematicWaveTitle != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { searchViewModel.resetToDefaultWave() }
                                            )
                                            .pointerHoverIcon(PointerIcon.Hand),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Сбросить к персональной Волне",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }

                        PlayerBar(
                            state = playerBarState,
                            actions = playerBarActions
                        )
                    }
                }
            ) { paddingValues ->
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp).padding(top = 16.dp)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!showTracksScreen) {
                            // ==================================================
                            // 👤 ЭКРАН А: ПОИСК И ВЫДАЧА
                            // ==================================================
                            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        SearchInputField(
                                            query = searchViewModel.searchQuery,
                                            onQueryChange = {
                                                searchViewModel.searchQuery = it
                                                if (it.isBlank() && searchViewModel.searchResult != null) {
                                                    searchViewModel.clearSearch()
                                                }
                                            },
                                            onSearchClick = { searchViewModel.performSearch() },
                                            onClear = { searchViewModel.clearSearch() },
                                            isLoading = searchViewModel.isLoading,
                                            onSettingsClick = { settingsExpanded = true }
                                        )
                                    }

                                    io.github.audiz.ui.sync.YamSyncPill(
                                        syncManager = searchViewModel.yamSyncManager,
                                        isCompact = isMobileUi,
                                        onOpenSyncDialog = { showYamSyncDialog = true }
                                    )
                                }

                            // ==================================================
                            // 🏠 МУЗЫКАЛЬНЫЙ ХАБ (Скрывается при активном поиске)
                            // ==================================================
                            AnimatedVisibility(
                                visible = searchViewModel.searchResult == null && !searchViewModel.isLoading,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    HomeHub(
                                        viewModel = searchViewModel,
                                        onOpenMediaLibrary = {
                                            searchViewModel.resetBrowsedFolder()
                                            showPlaylistsDialog = true
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Вывод лога ошибки на экран в случае сбоя сети
                            searchViewModel.errorMessage?.let { error ->
                                val displayText = if (error.startsWith("⚠️")) error else "⚠️ Ошибка: $error"
                                val isMissingFilesError = error.contains("YamSync", ignoreCase = true) ||
                                    error.contains("не найдены на устройстве", ignoreCase = true) ||
                                    error.contains("не скачаны", ignoreCase = true)
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Text(
                                                text = displayText,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f).clickable { showLogsDialog = true }
                                            )
                                            IconButton(
                                                onClick = { searchViewModel.errorMessage = null },
                                                modifier = Modifier.size(24.dp).padding(start = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = "Закрыть",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "📋 Нажмите на текст, чтобы открыть логи",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 11.sp,
                                                modifier = Modifier.clickable { showLogsDialog = true }
                                            )
                                            if (isMissingFilesError) {
                                                TextButton(
                                                    onClick = {
                                                        searchViewModel.errorMessage = null
                                                        showYamSyncDialog = true
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("🔄 Открыть YamSync", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Сетка результатов поиска
                            searchViewModel.searchResult?.let { response ->
                                SearchResultsList(
                                    result = response,
                                    onArtistClick = { artistId ->
                                        val artistName = response.result?.results?.firstOrNull { it.artist?.id == artistId }?.artist?.name
                                        searchViewModel.loadArtistTracks(artistId, artistName)
                                    },
                                    onPlaylistClick = { playlist ->
                                        searchViewModel.loadPlaylist(playlist)
                                    },
                                    isTrackDownloading = searchViewModel.isTrackDownloading,
                                    downloadingTrackId = searchViewModel.downloadingTrackId,
                                    onDownloadTrack = { trackId, trackTitle, artistName ->
                                        searchViewModel.downloadTrack(trackId, trackTitle, artistName)
                                    },
                                    onPlayTrack = { trackId, trackTitle, artistName ->
                                        searchViewModel.playTrack(trackId, trackTitle, artistName)
                                    },
                                    onWaveClick = { wave ->
                                        searchViewModel.startThematicWave(wave.title, wave.seeds)
                                    },
                                    playingTrackId = searchViewModel.playerTrackId,
                                    isPlaying = searchViewModel.playerIsPlaying,
                                    isPaused = searchViewModel.playerIsPaused,
                                    playbackPositionMs = searchViewModel.playerPositionMs,
                                    playbackDurationMs = searchViewModel.playerDurationMs,
                                    onTogglePlayPause = { searchViewModel.togglePlayPause() },
                                    onStopPlayback = { searchViewModel.stopPlayback() },
                                    onSeek = { searchViewModel.seekPlayer(it) },
                                    isTrackDownloaded = { artist, title -> searchViewModel.isTrackDownloaded(artist, title) },
                                    onDeleteTrack = { trackId, trackTitle, artistName ->
                                        trackToDelete = TrackDeleteConfirmInfo(trackId, trackTitle, artistName)
                                    },
                                    onAddToPlaylist = { track -> trackForPlaylistDialog = track }
                                )
                            }
                        }
                    } else {
                        val isBackGoingToFolderBrowser = searchViewModel.tracksListOrigin == TracksListOrigin.FOLDER_BROWSER && searchViewModel.activeBrowsedFolderSource != null
                        val isBackGoingToMobilePlayer = isMobileUi && searchViewModel.tracksListOrigin == TracksListOrigin.MOBILE_PLAYER

                        BackHandler(enabled = showTracksScreen) {
                            if (isBackGoingToFolderBrowser) {
                                val targetSource = searchViewModel.activeBrowsedFolderSource
                                val targetPath = searchViewModel.activeBrowsedFolderPath
                                searchViewModel.closeArtistTracks()
                                searchViewModel.activeBrowsedFolderSource = targetSource
                                searchViewModel.activeBrowsedFolderPath = targetPath
                                showPlaylistsDialog = true
                            } else if (isBackGoingToMobilePlayer) {
                                searchViewModel.isTracksListVisible = false
                                searchViewModel.tracksListOrigin = TracksListOrigin.HOME
                                isMobilePlayerExpanded = true
                            } else {
                                searchViewModel.closeArtistTracks()
                            }
                        }

                        // ==================================================
                        // 🎵 ЭКРАН Б: БЕСКОНЕЧНЫЙ СПИСОК ТРЕКОВ ИСПОЛНИТЕЛЯ
                        // ==================================================
                        ArtistTracksList(
                            tracks = searchViewModel.loadedTracks,
                            canLoadMore = searchViewModel.canLoadMore,
                            onLoadMore = { searchViewModel.loadNextPage() },
                            onHomeClick = if (isBackGoingToFolderBrowser || isBackGoingToMobilePlayer) {
                                { searchViewModel.closeArtistTracks() }
                            } else null,
                            onBackClick = {
                                if (isBackGoingToFolderBrowser) {
                                    val targetSource = searchViewModel.activeBrowsedFolderSource
                                    val targetPath = searchViewModel.activeBrowsedFolderPath
                                    searchViewModel.closeArtistTracks()
                                    searchViewModel.activeBrowsedFolderSource = targetSource
                                    searchViewModel.activeBrowsedFolderPath = targetPath
                                    showPlaylistsDialog = true
                                } else if (isBackGoingToMobilePlayer) {
                                    searchViewModel.isTracksListVisible = false
                                    searchViewModel.tracksListOrigin = TracksListOrigin.HOME
                                    isMobilePlayerExpanded = true
                                } else {
                                    searchViewModel.closeArtistTracks()
                                }
                            },
                            isTrackDownloading = searchViewModel.isTrackDownloading,
                            downloadingTrackId = searchViewModel.downloadingTrackId,
                            onDownloadTrack = { trackId, trackTitle, artistName ->
                                searchViewModel.downloadTrack(trackId, trackTitle, artistName)
                            },
                            onPlayTrack = { trackId, trackTitle, artistName ->
                                searchViewModel.playTrack(trackId, trackTitle, artistName)
                            },
                            playingTrackId = searchViewModel.playerTrackId,
                            isPlaying = searchViewModel.playerIsPlaying,
                            isPaused = searchViewModel.playerIsPaused,
                            playbackPositionMs = searchViewModel.playerPositionMs,
                            playbackDurationMs = searchViewModel.playerDurationMs,
                            onTogglePlayPause = { searchViewModel.togglePlayPause() },
                            onStopPlayback = { searchViewModel.stopPlayback() },
                            onSeek = { searchViewModel.seekPlayer(it) },
                            totalTracksCount = searchViewModel.totalTracksCount,
                            isLoadingAllPages = searchViewModel.isLoadingAllPages,
                            onLoadAllClick = { searchViewModel.loadAllRemainingPages() },
                            isTrackDownloaded = { artist, title -> searchViewModel.isTrackDownloaded(artist, title) },
                            title = searchViewModel.currentScreenTitle,
                            onRefresh = if (searchViewModel.isDownloadedTracksScreen) {
                                {
                                    val currentLocal = searchViewModel.localPlaylists.firstOrNull { it.title == searchViewModel.currentScreenTitle }
                                    if (currentLocal != null) {
                                        searchViewModel.openLocalPlaylist(currentLocal)
                                    } else if (searchViewModel.activeQueueFolderPath != null) {
                                        val session = searchViewModel.playbackSessionManager.currentSession
                                        val isRec = session?.isRecursive ?: true
                                        searchViewModel.openFolderPlaylist(
                                            folderPath = searchViewModel.activeQueueFolderPath!!,
                                            folderName = searchViewModel.activeQueueFolderName ?: searchViewModel.currentScreenTitle,
                                            source = searchViewModel.activeQueueSource,
                                            isRecursive = isRec
                                        )
                                    } else {
                                        searchViewModel.loadDownloadedTracksPlaylist()
                                    }
                                }
                            } else null,
                            onDeleteTrack = { trackId, trackTitle, artistName ->
                                trackToDelete = TrackDeleteConfirmInfo(trackId, trackTitle, artistName)
                            },
                            onAddToPlaylist = { track -> trackForPlaylistDialog = track },
                            onRemoveFromPlaylist = if (searchViewModel.isDownloadedTracksScreen) {
                                val currentLocal = searchViewModel.localPlaylists.firstOrNull { it.title == searchViewModel.currentScreenTitle }
                                if (currentLocal != null) {
                                    { track ->
                                        searchViewModel.removeTrackFromLocalPlaylist(currentLocal.id, track.realId ?: track.id)
                                    }
                                } else null
                            } else if (searchViewModel.currentOpenUserPlaylist != null) {
                                val currentYandex = searchViewModel.currentOpenUserPlaylist
                                if (currentYandex != null) {
                                    { track ->
                                        searchViewModel.removeTrackFromYandexPlaylist(currentYandex, track)
                                    }
                                } else null
                            } else null
                        )
                    }
                    } // closes outer Column
                }
            }
            // ==================================================
            // 📲 ПОЛНОЭКРАННЫЙ ПЛЕЕР ДЛЯ МОБИЛЬНЫХ (iOS / Android)
            // ==================================================
            AnimatedVisibility(
                visible = isMobileUi && isMobilePlayerExpanded,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutLinearInEasing)
                ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
            ) {
                MobileFullPlayerSheet(
                    state = playerBarState,
                    actions = playerBarActions,
                    onDismiss = { isMobilePlayerExpanded = false }
                )
            }

            // ==================================================
            // ⚙️ НАСТРОЙКИ (Оверлей поверх Scaffold и плеера)
            // ==================================================
            SettingsDialog(
                visible = settingsExpanded,
                state = SettingsState(
                    isRecordToDiskActive = searchViewModel.isRecordToDiskActive,
                    selectedQuality = searchViewModel.selectedQuality,
                    appTheme = searchViewModel.appTheme,
                    accentColor = searchViewModel.accentColor,
                    musicStoragePath = searchViewModel.musicStoragePath,
                    currentAccessToken = searchViewModel.currentAccessToken,
                    authStatusMessage = searchViewModel.authStatusMessage,
                    isFetchingYnison = searchViewModel.isFetchingYnison,
                    ynisonWaveSessionId = searchViewModel.ynisonWaveSessionId,
                    storageStatusMessage = searchViewModel.storageStatusMessage,
                    uiMode = searchViewModel.uiMode,
                    crossfadeSeconds = searchViewModel.playerCrossfadeSeconds,
                    isUiWatchdogActive = searchViewModel.isUiWatchdogActive,
                ),
                actions = SettingsActions(
                    onClose = { settingsExpanded = false },
                    onToggleRecordToDisk = { searchViewModel.toggleRecordToDisk(it) },
                    onSaveQuality = { searchViewModel.saveQuality(it) },
                    onSaveTheme = { searchViewModel.saveTheme(it) },
                    onSaveAccentColor = { searchViewModel.saveAccentColor(it) },
                    onSaveMusicPath = { searchViewModel.saveMusicPath(it) },
                    onSaveToken = { token, onComplete -> searchViewModel.saveNewToken(token, onComplete) },
                    onClearAuthStatus = { searchViewModel.authStatusMessage = null },
                    onFetchYnisonSession = { searchViewModel.fetchYnisonSession() },
                    onClearPlaylistsCache = { searchViewModel.clearPlaylistsCache() },
                    onClearAllDownloadedMusic = { searchViewModel.clearAllDownloadedMusic() },
                    onOpenEqualizer = { tab ->
                        settingsExpanded = false
                        equalizerInitialTab = tab
                        showEqualizerDialog = true
                    },
                    onOpenLogs = {
                        settingsExpanded = false
                        showLogsDialog = true
                    },
                    onSaveUiMode = { searchViewModel.saveUiMode(it) },
                    onSaveCrossfade = { searchViewModel.changeCrossfade(it) },
                    onToggleUiWatchdog = { searchViewModel.toggleUiWatchdog(it) }
                )
            )

            if (showEqualizerDialog) {
                EqualizerDialog(
                    initialTab = equalizerInitialTab,
                    onDismissRequest = { showEqualizerDialog = false }
                )
            }

            if (showPlaylistsDialog) {
                PlaylistsDialog(
                    viewModel = searchViewModel,
                    onDismiss = { showPlaylistsDialog = false },
                    onOpenSync = {
                        showPlaylistsDialog = false
                        showYamSyncDialog = true
                    }
                )
            }

            if (showYamSyncDialog) {
                io.github.audiz.ui.sync.YamSyncDialog(
                    syncManager = searchViewModel.yamSyncManager,
                    onDismiss = { showYamSyncDialog = false }
                )
            }

            if (showLogsDialog) {
                LogsDialog(
                    onDismiss = { showLogsDialog = false }
                )
            }

            trackForPlaylistDialog?.let { track ->
                AddToPlaylistDialog(
                    track = track,
                    viewModel = searchViewModel,
                    onDismiss = { trackForPlaylistDialog = null }
                )
            }

            // ==================================================
            // 🗑️ ПОДТВЕРЖДЕНИЕ УДАЛЕНИЯ ФАЙЛА С ДИСКА
            // ==================================================
            trackToDelete?.let { target ->
                val trackDesc = buildString {
                    append("«${target.title.ifBlank { "Без названия" }}»")
                    if (target.artist.isNotBlank()) {
                        append(" (${target.artist})")
                    }
                }
                AlertDialog(
                    onDismissRequest = { trackToDelete = null },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    title = {
                        Text(
                            text = "Удаление файла с диска",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = "Вы действительно хотите удалить аудиофайл $trackDesc из памяти устройства? Это действие нельзя отменить.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val toDelete = target
                                trackToDelete = null
                                searchViewModel.deleteTrack(
                                    trackId = toDelete.trackId,
                                    trackTitle = toDelete.title,
                                    artistName = toDelete.artist
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Удалить")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { trackToDelete = null }
                        ) {
                            Text("Отмена")
                        }
                    }
                )
            }
            } // closes outer Box
        }
    }
}

