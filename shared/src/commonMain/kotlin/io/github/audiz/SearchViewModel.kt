package io.github.audiz

import kotlin.concurrent.Volatile
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.audiz.api.MusicRepository
import io.github.audiz.models.YandexMusicResponse
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.FullArtistInfo
import io.github.audiz.models.FullAlbumInfo
import io.github.audiz.models.PlaylistInfo
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.ThematicWavePreset
import io.github.audiz.models.isLocalTrackId
import io.github.audiz.api.generatePlayUuid
import io.github.audiz.download.DownloadManager
import io.github.audiz.player.LocalTrackResolver
import io.github.audiz.player.PlaybackManager
import io.github.audiz.player.PlaybackQueueSource
import io.github.audiz.player.PlayerUiState
import io.github.audiz.playlist.PlaylistManager
import io.github.audiz.settings.SettingsManager
import io.github.audiz.wave.WaveManager
import io.github.audiz.local.LocalMediaManager
import io.github.audiz.models.CustomMediaSource
import io.github.audiz.models.FolderListing
import io.github.audiz.models.LocalSourceType
import io.github.audiz.models.LastPlaybackSession
import io.github.audiz.models.LastPlaybackType
import io.github.audiz.player.PlaybackSessionManager
import io.github.audiz.tracklist.TrackListCoordinator
import io.github.audiz.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

enum class TrackPlaySource {
    PLAY,
    NEXT,
    PREV
}

enum class TracksListOrigin {
    HOME,
    SEARCH,
    FOLDER_BROWSER,
    MOBILE_PLAYER
}

class SearchViewModel(private val repository: MusicRepository = MusicRepository()) : ViewModel() {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.e("SearchViewModel", "❌ Uncaught coroutine exception: ${throwable.message}")
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job {
        return viewModelScope.launch(Dispatchers.Main.immediate + coroutineExceptionHandler) {
            try {
                block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLogger.e("SearchViewModel", "❌ Error in safe coroutine: ${t.message}")
            }
        }
    }

    var searchQuery by mutableStateOf("")
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
    var searchResult by mutableStateOf<YandexMusicResponse?>(null)
        private set

    // 📊 Список уже загруженных детальных треков (делегирован в TrackListCoordinator)
    val loadedTracks: androidx.compose.runtime.snapshots.SnapshotStateList<FullTrackInfo>
        get() = trackListCoordinator.loadedTracks

    // 📁 Флаг экрана плейлиста загруженной музыки (локальные файлы на диске)
    var isDownloadedTracksScreen: Boolean
        get() = trackListCoordinator.isDownloadedTracksScreen
        set(value) { trackListCoordinator.isDownloadedTracksScreen = value }

    // 📋 Флаг видимости экрана списка треков / активной очереди (Экран Б)
    private var _isTracksListVisible = mutableStateOf(loadAppConfig(AppConfigKeys.TRACKS_LIST_VISIBLE) == "true")
    var isTracksListVisible: Boolean
        get() = _isTracksListVisible.value
        set(value) {
            _isTracksListVisible.value = value
            saveAppConfig(AppConfigKeys.TRACKS_LIST_VISIBLE, value.toString())
        }

    // 📁 Контекст текущего открытого проводника по локальным папкам (для возврата в ту же папку)
    var activeBrowsedFolderSource: CustomMediaSource?
        get() = trackListCoordinator.activeBrowsedFolderSource
        set(value) { trackListCoordinator.activeBrowsedFolderSource = value }
    var activeBrowsedFolderPath: String?
        get() = trackListCoordinator.activeBrowsedFolderPath
        set(value) { trackListCoordinator.activeBrowsedFolderPath = value }
    var tracksListOrigin: TracksListOrigin
        get() = trackListCoordinator.tracksListOrigin
        set(value) { trackListCoordinator.tracksListOrigin = value }

    fun resetBrowsedFolder() = trackListCoordinator.resetBrowsedFolder()

    // 🎵 Источник текущей активной очереди треков
    var activeQueueSource: CustomMediaSource?
        get() = trackListCoordinator.activeQueueSource
        set(value) { trackListCoordinator.activeQueueSource = value }
    var activeQueueFolderPath: String?
        get() = trackListCoordinator.activeQueueFolderPath
        set(value) { trackListCoordinator.activeQueueFolderPath = value }
    var activeQueueFolderName: String?
        get() = trackListCoordinator.activeQueueFolderName
        set(value) { trackListCoordinator.activeQueueFolderName = value }

    // 🎵 Персональные плейлисты (Плейлист дня, Премьера, Дежавю и т.д.)
    val personalPlaylists: androidx.compose.runtime.snapshots.SnapshotStateList<io.github.audiz.models.PersonalPlaylistItemData>
        get() = playlistManager.personalPlaylists
    val isPersonalPlaylistsLoading: Boolean
        get() = playlistManager.isPersonalPlaylistsLoading

    // 📁 Персональные локальные оффлайн-плейлисты (на диске)
    val localPlaylists: androidx.compose.runtime.snapshots.SnapshotStateList<LocalPlaylist>
        get() = playlistManager.localPlaylists
    val isLocalPlaylistsLoading: Boolean
        get() = playlistManager.isLocalPlaylistsLoading

    // ☁️ Пользовательские плейлисты из Яндекс Музыки
    val userPlaylists: androidx.compose.runtime.snapshots.SnapshotStateList<PlaylistInfo>
        get() = playlistManager.userPlaylists
    val isUserPlaylistsLoading: Boolean
        get() = playlistManager.isUserPlaylistsLoading
    var currentOpenUserPlaylist: PlaylistInfo?
        get() = playlistManager.currentOpenUserPlaylist
        set(value) { playlistManager.currentOpenUserPlaylist = value }

    // ☁️ Кеш идентификаторов треков пользовательских плейлистов Яндекс (kind -> Set<trackId>)
    val userPlaylistsTrackIds: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Set<String>>
        get() = playlistManager.userPlaylistsTrackIds

    var currentScreenTitle: String
        get() = trackListCoordinator.currentScreenTitle
        set(value) { trackListCoordinator.currentScreenTitle = value }

    // 🔀 Режим перемешивания (Shuffle) — делегируется в PlaybackManager
    val isShuffleEnabled: Boolean
        get() = playbackManager.isShuffleEnabled

    fun toggleShuffle() {
        playbackManager.toggleShuffle()
    }

    // 📜 Список всех ID треков, которые нужно загрузить частями
    val allTrackIds: List<String>
        get() = trackListCoordinator.allTrackIds
    val totalTracksCount: Int
        get() = trackListCoordinator.totalTracksCount

    val isLoadingAllPages: Boolean
        get() = trackListCoordinator.isLoadingAllPages

    val canLoadMore: Boolean
        get() = trackListCoordinator.canLoadMore

