package io.github.audiz.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import io.github.audiz.DispatcherIO
import io.github.audiz.api.MusicRepository
import io.github.audiz.api.generatePlayUuid
import io.github.audiz.currentTimeMillis
import io.github.audiz.download.DownloadManager
import io.github.audiz.exportPlaylistToM3u8
import io.github.audiz.getTracksFromLocalPaths
import io.github.audiz.loadLocalPlaylists
import io.github.audiz.loadPersonalPlaylistsCache
import io.github.audiz.loadUserPlaylistsCache
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.PersonalPlaylistItemData
import io.github.audiz.models.PlaylistInfo
import io.github.audiz.models.YandexPlaylistDetails
import io.github.audiz.saveLocalPlaylists
import io.github.audiz.savePersonalPlaylistsCache
import io.github.audiz.saveUserPlaylistsCache
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 📂 Менеджер плейлистов и пользовательских отметок (Лайки/Дизлайки).
 * Инкапсулирует:
 * - Персональные плейлисты Яндекса (Плейлист дня, Дежавю, Премьера и др.) и их дисковый кеш.
 * - Пользовательские плейлисты аккаунта Яндекс Музыки (CRUD, добавление/удаление треков, кеш ID).
 * - Локальные оффлайн-плейлисты на диске (CRUD, автоскачивание треков, экспорт в M3U8).
 * - Лайки и дизлайки (оптимистичный UI, фоновая синхронизация с сервером и откат при ошибках).
 */
