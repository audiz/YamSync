package io.github.audiz.sync

import io.github.audiz.models.YamSyncDevice
import io.github.audiz.models.YamSyncManifest
import io.github.audiz.models.YamSyncMergePayload
import kotlinx.serialization.json.Json
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Executors

private val syncJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

actual class YamSyncServer actual constructor(
    private val initialPort: Int,
    private val token: String,
    private val localDevice: YamSyncDevice,
    private val getManifest: () -> YamSyncManifest,
    private val resolveFilePath: (fileName: String, checksum: String) -> String?,
    private val onMergeReceived: (YamSyncMergePayload) -> Unit,
    private val onClientConnected: (YamSyncDevice) -> Unit,
    private val onManifestReceived: ((YamSyncManifest) -> Unit)?,
    private val onClientDisconnected: (() -> Unit)?
) {
    private var serverSocket: ServerSocket? = null
    private var activePort: Int = 0
    private var running: Boolean = false

    private val threadPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "YamSyncServer-Worker"
        }
    }

    actual val port: Int get() = activePort
    actual val isRunning: Boolean get() = running && serverSocket?.isClosed == false

    actual fun start(): Int {
        if (running && serverSocket != null && !serverSocket!!.isClosed) {
            return activePort
        }

        val socket = try {
            ServerSocket(initialPort)
        } catch (_: Exception) {
            // Если порт занят, выбираем любой свободный порт
            ServerSocket(0)
        }

        serverSocket = socket
        activePort = socket.localPort
        running = true
        println("⚡ [YamSyncServer] Запущен на порту $activePort (токен: $token)")

        threadPool.execute {
            while (running && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    threadPool.execute {
                        handleClient(client)
                    }
                } catch (e: SocketException) {
                    if (!running || socket.isClosed) break
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                } catch (e: Throwable) {
                    if (!running || socket.isClosed) break
                    println("⚡ [YamSyncServer] Ошибка accept: ${e.message}")
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }
        }

        return activePort
    }

    actual fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        println("⚡ [YamSyncServer] Остановлен")
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000 // 30 секунд таймаут
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawUri = parts[1]
            val path = rawUri.substringBefore("?")
            val queryString = rawUri.substringAfter("?", "")
            val queryParams = parseQuery(queryString)

            val headers = mutableMapOf<String, String>()
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isBlank()) break
                val colon = headerLine.indexOf(':')
                if (colon > 0) {
                    headers[headerLine.substring(0, colon).trim().lowercase()] = headerLine.substring(colon + 1).trim()
                }
            }

            if (method == "OPTIONS") {
                sendResponse(output, 204, "No Content", emptyMap(), ByteArray(0))
                return
            }

            // Веб-эндпоинт для перехода из системной камеры iOS/Android (Safari / Chrome)
            if (path == "/pair" || path == "/pair/") {
                val encodedName = URLDecoder.decode(localDevice.name, "UTF-8")
                val encodedPlatform = URLDecoder.decode(localDevice.platform, "UTF-8")
                val pairUri = "yamsync://pair?ip=${localDevice.ip}&port=$activePort&token=$token&name=${java.net.URLEncoder.encode(encodedName, "UTF-8")}&platform=${java.net.URLEncoder.encode(encodedPlatform, "UTF-8")}"
                val html = """
                    <!DOCTYPE html>
                    <html lang="ru">
                    <head>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <title>YamSync Подключение</title>
                        <meta http-equiv="refresh" content="0; url=$pairUri">
                        <style>
                            body { background: #121212; color: #ffffff; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; text-align: center; padding: 48px 24px; margin: 0; }
                            .card { background: #1e1e1e; border-radius: 18px; padding: 32px 24px; max-width: 360px; margin: 0 auto; box-shadow: 0 8px 24px rgba(0,0,0,0.5); }
                            h2 { margin-top: 0; font-size: 24px; color: #FFCC00; }
                            p { font-size: 15px; color: #cccccc; line-height: 1.5; }
                            .btn { display: inline-block; background: #FFCC00; color: #000000; font-weight: bold; padding: 14px 28px; border-radius: 12px; text-decoration: none; margin-top: 24px; font-size: 16px; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h2>⚡ YamSync</h2>
                            <p>Подключение к <b>${localDevice.name}</b></p>
                            <p>Перенаправление в приложение YamSync...</p>
                            <a class="btn" href="$pairUri">Открыть в приложении</a>
                        </div>
                        <script>
                            window.location.href = "$pairUri";
                        </script>
                    </body>
                    </html>
                """.trimIndent().toByteArray(Charsets.UTF_8)
                sendResponse(output, 200, "OK", mapOf("Content-Type" to "text/html; charset=utf-8"), html)
                return
            }

            val clientToken = headers["x-yamsync-token"] ?: queryParams["token"]

            // Валидация токена для закрытых эндпоинтов
            if (path != "/yamsync/v1/ping" && clientToken != token) {
                val err = """{"error":"Unauthorized"}""".encodeToByteArray()
                sendResponse(output, 401, "Unauthorized", mapOf("Content-Type" to "application/json"), err)
                return
            }

            when (path) {
                "/yamsync/v1/ping" -> {
                    val body = syncJson.encodeToString(YamSyncDevice.serializer(), localDevice).encodeToByteArray()
                    sendResponse(output, 200, "OK", mapOf("Content-Type" to "application/json"), body)
                }

                "/yamsync/v1/pair" -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyStr = if (contentLength > 0) {
                        val chars = CharArray(contentLength)
                        reader.read(chars, 0, contentLength)
                        String(chars)
                    } else ""

                    if (bodyStr.isNotBlank()) {
                        try {
                            val clientDev = syncJson.decodeFromString(YamSyncDevice.serializer(), bodyStr)
                            onClientConnected(clientDev)
                        } catch (t: Throwable) {
                            println("⚡ [YamSyncServer] Ошибка декодирования client device: ${t.message}")
                        }
                    }

                    val body = syncJson.encodeToString(YamSyncDevice.serializer(), localDevice).encodeToByteArray()
                    sendResponse(output, 200, "OK", mapOf("Content-Type" to "application/json"), body)
                }

                "/yamsync/v1/manifest" -> {
                    if (method == "POST") {
                        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                        val bodyStr = if (contentLength > 0) {
                            val chars = CharArray(contentLength)
                            reader.read(chars, 0, contentLength)
                            String(chars)
                        } else ""

                        if (bodyStr.isNotBlank()) {
                            try {
                                val clientManifest = syncJson.decodeFromString(YamSyncManifest.serializer(), bodyStr)
                                onManifestReceived?.invoke(clientManifest)
                            } catch (t: Throwable) {
                                println("⚡ [YamSyncServer] Ошибка декодирования client manifest: ${t.message}")
                            }
                        }
                    }

                    val manifest = getManifest()
                    val body = syncJson.encodeToString(YamSyncManifest.serializer(), manifest).encodeToByteArray()
                    sendResponse(output, 200, "OK", mapOf("Content-Type" to "application/json"), body)
                }

                "/yamsync/v1/file" -> {
                    val fileName = queryParams["name"] ?: ""
                    val checksum = queryParams["hash"] ?: ""
                    val filePath = resolveFilePath(fileName, checksum)

                    if (filePath.isNullOrBlank()) {
                        val err = """{"error":"File not found"}""".encodeToByteArray()
                        sendResponse(output, 404, "Not Found", mapOf("Content-Type" to "application/json"), err)
                        return
                    }

                    val file = File(filePath)
                    if (!file.exists() || !file.isFile) {
                        val err = """{"error":"File not found on disk"}""".encodeToByteArray()
                        sendResponse(output, 404, "Not Found", mapOf("Content-Type" to "application/json"), err)
                        return
                    }

                    streamFile(output, file, headers["range"])
                }

                "/yamsync/v1/merge" -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyStr = if (contentLength > 0) {
                        val chars = CharArray(contentLength)
                        reader.read(chars, 0, contentLength)
                        String(chars)
                    } else ""

                    try {
                        val payload = syncJson.decodeFromString(YamSyncMergePayload.serializer(), bodyStr)
                        onMergeReceived(payload)
                        val ok = """{"status":"ok"}""".encodeToByteArray()
                        sendResponse(output, 200, "OK", mapOf("Content-Type" to "application/json"), ok)
                    } catch (t: Throwable) {
                        val err = """{"error":"${t.message}"}""".encodeToByteArray()
                        sendResponse(output, 400, "Bad Request", mapOf("Content-Type" to "application/json"), err)
                    }
                }

                "/yamsync/v1/disconnect" -> {
                    onClientDisconnected?.invoke()
                    val ok = """{"status":"disconnected"}""".encodeToByteArray()
                    sendResponse(output, 200, "OK", mapOf("Content-Type" to "application/json"), ok)
                }

                else -> {
                    val err = """{"error":"Not Found"}""".encodeToByteArray()
                    sendResponse(output, 404, "Not Found", mapOf("Content-Type" to "application/json"), err)
                }
            }
        } catch (e: Throwable) {
            // Игнорируем штатные разрывы сокета
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun streamFile(output: BufferedOutputStream, file: File, rangeHeader: String?) {
        val fileLen = file.length()
        var start = 0L
        var end = fileLen - 1L

        val isRange = if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.removePrefix("bytes=").trim()
            val rParts = rangeVal.split("-")
            val rStart = rParts.getOrNull(0)?.toLongOrNull()
            val rEnd = rParts.getOrNull(1)?.toLongOrNull()

            if (rStart != null) start = rStart
            if (rEnd != null && rEnd < fileLen) end = rEnd
            true
        } else false

        val contentLen = end - start + 1L
        val statusCode = if (isRange) 206 else 200
        val statusText = if (isRange) "Partial Content" else "OK"

        val headers = mutableMapOf(
            "Content-Type" to "audio/octet-stream",
            "Content-Length" to contentLen.toString(),
            "Accept-Ranges" to "bytes",
            "Content-Disposition" to "attachment; filename=\"${file.name}\""
        )
        if (isRange) {
            headers["Content-Range"] = "bytes $start-$end/$fileLen"
        }

        val headerBuilder = StringBuilder()
        headerBuilder.append("HTTP/1.1 $statusCode $statusText\r\n")
        headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
        headerBuilder.append("Connection: close\r\n")
        for ((k, v) in headers) {
            headerBuilder.append("$k: $v\r\n")
        }
        headerBuilder.append("\r\n")

        output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
        output.flush()

        FileInputStream(file).use { fis ->
            if (start > 0L) {
                fis.skip(start)
            }
            val buffer = ByteArray(64 * 1024)
            var remaining = contentLen
            while (remaining > 0L) {
                val toRead = kotlin.math.min(buffer.size.toLong(), remaining).toInt()
                val read = fis.read(buffer, 0, toRead)
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            output.flush()
        }
    }

    private fun sendResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        statusText: String,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        for ((k, v) in headers) {
            sb.append("$k: $v\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val rawK = part.substring(0, idx)
                val rawV = part.substring(idx + 1)
                val k = try { URLDecoder.decode(rawK, "UTF-8") } catch (_: Exception) { rawK }
                val v = try { URLDecoder.decode(rawV, "UTF-8") } catch (_: Exception) { rawV }
                k to v
            } else null
        }.toMap()
    }
}