    // 🔥 СОСТОЯНИЯ ДЛЯ СКАЧИВАНИЯ ТРЕКОВ (Делегированы в DownloadManager):
    val downloadingTrackId: String? get() = downloadManager.downloadingTrackId
    val isTrackDownloading: Boolean get() = downloadManager.isTrackDownloading
    val downloadVersion: Int get() = downloadManager.downloadVersion

    // 🔧 Текущий OAuth access_token (виден из GUI для редактирования)
    val currentAccessToken: String
        get() = settingsManager.currentAccessToken

    var currentSessionCookie: String
        get() = settingsManager.currentSessionCookie
        set(value) { settingsManager.currentSessionCookie = value }

    // 🌊 Свойства Моей Волны (Делегированы в WaveManager):
    val ynisonWaveSessionId: String? get() = waveManager.ynisonWaveSessionId
    val isFetchingYnison: Boolean get() = waveManager.isFetchingYnison
    val ynisonHost: String? get() = waveManager.ynisonHost
    val ynisonError: String? get() = waveManager.ynisonError
    val waveRadioSessionId: String? get() = waveManager.waveRadioSessionId
    val waveBatchId: String? get() = waveManager.waveBatchId
    val waveTracks: androidx.compose.runtime.snapshots.SnapshotStateList<FullTrackInfo> get() = waveManager.waveTracks
    val waveCurrentIndex: Int get() = waveManager.waveCurrentIndex
    val isWaveMode: Boolean get() = waveManager.isWaveMode
    val isWaveLoading: Boolean get() = waveManager.isWaveLoading
    val activeWaveTrack: FullTrackInfo? get() = waveManager.activeWaveTrack
    val currentWaveTrack: FullTrackInfo? get() = waveManager.currentWaveTrack
    val wavePastTracks: androidx.compose.runtime.snapshots.SnapshotStateList<FullTrackInfo> get() = waveManager.wavePastTracks
    val waveFutureTracks: androidx.compose.runtime.snapshots.SnapshotStateList<FullTrackInfo> get() = waveManager.waveFutureTracks
    val currentWaveTitle: String? get() = waveManager.currentWaveTitle
    val currentWaveSeeds: List<String> get() = waveManager.currentWaveSeeds
    val recentThematicWaves: List<ThematicWavePreset> get() = waveManager.recentThematicWaves
    val defaultThematicWaves: List<ThematicWavePreset> get() = waveManager.defaultThematicWaves

    // 🔑 Сервис авторизации:
    val cookieService: YandexCookieService get() = settingsManager.cookieService
    val isExchangingToken: Boolean get() = settingsManager.isExchangingToken
    var authStatusMessage: String?
        get() = settingsManager.authStatusMessage
        set(value) { settingsManager.authStatusMessage = value }
    var storageStatusMessage: String?
        get() = settingsManager.storageStatusMessage
        set(value) { settingsManager.storageStatusMessage = value }

    // 👑 Статус подписки Яндекс Плюс:
    val hasPlusSubscription: Boolean? get() = settingsManager.hasPlusSubscription
    fun refreshPlusSubscription() {
        launchSafe { settingsManager.checkPlusSubscription() }
    }

    // 🎚️ Качество скачивания ("1" = low, "2" = high)
    val selectedQuality: String
        get() = settingsManager.selectedQuality

    // 🎨 Тема (Dark, Light, System)
    val appTheme: String
        get() = settingsManager.appTheme

    // 🎨 Акцентный цвет интерфейса
    val accentColor: String
        get() = settingsManager.accentColor

    fun saveQuality(quality: String) {
        settingsManager.saveQuality(quality)
    }

    fun saveTheme(theme: String) {
        settingsManager.saveTheme(theme)
    }

    fun saveAccentColor(color: String) {
        settingsManager.saveAccentColor(color)
    }

    // 📱 Режим интерфейса (Auto, Mobile, Desktop)
    val uiMode: String
        get() = settingsManager.uiMode

    fun saveUiMode(mode: String) {
        settingsManager.saveUiMode(mode)
    }

    val isMobileUi: Boolean
        get() = settingsManager.isMobileUi

    // 💾 Сохранять треки на диск при воспроизведении (Record to disk):
    val isRecordToDiskActive: Boolean get() = downloadManager.isRecordToDiskActive
    fun toggleRecordToDisk(active: Boolean = !isRecordToDiskActive) = downloadManager.toggleRecordToDisk(active)

    // 🐕 Детектор зависаний UI (Watchdog)
    val isUiWatchdogActive: Boolean get() = settingsManager.isUiWatchdogEnabled
    fun toggleUiWatchdog(active: Boolean = !isUiWatchdogActive) = settingsManager.toggleUiWatchdog(active)

    /** Проверяет, сохранен ли текущий воспроизводимый трек в библиотеке на диске */
    val isCurrentTrackSavedToDisk: Boolean get() = downloadManager.isCurrentTrackSavedToDisk

    /** Проверяет, идет ли скачивание текущего трека в библиотеку прямо сейчас */
    val isCurrentTrackSavingToDisk: Boolean get() = downloadManager.isCurrentTrackSavingToDisk

    /** Проверяет, является ли текущий воспроизводимый трек локальным файлом или источником */
    val isCurrentTrackLocal: Boolean
        get() {
            val id = playerTrackId ?: currentWaveTrack?.id ?: return false
            return isLocalTrackId(id)
        }

    /** Скачать текущий трек на диск или удалить его из библиотеки, если уже скачан */
    fun toggleCurrentTrackSaveOrDelete() {
        if (isCurrentTrackLocal) return
        downloadManager.toggleCurrentTrackSaveOrDelete()
    }

    // 📤 Поделиться треком:
    var isSharingTrack by mutableStateOf(false)
        private set

    /** Поделиться текущим воспроизводимым треком через мессенджер (без скачивания на диск) */
    fun shareCurrentTrack() {
        val trackId = playerTrackId ?: currentWaveTrack?.id ?: return
        val title = playerTrackTitle.ifBlank { currentWaveTrack?.title ?: "" }
        val artist = playerArtistName.ifBlank { currentWaveTrack?.artists?.joinToString { it.name } ?: "" }
        val albumId = playerAlbumId ?: currentWaveTrack?.albums?.firstOrNull()?.id

        // Если файл уже скачан или играет с диска — передаем его путь.
        // Иначе трек НЕ скачиваем на диск, передаем пустую строку (отправляется текстовая ссылка).
        val existingFilePath = currentPlayingFilePath?.takeIf { localFileExists(it) }
            ?: downloadManager.getDownloadedTrackPath(artist, title)

        try {
            shareTrackFile(
                filePath = existingFilePath ?: "",
                title = title,
                artist = artist,
                trackId = trackId,
                albumId = albumId
            )
            storageStatusMessage = "📋 Ссылка на трек скопирована в буфер обмена"
        } catch (e: Exception) {
            errorMessage = "Ошибка при отправке трека: ${e.message}"
        }
    }