class PlaylistManager(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val getMusicStoragePath: () -> String,
    private val getAccessToken: () -> String,
    private val downloadManager: DownloadManager,
    private val onStatusMessage: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        println("PlaylistManager: ❌ Uncaught coroutine exception: ${throwable.message}")
        throwable.printStackTrace()
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job {
        return scope.launch(Dispatchers.Main + coroutineExceptionHandler) {
            try {
                block()
            } catch (t: Throwable) {
                println("PlaylistManager: ❌ Error in safe coroutine: ${t.message}")
                t.printStackTrace()
            }
        }
    }

    // 🎵 Персональные плейлисты (Плейлист дня, Премьера, Дежавю и т.д.)
    val personalPlaylists = mutableStateListOf<PersonalPlaylistItemData>()
    var isPersonalPlaylistsLoading by mutableStateOf(false)
        private set

    // 📁 Персональные локальные оффлайн-плейлисты (на диске)
    val localPlaylists = mutableStateListOf<LocalPlaylist>()
    var isLocalPlaylistsLoading by mutableStateOf(false)
        private set

    // ☁️ Пользовательские плейлисты из Яндекс Музыки
    val userPlaylists = mutableStateListOf<PlaylistInfo>()
    var isUserPlaylistsLoading by mutableStateOf(false)
        private set
    var currentOpenUserPlaylist by mutableStateOf<PlaylistInfo?>(null)

    // ☁️ Кеш идентификаторов треков пользовательских плейлистов Яндекс (kind -> Set<trackId>)
    val userPlaylistsTrackIds = mutableStateMapOf<Long, Set<String>>()

    // ❤️ Список ID лайкнутых треков ("Мне нравится")
    val likedTrackIds = mutableStateSetOf<String>()

    // 💔 Список ID треков в дизлайках
    val dislikedTrackIds = mutableStateSetOf<String>()

    /**
     * 🎵 Загрузка персональных плейлистов (Плейлист дня, Премьера, Дежавю и др.)
     */
    fun loadPersonalPlaylists() {
        launchSafe {
            isPersonalPlaylistsLoading = true
            var loadedFromNetwork = false
            val token = getAccessToken()
            val storagePath = getMusicStoragePath()
            if (token.isNotBlank()) {
                try {
                    val playlists = repository.getPersonalPlaylists()
                    if (playlists.isNotEmpty()) {
                        personalPlaylists.clear()
                        personalPlaylists.addAll(playlists)
                        loadedFromNetwork = true
                        println("PlaylistManager: Загружено персональных плейлистов: ${playlists.size}")
                        withContext(DispatcherIO) {
                            savePersonalPlaylistsCache(storagePath, playlists)
                        }
                    }
                } catch (e: Throwable) {
                    println("PlaylistManager: Ошибка загрузки персональных плейлистов: ${e.message}")
                }
            }
            if (!loadedFromNetwork && personalPlaylists.isEmpty()) {
                try {
                    val cached = withContext(DispatcherIO) {
                        loadPersonalPlaylistsCache(storagePath)
                    }
                    if (cached.isNotEmpty()) {
                        personalPlaylists.clear()
                        personalPlaylists.addAll(cached)
                        println("PlaylistManager: Загружено персональных плейлистов из кеша: ${cached.size}")
                    }
                } catch (e: Throwable) {
                    println("PlaylistManager: Ошибка загрузки персональных плейлистов из кеша: ${e.message}")
                }
            }
            isPersonalPlaylistsLoading = false
        }
    }

    /**
     * ☁️ Загрузка пользовательских плейлистов из Яндекс Музыки
     */
    fun loadUserPlaylists() {
        launchSafe {
            isUserPlaylistsLoading = true
            var loadedFromNetwork = false
            val token = getAccessToken()
            val storagePath = getMusicStoragePath()
            if (token.isNotBlank()) {
                try {
                    val playlists = repository.getUserPlaylists()
                    if (playlists.isNotEmpty()) {
                        userPlaylists.clear()
                        userPlaylists.addAll(playlists)
                        loadedFromNetwork = true
                        println("PlaylistManager: Загружено пользовательских плейлистов из Яндекса: ${playlists.size}")
                        withContext(DispatcherIO) {
                            saveUserPlaylistsCache(storagePath, playlists)
                        }
                        val listCopy = playlists.toList()
                        scope.launch(DispatcherIO) {
                            for (pl in listCopy) {
                                val kind = pl.kind ?: continue
                                if (!userPlaylistsTrackIds.containsKey(kind)) {
                                    try {
                                        val trackIds = repository.getPlaylistTrackIds(pl.uid, kind)
                                        val cleanIds = trackIds.map { it.substringBefore(":") }.toSet()
                                        withContext(Dispatchers.Main) {
                                            userPlaylistsTrackIds[kind] = cleanIds
                                        }
                                    } catch (_: Throwable) {
                                        // Игнорируем сбои отдельных плейлистов
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    println("PlaylistManager: Ошибка загрузки пользовательских плейлистов: ${e.message}")
                }
            }
            if (!loadedFromNetwork && userPlaylists.isEmpty()) {
                try {
                    val cached = withContext(DispatcherIO) {
                        loadUserPlaylistsCache(storagePath)
                    }
                    if (cached.isNotEmpty()) {
                        userPlaylists.clear()
                        userPlaylists.addAll(cached)
                        println("PlaylistManager: Загружено пользовательских плейлистов из кеша: ${cached.size}")
                    }
                } catch (e: Throwable) {
                    println("PlaylistManager: Ошибка загрузки пользовательских плейлистов из кеша: ${e.message}")
                }
            }
            isUserPlaylistsLoading = false
        }
    }

    /**
     * 💾 Загрузка локальных плейлистов с диска
     */
    fun loadLocalPlaylistsFromDisk() {
        launchSafe {
            isLocalPlaylistsLoading = true
            val storagePath = getMusicStoragePath()
            try {
                val list = withContext(DispatcherIO) {
                    loadLocalPlaylists(storagePath)
                }
                localPlaylists.clear()
                localPlaylists.addAll(list)
                println("PlaylistManager: Загружено локальных плейлистов: ${list.size}")
            } catch (e: Throwable) {
                println("PlaylistManager: Ошибка загрузки локальных плейлистов: ${e.message}")
            } finally {
                isLocalPlaylistsLoading = false
            }
        }
    }

    /**
     * ➕ Создать новый локальный плейлист
     */
    fun createLocalPlaylist(title: String, description: String = ""): LocalPlaylist {
        val cleanTitle = title.trim().ifBlank { "Мой плейлист" }
        val now = currentTimeMillis()
        val newPlaylist = LocalPlaylist(
            id = generatePlayUuid(),
            title = cleanTitle,
            description = description.trim(),
            createdAt = now,
            updatedAt = now,
            trackPaths = emptyList()
        )
        localPlaylists.add(0, newPlaylist)
        val storagePath = getMusicStoragePath()
        scope.launch(DispatcherIO) {
            saveLocalPlaylists(storagePath, localPlaylists.toList())
        }
        onStatusMessage("✅ Создан плейлист '$cleanTitle'")
        return newPlaylist
    }

    /**
     * ✏️ Переименовать локальный плейлист
     */
    fun renameLocalPlaylist(playlistId: String, newTitle: String) {
        val idx = localPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0) return
        val current = localPlaylists[idx]
        val cleanTitle = newTitle.trim().ifBlank { current.title }
        val updated = current.copy(title = cleanTitle, updatedAt = currentTimeMillis())
        localPlaylists[idx] = updated
        val storagePath = getMusicStoragePath()
        scope.launch(DispatcherIO) {
            saveLocalPlaylists(storagePath, localPlaylists.toList())
        }
        onStatusMessage("✅ Плейлист переименован в '$cleanTitle'")
    }

    /**
     * 🗑️ Удалить локальный плейлист (файлы треков на диске не удаляются)
     */
    fun deleteLocalPlaylist(playlistId: String) {
        val removed = localPlaylists.removeAll { it.id == playlistId }
        if (removed) {
            val storagePath = getMusicStoragePath()
            scope.launch(DispatcherIO) {
                saveLocalPlaylists(storagePath, localPlaylists.toList())
            }
            onStatusMessage("🗑️ Плейлист удален")
        }
    }

    /**
     * 🎵 Добавить трек в локальный плейлист.
     * ⚡ Если трек еще не скачан на диск — автоматически скачивает его!
     */
    fun addTrackToLocalPlaylist(playlistId: String, track: FullTrackInfo, onComplete: ((Boolean) -> Unit)? = null) {
        val idx = localPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0) {
            onComplete?.invoke(false)
            return
        }
        val playlist = localPlaylists[idx]

        launchSafe {
            onStatusMessage("⏳ Добавление в '${playlist.title}': ${track.title}...")
            val artist = track.artists.firstOrNull()?.name ?: "Unknown Artist"
            val path = downloadManager.ensureTrackFile(track.id, track.title, artist)
            if (path == null) {
                onStatusMessage("❌ Не удалось сохранить трек на диск")
                onComplete?.invoke(false)
                return@launchSafe
            }

            if (!playlist.trackPaths.contains(path)) {
                val updated = playlist.copy(
                    trackPaths = playlist.trackPaths + path,
                    updatedAt = currentTimeMillis()
                )
                localPlaylists[idx] = updated
                val storagePath = getMusicStoragePath()
                withContext(DispatcherIO) {
                    saveLocalPlaylists(storagePath, localPlaylists.toList())
                }
                onStatusMessage("✅ Добавлено в '${playlist.title}': ${track.title}")
                onComplete?.invoke(true)
            } else {
                onStatusMessage("ℹ️ Трек уже в плейлисте '${playlist.title}'")
                onComplete?.invoke(true)
            }
        }
    }

    /**
     * ➖ Удалить трек из локального плейлиста по пути или ID
     */
    fun removeTrackFromLocalPlaylist(
        playlistId: String,
        trackPathOrId: String,
        onTrackRemoved: ((String) -> Unit)? = null
    ) {
        val idx = localPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0) return
        val playlist = localPlaylists[idx]
        val cleanPath = trackPathOrId.removePrefix("local:")
        val updatedList = playlist.trackPaths.filterNot { it == cleanPath || it == trackPathOrId }
        val updated = playlist.copy(trackPaths = updatedList, updatedAt = currentTimeMillis())
        localPlaylists[idx] = updated
        val storagePath = getMusicStoragePath()
        scope.launch(DispatcherIO) {
            saveLocalPlaylists(storagePath, localPlaylists.toList())
        }
        onTrackRemoved?.invoke(cleanPath)
        onStatusMessage("Удалено из плейлиста")
    }

    /**
     * ➖ Удалить трек из локального плейлиста по объекту трека
     */
    fun removeTrackFromLocalPlaylist(
        playlistId: String,
        track: FullTrackInfo,
        onTrackRemoved: ((String) -> Unit)? = null
    ) {
        val idx = localPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0) return
        val playlist = localPlaylists[idx]
        val cleanPath = (track.realId ?: track.id).removePrefix("local:")
        val rawId = track.id.removePrefix("local:")
        val artist = track.artists.firstOrNull()?.name ?: ""
        val downloadedPath = downloadManager.getDownloadedTrackPath(artist, track.title)
        val updatedList = playlist.trackPaths.filterNot { path ->
            path == cleanPath ||
            path == rawId ||
            path == track.id ||
            (downloadedPath != null && path == downloadedPath) ||
            (cleanPath.isNotBlank() && path.endsWith(cleanPath))
        }
        val updated = playlist.copy(trackPaths = updatedList, updatedAt = currentTimeMillis())
        localPlaylists[idx] = updated
        val storagePath = getMusicStoragePath()
        scope.launch(DispatcherIO) {
            saveLocalPlaylists(storagePath, localPlaylists.toList())
        }
        onTrackRemoved?.invoke(cleanPath)
        onStatusMessage("Удалено из плейлиста")
    }

    /**
     * 🗑️ Удалить трек из всех локальных плейлистов при его физическом удалении с диска
     */
    fun removeTrackFileFromAllPlaylists(trackPathOrId: String) {
        val clean = trackPathOrId.removePrefix("local:")
        var anyModified = false
        for (i in localPlaylists.indices) {
            val pl = localPlaylists[i]
            val filtered = pl.trackPaths.filterNot {
                it == clean || it == trackPathOrId || it.removePrefix("local:") == clean
            }
            if (filtered.size != pl.trackPaths.size) {
                localPlaylists[i] = pl.copy(trackPaths = filtered, updatedAt = currentTimeMillis())
                anyModified = true
            }
        }
        if (anyModified) {
            val storagePath = getMusicStoragePath()
            scope.launch(DispatcherIO) {
                saveLocalPlaylists(storagePath, localPlaylists.toList())
            }
        }
    }


    /**
     * ☁️ Создать новый плейлист в аккаунте Яндекс Музыки
     */
    fun createYandexPlaylist(title: String, onComplete: ((PlaylistInfo?) -> Unit)? = null) {
        val cleanTitle = title.trim().ifBlank { "Новый плейлист" }
        launchSafe {
            onStatusMessage("⏳ Создание плейлиста '$cleanTitle' в Яндекс Музыке...")
            val created = repository.createPlaylist(cleanTitle)
            if (created != null) {
                userPlaylists.add(0, created)
                val storagePath = getMusicStoragePath()
                withContext(DispatcherIO) {
                    saveUserPlaylistsCache(storagePath, userPlaylists.toList())
                }
                onStatusMessage("✅ Создан плейлист Яндекс '$cleanTitle'")
                onComplete?.invoke(created)
            } else {
                onStatusMessage("❌ Не удалось создать плейлист в аккаунте")
                onComplete?.invoke(null)
            }
        }
    }

    /**
     * ✏️ Переименовать плейлист в аккаунте Яндекс Музыки
     */
    fun renameYandexPlaylist(playlist: PlaylistInfo, newTitle: String, onComplete: ((Boolean) -> Unit)? = null) {
        val kind = playlist.kind
        if (kind == null) {
            onStatusMessage("❌ Невозможно переименовать данный плейлист (нет kind)")
            onComplete?.invoke(false)
            return
        }
        val cleanTitle = newTitle.trim().ifBlank { playlist.title }
        if (cleanTitle == playlist.title) {
            onComplete?.invoke(true)
            return
        }
        launchSafe {
            onStatusMessage("⏳ Переименование плейлиста в '$cleanTitle'...")
            val updated = repository.renamePlaylist(kind, cleanTitle, playlist.uid)
            if (updated != null) {
                val idx = userPlaylists.indexOfFirst { it.kind == kind }
                val merged = playlist.copy(
                    title = updated.title.ifBlank { cleanTitle },
                    trackCount = if (updated.trackCount > 0) updated.trackCount else playlist.trackCount,
                    coverUri = updated.coverUri ?: playlist.coverUri,
                    description = updated.description ?: playlist.description,
                    revision = if ((updated.revision ?: 0) > 0) updated.revision else playlist.revision
                )
                if (idx >= 0) {
                    userPlaylists[idx] = merged
                }
                val storagePath = getMusicStoragePath()
                withContext(DispatcherIO) {
                    saveUserPlaylistsCache(storagePath, userPlaylists.toList())
                }
                if (currentOpenUserPlaylist?.kind == kind) {
                    currentOpenUserPlaylist = merged
                }
                onStatusMessage("✅ Плейлист переименован в '$cleanTitle'")
                onComplete?.invoke(true)
            } else {
                onStatusMessage("❌ Не удалось переименовать плейлист в Яндекс Музыке")
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * ☁️ Добавить трек в персональный плейлист Яндекс Музыки
     */
    fun addTrackToYandexPlaylist(playlist: PlaylistInfo, track: FullTrackInfo, onComplete: ((Boolean) -> Unit)? = null) {
        val kind = playlist.kind
        if (kind == null || kind <= 0L) {
            onStatusMessage("❌ Некорректный идентификатор плейлиста")
            onComplete?.invoke(false)
            return
        }

        launchSafe {
            onStatusMessage("⏳ Добавление в плейлист '${playlist.title}': ${track.title}...")

            var cleanTrackId = (track.realId?.ifBlank { null } ?: track.id).removePrefix("local:")
            var albumId = track.albums.firstOrNull()?.id

            if (cleanTrackId.contains(":")) {
                val parts = cleanTrackId.split(":")
                cleanTrackId = parts[0]
                if (albumId == null || albumId <= 0L) {
                    albumId = parts.getOrNull(1)?.toLongOrNull()
                }
            }

            if (cleanTrackId.contains("/") || cleanTrackId.contains("\\") || cleanTrackId.toLongOrNull() == null) {
                try {
                    val query = "${track.artists.firstOrNull()?.name ?: ""} ${track.title}".trim()
                    if (query.isNotBlank()) {
                        val searchResp = repository.searchInstant(query)
                        val foundTrack = searchResp.result?.results?.firstOrNull { it.type == "track" }?.track
                        if (foundTrack != null) {
                            cleanTrackId = foundTrack.id
                        }
                    }
                } catch (e: Exception) {
                    println("PlaylistManager: Ошибка сопоставления локального трека в Яндекс Музыке: ${e.message}")
                }
            }

            if (cleanTrackId.toLongOrNull() == null) {
                onStatusMessage("❌ Трек не найден в каталоге Яндекс Музыки")
                onComplete?.invoke(false)
                return@launchSafe
            }

            val success = repository.addTrackToPlaylist(kind, cleanTrackId, albumId)
            if (success) {
                onStatusMessage("✅ Добавлено в плейлист Яндекс: ${track.title}")
                val currentIds = userPlaylistsTrackIds[kind] ?: emptySet()
                userPlaylistsTrackIds[kind] = currentIds + cleanTrackId
                val idx = userPlaylists.indexOfFirst { it.kind == kind }
                if (idx >= 0) {
                    val cur = userPlaylists[idx]
                    userPlaylists[idx] = cur.copy(trackCount = cur.trackCount + 1)
                    val storagePath = getMusicStoragePath()
                    withContext(DispatcherIO) {
                        saveUserPlaylistsCache(storagePath, userPlaylists.toList())
                    }
                }
                onComplete?.invoke(true)
            } else {
                onStatusMessage("❌ Ошибка добавления трека в плейлист Яндекс")
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * 🗑️ Удалить трек из персонального плейлиста Яндекс Музыки
     */
    fun removeTrackFromYandexPlaylist(
        playlist: PlaylistInfo,
        track: FullTrackInfo,
        onTrackRemoved: ((String) -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val kind = playlist.kind
        if (kind == null || kind <= 0L) {
            onStatusMessage("❌ Некорректный идентификатор плейлиста")
            onComplete?.invoke(false)
            return
        }

        launchSafe {
            onStatusMessage("⏳ Удаление из плейлиста '${playlist.title}': ${track.title}...")
            var cleanTrackId = (track.realId?.ifBlank { null } ?: track.id).removePrefix("local:").substringBefore(":")

            if (cleanTrackId.contains("/") || cleanTrackId.contains("\\") || cleanTrackId.toLongOrNull() == null) {
                try {
                    val query = "${track.artists.firstOrNull()?.name ?: ""} ${track.title}".trim()
                    if (query.isNotBlank()) {
                        val searchResp = repository.searchInstant(query)
                        val foundTrack = searchResp.result?.results?.firstOrNull { it.type == "track" }?.track
                        if (foundTrack != null) {
                            cleanTrackId = foundTrack.id
                        }
                    }
                } catch (e: Exception) {
                    println("PlaylistManager: Ошибка сопоставления трека при удалении: ${e.message}")
                }
            }

            val success = repository.removeTrackFromPlaylist(kind, cleanTrackId)
            if (success) {
                onStatusMessage("✅ Удалено из плейлиста '${playlist.title}': ${track.title}")
                val currentIds = userPlaylistsTrackIds[kind] ?: emptySet()
                userPlaylistsTrackIds[kind] = currentIds - cleanTrackId
                val idx = userPlaylists.indexOfFirst { it.kind == kind }
                if (idx >= 0) {
                    val cur = userPlaylists[idx]
                    val newCount = (cur.trackCount - 1).coerceAtLeast(0)
                    userPlaylists[idx] = cur.copy(trackCount = newCount)
                    val storagePath = getMusicStoragePath()
                    withContext(DispatcherIO) {
                        saveUserPlaylistsCache(storagePath, userPlaylists.toList())
                    }
                }
                onTrackRemoved?.invoke(cleanTrackId)
                onComplete?.invoke(true)
            } else {
                onStatusMessage("❌ Ошибка удаления трека из плейлиста Яндекс")
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * 📋 Загрузить детали плейлиста Яндекс Музыки (с треками) для отображения в диалоге
     */
    suspend fun loadYandexPlaylistDetails(playlist: PlaylistInfo): YandexPlaylistDetails? {
        val kind = playlist.kind ?: return null
        val uid = if (playlist.uid > 0L) playlist.uid else (repository.getAccountUid()?.toLongOrNull() ?: 0L)
        val details = repository.getPlaylistDetails(uid, kind)
        if (details != null) {
            val ids = details.tracks.map { it.id.substringBefore(":") }.toSet()
            val current = userPlaylistsTrackIds[kind] ?: emptySet()
            userPlaylistsTrackIds[kind] = current + ids
        }
        return details
    }

    /**
     * 🔍 Проверить, содержится ли трек в локальном плейлисте
     */
    fun isTrackInLocalPlaylist(playlist: LocalPlaylist, track: FullTrackInfo): Boolean {
        val cleanPath = (track.realId ?: track.id).removePrefix("local:")
        val rawId = track.id.removePrefix("local:")

        // 1. Быстрая проверка по памяти (ID и пути) без обращения к диску!
        if (playlist.trackPaths.any { path ->
            path == cleanPath ||
            path == rawId ||
            path == track.id ||
            (cleanPath.isNotBlank() && path.endsWith(cleanPath))
        }) {
            return true
        }

        // 2. Только если по ID не совпало — проверяем совпадение со скачанным файлом
        val artist = track.artists.firstOrNull()?.name ?: ""
        val downloadedPath = downloadManager.getDownloadedTrackPath(artist, track.title)
        return downloadedPath != null && playlist.trackPaths.contains(downloadedPath)
    }

    /**
     * 🔍 Проверить, содержится ли трек хотя бы в одном плейлисте (локальном или облачном Яндекс)
     */
    fun isTrackInAnyPlaylist(track: FullTrackInfo): Boolean {
        // 1. Сначала быстрая проверка облачных плейлистов Яндекс Музыки (чисто память O(1))
        val cleanTrackId = (track.realId?.ifBlank { null } ?: track.id).removePrefix("local:").substringBefore(":")
        val rawTrackId = track.id.removePrefix("local:").substringBefore(":")

        if (userPlaylistsTrackIds.values.any { ids ->
            ids.contains(cleanTrackId) || ids.contains(rawTrackId) || ids.contains(track.id)
        }) {
            return true
        }

        // 2. Проверяем локальные оффлайн-плейлисты
        return localPlaylists.any { isTrackInLocalPlaylist(it, track) }
    }

    /**
     * 🔢 Подсчитать количество плейлистов, в которых содержится трек
     */
    fun getTrackPlaylistsCount(track: FullTrackInfo): Int {
        val cleanPath = (track.realId ?: track.id).removePrefix("local:")
        val rawId = track.id.removePrefix("local:")
        val artist = track.artists.firstOrNull()?.name ?: ""
        val downloadedPath = downloadManager.getDownloadedTrackPath(artist, track.title)

        var count = 0
        for (playlist in localPlaylists) {
            val matched = playlist.trackPaths.any { path ->
                path == cleanPath ||
                path == rawId ||
                path == track.id ||
                (downloadedPath != null && path == downloadedPath) ||
                (cleanPath.isNotBlank() && path.endsWith(cleanPath))
            }
            if (matched) {
                count++
            }
        }

        val cleanTrackId = (track.realId?.ifBlank { null } ?: track.id).removePrefix("local:").substringBefore(":")
        val rawTrackId = track.id.removePrefix("local:").substringBefore(":")

        for (ids in userPlaylistsTrackIds.values) {
            if (ids.contains(cleanTrackId) || ids.contains(rawTrackId) || ids.contains(track.id)) {
                count++
            }
        }
        return count
    }

    /**
     * 💾 Скачать весь плейлист из Яндекс Музыки в локальный оффлайн-плейлист
     */
    fun downloadYandexPlaylistToLocal(playlist: PlaylistInfo) {
        launchSafe {
            onStatusMessage("⏳ Подготовка скачивания плейлиста '${playlist.title}'...")
            var localPl = localPlaylists.firstOrNull { it.title.equals(playlist.title, ignoreCase = true) }
            if (localPl == null) {
                localPl = createLocalPlaylist(playlist.title, playlist.description ?: "")
            }

            val trackIds = try {
                val uuid = playlist.playlistUuid
                if (!uuid.isNullOrBlank()) {
                    repository.getPlaylistTrackIdsByUuid(uuid)
                } else {
                    val kind = playlist.kind ?: 0L
                    repository.getPlaylistTrackIds(playlist.uid, kind)
                }
            } catch (e: Throwable) {
                onStatusMessage("❌ Ошибка получения треков плейлиста: ${e.message}")
                return@launchSafe
            }

            if (trackIds.isEmpty()) {
                onStatusMessage("ℹ️ Плейлист пуст")
                return@launchSafe
            }

            onStatusMessage("📥 Скачивание плейлиста: 0/${trackIds.size}")
            var downloadedCount = 0

            for (chunk in trackIds.chunked(20)) {
                try {
                    val fullTracks = repository.getTracksDetails(chunk).result
                    for (track in fullTracks) {
                        val artist = track.artists.firstOrNull()?.name ?: "Unknown Artist"
                        val path = downloadManager.ensureTrackFile(track.id, track.title, artist)
                        if (path != null) {
                            val idx = localPlaylists.indexOfFirst { it.id == localPl.id }
                            if (idx >= 0) {
                                val cur = localPlaylists[idx]
                                if (!cur.trackPaths.contains(path)) {
                                    localPlaylists[idx] = cur.copy(
                                        trackPaths = cur.trackPaths + path,
                                        updatedAt = currentTimeMillis()
                                    )
                                }
                            }
                            downloadedCount++
                            onStatusMessage("📥 Скачивание: $downloadedCount/${trackIds.size} (${track.title})")
                        }
                    }
                } catch (e: Throwable) {
                    println("PlaylistManager: Ошибка пачки скачивания: ${e.message}")
                }
            }

            val storagePath = getMusicStoragePath()
            withContext(DispatcherIO) {
                saveLocalPlaylists(storagePath, localPlaylists.toList())
            }
            onStatusMessage("✅ Плейлист '${playlist.title}' полностью сохранён ($downloadedCount треков)!")
        }
    }

    /**
     * 📋 Экспорт локального плейлиста в файл .m3u8
     */
    fun exportLocalPlaylist(playlistId: String): String {
        val playlist = localPlaylists.firstOrNull { it.id == playlistId } ?: return ""
        val tracks = getTracksFromLocalPaths(playlist.trackPaths)
        val storagePath = getMusicStoragePath()
        val path = exportPlaylistToM3u8(storagePath, playlist, tracks)
        if (path.isNotEmpty()) {
            onStatusMessage("✅ Экспортирован в M3U8: ${playlist.title}")
        }
        return path
    }

    /**
     * ❤️ Загрузить и обновить список всех лайкнутых треков пользователя в фоне
     */
    fun syncLikedTrackIds() {
        if (getAccessToken().isBlank()) return
        launchSafe {
            try {
                val uuid = repository.getLikesPlaylistUuid()
                val ids = repository.getPlaylistTrackIdsByUuid(uuid)
                likedTrackIds.addAll(ids)
                println("PlaylistManager: Синхронизировано ${ids.size} лайкнутых треков")
            } catch (e: Throwable) {
                println("PlaylistManager: Ошибка синхронизации лайкнутых треков: ${e.message}")
            }
        }
    }

    /**
     * Переключить лайк для трека (оптимистичное обновление + запрос на сервер)
     */
    fun toggleLike(trackId: String, albumId: Long? = null) {
        val trackKey = if (albumId != null && albumId > 0) "$trackId:$albumId" else trackId
        val wasLiked = likedTrackIds.contains(trackId)
        val wasDisliked = dislikedTrackIds.contains(trackId)

        if (wasLiked) {
            likedTrackIds.remove(trackId)
            println("PlaylistManager: Трек $trackId удален из лайков (локально)")
        } else {
            likedTrackIds.add(trackId)
            println("PlaylistManager: Трек $trackId добавлен в лайки (локально)")
            if (wasDisliked) {
                dislikedTrackIds.remove(trackId)
                println("PlaylistManager: Трек $trackId удален из дизлайков из-за лайка")
            }
        }

        if (trackId.startsWith("local:")) return

        launchSafe {
            if (wasLiked) {
                val success = repository.unlikeTrack(trackKey)
                if (!success) {
                    likedTrackIds.add(trackId)
                    println("PlaylistManager: ❌ Ошибка изменения статуса лайка на сервере для $trackKey, состояние откатано")
                } else {
                    println("PlaylistManager: ✅ Статус лайка для $trackKey успешно обновлен на сервере")
                }
            } else {
                if (wasDisliked) {
                    repository.undislikeTrack(trackKey)
                }
                val success = repository.likeTrack(trackKey)
                if (!success) {
                    likedTrackIds.remove(trackId)
                    if (wasDisliked) {
                        dislikedTrackIds.add(trackId)
                    }
                    println("PlaylistManager: ❌ Ошибка изменения статуса лайка на сервере для $trackKey, состояние откатано")
                } else {
                    println("PlaylistManager: ✅ Статус лайка для $trackKey успешно обновлен на сервере")
                }
            }
        }
    }

    /**
     * Переключить дизлайк для трека (оптимистичное обновление + запрос на сервер)
     */
    fun toggleDislike(trackId: String, albumId: Long? = null, onDisliked: (() -> Unit)? = null) {
        val trackKey = if (albumId != null && albumId > 0) "$trackId:$albumId" else trackId
        val wasDisliked = dislikedTrackIds.contains(trackId)
        val wasLiked = likedTrackIds.contains(trackId)

        if (wasDisliked) {
            dislikedTrackIds.remove(trackId)
            println("PlaylistManager: Трек $trackId удален из дизлайков (локально)")
        } else {
            dislikedTrackIds.add(trackId)
            println("PlaylistManager: Трек $trackId добавлен в дизлайки (локально)")
            if (wasLiked) {
                likedTrackIds.remove(trackId)
                println("PlaylistManager: Трек $trackId удален из лайков из-за дизлайка")
            }
        }

        if (!trackId.startsWith("local:")) {
            launchSafe {
                if (wasDisliked) {
                    val success = repository.undislikeTrack(trackKey)
                    if (!success) {
                        dislikedTrackIds.add(trackId)
                        println("PlaylistManager: ❌ Ошибка снятия дизлайка на сервере для $trackKey, состояние откатано")
                    } else {
                        println("PlaylistManager: ✅ Статус дизлайка для $trackKey успешно обновлен на сервере (снят)")
                    }
                } else {
                    if (wasLiked) {
                        repository.unlikeTrack(trackKey)
                    }
                    val success = repository.dislikeTrack(trackKey)
                    if (!success) {
                        dislikedTrackIds.remove(trackId)
                        if (wasLiked) {
                            likedTrackIds.add(trackId)
                        }
                        println("PlaylistManager: ❌ Ошибка добавления дизлайка на сервере для $trackKey, состояние откатано")
                    } else {
                        println("PlaylistManager: ✅ Дизлайк для $trackKey успешно отправлен на сервер")
                    }
                }
            }
        }

        if (!wasDisliked) {
            onDisliked?.invoke()
        }
    }
}
