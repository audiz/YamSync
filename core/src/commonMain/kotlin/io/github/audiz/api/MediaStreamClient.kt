package io.github.audiz.api

import io.github.audiz.core.CoreLogger
import io.github.audiz.core.parseTrackDownloadInfo
import io.github.audiz.models.DownloadedTrackAudio
import io.github.audiz.models.TrackStreamMeta
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

/**
 * 🎧 Клиент загрузки и подготовки аудиопотоков, метаданных воспроизведения и обложек
 */
class MediaStreamClient(
    private val context: ApiSessionContext
) {
    private val client get() = context.client

    private fun HttpRequestBuilder.applyAuthHeaders() {
        with(context) { applyAuthHeaders() }
    }

    /**
     * Загрузка аудиоданных трека и подготовка потока
     */
    suspend fun downloadTrackAudio(
        trackId: String,
        quality: String = "1",
        customTimestamp: String? = null
    ): DownloadedTrackAudio {
        val timestamp = customTimestamp ?: (io.ktor.util.date.getTimeMillis() / 1000).toString()

        var fileInfoUrl = context.trackSigner.signTrackUrl(trackId, quality, timestamp)
            ?: throw Exception("Не удалось получить ссылку на трек")

        if (fileInfoUrl.contains("sign=")) {
            val urlParts = fileInfoUrl.split("sign=")
            val baseUrl = urlParts[0]
            val sign = urlParts[1]
            val encodedSign = sign.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")
            fileInfoUrl = "${baseUrl}sign=$encodedSign"
        }

        try {
            val response: HttpResponse = client.get(fileInfoUrl) {
                applyAuthHeaders()
                headers["x-yandex-music-client"] = "YandexMusicWebNext/1.0.0"
                header("x-yandex-music-without-invocation-info", "1")
            }

            CoreLogger.i("MediaStreamClient", "Сервер ответил get-file-info. Статус: ${response.status}")

            val rawJsonText = response.bodyAsText()
            if (response.status.value !in 200..299) {
                CoreLogger.e("MediaStreamClient", "Ошибка получения get-file-info: ${response.status} ($rawJsonText)")
                throw Exception("Сервер вернул ошибку ${response.status}: $rawJsonText")
            }

            val parsedInfo = parseTrackDownloadInfo(rawJsonText)
                ?: throw Exception("Не удалось распарсить JSON метаданных: $rawJsonText")

            CoreLogger.d("MediaStreamClient", "Старт загрузки аудиопотока...")
            val streamBytes: ByteArray = client.get(parsedInfo.url).bodyAsBytes()
            CoreLogger.i("MediaStreamClient", "Аудиопоток успешно загружен. Размер: ${streamBytes.size} байт")

            val processedAudio = decryptAesCtr(streamBytes, io.ktor.util.hex(parsedInfo.hex), ByteArray(16))

            var bitrate: Int? = null
            try {
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(rawJsonText).jsonObject
                val resultArray = root["result"] as? JsonArray
                if (resultArray != null && resultArray.isNotEmpty()) {
                    val item = resultArray[0].jsonObject
                    bitrate = item["bitrateInKbps"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: item["bitrate"]?.jsonPrimitive?.content?.toIntOrNull()
                } else {
                    val resultObj = (root["result"] as? JsonObject)
                        ?: (root["downloadInfo"] as? JsonObject)
                        ?: root
                    bitrate = resultObj["bitrateInKbps"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: resultObj["bitrate"]?.jsonPrimitive?.content?.toIntOrNull()
                }
            } catch (e: Exception) {
                println("Не удалось распарсить битрейт: ${e.message}")
            }

            if (bitrate == null && parsedInfo.type.contains("-")) {
                val candidate = parsedInfo.type.substringAfter("-").substringBefore("-").toIntOrNull()
                if (candidate != null && candidate in 32..1000) {
                    bitrate = candidate
                }
            }

            return DownloadedTrackAudio(processedAudio, parsedInfo.type, bitrate)

        } catch (networkException: Throwable) {
            CoreLogger.e("MediaStreamClient", "Сетевая ошибка при загрузке аудиопотока: ${networkException.message}")
            throw networkException
        }
    }

    /**
     * 🌐 Получение информации о прямом потоке трека для локального стриминг-прокси.
     * Получает метаданные и размер аудиофайла.
     */
    suspend fun getTrackStreamMeta(
        trackId: String,
        quality: String = "1",
        customTimestamp: String? = null
    ): TrackStreamMeta {
        val timestamp = customTimestamp ?: (io.ktor.util.date.getTimeMillis() / 1000).toString()
        var fileInfoUrl = context.trackSigner.signTrackUrl(trackId, quality, timestamp)
            ?: throw Exception("Не удалось получить ссылку на поток")

        if (fileInfoUrl.contains("sign=")) {
            val urlParts = fileInfoUrl.split("sign=")
            val baseUrl = urlParts[0]
            val sign = urlParts[1]
            val encodedSign = sign.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")
            fileInfoUrl = "${baseUrl}sign=$encodedSign"
        }

        val response: HttpResponse = client.get(fileInfoUrl) {
            applyAuthHeaders()
            headers["x-yandex-music-client"] = "YandexMusicWebNext/1.0.0"
            header("x-yandex-music-without-invocation-info", "1")
        }

        val rawJsonText = response.bodyAsText()
        val parsedInfo = parseTrackDownloadInfo(rawJsonText)
            ?: throw Exception("Не удалось распарсить JSON метаданных скачивания трека")

        var bitrate: Int? = null
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(rawJsonText).jsonObject
            val resultArray = root["result"] as? JsonArray
            if (resultArray != null && resultArray.isNotEmpty()) {
                val item = resultArray[0].jsonObject
                bitrate = item["bitrateInKbps"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: item["bitrate"]?.jsonPrimitive?.content?.toIntOrNull()
            } else {
                val resultObj = (root["result"] as? JsonObject)
                    ?: (root["downloadInfo"] as? JsonObject)
                    ?: root
                bitrate = resultObj["bitrateInKbps"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: resultObj["bitrate"]?.jsonPrimitive?.content?.toIntOrNull()
            }
        } catch (_: Exception) {}

        if (bitrate == null && parsedInfo.type.contains("-")) {
            val candidate = parsedInfo.type.substringAfter("-").substringBefore("-").toIntOrNull()
            if (candidate != null && candidate in 32..1000) {
                bitrate = candidate
            }
        }

        var totalSizeBytes = 0L
        try {
            val headResponse: HttpResponse = client.get(parsedInfo.url) {
                header("Range", "bytes=0-0")
            }
            val contentRange = headResponse.headers["Content-Range"]
            if (contentRange != null && contentRange.contains("/")) {
                totalSizeBytes = contentRange.substringAfterLast("/").trim().toLongOrNull() ?: 0L
            }
            if (totalSizeBytes <= 0L) {
                totalSizeBytes = headResponse.headers["Content-Length"]?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            println("MediaStreamClient: Ошибка определения размера трека: ${e.message}")
        }

        return TrackStreamMeta(
            directUrl = parsedInfo.url,
            aesKey = io.ktor.util.hex(parsedInfo.hex),
            codec = parsedInfo.type,
            bitrate = bitrate,
            totalSizeBytes = totalSizeBytes
        )
    }

    /**
     * 📥 Загрузка фрагмента аудиопотока по HTTP Range
     */
    suspend fun fetchEncryptedRange(directUrl: String, startByte: Long, endByte: Long): ByteArray {
        return client.get(directUrl) {
            header("Range", "bytes=$startByte-$endByte")
        }.bodyAsBytes()
    }

    /**
     * 🖼️ Загрузка байтов изображения (обложки альбома, трека или плейлиста)
     */
    suspend fun fetchImageBytes(url: String): ByteArray? {
        return try {
            val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
            client.get(cleanUrl) {
                timeout {
                    requestTimeoutMillis = 6_000
                    connectTimeoutMillis = 4_000
                    socketTimeoutMillis = 6_000
                }
            }.bodyAsBytes()
        } catch (e: Exception) {
            println("MediaStreamClient: Ошибка загрузки изображения $url: ${e.message}")
            null
        }
    }
}
