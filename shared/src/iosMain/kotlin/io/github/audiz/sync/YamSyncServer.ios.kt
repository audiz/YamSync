@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.audiz.sync

import io.github.audiz.models.YamSyncDevice
import io.github.audiz.models.YamSyncManifest
import io.github.audiz.models.YamSyncMergePayload
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import platform.posix.*
import platform.Foundation.*

private val syncJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

private fun htons(port: UShort): UShort {
    val p = port.toInt()
    return (((p and 0xFF) shl 8) or ((p shr 8) and 0xFF)).toUShort()
}

private fun ntohs(port: UShort): UShort = htons(port)

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
    private var serverFd: Int = -1
    private var activePort: Int = 0
    private var running: Boolean = false
    private val serverScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    actual val port: Int get() = activePort
    actual val isRunning: Boolean get() = running && serverFd >= 0

    actual fun start(): Int {
        if (running && serverFd >= 0) return activePort

        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) {
            println("⚡ [YamSyncServer iOS] Ошибка создания сокета")
            return 0
        }

        // SO_REUSEADDR
        memScoped {
            val optVal = alloc<IntVar>()
            optVal.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, optVal.ptr, sizeOf<IntVar>().toUInt())
        }

        memScoped {
            val serverAddr = alloc<sockaddr_in>()
            serverAddr.sin_family = AF_INET.toUByte()
            serverAddr.sin_addr.s_addr = INADDR_ANY
            serverAddr.sin_port = htons(initialPort.toUShort())

            var bindRes = bind(fd, serverAddr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
            if (bindRes != 0) {
                // Если занят, привязываемся к 0 (любой свободный порт)
                serverAddr.sin_port = 0u
                bindRes = bind(fd, serverAddr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
            }

            if (bindRes != 0) {
                println("⚡ [YamSyncServer iOS] Ошибка bind: $bindRes")
                close(fd)
                return 0
            }

            val len = alloc<socklen_tVar>()
            len.value = sizeOf<sockaddr_in>().toUInt()
            getsockname(fd, serverAddr.ptr.reinterpret(), len.ptr)
            activePort = ntohs(serverAddr.sin_port).toInt()
        }

        if (listen(fd, 10) != 0) {
            println("⚡ [YamSyncServer iOS] Ошибка listen")
            close(fd)
            return 0
        }

        serverFd = fd
        running = true
        println("⚡ [YamSyncServer iOS] Запущен на порту $activePort (токен: $token)")

        serverScope.launch(Dispatchers.Default) {
            while (running && serverFd >= 0) {
                val clientFd = accept(serverFd, null, null)
                if (clientFd >= 0) {
                    launch(Dispatchers.Default) {
                        handleClient(clientFd)
                    }
                } else {
                    if (!running) break
                }
            }
        }

        return activePort
    }

    actual fun stop() {
        running = false
        if (serverFd >= 0) {
            close(serverFd)
            serverFd = -1
        }
        serverScope.cancel()
        println("⚡ [YamSyncServer iOS] Остановлен")
    }

    private fun handleClient(clientFd: Int) {
        try {
            val buffer = ByteArray(8192)
            val bytesRead = buffer.usePinned { pinned ->
                recv(clientFd, pinned.addressOf(0), buffer.size.toULong(), 0).toInt()
            }
            if (bytesRead <= 0) return

            val requestStr = buffer.decodeToString(0, bytesRead)
            val firstLine = requestStr.substringBefore("\r\n").substringBefore("\n")
            val parts = firstLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawUri = parts[1]
            val path = rawUri.substringBefore("?")
            val queryString = rawUri.substringAfter("?", "")

            if (method == "OPTIONS") {
                sendResponse(clientFd, 204, "No Content", emptyMap(), ByteArray(0))
                return
            }

            val headers = parseHeaders(requestStr)
            val queryParams = parseQuery(queryString)
            val clientToken = headers["x-yamsync-token"] ?: queryParams["token"]

            if (path != "/yamsync/v1/ping" && clientToken != token) {
                val err = """{"error":"Unauthorized"}""".encodeToByteArray()
                sendResponse(clientFd, 401, "Unauthorized", mapOf("Content-Type" to "application/json"), err)
                return
            }

            when (path) {
                "/yamsync/v1/ping" -> {
                    val body = syncJson.encodeToString(YamSyncDevice.serializer(), localDevice).encodeToByteArray()
                    sendResponse(clientFd, 200, "OK", mapOf("Content-Type" to "application/json"), body)
                }

                "/yamsync/v1/pair" -> {
                    val bodyStr = requestStr.substringAfter("\r\n\r\n", "")
                    if (bodyStr.isNotBlank()) {
                        try {
                            val clientDev = syncJson.decodeFromString(YamSyncDevice.serializer(), bodyStr)
                            onClientConnected(clientDev)
                        } catch (t: Throwable) {
                            println("⚡ [YamSyncServer iOS] Ошибка декодирования client device: ${t.message}")
                        }
                    }
                    val body = syncJson.encodeToString(YamSyncDevice.serializer(), localDevice).encodeToByteArray()
                    sendResponse(clientFd, 200, "OK", mapOf("Content-Type" to "application/json"), body)
                }

                "/yamsync/v1/manifest" -> {
                    if (method == "POST") {
                        val bodyStr = requestStr.substringAfter("\r\n\r\n", "")

                        if (bodyStr.isNotBlank()) {
                            try {
                                val clientManifest = syncJson.decodeFromString(YamSyncManifest.serializer(), bodyStr)
                                onManifestReceived?.invoke(clientManifest)
                            } catch (t: Throwable) {
                                println("⚡ [YamSyncServer iOS] Ошибка декодирования client manifest: ${t.message}")
                            }
                        }
                    }

                    val manifest = getManifest()
                    val body = syncJson.encodeToString(YamSyncManifest.serializer(), manifest).encodeToByteArray()
                    sendResponse(clientFd, 200, "OK", mapOf("Content-Type" to "application/json"), body)
                }

                "/yamsync/v1/file" -> {
                    val fileName = queryParams["name"] ?: ""
                    val checksum = queryParams["hash"] ?: ""
                    val filePath = resolveFilePath(fileName, checksum)

                    if (filePath.isNullOrBlank()) {
                        val err = """{"error":"File not found"}""".encodeToByteArray()
                        sendResponse(clientFd, 404, "Not Found", mapOf("Content-Type" to "application/json"), err)
                        return
                    }

                    val fileManager = NSFileManager.defaultManager
                    if (!fileManager.fileExistsAtPath(filePath)) {
                        val err = """{"error":"File not found on disk"}""".encodeToByteArray()
                        sendResponse(clientFd, 404, "Not Found", mapOf("Content-Type" to "application/json"), err)
                        return
                    }

                    streamIosFile(clientFd, filePath, headers["range"])
                }

                "/yamsync/v1/merge" -> {
                    val bodyStr = requestStr.substringAfter("\r\n\r\n", "")
                    try {
                        val payload = syncJson.decodeFromString(YamSyncMergePayload.serializer(), bodyStr)
                        onMergeReceived(payload)
                        val ok = """{"status":"ok"}""".encodeToByteArray()
                        sendResponse(clientFd, 200, "OK", mapOf("Content-Type" to "application/json"), ok)
                    } catch (t: Throwable) {
                        val err = """{"error":"${t.message}"}""".encodeToByteArray()
                        sendResponse(clientFd, 400, "Bad Request", mapOf("Content-Type" to "application/json"), err)
                    }
                }

                "/yamsync/v1/disconnect" -> {
                    onClientDisconnected?.invoke()
                    val ok = """{"status":"disconnected"}""".encodeToByteArray()
                    sendResponse(clientFd, 200, "OK", mapOf("Content-Type" to "application/json"), ok)
                }

                else -> {
                    val err = """{"error":"Not Found"}""".encodeToByteArray()
                    sendResponse(clientFd, 404, "Not Found", mapOf("Content-Type" to "application/json"), err)
                }
            }
        } catch (_: Throwable) {
        } finally {
            close(clientFd)
        }
    }

    private fun streamIosFile(clientFd: Int, filePath: String, rangeHeader: String?) {
        val fileHandle = NSFileHandle.fileHandleForReadingAtPath(filePath) ?: return
        val totalLen = fileHandle.seekToEndOfFile().toLong()

        var start = 0L
        var end = totalLen - 1L

        val isRange = if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.removePrefix("bytes=").trim()
            val rParts = rangeVal.split("-")
            val rStart = rParts.getOrNull(0)?.toLongOrNull()
            val rEnd = rParts.getOrNull(1)?.toLongOrNull()
            if (rStart != null) start = rStart
            if (rEnd != null && rEnd < totalLen) end = rEnd
            true
        } else false

        val contentLen = end - start + 1L
        val statusCode = if (isRange) 206 else 200
        val statusText = if (isRange) "Partial Content" else "OK"

        val headers = mutableMapOf(
            "Content-Type" to "audio/octet-stream",
            "Content-Length" to contentLen.toString(),
            "Accept-Ranges" to "bytes"
        )
        if (isRange) {
            headers["Content-Range"] = "bytes $start-$end/$totalLen"
        }

        val headerBuilder = StringBuilder()
        headerBuilder.append("HTTP/1.1 $statusCode $statusText\r\n")
        headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
        headerBuilder.append("Connection: close\r\n")
        for ((k, v) in headers) {
            headerBuilder.append("$k: $v\r\n")
        }
        headerBuilder.append("\r\n")

        val headerBytes = headerBuilder.toString().encodeToByteArray()
        headerBytes.usePinned { pinned ->
            send(clientFd, pinned.addressOf(0), headerBytes.size.toULong(), 0)
        }

        fileHandle.seekToFileOffset(start.toULong())
        var remaining = contentLen
        val chunkSize = 64 * 1024L

        while (remaining > 0L) {
            val toRead = kotlin.math.min(chunkSize, remaining).toULong()
            val data = fileHandle.readDataOfLength(toRead)
            if (data.length.toLong() == 0L) break

            val dataLen = data.length.toInt()
            val bytes = data.bytes?.let { ptr ->
                ptr.readBytes(dataLen)
            } ?: break

            bytes.usePinned { pinned ->
                send(clientFd, pinned.addressOf(0), dataLen.toULong(), 0)
            }
            remaining -= dataLen
        }
        fileHandle.closeFile()
    }

    private fun sendResponse(
        clientFd: Int,
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

        val headerBytes = sb.toString().encodeToByteArray()
        headerBytes.usePinned { pinned ->
            send(clientFd, pinned.addressOf(0), headerBytes.size.toULong(), 0)
        }
        if (body.isNotEmpty()) {
            body.usePinned { pinned ->
                send(clientFd, pinned.addressOf(0), body.size.toULong(), 0)
            }
        }
    }

    private fun parseHeaders(requestStr: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val lines = requestStr.split("\r\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val rawK = part.substring(0, idx)
                val rawV = part.substring(idx + 1)
                val k = (rawK as NSString).stringByRemovingPercentEncoding ?: rawK.replace("+", " ")
                val v = (rawV as NSString).stringByRemovingPercentEncoding ?: rawV.replace("+", " ")
                k to v
            } else null
        }.toMap()
    }
}
