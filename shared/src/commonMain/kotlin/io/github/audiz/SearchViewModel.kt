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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TrackPlaySource {
    PLAY,
    NEXT,
    PREV
}

enum class TracksListOrigin {
    HOME,
    FOLDER_BROWSER,
    MOBILE_PLAYER
}

class SearchViewModel(private val repository: MusicRepository = MusicRepository()) : ViewModel() {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        println("SearchViewModel: ❌ Uncaught coroutine exception: ${throwable.message}")
        throwable.printStackTrace()
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job {
        return viewModelScope.launch(Dispatchers.Main.immediate + coroutineExceptionHandler) {
            try {
                block()
            } catch (t: Throwable) {
                println("SearchViewModel: ❌ Error in safe coroutine: ${t.message}")
                t.printStackTrace()
            }
        }
    }

    var searchQuery by mutableStateOf("")
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
    var searchResult by mutableStateOf<YandexMusicResponse?>(null)
        private set

    // 📊 Список уже загруженных детальных треков
    var loadedTracks = mutableStateListOf<FullTrackInfo>()
        private set

    // 📁 Флаг экрана плейлиста загруженной музыки (локальные файлы на диске)
    var isDownloadedTracksScreen by mutableStateOf(false)

    // 📋 Флаг видимости экрана списка треков / активной очереди (Экран Б)
    private var _isTracksListVisible = mutableStateOf(loadAppConfig(AppConfigKeys.TRACKS_LIST_VISIBLE) == "true")
    var isTracksListVisible: Boolean
        get() = _isTracksListVisible.value
        set(value) {
            _isTracksListVisible.value = value
            saveAppConfig(AppConfigKeys.TRACKS_LIST_VISIBLE, value.toString())
        }

    // 📁 Контекст текущего открытого проводника по локальным папкам (для возврата в ту же папку)
    var activeBrowsedFolderSource by mutableStateOf<CustomMediaSource?>(null)
    var activeBrowsedFolderPath by mutableStateOf<String?>(null)
    var tracksListOrigin by mutableStateOf(TracksListOrigin.HOME)

    fun resetBrowsedFolder() {
        activeBrowsedFolderSource = null
        activeBrowsedFolderPath = null
        tracksListOrigin = TracksListOrigin.HOME
    }

    // 🎵 Источник текущей активной очереди треков
    var activeQueueSource by mutableStateOf<CustomMediaSource?>(null)
    var activeQueueFolderPath by mutableStateOf<String?>(null)
    var activeQueueFolderName by mutableStateOf<String?>(null)

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

    var currentScreenTitle by mutableStateOf("Загружено треков")

    // 🔀 Режим перемешивания (Shuffle) — делегируется в PlaybackManager
    val isShuffleEnabled: Boolean
        get() = playbackManager.isShuffleEnabled

    fun toggleShuffle() {
        playbackManager.toggleShuffle()
    }

    // 📜 Список всех ID треков, которые нужно загрузить частями
    var allTrackIds by mutableStateOf(listOf<String>())
        private set
    val totalTracksCount: Int
        get() = allTrackIds.size.coerceAtLeast(loadedTracks.size)

    var isLoadingAllPages by mutableStateOf(false)
        private set

    private val pageSize = 20
    private var currentOffset = 0

