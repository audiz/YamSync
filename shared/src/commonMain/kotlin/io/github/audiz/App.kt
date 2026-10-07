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
import io.github.audiz.ui.PlaylistsDialog
import io.github.audiz.ui.AddToPlaylistDialog
import io.github.audiz.ui.LogsDialog
import io.github.audiz.ui.PlayerBar
import io.github.audiz.ui.PlayerBarState
import io.github.audiz.ui.PlayerBarActions
import io.github.audiz.ui.SettingsDialog
import io.github.audiz.ui.SettingsState
import io.github.audiz.ui.SettingsActions
import io.github.audiz.ui.EqualizerDialog
import io.github.audiz.ui.MobileFullPlayerSheet
import io.github.audiz.dsp.EqualizerEngine

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
    
    val colors = if (isDarkTheme) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {


            // Навигация: если список загруженных треков не пуст — переключаемся на Экран Б
            val showTracksScreen = searchViewModel.loadedTracks.isNotEmpty()
            var settingsExpanded by remember { mutableStateOf(false) }
            var isMobilePlayerExpanded by remember { mutableStateOf(false) }
            var showEqualizerDialog by remember { mutableStateOf(false) }
            var equalizerInitialTab by remember { mutableStateOf(0) }
            var showPlaylistsDialog by remember { mutableStateOf(false) }
            var trackForPlaylistDialog by remember { mutableStateOf<FullTrackInfo?>(null) }
            var showLogsDialog by remember { mutableStateOf(false) }
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
                isRecordToDisk = searchViewModel.isCurrentTrackSavedToDisk,
                isSavingToDisk = searchViewModel.isCurrentTrackSavingToDisk,
                isSharing = searchViewModel.isSharingTrack,
                isFavorite = searchViewModel.isCurrentTrackLiked,
                isDisliked = searchViewModel.isCurrentTrackDisliked,
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
                isMobile = isMobileUi
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
                onToggleRecordToDisk = { searchViewModel.toggleCurrentTrackSaveOrDelete() },
                onShareTrack = { searchViewModel.shareCurrentTrack() },
                onAddToPlaylist = {
                    val track = searchViewModel.getCurrentPlayingTrackInfo()
                        ?: currentWave
                        ?: searchViewModel.plannedNextTrack
                    if (track != null) {
                        trackForPlaylistDialog = track
                    }
                },
                onToggleFavorite = { searchViewModel.toggleLikeCurrentTrack() },
                onToggleDislike = { searchViewModel.toggleDislikeCurrentTrack() },
                onOpenEqualizer = {
                    equalizerInitialTab = 0
                    showEqualizerDialog = true
                },
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

                            // ==================================================
                            // 📂 КНОПКИ ПЛЕЙЛИСТОВ (Скрываются при активном поиске)
                            // ==================================================
                            AnimatedVisibility(
                                visible = searchViewModel.searchResult == null && !searchViewModel.isLoading,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    Spacer(modifier = Modifier.height(12.dp))

                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilledTonalButton(
                                            onClick = { showPlaylistsDialog = true },
                                            enabled = !searchViewModel.isLoading,
                                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.LibraryMusic,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Медиатека")
                                        }
                                        Button(
                                            onClick = { searchViewModel.loadDownloadedTracksPlaylist() },
                                            enabled = !searchViewModel.isLoading,
                                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.DownloadDone,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Downloaded")
                                        }
                                        OutlinedButton(
                                            onClick = { searchViewModel.loadLikesPlaylist() },
                                            enabled = !searchViewModel.isLoading,
                                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Favorite,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("My Favorites")
                                        }
                                        OutlinedButton(
                                            onClick = { searchViewModel.loadHistory() },
                                            enabled = !searchViewModel.isLoading,
                                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.History,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("History")
                                        }
                                        OutlinedButton(
                                            onClick = { searchViewModel.togglePlayPauseWave() },
                                            enabled = !searchViewModel.isWaveLoading,
                                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Waves,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("My Wave")
                                        }

                                        // 🌊 Недавние тематические Волны (до 3 штук, сохраненных с прошлых сессий)
                                        searchViewModel.recentThematicWaves.forEach { wave ->
                                            val isCurrentThematicPlaying = searchViewModel.isWaveMode &&
                                                    searchViewModel.currentWaveSeeds == wave.seeds
                                            if (isCurrentThematicPlaying) {
                                                FilledTonalButton(
                                                    onClick = { searchViewModel.startThematicWave(wave.title, wave.seeds) },
                                                    enabled = !searchViewModel.isWaveLoading,
                                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Waves,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(wave.title)
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = { searchViewModel.startThematicWave(wave.title, wave.seeds) },
                                                    enabled = !searchViewModel.isWaveLoading,
                                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Waves,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(wave.title)
                                                }
                                            }
                                        }

                                        // 🌊 Постоянные пресеты тематических волн (не вытесняются, исключая дубликаты из недавних)
                                        searchViewModel.defaultThematicWaves.filterNot { defaultWave ->
                                            searchViewModel.recentThematicWaves.any { it.title == defaultWave.title || it.seeds == defaultWave.seeds }
                                        }.forEach { wave ->
                                            val isCurrentThematicPlaying = searchViewModel.isWaveMode &&
                                                    searchViewModel.currentWaveSeeds == wave.seeds
                                            if (isCurrentThematicPlaying) {
                                                FilledTonalButton(
                                                    onClick = { searchViewModel.startThematicWave(wave.title, wave.seeds) },
                                                    enabled = !searchViewModel.isWaveLoading,
                                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Waves,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(wave.title)
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = { searchViewModel.startThematicWave(wave.title, wave.seeds) },
                                                    enabled = !searchViewModel.isWaveLoading,
                                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Waves,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(wave.title)
                                                }
                                            }
                                        }

                                        // 🔄 Кнопка смены/обновления стилей
                                        if (searchViewModel.defaultThematicWaves.isNotEmpty()) {
                                            IconButton(
                                                onClick = { searchViewModel.refreshDefaultThematicWaves() },
                                                modifier = Modifier.size(40.dp).pointerHoverIcon(PointerIcon.Hand)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Refresh,
                                                    contentDescription = "Другие стили",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        // 🎵 Персональные плейлисты (Плейлист дня, Премьера, Дежавю и т.д.)
                                        searchViewModel.personalPlaylists.forEach { item ->
                                            val playlist = item.playlist ?: return@forEach
                                            val title = playlist.title.ifBlank {
                                                when (item.playlistType) {
                                                    "playlistOfTheDay" -> "Playlist of the Day"
                                                    "recentTracks" -> "Premiere"
                                                    "neverHeard" -> "Déjà Vu"
                                                    else -> "Personal Playlist"
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = { searchViewModel.loadPlaylistByUuid(playlist.playlistUuid, title) },
                                                enabled = !searchViewModel.isLoading,
                                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                            ) {
                                                Icon(
                                                    imageVector = when (item.playlistType) {
                                                        "playlistOfTheDay" -> Icons.Filled.AutoAwesome
                                                        "recentTracks" -> Icons.Filled.Refresh
                                                        else -> Icons.AutoMirrored.Filled.PlaylistPlay
                                                    },
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(title)
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Вывод лога ошибки на экран в случае сбоя сети
                            searchViewModel.errorMessage?.let { error ->
                                println(error)
                                val displayText = if (error.startsWith("⚠️")) error else "⚠️ Ошибка: $error"
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showLogsDialog = true }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = displayText,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "📋 Нажмите сюда, чтобы посмотреть и скопировать логи",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp
                                        )
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
                                        searchViewModel.deleteTrack(trackId, trackTitle, artistName)
                                    },
                                    onAddToPlaylist = { track -> trackForPlaylistDialog = track }
                                )
                            }
                        }
                    } else {
                        // ==================================================
                        // 🎵 ЭКРАН Б: БЕСКОНЕЧНЫЙ СПИСОК ТРЕКОВ ИСПОЛНИТЕЛЯ
                        // ==================================================
                        ArtistTracksList(
                            tracks = searchViewModel.loadedTracks,
                            canLoadMore = searchViewModel.canLoadMore,
                            onLoadMore = { searchViewModel.loadNextPage() },
                            onBackClick = { searchViewModel.closeArtistTracks() },
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
                                    } else {
                                        searchViewModel.loadDownloadedTracksPlaylist()
                                    }
                                }
                            } else null,
                            onDeleteTrack = { trackId, trackTitle, artistName ->
                                searchViewModel.deleteTrack(trackId, trackTitle, artistName)
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
                    musicStoragePath = searchViewModel.musicStoragePath,
                    currentAccessToken = searchViewModel.currentAccessToken,
                    authStatusMessage = searchViewModel.authStatusMessage,
                    isFetchingYnison = searchViewModel.isFetchingYnison,
                    ynisonWaveSessionId = searchViewModel.ynisonWaveSessionId,
                    storageStatusMessage = searchViewModel.storageStatusMessage,
                    uiMode = searchViewModel.uiMode,
                    crossfadeSeconds = searchViewModel.playerCrossfadeSeconds,
                ),
                actions = SettingsActions(
                    onClose = { settingsExpanded = false },
                    onToggleRecordToDisk = { searchViewModel.toggleRecordToDisk(it) },
                    onSaveQuality = { searchViewModel.saveQuality(it) },
                    onSaveTheme = { searchViewModel.saveTheme(it) },
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
                    onSaveCrossfade = { searchViewModel.changeCrossfade(it) }
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
                    onDismiss = { showPlaylistsDialog = false }
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
            } // closes outer Box
        }
    }
}

