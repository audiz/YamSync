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
        private set
    var searchResult by mutableStateOf<YandexMusicResponse?>(null)
        private set

    // 📊 Список уже загруженных детальных треков
    var loadedTracks = mutableStateListOf<FullTrackInfo>()
        private set

    // 📁 Флаг экрана плейлиста загруженной музыки
    var isDownloadedTracksScreen by mutableStateOf(false)
        private set

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
        private set

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

    fun saveQuality(quality: String) {
        settingsManager.saveQuality(quality)
    }

    fun saveTheme(theme: String) {
        settingsManager.saveTheme(theme)
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

    /** Проверяет, сохранен ли текущий воспроизводимый трек в библиотеке на диске */
    val isCurrentTrackSavedToDisk: Boolean get() = downloadManager.isCurrentTrackSavedToDisk

    /** Проверяет, идет ли скачивание текущего трека в библиотеку прямо сейчас */
    val isCurrentTrackSavingToDisk: Boolean get() = downloadManager.isCurrentTrackSavingToDisk

    /** Скачать текущий трек на диск или удалить его из библиотеки, если уже скачан */
    fun toggleCurrentTrackSaveOrDelete() = downloadManager.toggleCurrentTrackSaveOrDelete()

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
        val trackId = playerTrackId ?: currentWaveTrack?.id ?: return
        val albumId = playerAlbumId ?: currentWaveTrack?.albums?.firstOrNull()?.id
        playlistManager.toggleLike(trackId, albumId)
    }

    /** Переключить дизлайк для текущего воспроизводимого трека */
    fun toggleDislikeCurrentTrack() {
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
        onStopPlayback = { stopPlayback() },
        onTrackDeleted = { trackId ->
            if (isDownloadedTracksScreen) {
                loadedTracks.removeAll { it.id == trackId }
                allTrackIds = allTrackIds.filter { it != trackId }
            }
        },
        onAllTracksCleared = {
            if (isDownloadedTracksScreen) {
                loadedTracks.clear()
                allTrackIds = emptyList()
            }
        },
        onError = { errorMessage = it },
        onStatusMessage = { storageStatusMessage = it }
    )

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
            loadInitialWave(autoPlay = false)
        } else {
            loadPersonalPlaylists()
            loadUserPlaylists()
        }
    }

    fun fetchYnisonSession() {
        waveManager.loadInitialWave(autoPlay = false)
    }

    fun loadInitialWave(autoPlay: Boolean = false, source: TrackPlaySource = TrackPlaySource.PLAY) {
        waveManager.loadInitialWave(autoPlay, source)
    }

    fun playWaveTrack(index: Int = waveCurrentIndex, source: TrackPlaySource = TrackPlaySource.PLAY) {
        waveManager.playWaveTrack(index, source)
    }

    fun playPrevWaveTrack() {
        waveManager.playPrevWaveTrack()
    }

    fun playNextWaveTrack() {
        waveManager.playNextWaveTrack()
    }

    fun togglePlayPauseWave() {
        waveManager.togglePlayPauseWave()
    }

    fun skipWaveTrack() {
        waveManager.skipWaveTrack()
    }

    fun startThematicWave(title: String?, seeds: List<String>) {
        waveManager.startThematicWave(title, seeds)
    }

    fun refreshDefaultThematicWaves() {
        waveManager.refreshDefaultThematicWaves()
    }

    fun resetToDefaultWave() {
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
    fun loadDownloadedTracksPlaylist() {
        if (isLoading) return
        currentScreenTitle = "Загруженная музыка"
        isDownloadedTracksScreen = true
        currentOpenUserPlaylist = null
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

    fun loadArtistTracks(artistId: String, artistName: String? = null) {
        val title = if (!artistName.isNullOrBlank()) "Треки: $artistName" else "Треки исполнителя"
        startPagination(title = title) { repository.getTrackIds(artistId).result }
    }

    /**
     * 🎵 Загрузка треков плейлиста из поиска или каталога
     */
    fun loadPlaylist(playlist: PlaylistInfo) {
        val title = playlist.title.ifBlank { "Плейлист" }
        val isUserPlaylist = userPlaylists.any { it.kind == playlist.kind }
        startPagination(title = title, userPlaylist = if (isUserPlaylist) playlist else null) {
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

    fun loadPlaylistTracks(uid: Long, kind: Long, playlistTitle: String? = null) {
        val title = if (!playlistTitle.isNullOrBlank()) playlistTitle else "Треки плейлиста"
        startPagination(title = title) { repository.getPlaylistTrackIds(uid, kind) }
    }

    /**
     * 🔥 Загрузка плейлиста по UUID (для персональных плейлистов)
     */
    fun loadPlaylistByUuid(uuid: String, playlistTitle: String? = null) {
        startPagination(title = playlistTitle ?: "Плейлист") {
            repository.getPlaylistTrackIdsByUuid(uuid)
        }
    }

    /**
     * 🔥 Загрузка плейлиста "Мне нравится" (двухэтапный запрос)
     */
    fun loadLikesPlaylist() {
        startPagination(title = "Мне нравится") {
            val uuid = repository.getLikesPlaylistUuid()
            val ids = repository.getPlaylistTrackIdsByUuid(uuid)
            likedTrackIds.addAll(ids)
            ids
        }
    }

    /**
     * 🔥 Загрузка истории прослушивания
     */
    fun loadHistory() {
        startPagination(title = "История прослушиваний") {
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
    fun openLocalPlaylist(playlist: LocalPlaylist) {
        if (isLoading) return
        currentScreenTitle = playlist.title
        isDownloadedTracksScreen = true
        currentOpenUserPlaylist = null
        launchSafe {
            isLoading = true
            errorMessage = null
            resetPagination()
            try {
                val tracks = withContext(DispatcherIO) {
                    getTracksFromLocalPaths(playlist.trackPaths)
                }
                if (tracks.isNotEmpty()) {
                    loadedTracks.addAll(tracks)
                    allTrackIds = tracks.map { it.id }
                    canLoadMore = false
                    println("SearchViewModel: Открыт локальный плейлист '${playlist.title}' (${tracks.size} треков)")
                } else {
                    errorMessage = "В плейлисте '${playlist.title}' нет файлов или они были удалены с диска."
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
     * Инициализирует пагинацию для нового списка ID
     */
    private fun startPagination(
        title: String = "Загружено треков",
        userPlaylist: PlaylistInfo? = null,
        fetchIdsBlock: suspend () -> List<String>
    ) {
        if (isLoading) return
        isDownloadedTracksScreen = false
        currentOpenUserPlaylist = userPlaylist
        currentScreenTitle = title
        launchSafe {
            isLoading = true
            errorMessage = null
            resetPagination()
            val cleanTitle = title.removeSuffix(" [Офлайн-кеш]").trim()
            try {
                allTrackIds = fetchIdsBlock()
                if (allTrackIds.isNotEmpty()) {
                    canLoadMore = true
                    loadNextPage() // Загружаем первую порцию
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
                }
                canLoadMore = currentOffset < allTrackIds.size
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
     * 🗑️ Удалить трек с диска
     */
    fun deleteTrack(trackId: String, trackTitle: String, artistName: String) =
        downloadManager.deleteTrack(trackId, trackTitle, artistName)

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
        resetPagination()
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
        if (!isWave) {
            waveManager.resetWaveMode()
        }
        val targetCoverUri = loadedTracks.firstOrNull { it.id == trackId }?.coverUri
            ?: searchResult?.result?.results?.firstOrNull { it.track?.id == trackId }?.track?.coverUri
            ?: waveManager.currentWaveTrack?.takeIf { it.id == trackId }?.coverUri

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