    // 📂 Путь хранения музыки (настраиваемый)
    val musicStoragePath: String
        get() = settingsManager.musicStoragePath

    // ❤️ Список ID лайкнутых треков ("Мне нравится")
    val likedTrackIds: androidx.compose.runtime.snapshots.SnapshotStateSet<String> get() = playlistManager.likedTrackIds

    // 💔 Список ID треков в дизлайках
    val dislikedTrackIds: androidx.compose.runtime.snapshots.SnapshotStateSet<String> get() = playlistManager.dislikedTrackIds

    /** Проверяет, лайкнут ли текущий воспроизводимый трек */
    val isCurrentTrackLiked: Boolean
        get() {
            val id = playerTrackId ?: currentWaveTrack?.id
            return playlistManager.isTrackLiked(id)
        }

    /** Проверяет, дизлайкнут ли текущий воспроизводимый трек */
    val isCurrentTrackDisliked: Boolean
        get() {
            val id = playerTrackId ?: currentWaveTrack?.id
            return playlistManager.isTrackDisliked(id)
        }

    /** 🎵 Проверяет, содержится ли текущий воспроизводимый трек хотя бы в одном плейлисте (локальном или облачном) */
    val isCurrentTrackInPlaylist: Boolean
        get() {
            val track = getCurrentPlayingTrackInfo() ?: return false
            return isTrackInAnyPlaylist(track)
        }

    /** 🔢 Количество плейлистов, в которых содержится текущий воспроизводимый трек */
    val currentTrackPlaylistsCount: Int
        get() {
            val track = getCurrentPlayingTrackInfo() ?: return 0
            return getTrackPlaylistsCount(track)
        }

    /** 🔄 Версия состояния плейлистов (для реактивного обновления UI при добавлении/удалении треков) */
    val playlistsVersion: Long get() = playlistManager.playlistsVersion

    /** Переключить лайк для текущего воспроизводимого трека */
    fun toggleLikeCurrentTrack() {
        if (isCurrentTrackLocal) return
        val trackId = playerTrackId ?: currentWaveTrack?.id ?: return
        val albumId = playerAlbumId ?: currentWaveTrack?.albums?.firstOrNull()?.id
        playlistManager.toggleLike(trackId, albumId)
        val isLikedNow = playlistManager.isTrackLiked(trackId)
        playbackManager.systemMediaControls.updateLikedState(isLikedNow)
    }

    /** Всегда поставить дизлайк текущему треку и переключить на следующий */
    fun dislikeCurrentTrackAndSkip() {
        if (isCurrentTrackLocal) {
            playNextTrack()
            return
        }
        val trackId = playerTrackId ?: currentWaveTrack?.id ?: run {
            playNextTrack()
            return
        }
        val albumId = playerAlbumId ?: currentWaveTrack?.albums?.firstOrNull()?.id
        playlistManager.dislikeTrack(trackId, albumId) {
            playNextTrack()
        }
        playbackManager.systemMediaControls.updateLikedState(false)
    }

    /** Переключить дизлайк для текущего воспроизводимого трека */
    fun toggleDislikeCurrentTrack() {
        if (isCurrentTrackLocal) return
        val trackId = playerTrackId ?: currentWaveTrack?.id ?: return
        val albumId = playerAlbumId ?: currentWaveTrack?.albums?.firstOrNull()?.id
        playlistManager.toggleDislike(trackId, albumId) {
            playNextTrack()
        }
        val isLikedNow = playlistManager.isTrackLiked(trackId)
        playbackManager.systemMediaControls.updateLikedState(isLikedNow)
    }

    /** Загрузить и обновить список всех лайкнутых треков пользователя в фоне */
    fun syncLikedTrackIds() {
        playlistManager.syncLikedTrackIds()
    }

    // ⚙️ МЕНЕДЖЕР НАСТРОЕК И ТОКЕНОВ:
    val settingsManager: SettingsManager = SettingsManager(
        repository = repository,
        onTokenUpdated = { _ ->
            playlistManager.syncLikedTrackIds()
            playlistManager.loadPersonalPlaylists()
            playlistManager.loadUserPlaylists()
            loadInitialWave(autoPlay = false)
            refreshPlusSubscription()
        },
        onStoragePathUpdated = { _ ->
            playlistManager.loadPersonalPlaylists()
            playlistManager.loadUserPlaylists()
            playlistManager.loadLocalPlaylistsFromDisk()
        }
    )

    // 🌊 МЕНЕДЖЕР МОЕЙ ВОЛНЫ:
    val waveManager = WaveManager(
        scope = viewModelScope,
        repository = repository,
        getAccessToken = { settingsManager.currentAccessToken },
        onTrackLiked = { trackId -> playlistManager.likedTrackIds.add(trackId.removePrefix("local:").substringBefore(":")) },
        onError = { errorMessage = it }
    )

    // 📂 МЕНЕДЖЕР ПОЛЬЗОВАТЕЛЬСКИХ МЕДИА (Папки, файлы, M3U):
    val localMediaManager = LocalMediaManager()

    val customMediaSources: androidx.compose.runtime.snapshots.SnapshotStateList<CustomMediaSource>
        get() = localMediaManager.customSources

    val localMediaStatusMessage: String?
        get() = localMediaManager.statusMessage

    // 🔍 Резолвер локальных файлов:
    val localTrackResolver = LocalTrackResolver { settingsManager.musicStoragePath }

    // 💾 МЕНЕДЖЕР СКАЧИВАНИЯ И ФАЙЛОВ:
    val downloadManager = DownloadManager(
        scope = viewModelScope,
        repository = repository,
        localTrackResolver = localTrackResolver,
        getSelectedQuality = { settingsManager.selectedQuality },
        getPlayingTrackId = { playerTrackId },
        getPlayingTrackTitle = { playerTrackTitle },
        getPlayingArtistName = { playerArtistName },
        getPlayingFilePath = { currentPlayingFilePath },
        onUpdatePlayingFilePath = { playbackManager.updateCurrentPlayingFilePath(it) },
        onStopPlayback = { playbackManager.stopPlayback() },
        onTrackDeleted = { trackId, wasPlaying ->
            val cleanId = trackId.removePrefix("local:")
            val removedIndex = loadedTracks.indexOfFirst {
                it.id == trackId || it.realId == trackId ||
                it.id.removePrefix("local:") == cleanId ||
                it.realId?.removePrefix("local:") == cleanId
            }

            // Безусловно удаляем трек из текущего списка loadedTracks и allTrackIds
            trackListCoordinator.removeTrack(trackId)

            // Очищаем трек из локальных плейлистов
            playlistManager.removeTrackFileFromAllPlaylists(cleanId)

            // Если удаленный трек играл прямо сейчас и в списке еще остались треки —
            // плавно переключаемся на следующий трек в очереди
            if (wasPlaying) {
                if (loadedTracks.isNotEmpty()) {
                    val nextIndex = if (removedIndex != -1) removedIndex.coerceIn(0, loadedTracks.size - 1) else 0
                    val nextTrack = loadedTracks[nextIndex]
                    playTrack(
                        trackId = nextTrack.id,
                        trackTitle = nextTrack.title,
                        artistName = nextTrack.artists.joinToString { it.name },
                        albumId = nextTrack.albums.firstOrNull()?.id,
                        isManualSelection = false
                    )
                } else if (isWaveMode) {
                    playNextTrack()
                } else {
                    stopPlayback()
                }
            }
        },
        onAllTracksCleared = {
            trackListCoordinator.clearAll()
            stopPlayback()
        },
        onError = { errorMessage = it },
        onStatusMessage = { storageStatusMessage = it },
        findTrackInfo = { playbackManager.findTrackInfo(it) },
        getPlayingTrackCoverUrl = { playerCoverUri }
    )

