package io.github.audiz.stream

import io.github.audiz.api.MusicRepository
import io.github.audiz.api.decryptAesCtrChunk
import io.github.audiz.models.TrackStreamMeta
import io.github.audiz.sanitizeKeepSpaces
import io.github.audiz.saveTrackFile
import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

actual object LocalStreamProxy {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var assignedPort: Int = 0
    @Volatile private var repository: MusicRepository? = null
    @Volatile private var getMusicStoragePathFunc: (() -> String)? = null
    @Volatile private var isRecordToDiskFunc: (() -> Boolean)? = null
    @Volatile private var onTrackSavedCallback: ((trackId: String, filePath: String) -> Unit)? = null

    private val threadPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "LocalStreamProxy-Worker"
        }
    }

    private val metaCache = ConcurrentHashMap<String, TrackStreamMeta>()
    private val metaFetchLocks = ConcurrentHashMap<String, Any>()
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val proxyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    actual fun start(
        repository: MusicRepository,
        getMusicStoragePath: () -> String,
        isRecordToDisk: () -> Boolean,
        onTrackSaved: ((trackId: String, filePath: String) -> Unit)?
    ) {
        if (serverSocket != null && !serverSocket!!.isClosed) return

        this.repository = repository
        this.getMusicStoragePathFunc = getMusicStoragePath
        this.isRecordToDiskFunc = isRecordToDisk
        this.onTrackSavedCallback = onTrackSaved

        try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            assignedPort = server.localPort
            println("⚡ [StreamProxy] Сервер запущен на http://127.0.0.1:$assignedPort")

            threadPool.execute {
                while (!server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        threadPool.execute {
                            handleClient(clientSocket)
                        }
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        println("⚡ [StreamProxy] Ошибка accept: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("⚡ [StreamProxy] Не удалось запустить локальный сервер: ${e.message}")
        }
    }

    actual fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        assignedPort = 0
        metaCache.clear()
        metaFetchLocks.clear()
        activeDownloadJobs.values.forEach { it.cancel() }
        activeDownloadJobs.clear()
    }

    actual fun isRunning(): Boolean {
        return serverSocket != null && !serverSocket!!.isClosed
    }

    actual fun getStreamUrl(trackId: String, quality: String, title: String, artist: String): String {
        val cleanTrackId = trackId.removePrefix("local:")
        val cacheKey = "$cleanTrackId:$quality"
        val cached = metaCache[cacheKey]
        val ext = if (cached != null) {
            val rawExt = cached.codec.substringBefore("-")
            if (rawExt.equals("aac", ignoreCase = true)) "m4a" else rawExt
        } else {
            if (quality == "2") "flac" else "m4a"
        }
        return "http://127.0.0.1:$assignedPort/stream/$cleanTrackId.$ext?quality=$quality&title=${encodeParam(title)}&artist=${encodeParam(artist)}"
    }

    private fun encodeParam(s: String): String {
        return try {
            java.net.URLEncoder.encode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }

    private fun decodeParam(s: String): String {
        return try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val fullPath = parts[1]

            var rangeHeader: String? = null
            var line: String? = input.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
                line = input.readLine()
            }

            if (!fullPath.startsWith("/stream/")) {
                send404(socket)
                return
            }

            val pathWithoutQuery = fullPath.substringBefore("?")
            val query = fullPath.substringAfter("?", "")
            val queryParams = parseQueryParams(query)

            val trackIdWithExt = pathWithoutQuery.removePrefix("/stream/")
            val trackId = if (trackIdWithExt.contains(".")) trackIdWithExt.substringBeforeLast(".") else trackIdWithExt
            val requestedExt = if (trackIdWithExt.contains(".")) trackIdWithExt.substringAfterLast(".") else null
            val quality = queryParams["quality"] ?: (if (requestedExt?.equals("flac", ignoreCase = true) == true) "2" else "1")
            val title = queryParams["title"]?.let { decodeParam(it) } ?: "Track"
            val artist = queryParams["artist"]?.let { decodeParam(it) } ?: "Artist"

            val repo = repository ?: run {
                send500(socket, "Repository not initialized")
                return
            }

            val cacheKey = "$trackId:$quality"
            var meta = metaCache[cacheKey]
            if (meta == null) {
                val lock = metaFetchLocks.computeIfAbsent(cacheKey) { Any() }
                synchronized(lock) {
                    meta = metaCache[cacheKey]
                    if (meta == null) {
                        runBlocking {
                            try {
                                val fetched = repo.getTrackStreamMeta(trackId, quality)
                                meta = fetched
                                metaCache[cacheKey] = fetched
                            } catch (e: Exception) {
                                println("⚡ [StreamProxy] Ошибка получения метаданных для $trackId: ${e.message}")
                            }
                        }
                    }
                }
            }

            val trackMeta = meta ?: run {
                send500(socket, "Failed to fetch track metadata")
                return
            }

            serveAudioStream(
                socket = socket,
                method = method,
                meta = trackMeta,
                rangeHeader = rangeHeader,
                trackId = trackId,
                quality = quality,
                title = title,
                artist = artist
            )

        } catch (_: SocketException) {
            // Нормальное отключение плеера при скипе или паузе
        } catch (e: Exception) {
            println("⚡ [StreamProxy] Ошибка обработки запроса: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun serveAudioStream(
        socket: Socket,
        method: String,
        meta: TrackStreamMeta,
        rangeHeader: String?,
        trackId: String,
        quality: String,
        title: String,
        artist: String
    ) {
        val totalSize = meta.totalSizeBytes
        if (totalSize <= 0L) {
            send500(socket, "Unknown total file size")
            return
        }

        var rangeStart = 0L
        var rangeEnd = totalSize - 1L

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
            val dashIndex = rangeSpec.indexOf('-')
            if (dashIndex != -1) {
                val startStr = rangeSpec.substring(0, dashIndex).trim()
                val endStr = rangeSpec.substring(dashIndex + 1).trim()
                if (startStr.isNotEmpty()) {
                    rangeStart = startStr.toLongOrNull() ?: 0L
                }
                if (endStr.isNotEmpty()) {
                    val parsedEnd = endStr.toLongOrNull()
                    if (parsedEnd != null && parsedEnd < totalSize) {
                        rangeEnd = parsedEnd
                    }
                }
            }
        }

        rangeStart = rangeStart.coerceIn(0L, totalSize - 1L)
        rangeEnd = rangeEnd.coerceIn(rangeStart, totalSize - 1L)
        val contentLength = rangeEnd - rangeStart + 1L

        val isPartial = rangeHeader != null
        val rawExt = meta.codec.substringBefore("-")
        val ext = if (rawExt.equals("aac", ignoreCase = true)) "m4a" else rawExt
        val mimeType = when {
            ext.equals("flac", ignoreCase = true) -> "audio/flac"
            ext.equals("mp3", ignoreCase = true) -> "audio/mpeg"
            ext.equals("m4a", ignoreCase = true) || ext.equals("aac", ignoreCase = true) -> "audio/mp4"
            else -> "audio/octet-stream"
        }

        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
        val headerBuilder = StringBuilder().apply {
            append(statusLine)
            append("Content-Type: $mimeType\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isPartial) {
                append("Content-Range: bytes $rangeStart-$rangeEnd/$totalSize\r\n")
            }
            append("Content-Length: $contentLength\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        out.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
        out.flush()

        if (method.equals("HEAD", ignoreCase = true)) {
            return
        }

        // 💾 Если включена опция записи на диск — запускаем фоновое сохранение полного трека параллельно со стримингом
        if (isRecordToDiskFunc?.invoke() == true) {
            triggerBackgroundSave(meta, trackId, quality, title, artist)
        }

        // Потоковая отдача чанками по 256 КБ (быстрый старт!)
        val chunkSize = 256 * 1024L
        var currentPos = rangeStart
        val repo = repository ?: return

        while (currentPos <= rangeEnd && !socket.isClosed) {
            val chunkEnd = minOf(currentPos + chunkSize - 1L, rangeEnd)
            val chunkLength = (chunkEnd - currentPos + 1L).toInt()

            val decryptedChunk = runBlocking {
                val encrypted = repo.fetchEncryptedRange(meta.directUrl, currentPos, chunkEnd)
                decryptAesCtrChunk(encrypted, meta.aesKey, currentPos)
            }

            out.write(decryptedChunk, 0, minOf(decryptedChunk.size, chunkLength))
            out.flush()

            currentPos = chunkEnd + 1L
        }
    }

    private fun triggerBackgroundSave(
        meta: TrackStreamMeta,
        trackId: String,
        quality: String,
        title: String,
        artist: String
    ) {
        val saveKey = "save:$trackId:$quality"
        if (activeDownloadJobs.containsKey(saveKey)) return

        // Отменяем фоновые сохранения предыдущих треков для приоритета текущего
        activeDownloadJobs.forEach { (key, oldJob) ->
            if (key != saveKey) {
                oldJob.cancel()
                activeDownloadJobs.remove(key)
            }
        }

        val job = proxyScope.launch {
            try {
                val storagePath = getMusicStoragePathFunc?.invoke() ?: return@launch
                val qualityFolder = if (quality == "2") "HQ" else "LQ"
                val basePath = "$storagePath/$qualityFolder"
                val rawExt = meta.codec.substringBefore("-")
                val ext = if (rawExt.equals("aac", ignoreCase = true)) "m4a" else rawExt
                val cleanArtist = artist.trim()
                val cleanTitle = title.trim()
                val fullFileName = sanitizeKeepSpaces(if (cleanArtist.isNotEmpty()) {
                    "$cleanArtist — $cleanTitle.$ext"
                } else {
                    "$cleanTitle.$ext"
                })

                val finalPath = "$basePath/${sanitizeKeepSpaces(cleanArtist)}/$fullFileName"
                val existingFile = File(finalPath)
                if (existingFile.exists() && existingFile.length() > 0) {
                    println("⚡ [StreamProxy] Трек уже сохранен на диске: $finalPath")
                    onTrackSavedCallback?.invoke(trackId, finalPath)
                    return@launch
                }

                val repo = repository ?: return@launch
                val totalSize = meta.totalSizeBytes
                if (totalSize <= 0L) return@launch

                // Выкачиваем в фоновом потоке
                val tempFile = File.createTempFile("ya_save_", ".$ext")
                tempFile.deleteOnExit()

                val chunkSize = 512 * 1024L
                var pos = 0L

                FileOutputStream(tempFile).use { fos ->
                    while (pos < totalSize && isActive) {
                        val end = minOf(pos + chunkSize - 1L, totalSize - 1L)
                        val enc = repo.fetchEncryptedRange(meta.directUrl, pos, end)
                        val dec = decryptAesCtrChunk(enc, meta.aesKey, pos)
                        fos.write(dec)
                        pos = end + 1L
                    }
                }

                if (isActive && tempFile.length() == totalSize) {
                    val bytes = tempFile.readBytes()
                    saveTrackFile(basePath, cleanArtist, fullFileName, bytes)
                    println("⚡ [StreamProxy] Трек успешно сохранен на диск в фоне: $finalPath")
                    onTrackSavedCallback?.invoke(trackId, finalPath)
                }
                tempFile.delete()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                println("⚡ [StreamProxy] Ошибка фонового сохранения трека: ${e.message}")
            } finally {
                activeDownloadJobs.remove(saveKey)
            }
        }
        activeDownloadJobs[saveKey] = job
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx != -1) {
                val k = pair.substring(0, idx)
                val v = pair.substring(idx + 1)
                result[k] = v
            }
        }
        return result
    }

    private fun send404(socket: Socket) {
        val body = "404 Not Found"
        val resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        socket.getOutputStream().write(resp.toByteArray())
        socket.getOutputStream().flush()
    }

    private fun send500(socket: Socket, message: String) {
        val resp = "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\nContent-Length: ${message.length}\r\nConnection: close\r\n\r\n$message"
        socket.getOutputStream().write(resp.toByteArray())
        socket.getOutputStream().flush()
    }
}
