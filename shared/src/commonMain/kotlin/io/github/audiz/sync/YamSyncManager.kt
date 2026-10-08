package io.github.audiz.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import io.github.audiz.*
import io.github.audiz.models.*
import kotlinx.coroutines.*

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

    val playlistDiffs = mutableStateListOf<YamSyncPlaylistDiff>()
    val missingFiles = mutableStateListOf<YamSyncTrack>()
    val selectedFiles = mutableStateSetOf<String>()
    val fileTransfers = mutableStateMapOf<String, YamSyncTransferProgress>()

    private var activeServer: YamSyncServer? = null
    private val client = YamSyncClient()
    private var activePairInfo: YamSyncPairInfo? = null
    private var localManifestCache: YamSyncManifest? = null
    private var remoteManifestCache: YamSyncManifest? = null

    val isConnected: Boolean
        get() = connectionState is YamSyncConnectionState.Connected || connectionState is YamSyncConnectionState.Syncing

    val connectedDevice: YamSyncDevice?
        get() = (connectionState as? YamSyncConnectionState.Connected)?.peer

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

        val availableFiles = downloadedTracks.map { track ->
            val cleanPath = track.realId?.ifBlank { track.id.removePrefix("local:") } ?: track.id.removePrefix("local:")
            val fileName = cleanPath.substringAfterLast('/').substringAfterLast('\\')
            val artistName = track.artists.firstOrNull()?.name?.trim()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist"
            val titleName = track.title.trim().ifBlank { fileName.substringBeforeLast('.') }
            YamSyncTrack(
                fileName = fileName,
                artist = artistName,
                title = titleName,
                album = track.albums.firstOrNull()?.title ?: "",
                durationMs = track.durationMs,
                fileSize = getFileSize(cleanPath),
                checksum = "${artistName.lowercase()}_${titleName.lowercase()}"
            )
        }

        val syncPlaylists = localPls.map { pl ->
            val plTracks = pl.trackPaths.mapNotNull { path ->
                val fileName = path.substringAfterLast('/').substringAfterLast('\\')
                availableFiles.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) } ?: run {
                    val rawName = fileName.substringBeforeLast('.')
                    val delimiters = listOf(" — ", " – ", " - ", "_—_", "_-_")
                    var artist = "Unknown Artist"
                    var title = rawName
                    for (delim in delimiters) {
                        if (rawName.contains(delim)) {
                            val parts = rawName.split(delim, limit = 2)
                            artist = parts[0].trim()
                            title = parts[1].trim()
                            break
                        }
                    }
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

    /** Запустить режим раздачи (Host): поднять сервер и показать QR-код */
    fun startHosting() {
        stopHosting()
        errorMessage = null
        statusMessage = "Запуск сервера YamSync..."

        val basePath = getMusicStoragePath()
        val token = generateRandomSessionToken()
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
            resolveFilePath = { fileName, checksum ->
                val base = getMusicStoragePath()
                val downloaded = scanDownloadedTracks(base)

                // 1. Поиск по точному имени файла в просканированных локальных треках
                val byName = downloaded.firstOrNull {
                    val p = it.realId ?: it.id.removePrefix("local:")
                    val fn = p.substringAfterLast('/').substringAfterLast('\\')
                    fn.equals(fileName, ignoreCase = true)
                }
                if (byName != null) {
                    val p = byName.realId ?: byName.id.removePrefix("local:")
                    if (localFileExists(p)) return@YamSyncServer p
                }

                // 2. Поиск по checksum (artist_title) в просканированных треках
                if (checksum.isNotBlank()) {
                    val cleanCheck = checksum.trim().lowercase()
                    val byChecksum = downloaded.firstOrNull {
                        val artist = it.artists.firstOrNull()?.name?.trim()?.lowercase() ?: ""
                        val title = it.title.trim().lowercase()
                        "${artist}_${title}" == cleanCheck
                    }
                    if (byChecksum != null) {
                        val p = byChecksum.realId ?: byChecksum.id.removePrefix("local:")
                        if (localFileExists(p)) return@YamSyncServer p
                    }
                }

                // 3. Fallback через платформенный resolveLocalPath
                val resolved = resolveLocalPath(fileName)
                if (localFileExists(resolved)) return@YamSyncServer resolved

                // 4. Fallback в корень директории музыки
                val fallback = "$base/$fileName"
                if (localFileExists(fallback)) return@YamSyncServer fallback

                null
            },
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
                    connectionState = YamSyncConnectionState.Connected(clientDevice, pairInfo)
                    statusMessage = "Устройство подключено: ${clientDevice.name}"
                    refreshManifestAndDiff()
                }
            }
        )

        val assignedPort = server.start()
        activeServer = server

        val pairInfo = YamSyncPairInfo(
            ip = ip,
            port = assignedPort,
            token = token,
            name = getDeviceName(),
            platform = getPlatform().name
        )
        activePairInfo = pairInfo

        val qrMatrix = QrCodeGenerator.encode(pairInfo.toUri(), QrCodeGenerator.EccLevel.M)
        connectionState = YamSyncConnectionState.Hosting(pairInfo, qrMatrix)
        statusMessage = "Ожидание подключения партнёра..."
    }

    /** Остановить сервер и режим раздачи */
    fun stopHosting() {
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
        errorMessage = null
        activePairInfo = pairInfo
        connectionState = YamSyncConnectionState.Connecting("Подключение к ${pairInfo.name} (${pairInfo.ip})...")

        scope.launch(Dispatchers.Main) {
            val localDev = YamSyncDevice(
                id = "client_${currentTimeMillis()}",
                name = getDeviceName(),
                platform = getPlatform().name,
                ip = getLocalIpAddress(),
                port = 0
            )

            val pairRes = withContext(DispatcherIO) {
                client.pair(pairInfo.ip, pairInfo.port, pairInfo.token, localDev)
            }

            pairRes.fold(
                onSuccess = { remoteDev ->
                    connectionState = YamSyncConnectionState.Connected(remoteDev, pairInfo)
                    statusMessage = "Связано с ${remoteDev.name}"
                    refreshManifestAndDiff()
                },
                onFailure = { err ->
                    connectionState = YamSyncConnectionState.Disconnected
                    errorMessage = "Не удалось подключиться: ${err.message}"
                }
            )
        }
    }

    /** Разорвать текущее соединение */
    fun disconnect() {
        stopHosting()
        activePairInfo = null
        remoteManifestCache = null
        playlistDiffs.clear()
        missingFiles.clear()
        selectedFiles.clear()
        fileTransfers.clear()
        connectionState = YamSyncConnectionState.Disconnected
        statusMessage = null
    }

    /** Запросить свежий манифест с удаленного устройства и пересчитать diff */
    fun refreshManifestAndDiff() {
        val pair = activePairInfo ?: return
        scope.launch(Dispatchers.Main) {
            val localMan = buildLocalManifest()
            val remoteRes = withContext(DispatcherIO) {
                client.fetchManifest(pair.ip, pair.port, pair.token)
            }

            remoteRes.fold(
                onSuccess = { remoteMan ->
                    remoteManifestCache = remoteMan
                    val diffs = YamSyncDiffEngine.calculateDiff(localMan, remoteMan)
                    playlistDiffs.clear()
                    playlistDiffs.addAll(diffs)

                    val merged = YamSyncDiffEngine.mergePlaylists(diffs)
                    val missing = YamSyncDiffEngine.findMissingFiles(merged, localMan.availableFiles, remoteMan.availableFiles)
                    missingFiles.clear()
                    missingFiles.addAll(missing)

                    // По умолчанию выбираем все недостающие файлы
                    selectedFiles.clear()
                    selectedFiles.addAll(missing.map { it.matchKey })
                },
                onFailure = { err ->
                    errorMessage = "Ошибка получения манифеста: ${err.message}"
                }
            )
        }
    }

    /** Изменить стратегию слияния для конкретного плейлиста */
    fun setResolution(playlistId: String, resolution: YamSyncResolution) {
        val idx = playlistDiffs.indexOfFirst { it.playlistId == playlistId }
        if (idx >= 0) {
            val cur = playlistDiffs[idx]
            playlistDiffs[idx] = cur.copy(resolution = resolution)

            // Пересчитываем недостающие файлы
            val localMan = localManifestCache ?: return
            val remoteMan = remoteManifestCache ?: return
            val merged = YamSyncDiffEngine.mergePlaylists(playlistDiffs)
            val missing = YamSyncDiffEngine.findMissingFiles(merged, localMan.availableFiles, remoteMan.availableFiles)
            missingFiles.clear()
            missingFiles.addAll(missing)
        }
    }

    /** Переключить выбор файла для загрузки */
    fun toggleFileSelection(trackKey: String) {
        if (selectedFiles.contains(trackKey)) {
            selectedFiles.remove(trackKey)
        } else {
            selectedFiles.add(trackKey)
        }
    }

    /** Выбрать или снять выбор со всех файлов */
    fun toggleSelectAllFiles() {
        if (selectedFiles.size == missingFiles.size) {
            selectedFiles.clear()
        } else {
            selectedFiles.clear()
            selectedFiles.addAll(missingFiles.map { it.matchKey })
        }
    }

    /** Применить слияние плейлистов (Merge Request) */
    fun applyPlaylistMerge() {
        if (playlistDiffs.isEmpty()) return
        val pair = activePairInfo
        scope.launch(Dispatchers.Main) {
            statusMessage = "Применение изменений плейлистов..."
            val merged = YamSyncDiffEngine.mergePlaylists(playlistDiffs)

            // Сохраняем локально
            applyRemoteMergedPlaylists(merged)

            // Если мы клиент — отправляем объединенные плейлисты обратно на хост
            if (pair != null && activeServer == null) {
                withContext(DispatcherIO) {
                    client.sendMerge(pair.ip, pair.port, pair.token, YamSyncMergePayload(merged))
                }
            }

            statusMessage = "✅ Плейлисты успешно объединены!"
            refreshManifestAndDiff()
            onPlaylistsUpdated()
        }
    }

    /** Скачать выбранные недостающие аудиофайлы по требованию */
    fun downloadSelectedFiles() {
        val pair = activePairInfo ?: return
        val toDownload = missingFiles.filter { it.matchKey in selectedFiles }
        if (toDownload.isEmpty()) return

        scope.launch(Dispatchers.Main) {
            val totalCount = toDownload.size
            var currentIdx = 0
            val basePath = getMusicStoragePath()

            for (track in toDownload) {
                currentIdx++
                statusMessage = "Загрузка: $currentIdx/$totalCount (${track.title})"

                val res = withContext(DispatcherIO) {
                    client.downloadTrackBytes(pair.ip, pair.port, pair.token, track) { progress ->
                        fileTransfers[track.matchKey] = progress
                    }
                }

                res.fold(
                    onSuccess = { bytes ->
                        withContext(DispatcherIO) {
                            saveTrackFile(basePath, track.artist, track.fileName, bytes)
                        }
                        fileTransfers[track.matchKey] = YamSyncTransferProgress(
                            fileName = track.fileName,
                            trackTitle = "${track.artist} — ${track.title}",
                            bytesTransferred = bytes.size.toLong(),
                            totalBytes = bytes.size.toLong(),
                            isCompleted = true
                        )
                    },
                    onFailure = { err ->
                        fileTransfers[track.matchKey] = YamSyncTransferProgress(
                            fileName = track.fileName,
                            trackTitle = "${track.artist} — ${track.title}",
                            error = err.message,
                            isCompleted = true
                        )
                    }
                )
            }

            // Обновляем пути в локальных плейлистах с учетом скачанных файлов
            withContext(DispatcherIO) {
                val freshDownloaded = scanDownloadedTracks(basePath)
                val allLocal = loadLocalPlaylists(basePath)
                val updatedLocal = allLocal.map { pl ->
                    val updatedPaths = pl.trackPaths.map { originalPath ->
                        val fileName = originalPath.substringAfterLast('/').substringAfterLast('\\')
                        val matched = freshDownloaded.firstOrNull {
                            val p = it.realId ?: it.id.removePrefix("local:")
                            p.substringAfterLast('/').substringAfterLast('\\').equals(fileName, ignoreCase = true)
                        }
                        matched?.let { it.realId?.ifBlank { it.id.removePrefix("local:") } ?: it.id.removePrefix("local:") } ?: originalPath
                    }.distinct()
                    pl.copy(trackPaths = updatedPaths)
                }
                saveLocalPlaylists(basePath, updatedLocal)
            }

            statusMessage = "✅ Загрузка $totalCount файлов завершена!"
            refreshManifestAndDiff()
            onPlaylistsUpdated()
        }
    }

    private fun applyRemoteMergedPlaylists(remotePlaylists: List<YamSyncPlaylist>) {
        val basePath = getMusicStoragePath()
        val currentLocal = loadLocalPlaylists(basePath)
        val downloadedTracks = scanDownloadedTracks(basePath)

        val updatedList = remotePlaylists.map { syncPl ->
            val trackPaths = syncPl.tracks.mapNotNull { syncTrack ->
                // Ищем файл на диске
                val found = downloadedTracks.firstOrNull {
                    it.title.equals(syncTrack.title, ignoreCase = true) ||
                    (it.realId ?: "").endsWith(syncTrack.fileName, ignoreCase = true)
                }
                found?.let { it.realId?.ifBlank { it.id.removePrefix("local:") } ?: it.id.removePrefix("local:") }
                    ?: "$basePath/${syncTrack.artist}/${syncTrack.fileName}"
            }.distinct()

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