    // 💾 МЕНЕДЖЕР СЕССИИ ВОСПРОИЗВЕДЕНИЯ (Восстановление плейлиста или Волны при рестарте)
    val playbackSessionManager = PlaybackSessionManager()

    // 🎵 АУДИОПЛЕЕР (Делегирован в PlaybackManager):
    val playbackManager: PlaybackManager = PlaybackManager(
        scope = viewModelScope,
        repository = repository,
        localTrackResolver = localTrackResolver,
        queueSource = object : PlaybackQueueSource {
            override fun getLoadedTracks(): List<FullTrackInfo> = trackListCoordinator.loadedTracks
            override fun getSearchTracks(): List<FullTrackInfo> =
                searchResult?.result?.results?.filter { it.type == "track" }?.mapNotNull { it.track?.toFullTrackInfo() } ?: emptyList()
            override suspend fun loadMoreTracks(): Boolean = trackListCoordinator.loadNextPageChunk()
        },
        waveBridge = waveManager,
        getSelectedQuality = { settingsManager.selectedQuality },
        isRecordToDisk = { downloadManager.isRecordToDiskActive },
        onTrackSavedToDisk = { downloadManager.incrementDownloadVersion() },
        onTrackStarted = { trackId, title, artist, cover, isWave ->
            if (isWave) {
                playbackSessionManager.recordWave(waveManager.currentWaveTitle, waveManager.currentWaveSeeds)
            }
            playbackSessionManager.updateLastTrack(
                trackId = trackId,
                trackTitle = title,
                artistName = artist,
                coverUri = cover
            )
        },
        onError = { errorMessage = it }
    )

    // 📂 МЕНЕДЖЕР ПЛЕЙЛИСТОВ:
    val playlistManager: PlaylistManager = PlaylistManager(
        scope = viewModelScope,
        repository = repository,
        getMusicStoragePath = { settingsManager.musicStoragePath },
        getAccessToken = { settingsManager.currentAccessToken },
        downloadManager = downloadManager,
        onStatusMessage = { storageStatusMessage = it },
        onError = { errorMessage = it }
    )

    // 📋 КООРДИНАТОР СПИСКА ТРЕКОВ, ОЧЕРЕДЕЙ И ПАГИНАЦИИ:
    val trackListCoordinator: TrackListCoordinator = TrackListCoordinator(
        scope = viewModelScope,
        repository = repository,
        playbackManager = playbackManager,
        playbackSessionManager = playbackSessionManager,
        playlistManager = playlistManager,
        localMediaManager = localMediaManager,
        getMusicStoragePath = { settingsManager.musicStoragePath },
        getSearchResult = { searchResult },
        onPlayTrack = { trackId, trackTitle, artistName, albumId, isManual, isWave, source ->
            playTrack(trackId, trackTitle, artistName, albumId, isManual, isWave, source)
        },
        onSetTracksListVisible = { isTracksListVisible = it },
        onLoadingChanged = { isLoading = it },
        onError = { errorMessage = it }
    )

    // ⚡ МЕНЕДЖЕР P2P СИНХРОНИЗАЦИИ YAMSYNC:
    val yamSyncManager: io.github.audiz.sync.YamSyncManager = io.github.audiz.sync.YamSyncManager(
        scope = viewModelScope,
        getMusicStoragePath = { settingsManager.musicStoragePath },
        onPlaylistsUpdated = { playlistManager.loadLocalPlaylistsFromDisk() }
    ).apply {
        playlistManager.onLocalPlaylistsChanged = {
            invalidateLocalManifestAndRefresh()
        }
    }

    val audioPlayer: AudioPlayer get() = playbackManager.audioPlayer
    val systemMediaControls: SystemMediaControls get() = playbackManager.systemMediaControls

    val playerVolume: Float get() = playbackManager.volume
    fun changeVolume(volume: Float) = playbackManager.changeVolume(volume)

    val playerCrossfadeSeconds: Int get() = playbackManager.crossfadeSeconds
    fun changeCrossfade(seconds: Int) = playbackManager.changeCrossfade(seconds)

    val playerTrackId: String? get() = playbackManager.trackId
    val playerAlbumId: Long? get() = playbackManager.albumId
    val playerIsPlaying: Boolean get() = playbackManager.isPlaying
    val playerIsPaused: Boolean get() = playbackManager.isPaused
    val playerTrackTitle: String get() = playbackManager.trackTitle
    val playerArtistName: String get() = playbackManager.artistName
    val playerDurationMs: Long get() = playbackManager.durationMs
    val playerPositionMs: Long get() = playbackManager.positionMs
    val playerBitrate: Int? get() = playbackManager.bitrate
    val currentPlayingFilePath: String? get() = playbackManager.currentPlayingFilePath
    val isCurrentTrackHQ: Boolean get() = playbackManager.isCurrentTrackHQ
    val playerCoverUri: String?
        get() {
            val id = playerTrackId
            val isLocal = isLocalTrackId(id)
            if (isLocal) {
                return playbackManager.coverUri ?: loadedTracks.firstOrNull { it.id == id }?.coverUri
            }
            return playbackManager.coverUri
                ?: getCurrentPlayingTrackInfo()?.coverUri
                ?: if (isWaveMode) currentWaveTrack?.coverUri else null
        }

    val isNextTrackLoading: Boolean get() = playbackManager.isNextLoading
    val isPrevTrackLoading: Boolean get() = playbackManager.isPrevLoading
    val isPlayTrackLoading: Boolean get() = playbackManager.isPlayLoading
    val plannedNextTrack: FullTrackInfo? get() = playbackManager.plannedNextTrack

