package io.github.audiz.tracklist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.audiz.DispatcherIO
import io.github.audiz.TrackPlaySource
import io.github.audiz.TracksListOrigin
import io.github.audiz.api.MusicRepository
import io.github.audiz.getTracksFromLocalPaths
import io.github.audiz.loadPlaylistTracksCache
import io.github.audiz.models.*
import io.github.audiz.player.PlaybackManager
import io.github.audiz.player.PlaybackSessionManager
import io.github.audiz.playlist.PlaylistManager
import io.github.audiz.local.LocalMediaManager
import io.github.audiz.savePlaylistTracksCache
import io.github.audiz.scanDownloadedTracks
import io.github.audiz.util.AppLogger
import kotlinx.coroutines.*

/**
 * 📋 Координатор списка треков, очередей, пагинации и локального кэша плейлистов.
 * Инкапсулирует:
 * - Загрузку треков исполнителя, альбомов, плейлистов (включая UUID/UID/Kind), лайков и истории.
 * - Локальный дисковый кэш треков для мгновенного отклика и оффлайн-доступа.
 * - Пагинацию (порционная подгрузка чанками по 100 треков при скролле).
 * - Воспроизведение и открытие локальных папок, источников и отдельных аудиофайлов.
 * - Восстановление предыдущей сессии плейлиста.
 */
