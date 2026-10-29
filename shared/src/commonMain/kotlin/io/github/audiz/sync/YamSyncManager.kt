package io.github.audiz.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import io.github.audiz.*
import io.github.audiz.models.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Состояния подключения YamSync
 */
sealed interface YamSyncConnectionState {
    data object Disconnected : YamSyncConnectionState
    data class Hosting(val pairInfo: YamSyncPairInfo, val qrMatrix: Array<BooleanArray>) : YamSyncConnectionState
    data class Connecting(val message: String) : YamSyncConnectionState
    data class Connected(val peer: YamSyncDevice, val pairInfo: YamSyncPairInfo) : YamSyncConnectionState
    data class Syncing(val message: String, val progress: Float) : YamSyncConnectionState
}

/** Состояние синхронизации одного плейлиста: в очереди или скачано [done] из [total]. */
data class YamSyncPlaylistProgress(
    val isQueued: Boolean = false,
    val done: Int = 0,
    val total: Int = 0
)

/**
 * 🎛️ Менеджер сессии P2P-синхронизации медиатеки YamSync
 */
class YamSyncManager(
    private val scope: CoroutineScope,
    private val getMusicStoragePath: () -> String,
    private val onPlaylistsUpdated: () -> Unit
) {
    var connectionState by mutableStateOf<YamSyncConnectionState>(YamSyncConnectionState.Disconnected)
        private set

    var statusMessage by mutableStateOf<String?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var isDownloadingFiles by mutableStateOf(false)
        private set

    val playlistDiffs = mutableStateListOf<YamSyncPlaylistDiff>()
    val missingFiles = mutableStateListOf<YamSyncTrack>()
    val selectedFiles = mutableStateSetOf<String>()
    val fileTransfers = mutableStateMapOf<String, YamSyncTransferProgress>()
    val knownDevices = mutableStateListOf<YamSyncKnownDevice>()

    /** Прогресс синхронизации отдельных плейлистов (ключ — playlistId). Отсутствие ключа — плейлист не в работе. */
    val playlistSyncProgress = mutableStateMapOf<String, YamSyncPlaylistProgress>()
    /** Сколько треков придётся скачать при синхронизации плейлиста (ключ — playlistId). */
    val playlistMissingCounts = mutableStateMapOf<String, Int>()

    private val playlistSyncQueue = Channel<String>(Channel.UNLIMITED)
    private var playlistSyncWorker: Job? = null
    private var activePlaylistSyncId: String? = null
    private var activePlaylistSyncJob: Job? = null

    // 🔒 Сохраненные решения пользователя по слиянию плейлистов (защита от сброса при фоновом автообновлении)
    private val userResolutions = mutableMapOf<String, YamSyncResolution>()
    private val userExplicitlyUnselectedFiles = mutableSetOf<String>()

    private var activeServer: YamSyncServer? = null
    private val client = YamSyncClient()
    private var activePairInfo: YamSyncPairInfo? = null
    private var localManifestCache: YamSyncManifest? = null
    private var remoteManifestCache: YamSyncManifest? = null
    private val localFilePathsByMatchKey = mutableMapOf<String, String>()
    private var pollingJob: Job? = null
    private var isHostingMode = false
    private var hostPairInfo: YamSyncPairInfo? = null
    private var hostQrMatrix: Array<BooleanArray>? = null
    private var consecutivePollFailures = 0

    private val syncJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    init {
        loadKnownDevices()
    }

    private fun loadKnownDevices() {
        try {
            val jsonStr = loadAppConfig(AppConfigKeys.YAMSYNC_KNOWN_DEVICES)
            if (!jsonStr.isNullOrBlank()) {
                val list = syncJson.decodeFromString(
                    ListSerializer(YamSyncKnownDevice.serializer()),
                    jsonStr
                )
                knownDevices.clear()
                knownDevices.addAll(list.sortedByDescending { it.lastSeenMs })
            }
        } catch (e: Exception) {
            println("YamSync: Ошибка загрузки известных устройств: ${e.message}")
        }
    }

    private fun persistKnownDevices() {
        try {
            val jsonStr = syncJson.encodeToString(
                ListSerializer(YamSyncKnownDevice.serializer()),
                knownDevices.toList()
            )
            saveAppConfig(AppConfigKeys.YAMSYNC_KNOWN_DEVICES, jsonStr)
        } catch (e: Exception) {
            println("YamSync: Ошибка сохранения известных устройств: ${e.message}")
        }
    }

    fun saveKnownDevice(pairInfo: YamSyncPairInfo) {
        val existingIdx = knownDevices.indexOfFirst {
            it.name.equals(pairInfo.name, ignoreCase = true) || (it.ip == pairInfo.ip && it.port == pairInfo.port)
        }
        val item = YamSyncKnownDevice(
            name = pairInfo.name,
            ip = pairInfo.ip,
            port = pairInfo.port,
            token = pairInfo.token,
            platform = pairInfo.platform,
            lastSeenMs = currentTimeMillis()
        )
        if (existingIdx >= 0) {
            knownDevices[existingIdx] = item
        } else {
            knownDevices.add(0, item)
        }
        persistKnownDevices()
    }

    fun removeKnownDevice(device: YamSyncKnownDevice) {
        knownDevices.removeAll { it.name == device.name && it.ip == device.ip }
        persistKnownDevices()
    }

    fun connectToKnownDevice(device: YamSyncKnownDevice) {
        connectToPairInfo(device.toPairInfo())
    }

    fun getOrCreateDeviceSyncToken(): String {
        val saved = loadAppConfig(AppConfigKeys.YAMSYNC_DEVICE_TOKEN)
        if (!saved.isNullOrBlank()) return saved
        val newToken = generateRandomSessionToken()
        saveAppConfig(AppConfigKeys.YAMSYNC_DEVICE_TOKEN, newToken)
        return newToken
    }

    private fun handleRemotePeerDisconnected(reason: String) {
        stopBackgroundSyncPolling()
        activePairInfo = null
        remoteManifestCache = null
        userResolutions.clear()
        userExplicitlyUnselectedFiles.clear()
        playlistDiffs.clear()
        missingFiles.clear()
        selectedFiles.clear()
        fileTransfers.clear()

        val hostInfo = hostPairInfo
        val hostQr = hostQrMatrix
        if (isHostingMode && activeServer != null && hostInfo != null && hostQr != null) {
            connectionState = YamSyncConnectionState.Hosting(hostInfo, hostQr)
            statusMessage = "$reason. Ожидание подключения партнёра..."
            println("YamSync: Сервер вернулся в режим ожидания (Hosting): $reason")
        } else {
            stopHosting()
            connectionState = YamSyncConnectionState.Disconnected
            statusMessage = reason
            println("YamSync: Соединение разорвано: $reason")
        }
    }

    private fun startBackgroundSyncPolling() {
        pollingJob?.cancel()
        consecutivePollFailures = 0
        pollingJob = scope.launch(Dispatchers.Main) {
            while (isActive && isConnected) {
                delay(12000)
                if (!isConnected) break
                if (!isDownloadingFiles) {
                    refreshManifestAndDiffSilently()
                }
            }
        }
    }

    private fun stopBackgroundSyncPolling() {
        pollingJob?.cancel()
        pollingJob = null
        consecutivePollFailures = 0
    }

    private fun refreshManifestAndDiffSilently() {
        val pair = activePairInfo ?: return
        scope.launch(DispatcherIO) {
            val localMan = localManifestCache ?: buildLocalManifest().also { localManifestCache = it }
            if (pair.port <= 0) {
                val cached = remoteManifestCache
                if (cached != null) {
                    withContext(Dispatchers.Main) {
                        applyDiffs(localMan, cached)
                    }
                }
                return@launch
            }
            val remoteRes = client.exchangeManifests(pair.ip, pair.port, pair.token, localMan)
            remoteRes.fold(
                onSuccess = { remoteMan ->
                    consecutivePollFailures = 0
                    val oldRemote = remoteManifestCache
                    val hasChanged = oldRemote == null ||
                        oldRemote.playlists.size != remoteMan.playlists.size ||
                        oldRemote.availableFiles.size != remoteMan.availableFiles.size ||
                        oldRemote != remoteMan
                    if (hasChanged) {
                        remoteManifestCache = remoteMan
                        withContext(Dispatchers.Main) {
                            applyDiffs(localMan, remoteMan)
                        }
                    }
                },
                onFailure = {
                    val getRes = client.fetchManifest(pair.ip, pair.port, pair.token)
                    getRes.fold(
                        onSuccess = { remoteMan ->
                            consecutivePollFailures = 0
                            val oldRemote = remoteManifestCache
                            val hasChanged = oldRemote == null ||
                                oldRemote.playlists.size != remoteMan.playlists.size ||
                                oldRemote.availableFiles.size != remoteMan.availableFiles.size ||
                                oldRemote != remoteMan
                            if (hasChanged) {
                                remoteManifestCache = remoteMan
                                withContext(Dispatchers.Main) {
                                    applyDiffs(localMan, remoteMan)
                                }
                            }
                        },
                        onFailure = { err ->
                            consecutivePollFailures++
                            println("YamSync: Сбой фонового опроса ($consecutivePollFailures/2): ${err.message}")
                            if (consecutivePollFailures >= 2) {
                                withContext(Dispatchers.Main) {
                                    handleRemotePeerDisconnected("Связь с устройством потеряна")
                                }
                            }
                        }
                    )
                }
            )
        }
    }

    fun invalidateLocalManifestAndRefresh() {
        localManifestCache = null
        if (isConnected) {
            refreshManifestAndDiff()
        }
    }

    val isConnected: Boolean
        get() = connectionState is YamSyncConnectionState.Connected || connectionState is YamSyncConnectionState.Syncing

    val connectedDevice: YamSyncDevice?
        get() = (connectionState as? YamSyncConnectionState.Connected)?.peer

    private fun parseArtistAndTitleFromFileName(fileName: String): Pair<String, String> {
        val rawName = fileName.substringBeforeLast('.')
        val delimiters = listOf(" — ", " – ", " - ", "_—_", "_-_")
        for (delim in delimiters) {
            if (rawName.contains(delim)) {
                val parts = rawName.split(delim, limit = 2)
                val artist = parts[0].trim()
                val title = parts[1].trim()
                if (artist.isNotBlank() && title.isNotBlank()) {
                    return artist to title
                }
            }
        }

        val slugDelimiters = listOf("---", "--")
        for (delim in slugDelimiters) {
            if (rawName.contains(delim)) {
                val parts = rawName.split(delim, limit = 2)
                val artist = parts[0].replace('-', ' ').replace('_', ' ').trim()
                val title = parts[1].replace('-', ' ').replace('_', ' ').trim()
                if (artist.isNotBlank() && title.isNotBlank()) {
                    return artist to title
                }
            }
        }

        if (rawName.count { it == '-' } == 1) {
            val parts = rawName.split('-', limit = 2)
            val artist = parts[0].trim()
            val title = parts[1].trim()
            if (artist.length >= 2 && title.length >= 2) {
                return artist to title
            }
        }

        val cleanTitle = if (rawName.contains('-') && !rawName.contains(' ')) {
            rawName.replace('-', ' ').trim()
        } else {
            rawName.trim()
        }

        return "Unknown Artist" to cleanTitle.ifBlank { "Track" }
    }

    /** Сформировать актуальный манифест локальной медиатеки */
    fun buildLocalManifest(): YamSyncManifest {
        val basePath = getMusicStoragePath()
        val localPls = loadLocalPlaylists(basePath)
        val downloadedTracks = scanDownloadedTracks(basePath)

        val localDevice = YamSyncDevice(
            id = "device_${currentTimeMillis()}",
            name = getDeviceName(),
            platform = getPlatform().name,
            ip = getLocalIpAddress(),
            port = activeServer?.port ?: 43594
        )

        val availableFiles = mutableListOf<YamSyncTrack>()
        val seenAvailablePaths = mutableSetOf<String>()
        val seenAvailableFileNames = mutableSetOf<String>()

        fun addTrackIfNew(cleanPath: String, track: FullTrackInfo) {
            val fn = cleanPath.substringAfterLast('/').substringAfterLast('\\')
            if (fn.isBlank()) return
            val fnLower = fn.lowercase()
            val pathLower = cleanPath.lowercase()
            if (pathLower in seenAvailablePaths || fnLower in seenAvailableFileNames) return

            val artistName = track.artists.firstOrNull()?.name?.trim()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist"
            val titleName = track.title.trim().ifBlank { fn.substringBeforeLast('.') }
            val syncTrack = YamSyncTrack(
                fileName = fn,
                artist = artistName,
                title = titleName,
                album = track.albums.firstOrNull()?.title ?: "",
                durationMs = track.durationMs,
                fileSize = getFileSize(cleanPath),
                checksum = "${artistName.lowercase()}_${titleName.lowercase()}"
            )
            availableFiles.add(syncTrack)
            seenAvailablePaths.add(pathLower)
            seenAvailableFileNames.add(fnLower)
        }

        for (track in downloadedTracks) {
            val cleanPath = track.realId?.ifBlank { track.id.removePrefix("local:") } ?: track.id.removePrefix("local:")
            addTrackIfNew(cleanPath, track)
        }

        // Также обязательно добавляем все существующие на диске локальные файлы из плейлистов
        val allPlaylistPaths = localPls.flatMap { it.trackPaths }.distinct()
        val playlistTracksInfo = getTracksFromLocalPaths(allPlaylistPaths)
        for (track in playlistTracksInfo) {
            val cleanPath = track.realId?.ifBlank { track.id.removePrefix("local:") } ?: track.id.removePrefix("local:")
            addTrackIfNew(cleanPath, track)
        }

        // Для путей из плейлистов, которые не вернулись в getTracksFromLocalPaths, но реально есть на диске
        for (pl in localPls) {
            for (path in pl.trackPaths) {
                val clean = path.removePrefix("local:").removePrefix("file://").removePrefix("file:")
                val fn = clean.substringAfterLast('/').substringAfterLast('\\')
                if (fn.isBlank() || fn.lowercase() in seenAvailableFileNames) continue
                val existing = when {
                    localFileExists(clean) -> clean
                    localFileExists(path) -> path
                    else -> resolveLocalPath(clean).takeIf { localFileExists(it) }
                }
                if (existing != null) {
                    val (artist, title) = parseArtistAndTitleFromFileName(fn)
                    val syncTrack = YamSyncTrack(
                        fileName = fn,
                        artist = artist,
                        title = title,
                        durationMs = 0L,
                        fileSize = getFileSize(existing),
                        checksum = "${artist.lowercase()}_${title.lowercase()}"
                    )
                    availableFiles.add(syncTrack)
                    seenAvailablePaths.add(existing.lowercase())
                    seenAvailableFileNames.add(fn.lowercase())
                }
            }
        }

        // Кэшируем прямые пути для быстрого O(1) поиска сервером при отдаче файлов
        localFilePathsByMatchKey.clear()
        fun registerPath(key: String?, targetPath: String) {
            if (key.isNullOrBlank()) return
            localFilePathsByMatchKey[key.trim().lowercase()] = targetPath
        }

        for (track in downloadedTracks) {
            val cleanPath = track.realId?.ifBlank { track.id.removePrefix("local:") } ?: track.id.removePrefix("local:")
            val fn = cleanPath.substringAfterLast('/').substringAfterLast('\\')
            val artistName = track.artists.firstOrNull()?.name?.trim()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist"
            val titleName = track.title.trim().ifBlank { fn.substringBeforeLast('.') }
            val key = "${artistName.lowercase()}_${titleName.lowercase()}"
            registerPath(fn, cleanPath)
            registerPath(sanitizeKeepSpaces(fn), cleanPath)
            registerPath(key, cleanPath)
        }

        for (track in availableFiles) {
            val fn = track.fileName
            val path = seenAvailablePaths.firstOrNull { it.endsWith(fn.lowercase()) && localFileExists(it) }
            if (path != null) {
                registerPath(fn, path)
                registerPath(sanitizeKeepSpaces(fn), path)
                registerPath(track.checksum, path)
                registerPath(track.matchKey, path)
            }
        }

        for (pl in localPls) {
            for (path in pl.trackPaths) {
                val clean = path.removePrefix("local:").removePrefix("file://").removePrefix("file:")
                val existing = when {
                    localFileExists(clean) -> clean
                    localFileExists(path) -> path
                    else -> resolveLocalPath(clean).takeIf { localFileExists(it) }
                }
                if (existing != null) {
                    val fn = clean.substringAfterLast('/').substringAfterLast('\\')
                    registerPath(fn, existing)
                    registerPath(sanitizeKeepSpaces(fn), existing)
                    val matchedTrack = availableFiles.firstOrNull {
                        it.fileName.equals(fn, ignoreCase = true) ||
                        it.fileName.equals(sanitizeKeepSpaces(fn), ignoreCase = true)
                    }
                    if (matchedTrack != null) {
                        registerPath(matchedTrack.checksum, existing)
                        registerPath(matchedTrack.matchKey, existing)
                    }
                }
            }
        }

        val syncPlaylists = localPls.map { pl ->
            val plTracks = pl.trackPaths.mapNotNull { path ->
                val fileName = path.substringAfterLast('/').substringAfterLast('\\')
                val cleanFileName = sanitizeKeepSpaces(fileName)
                availableFiles.firstOrNull {
                    it.fileName.equals(fileName, ignoreCase = true) ||
                    it.fileName.equals(cleanFileName, ignoreCase = true) ||
                    YamSyncDiffEngine.areTracksMatching(it, YamSyncTrack(fileName = fileName, artist = "", title = ""))
                } ?: run {
                    val (artist, title) = parseArtistAndTitleFromFileName(fileName)
                    YamSyncTrack(
                        fileName = fileName,
                        artist = artist,
                        title = title,
                        durationMs = 0L,
                        fileSize = 0L,
                        checksum = "${artist.lowercase()}_${title.lowercase()}"
                    )
                }
            }.distinctBy { it.matchKey }

            YamSyncPlaylist(
                id = pl.id,
                title = pl.title,
                description = pl.description,
                createdAt = pl.createdAt,
                updatedAt = pl.updatedAt,
                tracks = plTracks
            )
        }

        val manifest = YamSyncManifest(
            device = localDevice,
            playlists = syncPlaylists,
            availableFiles = availableFiles,
            generatedAt = currentTimeMillis()
        )
        localManifestCache = manifest
        return manifest
    }

    private fun handleRemoteManifest(remoteMan: YamSyncManifest) {
        scope.launch(Dispatchers.Main) {
            remoteManifestCache = remoteMan
            val localMan = localManifestCache ?: buildLocalManifest()
            applyDiffs(localMan, remoteMan)
        }
    }

    internal fun applyDiffs(localMan: YamSyncManifest, remoteMan: YamSyncManifest) {
        localManifestCache = localMan
        remoteManifestCache = remoteMan

        val rawDiffs = YamSyncDiffEngine.calculateDiff(localMan, remoteMan)

        // Восстанавливаем выбранные пользователем правила слияния (resolution)
        val resolvedDiffs = rawDiffs.map { diff ->
            val normTitle = YamSyncDiffEngine.normalizeTitle(diff.title)
            val savedRes = userResolutions[diff.playlistId]
                ?: userResolutions[normTitle]
                ?: playlistDiffs.firstOrNull {
                    it.playlistId == diff.playlistId || YamSyncDiffEngine.normalizeTitle(it.title) == normTitle
                }?.resolution

            if (savedRes != null) diff.copy(resolution = savedRes) else diff
        }

        // Обновляем список плейлистов без резкого сброса (без .clear()) во избежание мерцания UI
        val newMap = resolvedDiffs.associateBy { it.playlistId }
        playlistDiffs.removeAll { it.playlistId !in newMap }
        resolvedDiffs.forEachIndexed { index, diff ->
            val existingIndex = playlistDiffs.indexOfFirst { it.playlistId == diff.playlistId }
            if (existingIndex >= 0) {
                if (playlistDiffs[existingIndex] != diff) {
                    playlistDiffs[existingIndex] = diff
                }
            } else {
                if (index <= playlistDiffs.size) {
                    playlistDiffs.add(index, diff)
                } else {
                    playlistDiffs.add(diff)
                }
            }
        }

        recomputeMissingFilesInternal()
    }

    private fun recomputeMissingFilesInternal() {
        val localMan = localManifestCache ?: return
        val remoteMan = remoteManifestCache ?: return

        val merged = YamSyncDiffEngine.mergePlaylists(playlistDiffs)
        val missing = YamSyncDiffEngine.findMissingFiles(merged, localMan.availableFiles, remoteMan.availableFiles)

        val newMissingKeys = missing.map { it.matchKey }.toSet()
        missingFiles.clear()
        missingFiles.addAll(missing)

        selectedFiles.retainAll(newMissingKeys)
        missing.forEach { track ->
            if (track.matchKey !in userExplicitlyUnselectedFiles) {
                selectedFiles.add(track.matchKey)
            }
        }

        val counts = playlistDiffs.associate { diff ->
            val single = YamSyncDiffEngine.mergePlaylists(listOf(diff.copy(resolution = effectiveResolution(diff))))
            diff.playlistId to YamSyncDiffEngine.findMissingFiles(single, localMan.availableFiles, remoteMan.availableFiles).size
        }
        playlistMissingCounts.keys.retainAll(counts.keys)
        playlistMissingCounts.putAll(counts)
    }

    /** Плейлист, который есть только на другом устройстве, всегда забираем целиком. */
    fun effectiveResolution(diff: YamSyncPlaylistDiff): YamSyncResolution =
        if (diff.state == YamSyncDiffState.NEW_REMOTE) YamSyncResolution.TAKE_REMOTE else diff.resolution

    private fun resolveFilePathInternal(fileName: String, checksum: String): String? {
        val rawName = fileName.trim()
        if (rawName.isBlank() || rawName.contains("..") || rawName.startsWith("/") || rawName.startsWith("\\") || (rawName.length >= 2 && rawName[1] == ':')) {
            return null
        }
        val safeName = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (safeName.isBlank()) return null

        val cleanFn = sanitizeKeepSpaces(safeName).trim()
        val base = getMusicStoragePath().trim()

        // Helper to test if a candidate path exists on disk and return its resolved path
        fun verify(candidate: String?): String? {
            if (candidate.isNullOrBlank()) return null
            val clean = candidate.trim().removePrefix("local:").removePrefix("file://").removePrefix("file:")
            val res = resolveLocalPath(clean)
            if (res.isNotBlank() && localFileExists(res)) return res
            if (localFileExists(clean)) return clean
            return null
        }

        // 1. O(1) поиск по сохраненным прямым путям локальной медиатеки
        val direct = localFilePathsByMatchKey[cleanFn.lowercase()]
            ?: localFilePathsByMatchKey[safeName.lowercase()]
            ?: if (checksum.isNotBlank()) localFilePathsByMatchKey[checksum.trim().lowercase()] else null
        verify(direct)?.let { return it }

        // 2. Поиск внутри базовой папки музыки (basePath)
        if (base.isNotBlank()) {
            verify("$base/$cleanFn")?.let { return it }
            verify("$base/$safeName")?.let { return it }
        }

        // 3. Поиск по уже сформированному кэшу доступных файлов манифеста
        val cachedTrack = localManifestCache?.availableFiles?.firstOrNull {
            it.fileName.equals(safeName, ignoreCase = true) ||
            it.fileName.equals(cleanFn, ignoreCase = true) ||
            (checksum.isNotBlank() && it.checksum.equals(checksum, ignoreCase = true)) ||
            (checksum.isNotBlank() && it.matchKey.equals(checksum, ignoreCase = true)) ||
            YamSyncDiffEngine.areTracksMatching(
                it,
                YamSyncTrack(fileName = safeName, artist = "", title = "", checksum = checksum)
            )
        }
        if (cachedTrack != null) {
            val directCached = localFilePathsByMatchKey[cachedTrack.matchKey] ?: localFilePathsByMatchKey[cachedTrack.fileName.lowercase()]
            verify(directCached)?.let { return it }

            if (base.isNotBlank()) {
                val cleanArt = sanitizeDirName(cachedTrack.artist.ifBlank { "Unknown Artist" })
                val fn = sanitizeKeepSpaces(cachedTrack.fileName)
                val candidates = listOf(
                    "$base/YamSync/$cleanArt/$fn",
                    "$base/$cleanArt/$fn",
                    "$base/HQ/$cleanArt/$fn",
                    "$base/LQ/$cleanArt/$fn",
                    "$base/YamSync/$fn",
                    "$base/$fn",
                    "$base/HQ/$fn",
                    "$base/LQ/$fn"
                )
                for (cand in candidates) {
                    verify(cand)?.let { return it }
                }
            }
        }

        // 4. Fallback через scanDownloadedTracks
        if (base.isNotBlank()) {
            val downloaded = scanDownloadedTracks(base)
            val byName = downloaded.firstOrNull {
                val p = it.realId ?: it.id.removePrefix("local:")
                val fn = p.substringAfterLast('/').substringAfterLast('\\')
                fn.equals(safeName, ignoreCase = true) || fn.equals(cleanFn, ignoreCase = true) ||
                YamSyncDiffEngine.areTracksMatching(
                    YamSyncTrack(fileName = fn, artist = it.artists.firstOrNull()?.name ?: "", title = it.title),
                    YamSyncTrack(fileName = safeName, artist = "", title = "", checksum = checksum)
                )
            }
            if (byName != null) {
                val p = byName.realId ?: byName.id.removePrefix("local:")
                verify(p)?.let { return it }
            }

            if (checksum.isNotBlank()) {
                val cleanCheck = checksum.trim().lowercase()
                val byChecksum = downloaded.firstOrNull {
                    val artist = it.artists.firstOrNull()?.name?.trim()?.lowercase() ?: ""
                    val title = it.title.trim().lowercase()
                    "${artist}_${title}" == cleanCheck
                }
                if (byChecksum != null) {
                    val p = byChecksum.realId ?: byChecksum.id.removePrefix("local:")
                    verify(p)?.let { return it }
                }
            }
        }

        return null
    }

    /** Запустить режим раздачи (Host): поднять сервер и показать QR-код */
    fun startHosting() {
        stopHosting()
        errorMessage = null
        statusMessage = "Запуск сервера YamSync..."

        val token = getOrCreateDeviceSyncToken()
        val ip = getLocalIpAddress()
        val localDevice = YamSyncDevice(
            id = "host_${currentTimeMillis()}",
            name = getDeviceName(),
            platform = getPlatform().name,
            ip = ip,
            port = 43594
        )

        val server = YamSyncServer(
            initialPort = 43594,
            token = token,
            localDevice = localDevice,
            getManifest = { buildLocalManifest() },
            resolveFilePath = { fileName, checksum -> resolveFilePathInternal(fileName, checksum) },
            onMergeReceived = { payload ->
                applyRemoteMergedPlaylists(payload.playlists)
            },
            onClientConnected = { clientDevice ->
                scope.launch(Dispatchers.Main) {
                    val pairInfo = YamSyncPairInfo(
                        ip = clientDevice.ip,
                        port = clientDevice.port,
                        token = token,
                        name = clientDevice.name,
                        platform = clientDevice.platform
                    )
                    activePairInfo = pairInfo
                    consecutivePollFailures = 0
                    saveKnownDevice(pairInfo)
                    startBackgroundSyncPolling()
                    connectionState = YamSyncConnectionState.Connected(clientDevice, pairInfo)
                    statusMessage = "Устройство подключено: ${clientDevice.name}"
                    if (remoteManifestCache == null && clientDevice.port > 0) {
                        refreshManifestAndDiff()
                    } else if (remoteManifestCache != null) {
                        val localMan = localManifestCache ?: buildLocalManifest()
                        applyDiffs(localMan, remoteManifestCache!!)
                    }
                }
            },
            onManifestReceived = { remoteMan -> handleRemoteManifest(remoteMan) },
            onClientDisconnected = {
                scope.launch(Dispatchers.Main) {
                    handleRemotePeerDisconnected("Клиент отключился")
                }
            }
        )

        val assignedPort = server.start()
        activeServer = server
        isHostingMode = true

        val pairInfo = YamSyncPairInfo(
            ip = ip,
            port = assignedPort,
            token = token,
            name = getDeviceName(),
            platform = getPlatform().name
        )
        hostPairInfo = pairInfo

        val qrMatrix = QrCodeGenerator.encode(pairInfo.toHttpUrl(), QrCodeGenerator.EccLevel.L)
        hostQrMatrix = qrMatrix
        connectionState = YamSyncConnectionState.Hosting(pairInfo, qrMatrix)
        statusMessage = "Ожидание подключения партнёра..."
    }

    /** Остановить сервер и режим раздачи */
    fun stopHosting() {
        isHostingMode = false
        hostPairInfo = null
        hostQrMatrix = null
        stopBackgroundSyncPolling()
        activeServer?.stop()
        activeServer = null
        if (connectionState is YamSyncConnectionState.Hosting) {
            connectionState = YamSyncConnectionState.Disconnected
        }
    }

    /** Подключиться к удаленному устройству по URI из QR-кода */
    fun connectToPeer(uriString: String) {
        val pairInfo = YamSyncPairInfo.parse(uriString)
        if (pairInfo == null) {
            errorMessage = "Неверный формат ссылки YamSync"
            return
        }
        connectToPairInfo(pairInfo)
    }

    /** Подключиться к удаленному устройству */
    fun connectToPairInfo(pairInfo: YamSyncPairInfo) {
        stopHosting()
        isHostingMode = false
        errorMessage = null
        activePairInfo = pairInfo
        connectionState = YamSyncConnectionState.Connecting("Подключение к ${pairInfo.name} (${pairInfo.ip})...")

        scope.launch(Dispatchers.Main) {
            val token = pairInfo.token
            val clientDev = YamSyncDevice(
                id = "client_${currentTimeMillis()}",
                name = getDeviceName(),
                platform = getPlatform().name,
                ip = getLocalIpAddress(),
                port = 0
            )

            // Запускаем сервер на клиенте с динамическим портом (0), чтобы хост мог при необходимости запрашивать файлы
            val clientServer = YamSyncServer(
                initialPort = 0,
                token = token,
                localDevice = clientDev,
                getManifest = { buildLocalManifest() },
                resolveFilePath = { fileName, checksum -> resolveFilePathInternal(fileName, checksum) },
                onMergeReceived = { payload -> applyRemoteMergedPlaylists(payload.playlists) },
                onClientConnected = { },
                onManifestReceived = { remoteMan -> handleRemoteManifest(remoteMan) },
                onClientDisconnected = {
                    scope.launch(Dispatchers.Main) {
                        handleRemotePeerDisconnected("Сервер разорвал соединение")
                    }
                }
            )
            val assignedPort = clientServer.start()
            activeServer = clientServer

            val devWithPort = clientDev.copy(port = assignedPort)

            val pairRes = withContext(DispatcherIO) {
                client.pair(pairInfo.ip, pairInfo.port, pairInfo.token, devWithPort)
            }

            pairRes.fold(
                onSuccess = { remoteDev ->
                    consecutivePollFailures = 0
                    saveKnownDevice(pairInfo.copy(name = remoteDev.name, platform = remoteDev.platform))
                    startBackgroundSyncPolling()
                    connectionState = YamSyncConnectionState.Connected(remoteDev, pairInfo)
                    statusMessage = "Связано с ${remoteDev.name}"
                    refreshManifestAndDiff()
                },
                onFailure = { err ->
                    stopHosting()
                    stopBackgroundSyncPolling()
                    connectionState = YamSyncConnectionState.Disconnected
                    errorMessage = "Не удалось подключиться: ${err.message}"
                }
            )
        }
    }

    /** Разорвать текущее соединение */
    fun disconnect() {
        val pair = activePairInfo
        val token = pair?.token ?: hostPairInfo?.token
        if (pair != null && token != null) {
            scope.launch(DispatcherIO) {
                try {
                    client.notifyDisconnect(pair.ip, pair.port, token)
                } catch (_: Exception) {}
            }
        }
        handleRemotePeerDisconnected("Связь разорвана")
    }

    /** Запросить свежий манифест с удаленного устройства и пересчитать diff */
    fun refreshManifestAndDiff() {
        val pair = activePairInfo ?: return
        scope.launch(Dispatchers.Main) {
            val localMan = buildLocalManifest()

            // Если удаленный порт <= 0, хост полагается на полученный кэш манифеста без сетевых вызовов в несуществующий порт
            if (pair.port <= 0) {
                val cached = remoteManifestCache
                if (cached != null) {
                    applyDiffs(localMan, cached)
                }
                return@launch
            }

            // Пробуем exchangeManifests (POST /manifest) для мгновенного двустороннего обмена манифестами
            val remoteRes = withContext(DispatcherIO) {
                client.exchangeManifests(pair.ip, pair.port, pair.token, localMan)
            }

            remoteRes.fold(
                onSuccess = { remoteMan ->
                    remoteManifestCache = remoteMan
                    applyDiffs(localMan, remoteMan)
                },
                onFailure = { exchangeErr ->
                    // Fallback на обычный GET /manifest
                    val getRes = withContext(DispatcherIO) {
                        client.fetchManifest(pair.ip, pair.port, pair.token)
                    }
                    getRes.fold(
                        onSuccess = { remoteMan ->
                            remoteManifestCache = remoteMan
                            applyDiffs(localMan, remoteMan)
                        },
                        onFailure = { getErr ->
                            val cached = remoteManifestCache
                            if (cached != null) {
                                applyDiffs(localMan, cached)
                            } else {
                                errorMessage = "Ошибка получения манифеста: ${getErr.message}"
                            }
                        }
                    )
                }
            )
        }
    }

    /** Изменить стратегию слияния для конкретного плейлиста */
    fun setResolution(playlistId: String, resolution: YamSyncResolution) {
        userResolutions[playlistId] = resolution
        val idx = playlistDiffs.indexOfFirst { it.playlistId == playlistId }
        if (idx >= 0) {
            val cur = playlistDiffs[idx]
            userResolutions[YamSyncDiffEngine.normalizeTitle(cur.title)] = resolution
            playlistDiffs[idx] = cur.copy(resolution = resolution)
            recomputeMissingFilesInternal()
        }
    }

    /** Переключить выбор файла для загрузки */
    fun toggleFileSelection(trackKey: String) {
        if (selectedFiles.contains(trackKey)) {
            selectedFiles.remove(trackKey)
            userExplicitlyUnselectedFiles.add(trackKey)
        } else {
            selectedFiles.add(trackKey)
            userExplicitlyUnselectedFiles.remove(trackKey)
        }
    }

    /** Выбрать или снять выбор со всех файлов */
    fun toggleSelectAllFiles() {
        if (selectedFiles.size == missingFiles.size) {
            selectedFiles.clear()
            userExplicitlyUnselectedFiles.addAll(missingFiles.map { it.matchKey })
        } else {
            userExplicitlyUnselectedFiles.clear()
            selectedFiles.clear()
            selectedFiles.addAll(missingFiles.map { it.matchKey })
        }
    }

    /**
     * Поставить плейлист в очередь синхронизации. [resolution] — явно выбранное действие из меню,
     * иначе используется текущее (по умолчанию «Объединить»).
     */
    fun syncPlaylist(playlistId: String, resolution: YamSyncResolution? = null) {
        if (resolution != null) setResolution(playlistId, resolution)
        if (playlistSyncProgress.containsKey(playlistId)) return
        playlistSyncProgress[playlistId] = YamSyncPlaylistProgress(isQueued = true)
        ensurePlaylistSyncWorker()
        playlistSyncQueue.trySend(playlistId)
    }

    /** Синхронизировать все плейлисты, которые отличаются на устройствах. */
    fun syncAllPlaylists() {
        playlistDiffs.filter { it.state != YamSyncDiffState.IDENTICAL }.forEach { syncPlaylist(it.playlistId) }
    }

    /** Остановить синхронизацию плейлиста или убрать его из очереди. */
    fun cancelPlaylistSync(playlistId: String) {
        if (activePlaylistSyncId == playlistId) {
            activePlaylistSyncJob?.cancel()
        } else {
            playlistSyncProgress.remove(playlistId)
        }
    }

    private fun ensurePlaylistSyncWorker() {
        if (playlistSyncWorker?.isActive == true) return
        playlistSyncWorker = scope.launch(Dispatchers.Main) {
            for (playlistId in playlistSyncQueue) {
                // Пропускаем отменённые, пока ждали в очереди
                if (playlistSyncProgress[playlistId]?.isQueued != true) continue
                // Не пересекаемся с ручной загрузкой на вкладке «Треки»
                snapshotFlow { isDownloadingFiles }.first { !it }

                val job = launch { runPlaylistSync(playlistId) }
                activePlaylistSyncId = playlistId
                activePlaylistSyncJob = job
                job.join()
                activePlaylistSyncId = null
                activePlaylistSyncJob = null
                playlistSyncProgress.remove(playlistId)
            }
        }
    }

    private suspend fun runPlaylistSync(playlistId: String) {
        val diff = playlistDiffs.firstOrNull { it.playlistId == playlistId } ?: return
        val pair = activePairInfo ?: return
        val localMan = localManifestCache ?: return
        val remoteMan = remoteManifestCache ?: return
        val resolution = effectiveResolution(diff)
        val merged = YamSyncDiffEngine.mergePlaylists(listOf(diff.copy(resolution = resolution))).firstOrNull() ?: return

        isDownloadingFiles = true
        errorMessage = null
        playlistSyncProgress[playlistId] = YamSyncPlaylistProgress()
        try {
            // 1. Сохраняем итоговый плейлист у себя под своим id, чтобы не создать дубликат
            if (resolution != YamSyncResolution.KEEP_LOCAL) {
                val localCopy = merged.copy(id = diff.localPlaylist?.id ?: merged.id)
                withContext(DispatcherIO) { applyRemoteMergedPlaylists(listOf(localCopy)) }
            }

            // 2. Отправляем его на другое устройство под его id
            if (resolution != YamSyncResolution.TAKE_REMOTE && pair.port > 0) {
                val remoteCopy = merged.copy(id = diff.remotePlaylist?.id ?: merged.id)
                withContext(DispatcherIO) {
                    client.sendMerge(pair.ip, pair.port, pair.token, YamSyncMergePayload(listOf(remoteCopy)))
                }.onFailure { err -> errorMessage = "Не удалось отправить «${diff.title}»: ${err.message}" }
            }

            // 3. Скачиваем недостающие треки этого плейлиста
            val missing = if (resolution == YamSyncResolution.KEEP_LOCAL) emptyList() else
                YamSyncDiffEngine.findMissingFiles(listOf(merged), localMan.availableFiles, remoteMan.availableFiles)
            if (missing.isNotEmpty()) {
                playlistSyncProgress[playlistId] = YamSyncPlaylistProgress(done = 0, total = missing.size)
                val result = downloadTracksInternal(pair, missing) { index, total, _ ->
                    playlistSyncProgress[playlistId] = YamSyncPlaylistProgress(done = index - 1, total = total)
                }
                reportDownloadResult(result)
            } else {
                statusMessage = "«${diff.title}» синхронизирован"
            }
        } catch (e: CancellationException) {
            statusMessage = "Синхронизация «${diff.title}» остановлена"
            throw e
        } finally {
            isDownloadingFiles = false
            userResolutions.remove(playlistId)
            userResolutions.remove(YamSyncDiffEngine.normalizeTitle(diff.title))
            onPlaylistsUpdated()
            refreshManifestAndDiff()
        }
    }

    /** Скачать выбранные на вкладке «Треки» недостающие аудиофайлы */
    fun downloadSelectedFiles() {
        if (isDownloadingFiles) return
        val pair = activePairInfo ?: return
        val toDownload = missingFiles.filter { it.matchKey in selectedFiles }
        if (toDownload.isEmpty()) return

        isDownloadingFiles = true
        errorMessage = null
        scope.launch(Dispatchers.Main) {
            try {
                val result = downloadTracksInternal(pair, toDownload) { index, total, track ->
                    statusMessage = "Загрузка: $index/$total (${track.title})"
                }
                reportDownloadResult(result)
                refreshManifestAndDiff()
                onPlaylistsUpdated()
            } finally {
                isDownloadingFiles = false
            }
        }
    }

    private class DownloadResult(val total: Int, val success: Int, val failed: Int, val errors: List<String>)

    private fun reportDownloadResult(result: DownloadResult) {
        when {
            result.failed == 0 -> statusMessage = "Скачано треков: ${result.success}"
            result.success > 0 -> {
                statusMessage = "Скачано ${result.success} из ${result.total}"
                errorMessage = "Не удалось скачать некоторые файлы:\n" + result.errors.take(3).joinToString("\n")
            }
            else -> {
                statusMessage = null
                errorMessage = "Ошибки загрузки файлов:\n" + result.errors.take(3).joinToString("\n")
            }
        }
    }

    /**
     * Последовательно скачивает треки с другого устройства в папку YamSync и обновляет пути
     * в локальных плейлистах. [onTrackStart] вызывается перед каждым треком (номер с единицы).
     */
    private suspend fun downloadTracksInternal(
        pair: YamSyncPairInfo,
        toDownload: List<YamSyncTrack>,
        onTrackStart: (index: Int, total: Int, track: YamSyncTrack) -> Unit
    ): DownloadResult {
        val totalCount = toDownload.size
        var successCount = 0
        var failCount = 0
        val errorDetails = mutableListOf<String>()
        val basePath = getMusicStoragePath()
        val downloadedFilePathsByFileName = mutableMapOf<String, String>()

        toDownload.forEachIndexed { i, track ->
            onTrackStart(i + 1, totalCount, track)
            val cleanArtist = sanitizeDirName(track.artist.ifBlank { "Unknown Artist" })
            val cleanFileName = sanitizeKeepSpaces(track.fileName)
            val syncBasePath = if (basePath.endsWith('/') || basePath.endsWith('\\')) "${basePath}YamSync" else "$basePath/YamSync"
            val sep = if (basePath.contains('\\')) "\\" else "/"
            val savedFilePath = "${syncBasePath.trimEnd('/', '\\')}$sep$cleanArtist$sep$cleanFileName"

            val res = withContext(DispatcherIO) {
                runCatching { openTrackFileWriter(syncBasePath, cleanArtist, cleanFileName) }.mapCatching { writer ->
                    client.downloadTrackToFile(pair.ip, pair.port, pair.token, track, writer) { progress ->
                        fileTransfers[track.matchKey] = progress
                    }.getOrThrow()
                }
            }

            res.fold(
                onSuccess = { size ->
                    downloadedFilePathsByFileName[cleanFileName.lowercase()] = savedFilePath
                    downloadedFilePathsByFileName[track.fileName.lowercase()] = savedFilePath
                    successCount++
                    fileTransfers[track.matchKey] = YamSyncTransferProgress(
                        fileName = track.fileName,
                        trackTitle = "${track.artist} — ${track.title}",
                        bytesTransferred = size,
                        totalBytes = size,
                        isCompleted = true
                    )
                },
                onFailure = { err ->
                    if (err is CancellationException) throw err
                    failCount++
                    val msg = err.message ?: err.toString()
                    errorDetails.add("${track.title}: $msg")
                    println("YamSync: ❌ Ошибка скачивания файла ${track.fileName}: $msg")
                    fileTransfers[track.matchKey] = YamSyncTransferProgress(
                        fileName = track.fileName,
                        trackTitle = "${track.artist} — ${track.title}",
                        error = msg,
                        isCompleted = true
                    )
                }
            )
        }

        // Обновляем пути в локальных плейлистах с учетом скачанных файлов
        if (successCount > 0) {
            withContext(DispatcherIO) {
                val freshDownloaded = scanDownloadedTracks(basePath)
                val allLocal = loadLocalPlaylists(basePath)
                val updatedLocal = allLocal.map { pl ->
                    val updatedPaths = pl.trackPaths.map { originalPath ->
                        val fileName = originalPath.substringAfterLast('/').substringAfterLast('\\')
                        val cleanFileName = sanitizeKeepSpaces(fileName)
                        val directSaved = downloadedFilePathsByFileName[cleanFileName.lowercase()]
                            ?: downloadedFilePathsByFileName[fileName.lowercase()]

                        val matched = freshDownloaded.firstOrNull {
                            val p = it.realId ?: it.id.removePrefix("local:")
                            val fn = p.substringAfterLast('/').substringAfterLast('\\')
                            fn.equals(fileName, ignoreCase = true) || fn.equals(cleanFileName, ignoreCase = true)
                        }
                        directSaved
                            ?: matched?.let { it.realId?.ifBlank { it.id.removePrefix("local:") } ?: it.id.removePrefix("local:") }
                            ?: resolveLocalPath(originalPath).takeIf { localFileExists(it) }
                            ?: originalPath
                    }.distinctBy { it.substringAfterLast('/').substringAfterLast('\\').lowercase() }
                    pl.copy(trackPaths = updatedPaths)
                }
                saveLocalPlaylists(basePath, updatedLocal)
            }
        }

        return DownloadResult(totalCount, successCount, failCount, errorDetails)
    }

    private fun applyRemoteMergedPlaylists(remotePlaylists: List<YamSyncPlaylist>) {
        val basePath = getMusicStoragePath()
        val currentLocal = loadLocalPlaylists(basePath)
        val downloadedTracks = scanDownloadedTracks(basePath)

        val updatedList = remotePlaylists.map { syncPl ->
            val trackPaths = syncPl.tracks.mapNotNull { syncTrack ->
                val cleanArtist = sanitizeDirName(syncTrack.artist.ifBlank { "Unknown Artist" })
                val cleanFileName = sanitizeKeepSpaces(syncTrack.fileName)
                val sep = if (basePath.contains('\\')) "\\" else "/"
                val fallbackCandidate = "${basePath.trimEnd('/', '\\')}${sep}YamSync$sep$cleanArtist$sep$cleanFileName"

                // 1. Проверяем, существует ли уже файл на диске
                val existingOnDisk = resolveLocalPath(syncTrack.fileName).takeIf { localFileExists(it) }
                    ?: resolveLocalPath(fallbackCandidate).takeIf { localFileExists(it) }
                if (existingOnDisk != null) return@mapNotNull existingOnDisk

                // 2. Ищем среди уже скачанных треков
                val found = downloadedTracks.firstOrNull {
                    val fn = (it.realId ?: it.id.removePrefix("local:")).substringAfterLast('/').substringAfterLast('\\')
                    fn.equals(syncTrack.fileName, ignoreCase = true) ||
                    fn.equals(cleanFileName, ignoreCase = true) ||
                    it.title.equals(syncTrack.title, ignoreCase = true)
                }
                found?.let { it.realId?.ifBlank { it.id.removePrefix("local:") } ?: it.id.removePrefix("local:") }
                    ?: fallbackCandidate
            }.distinctBy { it.substringAfterLast('/').substringAfterLast('\\').lowercase() }

            LocalPlaylist(
                id = syncPl.id,
                title = syncPl.title,
                description = syncPl.description,
                createdAt = syncPl.createdAt,
                updatedAt = syncPl.updatedAt,
                trackPaths = trackPaths
            )
        }

        // Сохраняем локальные плейлисты, которых не было в remotePlaylists
        val updatedIds = updatedList.map { it.id }.toSet()
        val untouchedLocal = currentLocal.filter { it.id !in updatedIds }
        val combinedPlaylists = updatedList + untouchedLocal

        saveLocalPlaylists(basePath, combinedPlaylists)
    }
}