    /**
     * 🎵 Получить FullTrackInfo текущего воспроизводимого трека
     */
    fun getCurrentPlayingTrackInfo(): FullTrackInfo? {
        val id = playerTrackId
        if (id != null) {
            val loaded = loadedTracks.firstOrNull { it.id == id || it.realId == id }
            if (loaded != null) return loaded
            if (currentWaveTrack?.id == id) return currentWaveTrack
            val cleanArtist = playerArtistName.ifBlank { "Unknown Artist" }
            val cleanTitle = playerTrackTitle.ifBlank { "Unknown Track" }
            val filePath = currentPlayingFilePath
            val isLocal = isLocalTrackId(id)
            return FullTrackInfo(
                id = id,
                realId = if (isLocal) (filePath ?: id.removePrefix("local:")) else null,
                title = cleanTitle,
                available = true,
                durationMs = playerDurationMs,
                artists = listOf(FullArtistInfo(id = 0L, name = cleanArtist)),
                albums = listOf(FullAlbumInfo(id = playerAlbumId ?: 0L, title = ""))
            )
        }
        if (isWaveMode) {
            return currentWaveTrack ?: plannedNextTrack
        }
        return plannedNextTrack
    }

    val playerUiState: PlayerUiState
        get() = PlayerUiState(
            trackId = playerTrackId,
            albumId = playerAlbumId,
            title = playerTrackTitle,
            artist = playerArtistName,
            coverUri = playerCoverUri,
            isPlaying = playerIsPlaying,
            isPaused = playerIsPaused,
            durationMs = playerDurationMs,
            positionMs = playerPositionMs,
            volume = playerVolume,
            crossfadeSeconds = playerCrossfadeSeconds,
            bitrate = playerBitrate,
            isHQ = isCurrentTrackHQ,
            isShuffle = isShuffleEnabled,
            isNextLoading = isNextTrackLoading,
            isPrevLoading = isPrevTrackLoading,
            isPlayLoading = isPlayTrackLoading,
            isLiked = isCurrentTrackLiked,
            isDisliked = isCurrentTrackDisliked,
            isSavedToDisk = isCurrentTrackSavedToDisk,
            isSavingToDisk = isCurrentTrackSavingToDisk,
            isWaveMode = isWaveMode,
            currentWaveTitle = currentWaveTitle
        )

    init {
        io.github.audiz.ui.CoverImageLoader.repository = repository
        waveManager.attachPlayback(playbackManager)
        playbackManager.isTrackLiked = { id ->
            if (id != null) playlistManager.isTrackLiked(id) else false
        }
        playbackManager.systemMediaControls.setCustomActions(
            onToggleLike = { toggleLikeCurrentTrack() },
            onDislike = { dislikeCurrentTrackAndSkip() }
        )

        // 🌊 При старте запрашиваем сессию Моей волны, персональные плейлисты и список лайков
        loadLocalPlaylistsFromDisk()
        if (currentAccessToken.isNotBlank()) {
            syncLikedTrackIds()
            loadPersonalPlaylists()
            loadUserPlaylists()
            launchSafe { settingsManager.checkPlusSubscription() }
        } else {
            loadPersonalPlaylists()
            loadUserPlaylists()
        }
        restoreLastPlaybackSession()
    }

    /**
     * 🔄 Восстановить последнюю сессию воспроизведения (плейлист или Мою волну)
     */
    private fun restoreLastPlaybackSession() {
        try {
            val session = playbackSessionManager.loadSession()
            val wasTracksListVisible = loadAppConfig(AppConfigKeys.TRACKS_LIST_VISIBLE) == "true"
            val savedWaveStyle = waveManager.loadSavedWaveStyle()

            if (session == null || session.type == LastPlaybackType.WAVE) {
                isTracksListVisible = false
                if (currentAccessToken.isNotBlank()) {
                    val seeds = session?.waveSeeds?.takeIf { it.isNotEmpty() } ?: savedWaveStyle?.seeds ?: emptyList()
                    val title = session?.waveTitle ?: savedWaveStyle?.title
                    if (seeds.isNotEmpty()) {
                        startThematicWave(title, seeds, autoPlay = false)
                    } else {
                        loadInitialWave(autoPlay = false)
                    }
                }
                return
            }

            // Если перед закрытием играл плейлист/диск, но у пользователя сохранен стиль Волны,
            // фоном инициализируем этот стиль (без автоплея), чтобы на Главном экране чип и карточка Волны
            // показывали выбранный стиль, а не сбрасывались на «Главное»
            if (currentAccessToken.isNotBlank() && savedWaveStyle != null && savedWaveStyle.seeds.isNotEmpty()) {
                startThematicWave(savedWaveStyle.title, savedWaveStyle.seeds, autoPlay = false)
            }

            restorePlaylistSession(session, wasTracksListVisible)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            println("SearchViewModel: ⚠️ Ошибка восстановления последней сессии: ${e.message}")
            isTracksListVisible = false
        }
    }

    fun resumeLastSession(session: LastPlaybackSession) {
        if (session.type == LastPlaybackType.WAVE) {
            if (waveManager.waveTracks.isNotEmpty()) {
                playWaveTrack(waveManager.waveCurrentIndex)
            } else if (session.waveSeeds.isNotEmpty()) {
                startThematicWave(session.waveTitle, session.waveSeeds, autoPlay = true)
            } else {
                loadInitialWave(autoPlay = true)
            }
        } else {
            trackListCoordinator.restorePlaylistSession(session, wasTracksListVisible = true)
        }
    }

    private fun restorePlaylistSession(session: LastPlaybackSession, wasTracksListVisible: Boolean = true) {
        if (session.type == LastPlaybackType.WAVE) {
            resumeLastSession(session)
        } else {
            trackListCoordinator.restorePlaylistSession(session, wasTracksListVisible)
        }
    }

    fun fetchYnisonSession() {
        waveManager.loadInitialWave(autoPlay = false)
    }

    fun loadInitialWave(autoPlay: Boolean = false, source: TrackPlaySource = TrackPlaySource.PLAY) {
        waveManager.loadInitialWave(autoPlay, source)
    }

    fun playWaveTrack(index: Int = waveCurrentIndex, source: TrackPlaySource = TrackPlaySource.PLAY) {
        playbackSessionManager.recordWave(waveManager.currentWaveTitle, waveManager.currentWaveSeeds)
        waveManager.playWaveTrack(index, source)
    }

    fun playPrevWaveTrack() {
        playbackSessionManager.recordWave(waveManager.currentWaveTitle, waveManager.currentWaveSeeds)
        waveManager.playPrevWaveTrack()
    }

    fun playNextWaveTrack() {
        playbackSessionManager.recordWave(waveManager.currentWaveTitle, waveManager.currentWaveSeeds)
        waveManager.playNextWaveTrack()
    }

    fun togglePlayPauseWave() {
        waveManager.togglePlayPauseWave()
    }

    fun skipWaveTrack() {
        waveManager.skipWaveTrack()
    }

    fun startThematicWave(title: String?, seeds: List<String>, autoPlay: Boolean = true) {
        playbackSessionManager.recordWave(title, seeds)
        waveManager.startThematicWave(title, seeds, autoPlay = autoPlay)
    }

    fun refreshDefaultThematicWaves() {
        waveManager.refreshDefaultThematicWaves()
    }

