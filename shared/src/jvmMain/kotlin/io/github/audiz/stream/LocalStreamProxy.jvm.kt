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
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 🌐 Высокопроизводительный локальный HTTP стриминг-прокси для Desktop (Windows / Linux / macOS).
 *
 * Особенности:
 * 1. On-Demand Chunk Streaming: отдача аудио чанками по 256 КБ по мере запроса плеером (Range Requests).
 *    Экономит до 98% сетевого трафика при быстром перелистывании треков (не качает весь файл целиком!).
 * 2. In-Memory Chunk Cache: сессии треков кэшируют уже загруженные чанки в памяти, исключая повторные
 *    запросы от Windows Media Foundation (WMF probe запросы заголовка и хвоста файла).
 * 3. Аппаратное AES-NI декодирование и отсутствие дублирующих фоновых загрузок исключают зависания
 *    и падение FPS в интерфейсе Compose Desktop на Windows.
 * 4. Нативное TCP Backpressure: темп отдачи согласуется со скоростью воспроизведения плеера.
 */
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

    private val proxyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 🎯 Сессии потокового воспроизведения треков с кэшем чанков
    private val sessions = ConcurrentHashMap<String, TrackStreamSession>()
    private val sessionCreationLocks = ConcurrentHashMap<String, Any>()

    class TrackStreamSession(
        val trackId: String,
        val quality: String,
        val meta: TrackStreamMeta,
        private val repository: MusicRepository
    ) {
        val totalSize: Long = meta.totalSizeBytes
        val chunkSize: Long = 256 * 1024L // 256 КБ — быстрый старт (< 200 мс) и микро-трафик
        val totalChunks: Int = if (totalSize > 0) ((totalSize + chunkSize - 1L) / chunkSize).toInt() else 0
        val chunkCache = ConcurrentHashMap<Int, ByteArray>()
        private val inFlightChunks = ConcurrentHashMap<Int, CompletableDeferred<ByteArray?>>()

        @Volatile var title: String = ""
        @Volatile var artist: String = ""
        @Volatile var isClosed: Boolean = false
        @Volatile var lastAccessTime: Long = System.currentTimeMillis()

        fun getChunk(chunkIndex: Int): ByteArray? {
            if (isClosed || chunkIndex < 0 || chunkIndex >= totalChunks) return null
            lastAccessTime = System.currentTimeMillis()

            chunkCache[chunkIndex]?.let { return it }

            val deferred = CompletableDeferred<ByteArray?>()
            val existing = inFlightChunks.putIfAbsent(chunkIndex, deferred)
            if (existing != null) {
                return runBlocking(Dispatchers.IO) {
                    try {
                        existing.await()
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            val startByte = chunkIndex * chunkSize
            val endByte = minOf(startByte + chunkSize - 1L, totalSize - 1L)

            return try {
                val encrypted = runBlocking(Dispatchers.IO) {
                    repository.fetchEncryptedRange(meta.directUrl, startByte, endByte)
                }
                if (encrypted.isEmpty() || isClosed) {
                    deferred.complete(null)
                    null
                } else {
                    val decrypted = decryptAesCtrChunk(encrypted, meta.aesKey, startByte)
                    chunkCache[chunkIndex] = decrypted
                    deferred.complete(decrypted)

                    // Упреждающий фоновый прогрев следующего 1 чанка (строго 1 шаг вперед)
                    val nextChunkIndex = chunkIndex + 1
                    if (nextChunkIndex < totalChunks && !chunkCache.containsKey(nextChunkIndex)) {
                        prefetchNextChunk(nextChunkIndex)
                    }

                    decrypted
                }
            } catch (e: Exception) {
                if (!isClosed) {
                    println("⚡ [StreamProxy] Ошибка загрузки чанка $chunkIndex: ${e.message}")
                }
                deferred.complete(null)
                null
            } finally {
                inFlightChunks.remove(chunkIndex)
            }
        }

        private fun prefetchNextChunk(nextChunkIndex: Int) {
            if (isClosed || chunkCache.containsKey(nextChunkIndex)) return
            val deferred = CompletableDeferred<ByteArray?>()
            val existing = inFlightChunks.putIfAbsent(nextChunkIndex, deferred)
            if (existing != null) return

            proxyScope.launch {
                try {
                    if (isClosed || chunkCache.containsKey(nextChunkIndex)) {
                        deferred.complete(null)
                        return@launch
                    }
                    val startByte = nextChunkIndex * chunkSize
                    val endByte = minOf(startByte + chunkSize - 1L, totalSize - 1L)
                    val encrypted = repository.fetchEncryptedRange(meta.directUrl, startByte, endByte)
                    if (!isClosed && encrypted.isNotEmpty()) {
                        val decrypted = decryptAesCtrChunk(encrypted, meta.aesKey, startByte)
                        chunkCache[nextChunkIndex] = decrypted
                        deferred.complete(decrypted)
                    } else {
                        deferred.complete(null)
                    }
                } catch (_: Exception) {
                    deferred.complete(null)
                } finally {
                    inFlightChunks.remove(nextChunkIndex)
                }
            }
        }

        fun close() {
            isClosed = true
            inFlightChunks.values.forEach { it.cancel() }
            inFlightChunks.clear()
            chunkCache.clear()
        }
    }

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
                    } catch (_: SocketException) {
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
        sessions.values.forEach { it.close() }
        sessions.clear()
        sessionCreationLocks.clear()
    }

    actual fun isRunning(): Boolean {
        return serverSocket != null && !serverSocket!!.isClosed && repository != null
    }

    /**
     * ⚡ Предзагрузка 0-го чанка (256 КБ) для нулевой задержки старта следующего трека
     */
    actual suspend fun preloadTrack(trackId: String, quality: String): String {
        val repo = repository ?: return ""
        val cleanTrackId = trackId.removePrefix("local:")
        val sessionKey = "$cleanTrackId:$quality"
        return try {
            val session = withContext(Dispatchers.IO) {
                sessions[sessionKey] ?: run {
                    val meta = repo.getTrackStreamMeta(cleanTrackId, quality)
                    val newSession = TrackStreamSession(cleanTrackId, quality, meta, repo)
                    evictOldSessions()
                    sessions[sessionKey] = newSession
                    newSession
                }
            }
            withContext(Dispatchers.IO) {
                session.getChunk(0)
            }
            getStreamUrl(cleanTrackId, quality, "", "")
        } catch (e: Exception) {
            println("⚡ [StreamProxy] Ошибка предзагрузки 0-го чанка для $trackId: ${e.message}")
            ""
        }
    }

    actual fun onTrackCompleted(trackId: String, quality: String) {
        if (isRecordToDiskFunc?.invoke() != true) return
        val cleanTrackId = trackId.removePrefix("local:")
        val sessionKey = "$cleanTrackId:$quality"
        val session = sessions[sessionKey] ?: return

        // Если трек был прослушан почти полностью (> 75%), сохраняем его на диск без повторных загрузок
        proxyScope.launch {
            try {
                val total = session.totalChunks
                if (total <= 0) return@launch
                val cachedCount = session.chunkCache.size
                if (cachedCount >= (total * 0.75).toInt().coerceAtLeast(1)) {
                    println("💾 [StreamProxy] Трек $cleanTrackId прослушан ($cachedCount/$total чанков в памяти). Сохраняем на диск...")
                    saveSessionToDisk(session)
                }
            } catch (e: Exception) {
                println("💾 [StreamProxy] Ошибка сохранения прослушанного трека: ${e.message}")
            }
        }
    }

    actual fun getStreamUrl(trackId: String, quality: String, title: String, artist: String): String {
        val cleanTrackId = trackId.removePrefix("local:")
        val sessionKey = "$cleanTrackId:$quality"
        val session = sessions[sessionKey]
        val ext = if (session != null) {
            val rawCodec = session.meta.codec.substringBefore("-")
            when {
                rawCodec.equals("flac", ignoreCase = true) -> "flac"
                rawCodec.equals("mp3", ignoreCase = true) -> "mp3"
                else -> "m4a"
            }
        } else {
            // По умолчанию m4a (AAC) — аппаратно поддерживается всеми ОС без вызова сторонних ffmpeg
            "m4a"
        }
        val encTitle = encodeParam(title)
        val encArtist = encodeParam(artist)
        return "http://127.0.0.1:$assignedPort/stream/$cleanTrackId.$ext?quality=$quality&title=$encTitle&artist=$encArtist"
    }

    private fun getOrCreateSession(trackId: String, quality: String): TrackStreamSession? {
        val repo = repository ?: return null
        val sessionKey = "$trackId:$quality"
        sessions[sessionKey]?.let { return it }

        val lock = sessionCreationLocks.computeIfAbsent(sessionKey) { Any() }
        return synchronized(lock) {
            sessions[sessionKey]?.let { return it }
            try {
                val meta = runBlocking(Dispatchers.IO) {
                    repo.getTrackStreamMeta(trackId, quality)
                }
                val newSession = TrackStreamSession(trackId, quality, meta, repo)
                evictOldSessions()
                sessions[sessionKey] = newSession
                newSession
            } catch (e: Exception) {
                println("⚡ [StreamProxy] Не удалось получить метаданные для $trackId: ${e.message}")
                null
            } finally {
                sessionCreationLocks.remove(sessionKey)
            }
        }
    }

    private fun evictOldSessions() {
        if (sessions.size >= 4) {
            val oldest = sessions.entries.sortedBy { it.value.lastAccessTime }.take(sessions.size - 3)
            for ((key, session) in oldest) {
                session.close()
                sessions.remove(key)
            }
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

            val session = getOrCreateSession(trackId, quality) ?: run {
                send500(socket, "Failed to initialize track session")
                return
            }

            if (title.isNotBlank() && title != "Track") session.title = title
            if (artist.isNotBlank() && artist != "Artist") session.artist = artist

            serveAudioStream(
                socket = socket,
                method = method,
                session = session,
                rangeHeader = rangeHeader
            )

        } catch (_: SocketException) {
            // Нормальное отключение плеера (скип, пауза или смена трека)
        } catch (_: SocketTimeoutException) {
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
        session: TrackStreamSession,
        rangeHeader: String?
    ) {
        val totalSize = session.totalSize
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
        val rawCodec = session.meta.codec.substringBefore("-")
        val mimeType = when {
            rawCodec.equals("flac", ignoreCase = true) -> "audio/flac"
            rawCodec.equals("mp3", ignoreCase = true) -> "audio/mpeg"
            else -> "audio/mp4"
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

        val chunkSize = session.chunkSize
        var currentPos = rangeStart

        try {
            while (currentPos <= rangeEnd && !session.isClosed && !socket.isClosed) {
                val chunkIndex = (currentPos / chunkSize).toInt()
                val offsetInChunk = (currentPos % chunkSize).toInt()
                val chunk = session.getChunk(chunkIndex) ?: break

                val available = chunk.size - offsetInChunk
                if (available <= 0) break

                val bytesToSend = minOf(available.toLong(), rangeEnd - currentPos + 1L).toInt()
                out.write(chunk, offsetInChunk, bytesToSend)
                out.flush()

                currentPos += bytesToSend
            }
            checkAndSaveIfComplete(session)
        } catch (_: SocketException) {
            // Нормальное отключение плеера (скип, пауза или окончание probe)
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        }
    }

    private fun checkAndSaveIfComplete(session: TrackStreamSession) {
        if (isRecordToDiskFunc?.invoke() != true) return
        if (session.chunkCache.size == session.totalChunks && session.totalChunks > 0) {
            proxyScope.launch {
                saveSessionToDisk(session)
            }
        }
    }

    private fun saveSessionToDisk(session: TrackStreamSession) {
        val storagePath = getMusicStoragePathFunc?.invoke() ?: return
        if (storagePath.isBlank()) return
        val meta = session.meta
        val totalSize = session.totalSize
        if (totalSize <= 0L) return

        val qualityFolder = if (session.quality == "2") "HQ" else "LQ"
        val basePath = "$storagePath/$qualityFolder"
        val rawCodec = meta.codec.substringBefore("-")
        val ext = when {
            rawCodec.equals("flac", ignoreCase = true) -> "flac"
            rawCodec.equals("mp3", ignoreCase = true) -> "mp3"
            else -> "m4a"
        }

        val cleanArtist = session.artist.trim().ifBlank { "Unknown Artist" }
        val cleanTitle = session.title.trim().ifBlank { session.trackId }
        val fullFileName = sanitizeKeepSpaces(if (cleanArtist.isNotBlank() && cleanArtist != "Unknown Artist") {
            "$cleanArtist — $cleanTitle.$ext"
        } else {
            "$cleanTitle.$ext"
        })

        val finalPath = "$basePath/${sanitizeKeepSpaces(cleanArtist)}/$fullFileName"
        val existingFile = File(finalPath)
        if (existingFile.exists() && existingFile.length() > 0) {
            onTrackSavedCallback?.invoke(session.trackId, finalPath)
            return
        }

        // Догружаем недостающие чанки, если их нет
        for (i in 0 until session.totalChunks) {
            if (!session.chunkCache.containsKey(i)) {
                session.getChunk(i) ?: return
            }
        }

        val bos = ByteArrayOutputStream(totalSize.toInt())
        for (i in 0 until session.totalChunks) {
            val chunk = session.chunkCache[i] ?: return
            bos.write(chunk)
        }

        val trackBytes = bos.toByteArray()
        if (trackBytes.size.toLong() == totalSize) {
            saveTrackFile(basePath, cleanArtist, fullFileName, trackBytes)
            println("💾 [StreamProxy] Трек успешно сохранен на диск из кэша стриминга: $finalPath")
            onTrackSavedCallback?.invoke(session.trackId, finalPath)
        }
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