class TrackListCoordinator(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
    private val playbackSessionManager: PlaybackSessionManager,
    private val playlistManager: PlaylistManager,
    private val localMediaManager: LocalMediaManager,
    private val getMusicStoragePath: () -> String,
    private val getSearchResult: () -> YandexMusicResponse?,
    private val onPlayTrack: (
        trackId: String,
        trackTitle: String,
        artistName: String,
        albumId: Long?,
        isManualSelection: Boolean,
        isWave: Boolean,
        source: TrackPlaySource
    ) -> Unit,
    private val onSetTracksListVisible: (Boolean) -> Unit,
    private val onLoadingChanged: (Boolean) -> Unit,
    private val onError: (String?) -> Unit
) {
    // 📊 Список уже загруженных детальных треков
    val loadedTracks = mutableStateListOf<FullTrackInfo>()

    // 📜 Список всех ID треков, которые нужно загрузить частями
    var allTrackIds by mutableStateOf(listOf<String>())
        private set

    val totalTracksCount: Int
        get() = allTrackIds.size.coerceAtLeast(loadedTracks.size)

    var canLoadMore by mutableStateOf(false)
        private set

    var isLoadingAllPages by mutableStateOf(false)
        private set

    // 📁 Флаг экрана плейлиста загруженной музыки (локальные файлы на диске)
    var isDownloadedTracksScreen by mutableStateOf(false)

    var currentScreenTitle by mutableStateOf("Загружено треков")

    var tracksListOrigin by mutableStateOf(TracksListOrigin.HOME)

    // 📁 Контекст текущего открытого проводника по локальным папкам (для возврата в ту же папку)
    var activeBrowsedFolderSource by mutableStateOf<CustomMediaSource?>(null)
    var activeBrowsedFolderPath by mutableStateOf<String?>(null)

    // 🎵 Источник текущей активной очереди треков
    var activeQueueSource by mutableStateOf<CustomMediaSource?>(null)
    var activeQueueFolderPath by mutableStateOf<String?>(null)
    var activeQueueFolderName by mutableStateOf<String?>(null)

    private val pageSize = 100
    private var currentOffset = 0
    private var isChunkLoading = false
    private var currentCacheKey: String? = null
    private var paginationJob: Job? = null
    private var isCoordinatorLoading = false

    fun resetBrowsedFolder() {
        activeBrowsedFolderSource = null
        activeBrowsedFolderPath = null
        tracksListOrigin = TracksListOrigin.HOME
    }

    fun resetPagination() {
        loadedTracks.clear()
        allTrackIds = emptyList()
        currentOffset = 0
        canLoadMore = false
        isLoadingAllPages = false
    }

    fun clearAll() {
        loadedTracks.clear()
        allTrackIds = emptyList()
    }

    fun removeTrack(trackId: String) {
        val cleanId = trackId.removePrefix("local:")
        loadedTracks.removeAll {
            it.id == trackId || it.realId == trackId ||
            it.id.removePrefix("local:") == cleanId ||
            it.realId?.removePrefix("local:") == cleanId
        }
        allTrackIds = allTrackIds.filter {
            it != trackId && it.removePrefix("local:") != cleanId
        }
    }

    fun removeTrackFromLoaded(predicate: (FullTrackInfo) -> Boolean) {
        loadedTracks.removeAll(predicate)
        allTrackIds = loadedTracks.map { it.id }
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job {
        return scope.launch(Dispatchers.Main.immediate) {
            try {
                block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLogger.e("TrackListCoordinator", "❌ Ошибка в корутине: ${t.message}")
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        isCoordinatorLoading = loading
        onLoadingChanged(loading)
    }

    /**
     * Загружает один чанк треков (для пагинации)
     */
    suspend fun loadNextPageChunk(): Boolean {
        if (isChunkLoading) return false
        isChunkLoading = true
        return try {
            val nextChunk = allTrackIds.drop(currentOffset).take(pageSize)
            if (nextChunk.isNotEmpty()) {
                val details = withContext(DispatcherIO) {
                    repository.getTracksDetails(nextChunk)
                }
                val existingIds = loadedTracks.map { it.id }.toSet()
                val uniqueNewTracks = details.result.filter { it.id !in existingIds }
                loadedTracks.addAll(uniqueNewTracks)
                currentOffset += nextChunk.size

                val cleanTitle = currentScreenTitle.removeSuffix(" [Офлайн-кеш]").trim()
                val resolvedCacheKey = currentCacheKey ?: cleanTitle
                if (!isDownloadedTracksScreen && resolvedCacheKey.isNotBlank() && resolvedCacheKey != "Поиск" && resolvedCacheKey != "Результаты поиска") {
                    withContext(DispatcherIO) {
                        savePlaylistTracksCache(getMusicStoragePath(), resolvedCacheKey, loadedTracks.toList())
                    }
                }
                canLoadMore = currentOffset < allTrackIds.size
                true
            } else {
                canLoadMore = false
                false
            }
        } finally {
            isChunkLoading = false
        }
    }

    /**
     * Инициализирует пагинацию для нового списка ID или списка с полными метаданными треков
     */
    fun startPagination(
        title: String = "Загружено треков",
        userPlaylist: PlaylistInfo? = null,
        restoreTrackId: String? = null,
        origin: TracksListOrigin? = null,
        cacheKey: String? = null,
        fetchIdsAndTracksBlock: (suspend () -> Pair<List<String>, List<FullTrackInfo>>)? = null,
        fetchIdsBlock: (suspend () -> List<String>)? = null
    ) {
        paginationJob?.cancel()
        isDownloadedTracksScreen = false
        onSetTracksListVisible(true)
        tracksListOrigin = origin ?: if (getSearchResult() != null) TracksListOrigin.SEARCH else TracksListOrigin.HOME
        playlistManager.currentOpenUserPlaylist = userPlaylist
        currentScreenTitle = title
        val cleanTitle = title.removeSuffix(" [Офлайн-кеш]").trim()
        val resolvedCacheKey = cacheKey ?: cleanTitle
        currentCacheKey = resolvedCacheKey
        activeBrowsedFolderSource = null
        activeBrowsedFolderPath = null
        activeQueueSource = null
        activeQueueFolderPath = null
        activeQueueFolderName = title

        resetPagination()
        setLoading(true)

        paginationJob = launchSafe {
            onError(null)
            yield()
            try {
                if (resolvedCacheKey.isNotBlank() && resolvedCacheKey != "Поиск" && resolvedCacheKey != "Результаты поиска") {
                    val cached = withContext(DispatcherIO) {
                        try {
                            loadPlaylistTracksCache(getMusicStoragePath(), resolvedCacheKey)
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    }
                    if (cached.isNotEmpty() && loadedTracks.isEmpty()) {
                        loadedTracks.addAll(cached)
                        allTrackIds = cached.map { it.id }
                        currentOffset = cached.size
                        canLoadMore = false
                        if (restoreTrackId != null) {
                            val target = cached.firstOrNull { it.id == restoreTrackId || it.realId == restoreTrackId } ?: cached.firstOrNull()
                            if (target != null) {
                                playbackManager.setInitialTrack(target)
                            }
                        }
                    }
                }

                if (fetchIdsAndTracksBlock != null) {
                    val (ids, richTracks) = fetchIdsAndTracksBlock()
                    allTrackIds = ids
                    if (title == "Мне нравится") {
                        playlistManager.likedTrackIds.addAll(ids.map { it.removePrefix("local:").substringBefore(":") })
                    }
                    if (richTracks.isNotEmpty()) {
                        loadedTracks.clear()
                        loadedTracks.addAll(richTracks)
                        currentOffset = richTracks.size
                        canLoadMore = currentOffset < allTrackIds.size
                        if (resolvedCacheKey.isNotBlank() && resolvedCacheKey != "Поиск" && resolvedCacheKey != "Результаты поиска") {
                            withContext(DispatcherIO) {
                                savePlaylistTracksCache(getMusicStoragePath(), resolvedCacheKey, loadedTracks.toList())
                            }
                        }
                    } else if (allTrackIds.isNotEmpty()) {
                        currentOffset = 0
                        loadNextPageChunk()
                        canLoadMore = currentOffset < allTrackIds.size
                    } else {
                        if (loadedTracks.isEmpty()) {
                            onError("Треков не найдено.")
                        }
                    }
                } else if (fetchIdsBlock != null) {
                    allTrackIds = fetchIdsBlock()
                    if (allTrackIds.isNotEmpty()) {
                        val validCached = loadedTracks.filter { it.id in allTrackIds || (it.realId != null && it.realId in allTrackIds) }
                        loadedTracks.clear()
                        if (validCached.isNotEmpty()) {
                            loadedTracks.addAll(validCached)
                            currentOffset = validCached.size
                            canLoadMore = currentOffset < allTrackIds.size
                        } else {
                            currentOffset = 0
                            loadNextPageChunk()
                            canLoadMore = currentOffset < allTrackIds.size
                        }
                    } else {
                        if (loadedTracks.isEmpty()) {
                            onError("Треков не найдено.")
                        }
                    }
                }

                if (restoreTrackId != null && loadedTracks.isNotEmpty()) {
                    val target = loadedTracks.firstOrNull { it.id == restoreTrackId || it.realId == restoreTrackId }
                    if (target != null) {
                        playbackManager.setInitialTrack(target)
                    } else {
                        try {
                            val detail = withContext(DispatcherIO) {
                                repository.getTracksDetails(listOf(restoreTrackId)).result.firstOrNull()
                            }
                            if (detail != null) {
                                playbackManager.setInitialTrack(detail)
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                println("TrackListCoordinator: Ошибка загрузки плейлиста '$cleanTitle': ${e.message}")
                if (loadedTracks.isEmpty()) {
                    val fallbackCached = withContext(DispatcherIO) {
                        loadPlaylistTracksCache(getMusicStoragePath(), resolvedCacheKey)
                    }
                    if (fallbackCached.isNotEmpty()) {
                        loadedTracks.clear()
                        loadedTracks.addAll(fallbackCached)
                        allTrackIds = fallbackCached.map { it.id }
                        currentOffset = fallbackCached.size
                        canLoadMore = false
                        currentScreenTitle = "$cleanTitle [Офлайн-кеш]"
                        onError(null)
                    } else {
                        onError("Не удалось подключиться к интернету и нет сохранённого кеша для '$cleanTitle'")
                    }
                }
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * Догружает следующую пачку треков (вызывается при скролле)
     */
    fun loadNextPage() {
        if (!canLoadMore || isCoordinatorLoading || isChunkLoading) return

        launchSafe {
            setLoading(true)
            try {
                loadNextPageChunk()
            } catch (e: Exception) {
                val cleanTitle = currentScreenTitle.removeSuffix(" [Офлайн-кеш]").trim()
                val resolvedCacheKey = currentCacheKey ?: cleanTitle
                if (loadedTracks.isEmpty()) {
                    val cached = withContext(DispatcherIO) {
                        loadPlaylistTracksCache(getMusicStoragePath(), resolvedCacheKey)
                    }
                    if (cached.isNotEmpty()) {
                        loadedTracks.clear()
                        loadedTracks.addAll(cached)
                        allTrackIds = cached.map { it.id }
                        currentOffset = cached.size
                        canLoadMore = false
                        currentScreenTitle = "$cleanTitle [Офлайн-кеш]"
                        onError(null)
                        return@launchSafe
                    }
                }
                onError(e.message ?: e.toString())
            } finally {
                setLoading(false)
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
                    while (isCoordinatorLoading) {
                        delay(60)
                    }
                    delay(50)
                }
            } catch (e: Exception) {
                onError("Ошибка при загрузке треков: ${e.message}")
            } finally {
                isLoadingAllPages = false
            }
        }
    }

    fun loadDownloadedTracksPlaylist(restoreTrackId: String? = null) {
        if (isCoordinatorLoading) return
        currentScreenTitle = "Загруженная музыка"
        isDownloadedTracksScreen = true
        onSetTracksListVisible(true)
        tracksListOrigin = TracksListOrigin.HOME
        playlistManager.currentOpenUserPlaylist = null
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
            setLoading(true)
            onError(null)
            resetPagination()
            try {
                val tracks = withContext(DispatcherIO) {
                    scanDownloadedTracks(getMusicStoragePath())
                }
                if (tracks.isNotEmpty()) {
                    loadedTracks.addAll(tracks)
                    allTrackIds = tracks.map { it.id }
                    canLoadMore = false
                    println("TrackListCoordinator: Загружено с диска ${tracks.size} треков без сетевых запросов")
                    if (restoreTrackId != null) {
                        val target = tracks.firstOrNull { it.id == restoreTrackId } ?: tracks.firstOrNull()
                        if (target != null) {
                            playbackManager.setInitialTrack(target)
                        }
                    }
                } else {
                    onError("На диске не найдено аудиофайлов в папке:\n${getMusicStoragePath()}")
                }
            } catch (e: Exception) {
                onError("Ошибка чтения файлов с диска: ${e.message}")
                AppLogger.e("TrackListCoordinator", "Ошибка чтения файлов с диска: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    fun loadArtistTracks(artistId: String, artistName: String? = null, restoreTrackId: String? = null) {
        val title = if (!artistName.isNullOrBlank()) "Треки: $artistName" else "Треки исполнителя"
        val cacheKey = "artist_$artistId"
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.ARTIST,
                artistId = artistId,
                artistName = artistName,
                title = title,
                lastTrackId = restoreTrackId
            )
        )
        startPagination(title = title, cacheKey = cacheKey, restoreTrackId = restoreTrackId) {
            withContext(DispatcherIO) {
                repository.getTrackIds(artistId).result
            }
        }
    }

    /**
     * 🎵 Загрузка треков плейлиста из поиска или каталога
     */
    fun loadPlaylist(playlist: PlaylistInfo, restoreTrackId: String? = null) {
        val title = playlist.title.ifBlank { "Плейлист" }
        val isUserPlaylist = playlistManager.userPlaylists.any { it.kind == playlist.kind }
        val kind = playlist.kind
        val uuid = playlist.playlistUuid
        val cacheKey = when {
            kind != null && kind > 0L && playlist.uid > 0L -> "playlist_${playlist.uid}_${kind}"
            !uuid.isNullOrBlank() -> "playlist_$uuid"
            else -> "playlist_${playlist.title}"
        }
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
        if (kind != null && kind > 0L && playlist.uid > 0L) {
            startPagination(
                title = title,
                cacheKey = cacheKey,
                userPlaylist = if (isUserPlaylist) playlist else null,
                restoreTrackId = restoreTrackId,
                fetchIdsAndTracksBlock = {
                    withContext(DispatcherIO) {
                        repository.getPlaylistWithTracksByUidKind(playlist.uid, kind)
                    }
                }
            )
        } else if (!uuid.isNullOrBlank()) {
            startPagination(
                title = title,
                cacheKey = cacheKey,
                userPlaylist = if (isUserPlaylist) playlist else null,
                restoreTrackId = restoreTrackId,
                fetchIdsAndTracksBlock = {
                    withContext(DispatcherIO) {
                        try {
                            repository.getPlaylistWithTracksByUuid(uuid)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            val fallbackKind = playlist.kind ?: 0L
                            repository.getPlaylistWithTracksByUidKind(playlist.uid, fallbackKind)
                        }
                    }
                }
            )
        } else {
            val fallbackKind = kind ?: 0L
            startPagination(
                title = title,
                cacheKey = cacheKey,
                userPlaylist = if (isUserPlaylist) playlist else null,
                restoreTrackId = restoreTrackId,
                fetchIdsAndTracksBlock = {
                    withContext(DispatcherIO) {
                        repository.getPlaylistWithTracksByUidKind(playlist.uid, fallbackKind)
                    }
                }
            )
        }
    }

    /**
     * 💿 Загрузка треков альбома из поиска
     */
    fun loadAlbumTracks(albumId: Long, albumTitle: String, restoreTrackId: String? = null) {
        val title = if (albumTitle.isNotBlank()) "Альбом: $albumTitle" else "Альбом"
        val cacheKey = "album_$albumId"
        startPagination(
            title = title,
            cacheKey = cacheKey,
            restoreTrackId = restoreTrackId,
            origin = if (getSearchResult() != null) TracksListOrigin.SEARCH else TracksListOrigin.HOME,
            fetchIdsAndTracksBlock = {
                withContext(DispatcherIO) {
                    repository.getAlbumWithTracks(albumId)
                }
            }
        )
    }

    fun loadPlaylistTracks(uid: Long, kind: Long, playlistTitle: String? = null, restoreTrackId: String? = null) {
        val title = if (!playlistTitle.isNullOrBlank()) playlistTitle else "Треки плейлиста"
        val cacheKey = "playlist_${uid}_${kind}"
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.YANDEX_USER_PLAYLIST,
                uid = uid,
                kind = kind,
                title = title,
                lastTrackId = restoreTrackId
            )
        )
        startPagination(
            title = title,
            cacheKey = cacheKey,
            restoreTrackId = restoreTrackId,
            fetchIdsAndTracksBlock = {
                withContext(DispatcherIO) {
                    repository.getPlaylistWithTracksByUidKind(uid, kind)
                }
            }
        )
    }

    /**
     * 🔥 Быстрая загрузка плейлиста по UUID (для персональных подборок Яндекса)
     */
    fun loadPlaylistByUuid(uuid: String, playlistTitle: String? = null, restoreTrackId: String? = null) {
        val title = playlistTitle ?: "Плейлист"
        val cacheKey = "playlist_$uuid"
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.YANDEX_UUID,
                uuid = uuid,
                title = title,
                lastTrackId = restoreTrackId
            )
        )
        startPagination(
            title = title,
            cacheKey = cacheKey,
            restoreTrackId = restoreTrackId,
            fetchIdsAndTracksBlock = {
                withContext(DispatcherIO) {
                    repository.getPlaylistWithTracksByUuid(uuid)
                }
            }
        )
    }

    /**
     * 🔥 Загрузка плейлиста "Мне нравится"
     */
    fun loadLikesPlaylist(restoreTrackId: String? = null) {
        playbackSessionManager.recordSession(
            LastPlaybackSession(
                type = LastPlaybackType.YANDEX_LIKES,
                title = "Мне нравится",
                lastTrackId = restoreTrackId
            )
        )
        startPagination(
            title = "Мне нравится",
            cacheKey = "likes",
            restoreTrackId = restoreTrackId,
            fetchIdsAndTracksBlock = {
                withContext(DispatcherIO) {
                    val uuid = repository.getLikesPlaylistUuid()
                    repository.getPlaylistWithTracksByUuid(uuid)
                }
            }
        )
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
        startPagination(
            title = "История прослушиваний",
            cacheKey = "history",
            restoreTrackId = restoreTrackId
        ) {
            withContext(DispatcherIO) {
                repository.getHistoryTrackIds()
            }
        }
    }

    fun openLocalPlaylist(
        playlist: LocalPlaylist,
        restoreTrackId: String? = null,
        autoPlayFirst: Boolean = false
    ) {
        if (isCoordinatorLoading) return
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
        onSetTracksListVisible(true)
        tracksListOrigin = TracksListOrigin.HOME
        playlistManager.currentOpenUserPlaylist = null
        activeBrowsedFolderSource = null
        activeBrowsedFolderPath = null
        activeQueueSource = null
        activeQueueFolderPath = null
        activeQueueFolderName = playlist.title
        launchSafe {
            setLoading(true)
            onError(null)
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
                    println("TrackListCoordinator: Открыт локальный плейлист '${playlist.title}' (${tracks.size} треков)")
                    if (tracks.size < playlist.trackPaths.size) {
                        println("TrackListCoordinator: Найдено ${tracks.size} из ${playlist.trackPaths.size} треков плейлиста '${playlist.title}'")
                    }
                    if (autoPlayFirst) {
                        val target = restoreTrackId?.let { rid ->
                            tracks.firstOrNull { it.id == rid || it.realId == rid }
                        } ?: tracks.first()
                        onPlayTrack(
                            target.id,
                            target.title,
                            target.artists.firstOrNull()?.name ?: "",
                            null,
                            true,
                            false,
                            TrackPlaySource.PLAY
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
                        onError("Плейлист '${playlist.title}' пуст.")
                    } else {
                        onError("В плейлисте '${playlist.title}' ${playlist.trackPaths.size} трек(ов), но файлы еще не скачаны на это устройство. Откройте YamSync («Синхронизация») для быстрой загрузки файлов.")
                    }
                }
            } catch (e: Exception) {
                onError("Ошибка чтения плейлиста: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    fun openCustomSource(source: CustomMediaSource, restoreTrackId: String? = null) {
        if (isCoordinatorLoading) return
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
        onSetTracksListVisible(true)
        tracksListOrigin = TracksListOrigin.HOME
        playlistManager.currentOpenUserPlaylist = null
        val isDir = source.type == LocalSourceType.FOLDER
        activeBrowsedFolderSource = null
        activeBrowsedFolderPath = null
        activeQueueSource = if (isDir) source else null
        activeQueueFolderPath = if (isDir) source.path else null
        activeQueueFolderName = source.name
        launchSafe {
            setLoading(true)
            onError(null)
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
                    println("TrackListCoordinator: Открыт пользовательский источник '${source.name}' (${tracks.size} треков)")
                    if (restoreTrackId != null) {
                        val target = tracks.firstOrNull { it.id == restoreTrackId || it.realId == restoreTrackId } ?: tracks.firstOrNull()
                        if (target != null) {
                            playbackManager.setInitialTrack(target)
                        }
                    }
                } else {
                    onError("В источнике '${source.name}' нет доступных аудиофайлов.")
                }
            } catch (e: Exception) {
                onError("Ошибка чтения источника: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    fun browseFolder(folderPath: String, rootPath: String? = null): FolderListing {
        return localMediaManager.browseFolder(folderPath, rootPath)
    }

    fun playFolder(
        folderPath: String,
        folderName: String,
        source: CustomMediaSource? = null,
        isRecursive: Boolean = true
    ) {
        if (isCoordinatorLoading) return
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
        onSetTracksListVisible(true)
        playlistManager.currentOpenUserPlaylist = null
        launchSafe {
            setLoading(true)
            onError(null)
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
                    println("TrackListCoordinator: Запущена папка '$folderName' (рекурсивно: $isRecursive, ${tracks.size} треков)")
                    val first = tracks.first()
                    onPlayTrack(
                        first.id,
                        first.title,
                        first.artists.firstOrNull()?.name ?: "Unknown Artist",
                        first.albums.firstOrNull()?.id,
                        true,
                        false,
                        TrackPlaySource.PLAY
                    )
                } else {
                    onError("В папке '$folderName' нет аудиофайлов.")
                }
            } catch (e: Exception) {
                onError("Ошибка чтения папки: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    fun openFolderPlaylist(
        folderPath: String,
        folderName: String,
        source: CustomMediaSource? = null,
        restoreTrackId: String? = null,
        isRecursive: Boolean = true
    ) {
        if (isCoordinatorLoading) return
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
        onSetTracksListVisible(true)
        playlistManager.currentOpenUserPlaylist = null
        launchSafe {
            setLoading(true)
            onError(null)
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
                    println("TrackListCoordinator: Восстановлена папка '$folderName' (рекурсивно: $isRecursive, ${tracks.size} треков)")
                    if (restoreTrackId != null) {
                        val target = tracks.firstOrNull { it.id == restoreTrackId || it.realId == restoreTrackId } ?: tracks.firstOrNull()
                        if (target != null) {
                            playbackManager.setInitialTrack(target)
                        }
                    }
                } else {
                    onError("В папке '$folderName' нет аудиофайлов.")
                }
            } catch (e: Exception) {
                onError("Ошибка чтения папки: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

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
        onSetTracksListVisible(true)
        playlistManager.currentOpenUserPlaylist = null
        resetPagination()
        val queue = if (folderTracks.contains(track)) folderTracks else (listOf(track) + folderTracks)
        loadedTracks.clear()
        loadedTracks.addAll(queue)
        allTrackIds = queue.map { it.id }
        canLoadMore = false
        onPlayTrack(
            track.id,
            track.title,
            track.artists.firstOrNull()?.name ?: "Unknown Artist",
            track.albums.firstOrNull()?.id,
            true,
            false,
            TrackPlaySource.PLAY
        )
    }

    fun playTrackByExternalId(trackId: String) {
        launchSafe {
            try {
                val response = withContext(DispatcherIO) {
                    repository.getTracksDetails(listOf(trackId))
                }
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
                    onPlayTrack(
                        trackInfo.id,
                        trackInfo.title,
                        artistName,
                        albumId,
                        true,
                        false,
                        TrackPlaySource.PLAY
                    )
                } else {
                    onError("Трек не найден: $trackId")
                }
            } catch (e: Exception) {
                onError("Не удалось загрузить трек: ${e.message}")
            }
        }
    }

    fun restorePlaylistSession(session: LastPlaybackSession, wasTracksListVisible: Boolean = true) {
        when (session.type) {
            LastPlaybackType.DOWNLOADED -> {
                loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                if (!wasTracksListVisible) onSetTracksListVisible(false)
            }
            LastPlaybackType.LOCAL_PLAYLIST -> {
                launchSafe {
                    if (playlistManager.localPlaylists.isEmpty()) {
                        val list = withContext(DispatcherIO) {
                            io.github.audiz.loadLocalPlaylists(getMusicStoragePath())
                        }
                        if (list.isNotEmpty()) {
                            playlistManager.localPlaylists.clear()
                            playlistManager.localPlaylists.addAll(list)
                        }
                    }
                    val playlist = playlistManager.localPlaylists.firstOrNull {
                        it.id == session.id || it.title.equals(session.title, ignoreCase = true)
                    }
                    if (playlist != null) {
                        openLocalPlaylist(playlist, restoreTrackId = session.lastTrackId)
                    } else {
                        loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                    }
                    if (!wasTracksListVisible) onSetTracksListVisible(false)
                }
            }
            LastPlaybackType.CUSTOM_SOURCE -> {
                val path = session.path
                val source = localMediaManager.customSources.firstOrNull { it.id == session.sourceId || it.path == path }
                if (source != null) {
                    openCustomSource(source, restoreTrackId = session.lastTrackId)
                } else if (!path.isNullOrBlank()) {
                    openFolderPlaylist(
                        folderPath = path,
                        folderName = session.title ?: "Папка",
                        restoreTrackId = session.lastTrackId,
                        isRecursive = session.isRecursive
                    )
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) onSetTracksListVisible(false)
            }
            LastPlaybackType.CUSTOM_FOLDER -> {
                val path = session.path
                if (!path.isNullOrBlank()) {
                    val source = localMediaManager.customSources.firstOrNull { it.id == session.sourceId || it.path == path }
                    openFolderPlaylist(
                        folderPath = path,
                        folderName = session.title ?: "Папка",
                        source = source,
                        restoreTrackId = session.lastTrackId,
                        isRecursive = session.isRecursive
                    )
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) onSetTracksListVisible(false)
            }
            LastPlaybackType.YANDEX_USER_PLAYLIST -> {
                val kind = session.kind
                val uid = session.uid
                val uuid = session.uuid
                val title = session.title ?: "Плейлист"
                if (kind != null && kind > 0L && uid != null && uid > 0L) {
                    loadPlaylistTracks(uid, kind, title, restoreTrackId = session.lastTrackId)
                } else if (!uuid.isNullOrBlank()) {
                    loadPlaylistByUuid(uuid, title, restoreTrackId = session.lastTrackId)
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) onSetTracksListVisible(false)
            }
            LastPlaybackType.YANDEX_UUID -> {
                val uuid = session.uuid
                if (!uuid.isNullOrBlank()) {
                    loadPlaylistByUuid(uuid, session.title, restoreTrackId = session.lastTrackId)
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) onSetTracksListVisible(false)
            }
            LastPlaybackType.YANDEX_LIKES -> {
                loadLikesPlaylist(restoreTrackId = session.lastTrackId)
                if (!wasTracksListVisible) onSetTracksListVisible(false)
            }
            LastPlaybackType.YANDEX_HISTORY -> {
                loadHistory(restoreTrackId = session.lastTrackId)
                if (!wasTracksListVisible) onSetTracksListVisible(false)
            }
            LastPlaybackType.ARTIST -> {
                val artistId = session.artistId
                if (!artistId.isNullOrBlank()) {
                    loadArtistTracks(artistId, session.artistName, restoreTrackId = session.lastTrackId)
                } else {
                    loadDownloadedTracksPlaylist(restoreTrackId = session.lastTrackId)
                }
                if (!wasTracksListVisible) onSetTracksListVisible(false)
            }
            LastPlaybackType.WAVE -> {
                // Волна обрабатывается WaveManager
            }
        }
    }

    fun openQueueView(
        origin: TracksListOrigin = TracksListOrigin.HOME,
        isWaveMode: Boolean = false,
        waveTracks: List<FullTrackInfo> = emptyList(),
        waveTitle: String? = null
    ) {
        if (isWaveMode && waveTracks.isNotEmpty()) {
            loadedTracks.clear()
            loadedTracks.addAll(waveTracks)
            currentScreenTitle = if (!waveTitle.isNullOrBlank()) {
                "Моя Волна • $waveTitle"
            } else {
                "Очередь Моей Волны"
            }
            activeBrowsedFolderSource = null
            activeBrowsedFolderPath = null
            playlistManager.currentOpenUserPlaylist = null
            tracksListOrigin = origin
            onSetTracksListVisible(true)
        } else if (loadedTracks.isNotEmpty()) {
            if (activeQueueFolderName != null && (currentScreenTitle.isBlank() || currentScreenTitle == "Загружено треков")) {
                currentScreenTitle = activeQueueFolderName ?: "Очередь воспроизведения"
            }
            tracksListOrigin = origin
            onSetTracksListVisible(true)
        }
    }

    fun toggleQueueView(
        isWaveMode: Boolean = false,
        waveTracks: List<FullTrackInfo> = emptyList(),
        waveTitle: String? = null,
        isTracksListVisible: Boolean,
        setTracksListVisible: (Boolean) -> Unit
    ) {
        if (isTracksListVisible) {
            setTracksListVisible(false)
            tracksListOrigin = TracksListOrigin.HOME
        } else if (isWaveMode && waveTracks.isNotEmpty()) {
            openQueueView(TracksListOrigin.HOME, isWaveMode = true, waveTracks = waveTracks, waveTitle = waveTitle)
        } else if (loadedTracks.isNotEmpty()) {
            if (activeQueueFolderName != null && (currentScreenTitle.isBlank() || currentScreenTitle == "Загружено треков")) {
                currentScreenTitle = activeQueueFolderName ?: "Очередь воспроизведения"
            }
            tracksListOrigin = TracksListOrigin.HOME
            setTracksListVisible(true)
        }
    }
}