    // Флаг, показывающий, есть ли еще треки для загрузки
    var canLoadMore by mutableStateOf(false)
        private set

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
            val id = playerTrackId
            return id?.startsWith("local:") == true ||
                   id?.startsWith("/") == true ||
                   (id != null && id.length > 2 && id[1] == ':') ||
                   currentPlayingFilePath != null
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
            return id != null && likedTrackIds.contains(id)
        }

    /** Проверяет, дизлайкнут ли текущий воспроизводимый трек */
    val isCurrentTrackDisliked: Boolean
        get() {
            val id = playerTrackId ?: currentWaveTrack?.id
            return id != null && dislikedTrackIds.contains(id)
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

    /** Переключить лайк для текущего воспроизводимого трека */
    fun toggleLikeCurrentTrack() {
        if (isCurrentTrackLocal) return
        val trackId = playerTrackId ?: currentWaveTrack?.id ?: return
        val albumId = playerAlbumId ?: currentWaveTrack?.albums?.firstOrNull()?.id
        playlistManager.toggleLike(trackId, albumId)
    }

    /** Переключить дизлайк для текущего воспроизводимого трека */
    fun toggleDislikeCurrentTrack() {
        if (isCurrentTrackLocal) return
        val trackId = playerTrackId ?: currentWaveTrack?.id ?: return
        val albumId = playerAlbumId ?: currentWaveTrack?.albums?.firstOrNull()?.id
        playlistManager.toggleDislike(trackId, albumId) {
            playNextTrack()
        }
    }

    /** Загрузить и обновить список всех лайкнутых треков пользователя в фоне */
    fun syncLikedTrackIds() {
        playlistManager.syncLikedTrackIds()
    }

    // ⚙️ МЕНЕДЖЕР НАСТРОЕК И ТОКЕНОВ:
    val settingsManager = SettingsManager(
        repository = repository,
        onTokenUpdated = { _ ->
            playlistManager.syncLikedTrackIds()
            playlistManager.loadPersonalPlaylists()
            playlistManager.loadUserPlaylists()
            loadInitialWave(autoPlay = false)
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
        onTrackLiked = { trackId -> playlistManager.likedTrackIds.add(trackId) },
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
            loadedTracks.removeAll {
                it.id == trackId || it.realId == trackId ||
                it.id.removePrefix("local:") == cleanId ||
                it.realId?.removePrefix("local:") == cleanId
            }
            allTrackIds = allTrackIds.filter {
                it != trackId && it.removePrefix("local:") != cleanId
            }

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
            loadedTracks.clear()
            allTrackIds = emptyList()
            stopPlayback()
        },
        onError = { errorMessage = it },
        onStatusMessage = { storageStatusMessage = it }
    )

    // 💾 МЕНЕДЖЕР СЕССИИ ВОСПРОИЗВЕДЕНИЯ (Восстановление плейлиста или Волны при рестарте)
    val playbackSessionManager = PlaybackSessionManager()

    // 🎵 АУДИОПЛЕЕР (Делегирован в PlaybackManager):
    val playbackManager: PlaybackManager = PlaybackManager(
        scope = viewModelScope,
        repository = repository,
        localTrackResolver = localTrackResolver,
        queueSource = object : PlaybackQueueSource {
            override fun getLoadedTracks(): List<FullTrackInfo> = loadedTracks
            override fun getSearchTracks(): List<FullTrackInfo> =
                searchResult?.result?.results?.filter { it.type == "track" }?.mapNotNull { it.track?.toFullTrackInfo() } ?: emptyList()
            override suspend fun loadMoreTracks(): Boolean {
                val nextChunk = allTrackIds.drop(currentOffset).take(pageSize)
                return if (nextChunk.isNotEmpty()) {
                    val details = repository.getTracksDetails(nextChunk)
                    loadedTracks.addAll(details.result)
                    currentOffset += nextChunk.size
                    canLoadMore = currentOffset < allTrackIds.size
                    true
                } else false
            }
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
            val isLocal = id?.startsWith("local:") == true || currentPlayingFilePath != null
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
            return FullTrackInfo(
                id = id,
                realId = filePath ?: if (id.startsWith("local:")) id.removePrefix("local:") else null,
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

        // 🌊 При старте запрашиваем сессию Моей волны, персональные плейлисты и список лайков
        loadLocalPlaylistsFromDisk()
        if (currentAccessToken.isNotBlank()) {
            syncLikedTrackIds()
            loadPersonalPlaylists()
            loadUserPlaylists()
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
    }

    fun resumeLastSession(session: LastPlaybackSession) {
        restorePlaylistSession(session, wasTracksListVisible = true)
    }

    private fun restorePlaylistSession(session: LastPlaybackSession, wasTracksListVisible: Boolean = true) {
        when (session.type) {
            LastPlaybackType.DOWNLOADED -> {
                loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                if (!wasTracksListVisible) isTracksListVisible = false
            }
            LastPlaybackType.LOCAL_PLAYLIST -> {
                launchSafe {
                    if (localPlaylists.isEmpty()) {
                        val list = withContext(DispatcherIO) {
                            loadLocalPlaylists(musicStoragePath)
                        }
                        if (list.isNotEmpty()) {
                            localPlaylists.clear()
                            localPlaylists.addAll(list)
                        }
                    }
                    val playlist = localPlaylists.firstOrNull { 
                        it.id == session.id || it.title.equals(session.title, ignoreCase = true) 
                    }
                    if (playlist != null) {
                        openLocalPlaylist(playlist, restoreTrackId = session.lastTrackId)
                    } else {
                        loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                    }
                    if (!wasTracksListVisible) isTracksListVisible = false
                }
            }
            LastPlaybackType.CUSTOM_SOURCE -> {
                val path = session.path
                val source = customMediaSources.firstOrNull { it.id == session.sourceId || it.path == path }
                if (source != null) {
                    openCustomSource(source, restoreTrackId = session.lastTrackId)
                } else if (!path.isNullOrBlank()) {
                    openFolderPlaylist(
                        folderPath = path,
                        folderName = session.title ?: "Папка",
                        restoreTrackId = session.lastTrackId
                    )
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) isTracksListVisible = false
            }
            LastPlaybackType.CUSTOM_FOLDER -> {
                val folderPath = session.path
                if (!folderPath.isNullOrBlank()) {
                    val src = customMediaSources.firstOrNull { it.id == session.sourceId }
                    openFolderPlaylist(
                        folderPath = folderPath,
                        folderName = session.title ?: folderPath.substringAfterLast('/').ifBlank { "Папка" },
                        source = src,
                        restoreTrackId = session.lastTrackId,
                        isRecursive = session.isRecursive
                    )
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) isTracksListVisible = false
            }
            LastPlaybackType.YANDEX_USER_PLAYLIST -> {
                val uid = session.uid
                val kind = session.kind
                val uuid = session.uuid
                if (uid != null && kind != null) {
                    loadPlaylistTracks(
                        uid = uid,
                        kind = kind,
                        playlistTitle = session.title,
                        restoreTrackId = session.lastTrackId
                    )
                } else if (!uuid.isNullOrBlank()) {
                    loadPlaylistByUuid(uuid, session.title, restoreTrackId = session.lastTrackId)
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) isTracksListVisible = false
            }
            LastPlaybackType.YANDEX_UUID -> {
                val uuid = session.uuid
                if (!uuid.isNullOrBlank()) {
                    loadPlaylistByUuid(uuid, session.title, restoreTrackId = session.lastTrackId)
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) isTracksListVisible = false
            }
            LastPlaybackType.YANDEX_LIKES -> {
                loadLikesPlaylist(restoreTrackId = session.lastTrackId)
                if (!wasTracksListVisible) isTracksListVisible = false
            }
            LastPlaybackType.YANDEX_HISTORY -> {
                loadHistory(restoreTrackId = session.lastTrackId)
                if (!wasTracksListVisible) isTracksListVisible = false
            }
            LastPlaybackType.ARTIST -> {
                val artistId = session.artistId
                if (!artistId.isNullOrBlank()) {
                    loadArtistTracks(
                        artistId = artistId,
                        artistName = session.artistName,
                        restoreTrackId = session.lastTrackId
                    )
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) isTracksListVisible = false
            }
            LastPlaybackType.WAVE -> {
                if (waveManager.waveTracks.isNotEmpty()) {
                    playWaveTrack(waveManager.waveCurrentIndex)
                } else if (session.waveSeeds.isNotEmpty()) {
                    startThematicWave(session.waveTitle, session.waveSeeds, autoPlay = true)
                } else {
                    loadInitialWave(autoPlay = true)
                }
            }
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
                searchResult = repository.searchInstant(query)
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
                e.printStackTrace()
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

    /**
     * 📁 Загрузка плейлиста сохранённой на диске музыки (БЕЗ запросов в интернет)
     */
    fun loadDownloadedTracksPlaylist(restoreTrackId: String? = null) {
        if (isLoading) return
        currentScreenTitle = "Загруженная музыка"
        isDownloadedTracksScreen = true
        isTracksListVisible = true
        tracksListOrigin = TracksListOrigin.HOME
        currentOpenUserPlaylist = null
        activeBrowsedFolderSource = null
        activeBrowsedFolderPath = null
        activeQueueSource = null
        activeQueueFolderPath = null
        activeQueueFolderName = "Загруженная музыка"
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.DOWNLOADED,
                title = "Загруженная музыка",
                lastTrackId = restoreTrackId
            )
        )
        launchSafe {
            isLoading = true
            errorMessage = null
            resetPagination()
            try {
                val tracks = withContext(DispatcherIO) {
                    scanDownloadedTracks(musicStoragePath)
                }
                if (tracks.isNotEmpty()) {
                    loadedTracks.addAll(tracks)
                    allTrackIds = tracks.map { it.id }
                    canLoadMore = false
                    println("SearchViewModel: Загружено с диска ${tracks.size} треков без сетевых запросов")
                    if (restoreTrackId != null) {
                        val target = tracks.firstOrNull { it.id == restoreTrackId } ?: tracks.firstOrNull()
                        if (target != null) {
                            playbackManager.setInitialTrack(target)
                        }
                    }
                } else {
                    errorMessage = "На диске не найдено аудиофайлов в папке:\n$musicStoragePath"
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка чтения файлов с диска: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun loadArtistTracks(artistId: String, artistName: String? = null, restoreTrackId: String? = null) {
        val title = if (!artistName.isNullOrBlank()) "Треки: $artistName" else "Треки исполнителя"
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.ARTIST,
                artistId = artistId,
                artistName = artistName,
                title = title,
                lastTrackId = restoreTrackId
            )
        )
        startPagination(title = title, restoreTrackId = restoreTrackId) { repository.getTrackIds(artistId).result }
    }

    /**
     * 🎵 Загрузка треков плейлиста из поиска или каталога
     */
    fun loadPlaylist(playlist: PlaylistInfo, restoreTrackId: String? = null) {
        val title = playlist.title.ifBlank { "Плейлист" }
        val isUserPlaylist = userPlaylists.any { it.kind == playlist.kind }
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.YANDEX_USER_PLAYLIST,
                uid = playlist.uid,
                kind = playlist.kind,
                title = title,
                uuid = playlist.playlistUuid,
                lastTrackId = restoreTrackId
            )
        )
        startPagination(title = title, userPlaylist = if (isUserPlaylist) playlist else null, restoreTrackId = restoreTrackId) {
            val uuid = playlist.playlistUuid
            if (!uuid.isNullOrBlank()) {
                try {
                    return@startPagination repository.getPlaylistTrackIdsByUuid(uuid)
                } catch (e: Throwable) {
                    println("SearchViewModel: Ошибка загрузки по UUID ($uuid): ${e.message}, пробуем через uid/kind...")
                }
            }
            val kind = playlist.kind ?: 0L
            repository.getPlaylistTrackIds(playlist.uid, kind)
        }
    }

    fun loadPlaylistTracks(uid: Long, kind: Long, playlistTitle: String? = null, restoreTrackId: String? = null) {
        val title = if (!playlistTitle.isNullOrBlank()) playlistTitle else "Треки плейлиста"
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.YANDEX_USER_PLAYLIST,
                uid = uid,
                kind = kind,
                title = title,
                lastTrackId = restoreTrackId
            )
        )
        startPagination(title = title, restoreTrackId = restoreTrackId) { repository.getPlaylistTrackIds(uid, kind) }
    }

    /**
     * 🔥 Загрузка плейлиста по UUID (для персональных плейлистов)
     */
    fun loadPlaylistByUuid(uuid: String, playlistTitle: String? = null, restoreTrackId: String? = null) {
        val title = playlistTitle ?: "Плейлист"
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.YANDEX_UUID,
                uuid = uuid,
                title = title,
                lastTrackId = restoreTrackId
            )
        )
        startPagination(title = title, restoreTrackId = restoreTrackId) {
            repository.getPlaylistTrackIdsByUuid(uuid)
        }
    }

    /**
     * 🔥 Загрузка плейлиста "Мне нравится" (двухэтапный запрос)
     */
    fun loadLikesPlaylist(restoreTrackId: String? = null) {
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.YANDEX_LIKES,
                title = "Мне нравится",
                lastTrackId = restoreTrackId
            )
        )
        startPagination(title = "Мне нравится", restoreTrackId = restoreTrackId) {
            val uuid = repository.getLikesPlaylistUuid()
            val ids = repository.getPlaylistTrackIdsByUuid(uuid)
            likedTrackIds.addAll(ids)
            ids
        }
    }

    /**
     * 🔥 Загрузка истории прослушивания
     */
    fun loadHistory(restoreTrackId: String? = null) {
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.YANDEX_HISTORY,
                title = "История прослушиваний",
                lastTrackId = restoreTrackId
            )
        )
        startPagination(title = "История прослушиваний", restoreTrackId = restoreTrackId) {
            repository.getHistoryTrackIds()
        }
    }

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
     * 🗑️ Удалить локальный плейлист (файлы треков на диске не удаляются)
     */
    fun deleteLocalPlaylist(playlistId: String) {
        playlistManager.deleteLocalPlaylist(playlistId)
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
                    loadedTracks.removeAll { it.id == track.id || it.realId == cleanTrackId || it.id == cleanTrackId }
                    allTrackIds = allTrackIds.filterNot { it == cleanTrackId || it == track.id }
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
                    loadedTracks.removeAll { it.realId == cleanPath || it.id == track.id }
                    allTrackIds = loadedTracks.map { it.id }
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
                    loadedTracks.removeAll { it.realId == cleanPath || it.id == trackPathOrId }
                    allTrackIds = loadedTracks.map { it.id }
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
    ) {
        if (isLoading) return
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.LOCAL_PLAYLIST,
                id = playlist.id,
                title = playlist.title,
                lastTrackId = restoreTrackId
            )
        )
        currentScreenTitle = playlist.title
        isDownloadedTracksScreen = true
        isTracksListVisible = true
        tracksListOrigin = TracksListOrigin.HOME
        currentOpenUserPlaylist = null
        activeBrowsedFolderSource = null
        activeBrowsedFolderPath = null
        activeQueueSource = null
        activeQueueFolderPath = null
        activeQueueFolderName = playlist.title
        launchSafe {
            isLoading = true
            errorMessage = null
            resetPagination()
            try {
                val tracks = withContext(DispatcherIO) {
                    getTracksFromLocalPaths(playlist.trackPaths)
                }
                if (tracks.isNotEmpty()) {
                    loadedTracks.clear()
                    loadedTracks.addAll(tracks)
                    allTrackIds = tracks.map { it.id }
                    canLoadMore = false
                    println("SearchViewModel: Открыт локальный плейлист '${playlist.title}' (${tracks.size} треков)")
                    if (tracks.size < playlist.trackPaths.size) {
                        println("SearchViewModel: Найдено ${tracks.size} из ${playlist.trackPaths.size} треков плейлиста '${playlist.title}'")
                    }
                    if (autoPlayFirst) {
                        val target = restoreTrackId?.let { rid ->
                            tracks.firstOrNull { it.id == rid || it.realId == rid }
                        } ?: tracks.first()
                        playTrack(
                            trackId = target.id,
                            trackTitle = target.title,
                            artistName = target.artists.firstOrNull()?.name ?: "",
                            source = TrackPlaySource.PLAY
                        )
                    } else if (restoreTrackId != null) {
                        val target = tracks.firstOrNull { it.id == restoreTrackId || it.realId == restoreTrackId } ?: tracks.firstOrNull()
                        if (target != null) {
                            playbackManager.setInitialTrack(target)
                        }
                    } else {
                        val first = tracks.firstOrNull()
                        if (first != null) {
                            playbackManager.setInitialTrack(first)
                        }
                    }
                } else {
                    loadedTracks.clear()
                    allTrackIds = emptyList()
                    if (playlist.trackPaths.isEmpty()) {
                        errorMessage = "Плейлист '${playlist.title}' пуст."
                    } else {
                        errorMessage = "В плейлисте '${playlist.title}' ${playlist.trackPaths.size} трек(ов), но файлы еще не скачаны на это устройство. Откройте YamSync («Синхронизация») для быстрой загрузки файлов."
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка чтения плейлиста: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

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
    fun openCustomSource(source: CustomMediaSource, restoreTrackId: String? = null) {
        if (isLoading) return
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.CUSTOM_SOURCE,
                sourceId = source.id,
                path = source.path,
                title = source.name,
                lastTrackId = restoreTrackId
            )
        )
        currentScreenTitle = source.name
        isDownloadedTracksScreen = true
        isTracksListVisible = true
        tracksListOrigin = TracksListOrigin.HOME
        currentOpenUserPlaylist = null
        val isDir = source.type == LocalSourceType.FOLDER
        activeBrowsedFolderSource = null
        activeBrowsedFolderPath = null
        activeQueueSource = if (isDir) source else null
        activeQueueFolderPath = if (isDir) source.path else null
        activeQueueFolderName = source.name
        launchSafe {
            isLoading = true
            errorMessage = null
            resetPagination()
            try {
                val tracks = withContext(DispatcherIO) {
                    localMediaManager.getTracksForSource(source)
                }
                if (tracks.isNotEmpty()) {
                    loadedTracks.clear()
                    loadedTracks.addAll(tracks)
                    allTrackIds = tracks.map { it.id }
                    canLoadMore = false
                    println("SearchViewModel: Открыт пользовательский источник '${source.name}' (${tracks.size} треков)")
                    if (restoreTrackId != null) {
                        val target = tracks.firstOrNull { it.id == restoreTrackId || it.realId == restoreTrackId } ?: tracks.firstOrNull()
                        if (target != null) {
                            playbackManager.setInitialTrack(target)
                        }
                    }
                } else {
                    errorMessage = "В источнике '${source.name}' нет доступных аудиофайлов."
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка чтения источника: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 🔍 Получить листинг содержимого директории для проводника
     */
    fun browseFolder(folderPath: String, rootPath: String? = null): FolderListing {
        return localMediaManager.browseFolder(folderPath, rootPath)
    }

    /**
     * 📁 Воспроизвести выбранную папку или подпапку (рекурсивно или только файлы текущей папки)
     */
    fun playFolder(
        folderPath: String,
        folderName: String,
        source: CustomMediaSource? = null,
        isRecursive: Boolean = true
    ) {
        if (isLoading) return
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.CUSTOM_FOLDER,
                sourceId = source?.id,
                path = folderPath,
                title = folderName,
                isRecursive = isRecursive
            )
        )
        activeBrowsedFolderSource = source
        activeBrowsedFolderPath = folderPath
        tracksListOrigin = if (source != null) TracksListOrigin.FOLDER_BROWSER else TracksListOrigin.HOME
        activeQueueSource = source
        activeQueueFolderPath = folderPath
        activeQueueFolderName = folderName
        currentScreenTitle = folderName
        isDownloadedTracksScreen = true
        isTracksListVisible = true
        currentOpenUserPlaylist = null
        launchSafe {
            isLoading = true
            errorMessage = null
            resetPagination()
            try {
                val tracks = withContext(DispatcherIO) {
                    if (isRecursive) {
                        scanDownloadedTracks(folderPath)
                    } else {
                        localMediaManager.browseFolder(folderPath).tracks
                    }
                }
                if (tracks.isNotEmpty()) {
                    loadedTracks.clear()
                    loadedTracks.addAll(tracks)
                    allTrackIds = tracks.map { it.id }
                    canLoadMore = false
                    println("SearchViewModel: Запущена папка '$folderName' (рекурсивно: $isRecursive, ${tracks.size} треков)")
                    val first = tracks.first()
                    playTrack(
                        trackId = first.id,
                        trackTitle = first.title,
                        artistName = first.artists.firstOrNull()?.name ?: "Unknown Artist",
                        albumId = first.albums.firstOrNull()?.id
                    )
                } else {
                    errorMessage = "В папке '$folderName' нет аудиофайлов."
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка чтения папки: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 📁 Открыть плейлист папки без автоматического начала воспроизведения (для восстановления сессии)
     */
    fun openFolderPlaylist(
        folderPath: String,
        folderName: String,
        source: CustomMediaSource? = null,
        restoreTrackId: String? = null,
        isRecursive: Boolean = true
    ) {
        if (isLoading) return
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.CUSTOM_FOLDER,
                sourceId = source?.id,
                path = folderPath,
                title = folderName,
                isRecursive = isRecursive,
                lastTrackId = restoreTrackId
            )
        )
        activeBrowsedFolderSource = source
        activeBrowsedFolderPath = folderPath
        tracksListOrigin = if (source != null) TracksListOrigin.FOLDER_BROWSER else TracksListOrigin.HOME
        activeQueueSource = source
        activeQueueFolderPath = folderPath
        activeQueueFolderName = folderName
        currentScreenTitle = folderName
        isDownloadedTracksScreen = true
        isTracksListVisible = true
        currentOpenUserPlaylist = null
        launchSafe {
            isLoading = true
            errorMessage = null
            resetPagination()
            try {
                val tracks = withContext(DispatcherIO) {
                    if (isRecursive) {
                        scanDownloadedTracks(folderPath)
                    } else {
                        localMediaManager.browseFolder(folderPath).tracks
                    }
                }
                if (tracks.isNotEmpty()) {
                    loadedTracks.clear()
                    loadedTracks.addAll(tracks)
                    allTrackIds = tracks.map { it.id }
                    canLoadMore = false
                    println("SearchViewModel: Восстановлена папка '$folderName' (рекурсивно: $isRecursive, ${tracks.size} треков)")
                    if (restoreTrackId != null) {
                        val target = tracks.firstOrNull { it.id == restoreTrackId || it.realId == restoreTrackId } ?: tracks.firstOrNull()
                        if (target != null) {
                            playbackManager.setInitialTrack(target)
                        }
                    }
                } else {
                    errorMessage = "В папке '$folderName' нет аудиофайлов."
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка чтения папки: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 🎵 Воспроизвести отдельный локальный трек и сформировать очередь из треков папки
     */
    fun playSingleLocalTrack(
        track: FullTrackInfo,
        folderTracks: List<FullTrackInfo> = listOf(track),
        folderName: String? = null,
        folderPath: String? = null,
        source: CustomMediaSource? = null
    ) {
        val title = folderName?.takeIf { it.isNotBlank() } ?: track.title
        if (!folderPath.isNullOrBlank()) {
            playbackSessionManager.recordSession(
                LastPlaybackSession(
                    type = LastPlaybackType.CUSTOM_FOLDER,
                    sourceId = source?.id,
                    path = folderPath,
                    title = title,
                    isRecursive = false,
                    lastTrackId = track.id,
                    lastTrackTitle = track.title,
                    lastArtistName = track.artists.firstOrNull()?.name,
                    lastCoverUri = track.coverUri
                )
            )
        } else if (source != null) {
            playbackSessionManager.recordSession(
                LastPlaybackSession(
                    type = LastPlaybackType.CUSTOM_SOURCE,
                    sourceId = source.id,
                    path = source.path,
                    title = source.name,
                    isRecursive = false,
                    lastTrackId = track.id,
                    lastTrackTitle = track.title,
                    lastArtistName = track.artists.firstOrNull()?.name,
                    lastCoverUri = track.coverUri
                )
            )
        }
        currentScreenTitle = title
        activeBrowsedFolderSource = source
        activeBrowsedFolderPath = folderPath
        tracksListOrigin = if (source != null) TracksListOrigin.FOLDER_BROWSER else TracksListOrigin.HOME
        activeQueueSource = source
        activeQueueFolderPath = folderPath
        activeQueueFolderName = title
        isDownloadedTracksScreen = true
        isTracksListVisible = true
        currentOpenUserPlaylist = null
        resetPagination()
        val queue = if (folderTracks.contains(track)) folderTracks else (listOf(track) + folderTracks)
        loadedTracks.clear()
        loadedTracks.addAll(queue)
        allTrackIds = queue.map { it.id }
        canLoadMore = false
        playTrack(
            trackId = track.id,
            trackTitle = track.title,
            artistName = track.artists.firstOrNull()?.name ?: "Unknown Artist",
            albumId = track.albums.firstOrNull()?.id
        )
    }

    /**
     * Загружает один чанк треков (для пагинации)
     */
    private suspend fun loadNextPageChunk(): Boolean {
        val nextChunk = allTrackIds.drop(currentOffset).take(pageSize)
        if (nextChunk.isNotEmpty()) {
            val details = repository.getTracksDetails(nextChunk)
            loadedTracks.addAll(details.result) // Дописываем новые треки в конец списка
            currentOffset += nextChunk.size

            // 🔥 Сохраняем обновленный список в локальный кеш плейлиста
            val cleanTitle = currentScreenTitle.removeSuffix(" [Офлайн-кеш]").trim()
            if (!isDownloadedTracksScreen && cleanTitle.isNotBlank() && cleanTitle != "Поиск" && cleanTitle != "Результаты поиска") {
                withContext(DispatcherIO) {
                    savePlaylistTracksCache(musicStoragePath, cleanTitle, loadedTracks.toList())
                }
            }
            canLoadMore = currentOffset < allTrackIds.size
            return true
        }
        canLoadMore = false
        return false
    }

    /**
     * Инициализирует пагинацию для нового списка ID
     */
    private fun startPagination(
        title: String = "Загружено треков",
        userPlaylist: PlaylistInfo? = null,
        restoreTrackId: String? = null,
        fetchIdsBlock: suspend () -> List<String>
    ) {
        if (isLoading) return
        isDownloadedTracksScreen = false
        isTracksListVisible = true
        tracksListOrigin = TracksListOrigin.HOME
        currentOpenUserPlaylist = userPlaylist
        currentScreenTitle = title
        activeBrowsedFolderSource = null
        activeBrowsedFolderPath = null
        activeQueueSource = null
        activeQueueFolderPath = null
        activeQueueFolderName = title
        launchSafe {
            isLoading = true
            errorMessage = null
            resetPagination()
            val cleanTitle = title.removeSuffix(" [Офлайн-кеш]").trim()
            try {
                allTrackIds = fetchIdsBlock()
                if (allTrackIds.isNotEmpty()) {
                    canLoadMore = true
                    loadNextPageChunk()
                    if (restoreTrackId != null && loadedTracks.isNotEmpty()) {
                        val target = loadedTracks.firstOrNull { it.id == restoreTrackId || it.realId == restoreTrackId }
                        if (target != null) {
                            playbackManager.setInitialTrack(target)
                        } else {
                            try {
                                val detail = repository.getTracksDetails(listOf(restoreTrackId)).result.firstOrNull()
                                if (detail != null) {
                                    playbackManager.setInitialTrack(detail)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    errorMessage = "Треков не найдено."
                }
            } catch (e: Exception) {
                // Если произошла сетевая ошибка или нет интернета — пробуем загрузить из кеша на диске
                println("SearchViewModel: Ошибка получения ID для '$cleanTitle', проверяем кеш на диске: ${e.message}")
                val cached = withContext(DispatcherIO) {
                    loadPlaylistTracksCache(musicStoragePath, cleanTitle)
                }
                if (cached.isNotEmpty()) {
                    loadedTracks.clear()
                    loadedTracks.addAll(cached)
                    allTrackIds = cached.map { it.id }
                    currentOffset = cached.size
                    canLoadMore = false
                    currentScreenTitle = "$cleanTitle [Офлайн-кеш]"
                    errorMessage = null
                    println("SearchViewModel: Загружено из кеша для '$cleanTitle' (${cached.size} треков)")
                    if (restoreTrackId != null) {
                        val target = cached.firstOrNull { it.id == restoreTrackId || it.realId == restoreTrackId }
                            ?: cached.firstOrNull()
                        if (target != null) {
                            playbackManager.setInitialTrack(target)
                        }
                    }
                } else {
                    errorMessage = "Не удалось подключиться к интернету и нет сохранённого кеша для '$cleanTitle'"
                }
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Догружает следующую пачку треков (вызывается при скролле)
     */
    fun loadNextPage() {
        if (!canLoadMore || (isLoading && currentOffset > 0)) return

        launchSafe {
            isLoading = true
            try {
                loadNextPageChunk()
            } catch (e: Exception) {
                val cleanTitle = currentScreenTitle.removeSuffix(" [Офлайн-кеш]").trim()
                if (loadedTracks.isEmpty()) {
                    val cached = withContext(DispatcherIO) {
                        loadPlaylistTracksCache(musicStoragePath, cleanTitle)
                    }
                    if (cached.isNotEmpty()) {
                        loadedTracks.clear()
                        loadedTracks.addAll(cached)
                        allTrackIds = cached.map { it.id }
                        currentOffset = cached.size
                        canLoadMore = false
                        currentScreenTitle = "$cleanTitle [Офлайн-кеш]"
                        errorMessage = null
                        return@launchSafe
                    }
                }
                errorMessage = e.message ?: e.toString()
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 📥 Постепенно загружает все оставшиеся страницы (как непрерывный скролл)
     */
    fun loadAllRemainingPages() {
        if (isLoadingAllPages || !canLoadMore) return

        launchSafe {
            isLoadingAllPages = true
            try {
                while (canLoadMore) {
                    loadNextPage()
                    // Ждем завершения загрузки текущей страницы
                    while (isLoading) {
                        delay(60)
                    }
                    delay(50)
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка при загрузке треков: ${e.message}"
            } finally {
                isLoadingAllPages = false
            }
        }
    }

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
    ) {
        launchSafe {
            try {
                val cleanArtist = track.artists.joinToString(", ") { it.name }.trim()
                val cleanTitle = track.title.trim()
                val defaultBaseTitle = if (cleanArtist.isNotEmpty()) "$cleanArtist — $cleanTitle" else cleanTitle
                val chosenBaseTitle = customFileName?.trim()?.ifBlank { null } ?: defaultBaseTitle

                storageStatusMessage = "⏳ Сохранение '$chosenBaseTitle' в папку..."

                // 1. Проверяем, есть ли трек уже локально на диске
                var localSrcPath: String? = null
                if (track.id.startsWith("local:")) {
                    val p = track.id.removePrefix("local:")
                    if (localFileExists(p)) localSrcPath = p
                }
                if (localSrcPath == null) {
                    val downloaded = downloadManager.getDownloadedTrackPath(cleanArtist, cleanTitle)
                    if (downloaded != null && localFileExists(downloaded)) {
                        localSrcPath = downloaded
                    }
                }
                if (localSrcPath == null) {
                    val currentPlayPath = currentPlayingFilePath?.removePrefix("local:")
                    if (currentPlayPath != null && (playerTrackId == track.id || playerTrackId == track.realId) && localFileExists(currentPlayPath)) {
                        localSrcPath = currentPlayPath
                    }
                }

                if (localSrcPath != null) {
                    val ext = localSrcPath.substringAfterLast('.', "mp3")
                    val rawName = if (chosenBaseTitle.endsWith(".$ext", ignoreCase = true)) {
                        chosenBaseTitle
                    } else {
                        "$chosenBaseTitle.$ext"
                    }
                    val fileName = sanitizeKeepSpaces(rawName)
                    val saved = withContext(DispatcherIO) {
                        copyFileToFolder(localSrcPath, targetDirectory, fileName)
                    }
                    if (saved != null) {
                        storageStatusMessage = "✅ Файл сохранён: $fileName"
                        onComplete?.invoke(saved)
                    } else {
                        storageStatusMessage = "❌ Ошибка копирования файла"
                        onComplete?.invoke(null)
                    }
                    return@launchSafe
                }

                // 2. Если трека нет на диске — скачиваем аудиопоток из Яндекс Музыки
                val quality = selectedQuality
                val audioData = repository.downloadTrackAudio(track.id, quality = quality)
                val rawExtension = audioData.type.substringBefore("-")
                val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension
                val rawName = if (chosenBaseTitle.endsWith(".$extension", ignoreCase = true)) {
                    chosenBaseTitle
                } else {
                    "$chosenBaseTitle.$extension"
                }
                val fileName = sanitizeKeepSpaces(rawName)

                val saved = withContext(DispatcherIO) {
                    saveTrackToFolder(targetDirectory, fileName, audioData.result)
                }

                if (saved != null) {
                    storageStatusMessage = "✅ Трек скачан и сохранён: $fileName"
                    onComplete?.invoke(saved)
                } else {
                    storageStatusMessage = "❌ Ошибка сохранения трека на диск"
                    onComplete?.invoke(null)
                }
            } catch (e: Exception) {
                storageStatusMessage = "❌ Ошибка: ${e.message ?: e.toString()}"
                onComplete?.invoke(null)
            }
        }
    }

    /**
     * 💾 Сохранить трек по конкретному выбранному пути файла (из системного Save As диалога)
     */
    fun saveTrackToFilePath(
        track: FullTrackInfo,
        targetFilePath: String,
        onComplete: ((String?) -> Unit)? = null
    ) {
        launchSafe {
            try {
                val cleanArtist = track.artists.joinToString(", ") { it.name }.trim()
                val cleanTitle = track.title.trim()
                val baseTitle = if (cleanArtist.isNotEmpty()) "$cleanArtist — $cleanTitle" else cleanTitle

                storageStatusMessage = "⏳ Сохранение '$baseTitle'..."

                // 1. Проверяем, есть ли трек уже локально на диске
                var localSrcPath: String? = null
                if (track.id.startsWith("local:")) {
                    val p = track.id.removePrefix("local:")
                    if (localFileExists(p)) localSrcPath = p
                }
                if (localSrcPath == null) {
                    val downloaded = downloadManager.getDownloadedTrackPath(cleanArtist, cleanTitle)
                    if (downloaded != null && localFileExists(downloaded)) {
                        localSrcPath = downloaded
                    }
                }
                if (localSrcPath == null) {
                    val currentPlayPath = currentPlayingFilePath?.removePrefix("local:")
                    if (currentPlayPath != null && (playerTrackId == track.id || playerTrackId == track.realId) && localFileExists(currentPlayPath)) {
                        localSrcPath = currentPlayPath
                    }
                }

                if (localSrcPath != null) {
                    val ext = localSrcPath.substringAfterLast('.', "mp3")
                    val normalizedPath = if (targetFilePath.substringAfterLast('/', "").contains('.')) {
                        targetFilePath
                    } else {
                        "$targetFilePath.$ext"
                    }
                    val saved = withContext(DispatcherIO) {
                        copyFileToDirectPath(localSrcPath, normalizedPath)
                    }
                    if (saved != null) {
                        val fileName = saved.substringAfterLast('/', saved.substringAfterLast('\\', saved))
                        storageStatusMessage = "✅ Файл сохранён: $fileName"
                        onComplete?.invoke(saved)
                    } else {
                        storageStatusMessage = "❌ Ошибка копирования файла"
                        onComplete?.invoke(null)
                    }
                    return@launchSafe
                }

                // 2. Если трека нет на диске — скачиваем аудиопоток из Яндекс Музыки
                val quality = selectedQuality
                val audioData = repository.downloadTrackAudio(track.id, quality = quality)
                val rawExtension = audioData.type.substringBefore("-")
                val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension

                val normalizedPath = if (targetFilePath.substringAfterLast('/', "").contains('.')) {
                    targetFilePath
                } else {
                    "$targetFilePath.$extension"
                }

                val saved = withContext(DispatcherIO) {
                    saveFileToDirectPath(normalizedPath, audioData.result)
                }

                if (saved != null) {
                    val fileName = saved.substringAfterLast('/', saved.substringAfterLast('\\', saved))
                    storageStatusMessage = "✅ Трек скачан и сохранён: $fileName"
                    onComplete?.invoke(saved)
                } else {
                    storageStatusMessage = "❌ Ошибка сохранения трека на диск"
                    onComplete?.invoke(null)
                }
            } catch (e: Exception) {
                storageStatusMessage = "❌ Ошибка: ${e.message ?: e.toString()}"
                onComplete?.invoke(null)
            }
        }
    }

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
        val effectiveTrackId = when {
            trackId.startsWith("local:") || trackId.startsWith("/") || trackId.startsWith("~") || (trackId.length > 2 && trackId[1] == ':') -> trackId
            foundTrack?.realId != null -> foundTrack.realId!!
            foundTrack?.id?.startsWith("local:") == true -> foundTrack.id
            else -> trackId
        }
        val playingPath = currentPlayingFilePath?.removePrefix("local:")
        val isCurrentlyPlayingThis = (playerTrackId == trackId || playerTrackId?.removePrefix("local:") == cleanId || playingPath == cleanId)
        val finalTrackId = if (isCurrentlyPlayingThis && playingPath != null && !effectiveTrackId.startsWith("/") && !effectiveTrackId.startsWith("local:") && !effectiveTrackId.startsWith("~") && !(effectiveTrackId.length > 2 && effectiveTrackId[1] == ':')) {
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


    private fun resetPagination() {
        loadedTracks.clear()
        allTrackIds = emptyList()
        currentOffset = 0
        canLoadMore = false
        isLoadingAllPages = false
    }

    fun closeArtistTracks() {
        isDownloadedTracksScreen = false
        isTracksListVisible = false
        tracksListOrigin = TracksListOrigin.HOME
        resetBrowsedFolder()
        if (searchResult != null) {
            clearSearch()
        }
    }

    /**
     * 🎶 Переключить отображение экрана очереди воспроизведения
     */
    fun toggleQueueView() {
        if (isTracksListVisible) {
            isTracksListVisible = false
            tracksListOrigin = TracksListOrigin.HOME
        } else if (loadedTracks.isNotEmpty()) {
            if (activeQueueFolderName != null && (currentScreenTitle.isBlank() || currentScreenTitle == "Загружено треков")) {
                currentScreenTitle = activeQueueFolderName ?: "Очередь воспроизведения"
            }
            tracksListOrigin = TracksListOrigin.HOME
            isTracksListVisible = true
        }
    }

    /**
     * 🎶 Открыть экран очереди воспроизведения
     */
    fun openQueueView(origin: TracksListOrigin = TracksListOrigin.HOME) {
        if (loadedTracks.isNotEmpty()) {
            if (activeQueueFolderName != null && (currentScreenTitle.isBlank() || currentScreenTitle == "Загружено треков")) {
                currentScreenTitle = activeQueueFolderName ?: "Очередь воспроизведения"
            }
            tracksListOrigin = origin
            isTracksListVisible = true
        }
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
    fun playTrackByExternalId(trackId: String) {
        launchSafe {
            try {
                val response = repository.getTracksDetails(listOf(trackId))
                val trackInfo = response.result.firstOrNull()
                if (trackInfo != null) {
                    if (loadedTracks.none { it.id == trackInfo.id }) {
                        loadedTracks.add(0, trackInfo)
                    }
                    if (!allTrackIds.contains(trackInfo.id)) {
                        allTrackIds = listOf(trackInfo.id) + allTrackIds
                    }
                    val artistName = trackInfo.artists.firstOrNull()?.name ?: "Unknown Artist"
                    val albumId = trackInfo.albums.firstOrNull()?.id
                    playTrack(
                        trackId = trackInfo.id,
                        trackTitle = trackInfo.title,
                        artistName = artistName,
                        albumId = albumId,
                        isManualSelection = true
                    )
                } else {
                    errorMessage = "Трек не найден: $trackId"
                }
            } catch (e: Exception) {
                errorMessage = "Не удалось загрузить трек: ${e.message}"
            }
        }
    }

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
        playbackManager.playNextTrack(currentId, source)
    }

    fun playPrevTrack(currentId: String? = playerTrackId, source: TrackPlaySource = TrackPlaySource.PREV) {
        playbackManager.playPrevTrack(currentId, source)
    }

    override fun onCleared() {
        super.onCleared()
        playbackManager.release()
    }
}

