package io.github.audiz.sync

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
     * Потоковая загрузка аудиофайла по кусочкам с отправкой обновлений прогресса в UI
     */
    suspend fun downloadTrackBytes(
        ip: String,
        port: Int,
        token: String,
        track: YamSyncTrack,
        onProgress: (YamSyncTransferProgress) -> Unit
    ): Result<ByteArray> = runCatching {
        val response = httpClient.get("http://$ip:$port/yamsync/v1/file") {
            parameter("name", track.fileName)
            parameter("hash", track.checksum)
            header("X-YamSync-Token", token)
        }
        if (!response.status.isSuccess()) {
            error("Download error: HTTP ${response.status}")
        }

        val totalBytes = response.contentLength() ?: track.fileSize
        val channel: ByteReadChannel = response.bodyAsChannel()
        val buffer = ByteArray(32 * 1024)
        val chunks = mutableListOf<ByteArray>()
        var totalRead = 0

        var downloaded = 0L
        var lastTime = currentTimeMillis()
        var lastDownloaded = 0L
        var speed = 0L

        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) break
            val chunk = buffer.copyOf(read)
            chunks.add(chunk)
            totalRead += read
            downloaded += read

            val now = currentTimeMillis()
            val timeDiff = now - lastTime
            if (timeDiff >= 400) {
                speed = ((downloaded - lastDownloaded) * 1000L) / timeDiff
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

        val finalBytes = ByteArray(totalRead)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(finalBytes, offset)
            offset += chunk.size
        }
        onProgress(
            YamSyncTransferProgress(
                fileName = track.fileName,
                trackTitle = "${track.artist} — ${track.title}",
                bytesTransferred = downloaded,
                totalBytes = if (totalBytes > 0) totalBytes else downloaded,
                speedBytesPerSec = 0L,
                isCompleted = true
            )
        )

        finalBytes
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
}
