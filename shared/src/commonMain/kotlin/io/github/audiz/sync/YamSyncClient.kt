package io.github.audiz.sync

import io.github.audiz.TrackFileWriter
import io.github.audiz.currentTimeMillis
import io.github.audiz.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * 🚀 HTTP-клиент YamSync для взаимодействия с удалённым сервером по Wi-Fi.
 */
class YamSyncClient {
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient {
        followRedirects = true
        expectSuccess = false
        install(ContentNegotiation) {
            json(jsonConfig)
        }
    }

    /** Проверить доступность удаленного устройства */
    suspend fun ping(ip: String, port: Int): Result<YamSyncDevice> = runCatching {
        val response = httpClient.get("http://$ip:$port/yamsync/v1/ping")
        if (response.status.isSuccess()) {
            response.body<YamSyncDevice>()
        } else {
            error("HTTP ${response.status}")
        }
    }

    /** Выполнить рукопожатие (Pairing) и авторизацию по токену */
    suspend fun pair(ip: String, port: Int, token: String, localDevice: YamSyncDevice): Result<YamSyncDevice> = runCatching {
        val response = httpClient.post("http://$ip:$port/yamsync/v1/pair") {
            header("X-YamSync-Token", token)
            contentType(ContentType.Application.Json)
            setBody(localDevice)
        }
        if (response.status.isSuccess()) {
            response.body<YamSyncDevice>()
        } else {
            error("Pairing rejected: HTTP ${response.status}")
        }
    }

    /** Запросить манифест медиатеки с удаленного устройства */
    suspend fun fetchManifest(ip: String, port: Int, token: String): Result<YamSyncManifest> = runCatching {
        val response = httpClient.get("http://$ip:$port/yamsync/v1/manifest") {
            header("X-YamSync-Token", token)
        }
        if (response.status.isSuccess()) {
            response.body<YamSyncManifest>()
        } else {
            error("Failed to fetch manifest: HTTP ${response.status}")
        }
    }

    /**
     * Отправить свой манифест на удаленный хост (POST) и одновременно получить свежий манифест хоста в ответе
     */
    suspend fun exchangeManifests(ip: String, port: Int, token: String, localManifest: YamSyncManifest): Result<YamSyncManifest> = runCatching {
        val response = httpClient.post("http://$ip:$port/yamsync/v1/manifest") {
            header("X-YamSync-Token", token)
            contentType(ContentType.Application.Json)
            setBody(localManifest)
        }
        if (response.status.isSuccess()) {
            response.body<YamSyncManifest>()
        } else {
            error("Failed to exchange manifests: HTTP ${response.status}")
        }
    }

    /**
     * Потоковая загрузка аудиофайла: данные пишутся в [writer] кусками по 128 КБ,
     * прогресс отправляется в UI. Возвращает размер файла в байтах.
     */
    suspend fun downloadTrackToFile(
        ip: String,
        port: Int,
        token: String,
        track: YamSyncTrack,
        writer: TrackFileWriter,
        onProgress: (YamSyncTransferProgress) -> Unit
    ): Result<Long> = runCatching {
        try {
            httpClient.prepareGet("http://$ip:$port/yamsync/v1/file") {
                parameter("name", track.fileName)
                parameter("hash", track.checksum)
                header("X-YamSync-Token", token)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    error("Download error: HTTP ${response.status}")
                }

                val totalBytes = response.contentLength() ?: track.fileSize
                val channel: ByteReadChannel = response.bodyAsChannel()
                val buffer = ByteArray(128 * 1024)

                var downloaded = 0L
                var lastTime = currentTimeMillis()
                var lastDownloaded = 0L

                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    writer.write(buffer, read)
                    downloaded += read

                    val now = currentTimeMillis()
                    val timeDiff = now - lastTime
                    if (timeDiff >= 400) {
                        val speed = ((downloaded - lastDownloaded) * 1000L) / timeDiff
                        lastTime = now
                        lastDownloaded = downloaded
                        onProgress(
                            YamSyncTransferProgress(
                                fileName = track.fileName,
                                trackTitle = "${track.artist} — ${track.title}",
                                bytesTransferred = downloaded,
                                totalBytes = totalBytes,
                                speedBytesPerSec = speed,
                                isCompleted = false
                            )
                        )
                    }
                }
                writer.close()
                downloaded
            }
        } catch (e: Throwable) {
            writer.abort()
            throw e
        }
    }

    /** Отправить результат слияния плейлистов обратно на хост */
    suspend fun sendMerge(ip: String, port: Int, token: String, payload: YamSyncMergePayload): Result<Unit> = runCatching {
        val response = httpClient.post("http://$ip:$port/yamsync/v1/merge") {
            header("X-YamSync-Token", token)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            error("Merge push rejected: HTTP ${response.status}")
        }
    }

    /** Уведомить удаленное устройство о разрыве соединения */
    suspend fun notifyDisconnect(ip: String, port: Int, token: String): Result<Unit> = runCatching {
        if (port <= 0 || ip.isBlank()) return@runCatching
        withTimeout(1500) {
            httpClient.post("http://$ip:$port/yamsync/v1/disconnect") {
                header("X-YamSync-Token", token)
            }
        }
        Unit
    }
}