    fun resetToDefaultWave() {
        playbackSessionManager.recordWave(null, emptyList())
        waveManager.resetToDefaultWave()
    }

    fun saveNewToken(tokenOrUrl: String, onComplete: ((Boolean) -> Unit)? = null) {
        settingsManager.saveNewToken(tokenOrUrl, onComplete)
    }

    fun saveNewSession(tokenOrCookie: String) {
        settingsManager.saveNewSession(tokenOrCookie)
    }

    fun exchangeTokenForSession(tokenOrUrl: String, onComplete: ((Boolean) -> Unit)? = null) {
        settingsManager.exchangeTokenForSession(tokenOrUrl, onComplete)
    }

    fun saveMusicPath(newPath: String) {
        settingsManager.saveMusicPath(newPath)
    }

    fun performSearch() {
        val query = searchQuery.trim()
        if (query.isBlank() || isLoading) return

        // 🔗 Если в поиск вставлена ссылка или ID трека из YamSync / Яндекс Музыки
        val linkTrackId = DeepLinkHandler.extractTrackId(query)
        if (linkTrackId != null) {
            playTrackByExternalId(linkTrackId)
            return
        }

        if (currentAccessToken.isBlank()) {
            errorMessage = "⚠️ Требуется авторизация. Пожалуйста, откройте настройки (шестерёнка вверху) и сохраните OAuth токен Яндекс Музыки."
            return
        }

        isDownloadedTracksScreen = false
        launchSafe {
            isLoading = true
            errorMessage = null
            resetPagination()
            try {
                searchResult = withContext(DispatcherIO) {
                    repository.searchInstant(query)
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
                AppLogger.e("SearchViewModel", "Ошибка поиска: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 🔍 Сброс поискового запроса и скрытие результатов поиска
     */
    fun clearSearch() {
        searchQuery = ""
        searchResult = null
        errorMessage = null
        currentOpenUserPlaylist = null
    }

    fun loadDownloadedTracksPlaylist(restoreTrackId: String? = null) =
        trackListCoordinator.loadDownloadedTracksPlaylist(restoreTrackId)

    fun loadArtistTracks(artistId: String, artistName: String? = null, restoreTrackId: String? = null) =
        trackListCoordinator.loadArtistTracks(artistId, artistName, restoreTrackId)

    fun loadPlaylist(playlist: PlaylistInfo, restoreTrackId: String? = null) =
        trackListCoordinator.loadPlaylist(playlist, restoreTrackId)

    fun loadAlbumTracks(albumId: Long, albumTitle: String, restoreTrackId: String? = null) =
        trackListCoordinator.loadAlbumTracks(albumId, albumTitle, restoreTrackId)

    fun loadPlaylistTracks(uid: Long, kind: Long, playlistTitle: String? = null, restoreTrackId: String? = null) =
        trackListCoordinator.loadPlaylistTracks(uid, kind, playlistTitle, restoreTrackId)

    fun loadPlaylistByUuid(uuid: String, playlistTitle: String? = null, restoreTrackId: String? = null) =
        trackListCoordinator.loadPlaylistByUuid(uuid, playlistTitle, restoreTrackId)

    fun loadLikesPlaylist(restoreTrackId: String? = null) =
        trackListCoordinator.loadLikesPlaylist(restoreTrackId)

    fun loadHistory(restoreTrackId: String? = null) =
        trackListCoordinator.loadHistory(restoreTrackId)

    /**
     * 🎵 Загрузка персональных плейлистов (Плейлист дня, Премьера, Дежавю и др.)
     */
    fun loadPersonalPlaylists() {
        playlistManager.loadPersonalPlaylists()
    }

    /**
     * ☁️ Загрузка пользовательских плейлистов из Яндекс Музыки
     */
    fun loadUserPlaylists() {
        playlistManager.loadUserPlaylists()
    }

    /**
     * 💾 Загрузка локальных плейлистов с диска
     */
    fun loadLocalPlaylistsFromDisk() {
        playlistManager.loadLocalPlaylistsFromDisk()
    }

    /**
     * ➕ Создать новый локальный плейлист
     */
    fun createLocalPlaylist(title: String, description: String = ""): LocalPlaylist {
        return playlistManager.createLocalPlaylist(title, description)
    }

    /**
     * ✏️ Переименовать локальный плейлист
     */
    fun renameLocalPlaylist(playlistId: String, newTitle: String) {
        playlistManager.renameLocalPlaylist(playlistId, newTitle)
    }

    /**
     * 🗑️ Удалить локальный плейлист
     */
    fun deleteLocalPlaylist(playlistId: String, deleteDownloadedTracks: Boolean = true) {
        playlistManager.deleteLocalPlaylist(playlistId, deleteDownloadedTracks)
    }

    /**
     * 🎵 Добавить трек в локальный плейлист.
     * ⚡ Если трек еще не скачан на диск — автоматически скачивает его!
     */
    fun addTrackToLocalPlaylist(playlistId: String, track: FullTrackInfo, onComplete: ((Boolean) -> Unit)? = null) {
        playlistManager.addTrackToLocalPlaylist(playlistId, track, onComplete)
    }

    /**
     * ☁️ Добавить трек в персональный плейлист Яндекс Музыки
     */
    fun addTrackToYandexPlaylist(playlist: PlaylistInfo, track: FullTrackInfo, onComplete: ((Boolean) -> Unit)? = null) {
        playlistManager.addTrackToYandexPlaylist(playlist, track, onComplete)
    }

    /**
     * 🗑️ Удалить трек из персонального плейлиста Яндекс Музыки
     */
    fun removeTrackFromYandexPlaylist(playlist: PlaylistInfo, track: FullTrackInfo, onComplete: ((Boolean) -> Unit)? = null) {
        playlistManager.removeTrackFromYandexPlaylist(
            playlist = playlist,
            track = track,
            onTrackRemoved = { cleanTrackId ->
                if (currentOpenUserPlaylist?.kind == playlist.kind) {
                    trackListCoordinator.removeTrack(cleanTrackId)
                    trackListCoordinator.removeTrack(track.id)
                }
            },
            onComplete = onComplete
        )
    }

    /**
     * 📋 Загрузить детали плейлиста Яндекс Музыки (с треками) для отображения в диалоге
     */
    suspend fun loadYandexPlaylistDetails(playlist: PlaylistInfo): io.github.audiz.models.YandexPlaylistDetails? {
        return playlistManager.loadYandexPlaylistDetails(playlist)
    }

    /**
     * 🔍 Проверить, содержится ли трек в локальном плейлисте
     */
    fun isTrackInLocalPlaylist(playlist: LocalPlaylist, track: FullTrackInfo): Boolean {
        return playlistManager.isTrackInLocalPlaylist(playlist, track)
    }

    /**
     * 🔍 Проверить, содержится ли трек хотя бы в одном плейлисте (локальном или облачном Яндекс)
     */
    fun isTrackInAnyPlaylist(track: FullTrackInfo): Boolean {
        return playlistManager.isTrackInAnyPlaylist(track)
    }

    /**
     * 🔢 Подсчитать количество плейлистов, в которых содержится трек
     */
    fun getTrackPlaylistsCount(track: FullTrackInfo): Int {
        return playlistManager.getTrackPlaylistsCount(track)
    }

    /**
     * ➖ Удалить трек из локального плейлиста по объекту трека
     */
    fun removeTrackFromLocalPlaylist(playlistId: String, track: FullTrackInfo) {
        playlistManager.removeTrackFromLocalPlaylist(
            playlistId = playlistId,
            track = track,
            onTrackRemoved = { cleanPath ->
                val playlist = localPlaylists.firstOrNull { it.id == playlistId }
                if (isDownloadedTracksScreen && currentScreenTitle == playlist?.title) {
                    trackListCoordinator.removeTrackFromLoaded { it.realId == cleanPath || it.id == track.id }
                }
            }
        )
    }

    /**
     * ☁️ Создать новый плейлист в аккаунте Яндекс Музыки
     */
    fun createYandexPlaylist(title: String, onComplete: ((PlaylistInfo?) -> Unit)? = null) {
        playlistManager.createYandexPlaylist(title, onComplete)
    }

    /**
     * ✏️ Переименовать плейлист в аккаунте Яндекс Музыки
     */
    fun renameYandexPlaylist(playlist: PlaylistInfo, newTitle: String, onComplete: ((Boolean) -> Unit)? = null) {
        val oldTitle = playlist.title
        val cleanTitle = newTitle.trim().ifBlank { oldTitle }
        playlistManager.renameYandexPlaylist(playlist, newTitle) { success ->
            if (success) {
                if (currentOpenUserPlaylist?.kind == playlist.kind) {
                    val isOffline = currentScreenTitle.contains("[Офлайн-кеш]")
                    currentScreenTitle = if (isOffline) "$cleanTitle [Офлайн-кеш]" else cleanTitle
                }
            }
            onComplete?.invoke(success)
        }
    }

    /**
     * ➖ Удалить трек из локального плейлиста
     */
    fun removeTrackFromLocalPlaylist(playlistId: String, trackPathOrId: String) {
        playlistManager.removeTrackFromLocalPlaylist(
            playlistId = playlistId,
            trackPathOrId = trackPathOrId,
            onTrackRemoved = { cleanPath ->
                val playlist = localPlaylists.firstOrNull { it.id == playlistId }
                if (isDownloadedTracksScreen && currentScreenTitle == playlist?.title) {
                    trackListCoordinator.removeTrackFromLoaded { it.realId == cleanPath || it.id == trackPathOrId }
                }
            }
        )
    }

    /**
     * 📂 Открыть локальный плейлист для воспроизведения
     */
    fun openLocalPlaylist(
        playlist: LocalPlaylist,
        restoreTrackId: String? = null,
        autoPlayFirst: Boolean = false
    ) = trackListCoordinator.openLocalPlaylist(playlist, restoreTrackId, autoPlayFirst)

    /**
     * 💾 Скачать весь плейлист из Яндекс Музыки в локальный оффлайн-плейлист
     */
    fun downloadYandexPlaylistToLocal(playlist: PlaylistInfo) {
        playlistManager.downloadYandexPlaylistToLocal(playlist)
    }

    /**
     * 📋 Экспорт локального плейлиста в файл .m3u8
     */
    fun exportLocalPlaylist(playlistId: String): String {
        return playlistManager.exportLocalPlaylist(playlistId)
    }

    /**
     * ➕ Добавить пользовательский источник (директорию, отдельный файл или M3U)
     */
    fun addCustomSource(path: String, customName: String? = null): CustomMediaSource? {
        return localMediaManager.addSource(path, customName)
    }

    /**
     * 🗑️ Удалить пользовательский источник из сохраненных
     */
    fun removeCustomSource(id: String) {
        localMediaManager.removeSource(id)
    }

    /**
     * 📂 Открыть пользовательский источник (папку, отдельный аудиофайл или M3U-плейлист)
     */
    fun openCustomSource(source: CustomMediaSource, restoreTrackId: String? = null) =
        trackListCoordinator.openCustomSource(source, restoreTrackId)

    /**
     * 🔍 Получить листинг содержимого директории для проводника
     */
    fun browseFolder(folderPath: String, rootPath: String? = null): FolderListing {
        return trackListCoordinator.browseFolder(folderPath, rootPath)
    }

    /**
     * 📁 Воспроизвести выбранную папку или подпапку (рекурсивно или только файлы текущей папки)
     */
    fun playFolder(
        folderPath: String,
        folderName: String,
        source: CustomMediaSource? = null,
        isRecursive: Boolean = true
    ) = trackListCoordinator.playFolder(folderPath, folderName, source, isRecursive)

    /**
     * 📁 Открыть плейлист папки без автоматического начала воспроизведения (для восстановления сессии)
     */
    fun openFolderPlaylist(
        folderPath: String,
        folderName: String,
        source: CustomMediaSource? = null,
        restoreTrackId: String? = null,
        isRecursive: Boolean = true
    ) = trackListCoordinator.openFolderPlaylist(folderPath, folderName, source, restoreTrackId, isRecursive)

    /**
     * 🎵 Воспроизвести отдельный локальный трек и сформировать очередь из треков папки
     */
    fun playSingleLocalTrack(
        track: FullTrackInfo,
        folderTracks: List<FullTrackInfo> = listOf(track),
        folderName: String? = null,
        folderPath: String? = null,
        source: CustomMediaSource? = null
    ) = trackListCoordinator.playSingleLocalTrack(track, folderTracks, folderName, folderPath, source)

    /**
     * Догружает следующую пачку треков (вызывается при скролле)
     */
    fun loadNextPage() = trackListCoordinator.loadNextPage()

    /**
     * 📥 Постепенно загружает все оставшиеся страницы (как непрерывный скролл)
     */
    fun loadAllRemainingPages() = trackListCoordinator.loadAllRemainingPages()

    /**
     * 🔥 ФУНКЦИЯ СКАЧИВАНИЯ ТРЕКА ПО КЛИКУ (ОБНОВЛЕНО: принимает artistName)
     */
    fun downloadTrack(trackId: String, trackTitle: String, artistName: String) =
        downloadManager.downloadTrack(trackId, trackTitle, artistName)

    /**
     * 📁 Экспортировать / сохранить трек в произвольную директорию на устройстве
     */
    fun saveTrackToCustomFolder(
        track: FullTrackInfo,
        targetDirectory: String,
        customFileName: String? = null,
        onComplete: ((String?) -> Unit)? = null
    ) = downloadManager.saveTrackToCustomFolder(track, targetDirectory, customFileName, onComplete)

    /**
     * 💾 Сохранить трек по конкретному выбранному пути файла (из системного Save As диалога)
     */
    fun saveTrackToFilePath(
        track: FullTrackInfo,
        targetFilePath: String,
        onComplete: ((String?) -> Unit)? = null
    ) = downloadManager.saveTrackToFilePath(track, targetFilePath, onComplete)

    /**
     * 🗑️ Удалить трек с диска
     */
    fun deleteTrack(trackId: String, trackTitle: String, artistName: String) {
        val cleanId = trackId.removePrefix("local:")
        val foundTrack = loadedTracks.firstOrNull {
            it.id == trackId || it.realId == trackId ||
            it.id.removePrefix("local:") == cleanId ||
            it.realId?.removePrefix("local:") == cleanId
        }
        val effectiveTrackId: String = when {
            isLocalTrackId(trackId) -> trackId
            !foundTrack?.realId.isNullOrBlank() -> foundTrack!!.realId!!
            foundTrack != null && isLocalTrackId(foundTrack.id) -> foundTrack.id
            else -> trackId
        }
        val playingPath = currentPlayingFilePath?.removePrefix("local:")
        val isCurrentlyPlayingThis = (playerTrackId == trackId || playerTrackId?.removePrefix("local:") == cleanId || playingPath == cleanId)
        val finalTrackId = if (isCurrentlyPlayingThis && playingPath != null && !isLocalTrackId(effectiveTrackId)) {
            playingPath
        } else {
            effectiveTrackId
        }
        val effectiveTitle = trackTitle.ifBlank { foundTrack?.title ?: "" }
        val effectiveArtist = artistName.ifBlank { foundTrack?.artists?.joinToString { it.name } ?: "" }
        downloadManager.deleteTrack(finalTrackId, effectiveTitle, effectiveArtist)
    }

    /**
     * 🧹 Очистить кеш плейлистов
     */
    fun clearPlaylistsCache() = downloadManager.clearPlaylistsCache()

    /**
     * 💣 Удалить все скачанные треки с диска
     */
    fun clearAllDownloadedMusic() = downloadManager.clearAllDownloadedMusic()

    fun sanitizeKeepSpaces(input: String): String = downloadManager.sanitizeKeepSpaces(input)

    /**
     * Проверка, скачан ли трек на диск (в HQ или LQ или в корневую папку)
     */
    fun isTrackDownloaded(artistName: String, trackTitle: String): Boolean =
        downloadManager.isTrackDownloaded(artistName, trackTitle)


    private fun resetPagination() = trackListCoordinator.resetPagination()

    fun closeArtistTracks() {
        isDownloadedTracksScreen = false
        isTracksListVisible = false
        tracksListOrigin = TracksListOrigin.HOME
        resetBrowsedFolder()
    }

    /**
     * 🎶 Переключить отображение экрана очереди воспроизведения
     */
    fun toggleQueueView() {
        trackListCoordinator.toggleQueueView(
            isWaveMode = isWaveMode,
            waveTracks = waveTracks,
            waveTitle = waveManager.currentWaveTitle,
            isTracksListVisible = isTracksListVisible,
            setTracksListVisible = { isTracksListVisible = it }
        )
    }

    /**
     * 🎶 Открыть экран очереди воспроизведения
     */
    fun openQueueView(origin: TracksListOrigin = TracksListOrigin.HOME) {
        trackListCoordinator.openQueueView(
            origin = origin,
            isWaveMode = isWaveMode,
            waveTracks = waveTracks,
            waveTitle = waveManager.currentWaveTitle
        )
    }

    // ==========================================
    // 🎵 УПРАВЛЕНИЕ АУДИОПЛЕЕРОМ
    // ==========================================

    // ==========================================
    // 🎵 УПРАВЛЕНИЕ АУДИОПЛЕЕРОМ (Делегировано в PlaybackManager)
    // ==========================================

    fun playTrack(
        trackId: String,
        trackTitle: String,
        artistName: String,
        albumId: Long? = null,
        isManualSelection: Boolean = true,
        isWave: Boolean = false,
        source: TrackPlaySource = TrackPlaySource.PLAY
    ) {
        val cleanId = trackId.removePrefix("local:")
        val targetCoverUri = loadedTracks.firstOrNull {
            it.id == trackId || it.realId == trackId ||
            it.id.removePrefix("local:") == cleanId ||
            it.realId?.removePrefix("local:") == cleanId
        }?.coverUri
            ?: searchResult?.result?.results?.firstOrNull {
                it.track?.id == trackId || it.track?.id?.removePrefix("local:") == cleanId
            }?.track?.coverUri
            ?: waveManager.currentWaveTrack?.takeIf {
                it.id == trackId || it.id.removePrefix("local:") == cleanId
            }?.coverUri

        if (!isWave) {
            waveManager.resetWaveMode()
            playbackSessionManager.updateLastTrack(
                trackId = trackId,
                trackTitle = trackTitle,
                artistName = artistName,
                coverUri = targetCoverUri
            )
        }

        playbackManager.playTrack(
            targetTrackId = trackId,
            targetTrackTitle = trackTitle,
            targetArtistName = artistName,
            targetAlbumId = albumId,
            targetCoverUri = targetCoverUri,
            isManualSelection = isManualSelection,
            isWave = isWave,
            source = source
        )
    }

    /** Запуск трека по внешнему ID (например, из Deep Link) */
    fun playTrackByExternalId(trackId: String) =
        trackListCoordinator.playTrackByExternalId(trackId)

    fun playTrackForceLQ(
        trackId: String,
        trackTitle: String,
        artistName: String,
        source: TrackPlaySource = TrackPlaySource.PLAY
    ) {
        playbackManager.playTrackForceLQ(
            targetTrackId = trackId,
            targetTrackTitle = trackTitle,
            targetArtistName = artistName,
            source = source
        )
    }

    fun togglePlayPause() {
        if (playbackManager.trackId == null && waveManager.currentWaveTrack != null) {
            waveManager.playWaveTrack(waveManager.waveCurrentIndex)
        } else {
            playbackManager.togglePlayPause()
        }
    }

    fun stopPlayback() = playbackManager.stopPlayback()

    fun seekPlayer(positionMs: Long) = playbackManager.seekTo(positionMs)

    fun playNextTrack(currentId: String? = playerTrackId, source: TrackPlaySource = TrackPlaySource.NEXT) {
        try {
            playbackManager.playNextTrack(currentId, source)
        } catch (t: Throwable) {
            AppLogger.e("SearchViewModel", "Ошибка playNextTrack: ${t.message}")
        }
    }

    fun playPrevTrack(currentId: String? = playerTrackId, source: TrackPlaySource = TrackPlaySource.PREV) {
        try {
            playbackManager.playPrevTrack(currentId, source)
        } catch (t: Throwable) {
            AppLogger.e("SearchViewModel", "Ошибка playPrevTrack: ${t.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackManager.release()
    }
}

