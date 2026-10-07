package io.github.audiz.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Общая кроссплатформенная модель для распарсенных параметров трека
data class ParsedTrackInfo(val url: String, val hex: String, val type: String)

@Serializable
private data class YandexTrackDownloadInfoResponse(
    val downloadInfo: DownloadInfo
)

@Serializable
private data class DownloadInfo(
    val url: String,
    val key: String,
    val codec: String
)

private val jsonParser = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun parseTrackDownloadInfo(jsonText: String): ParsedTrackInfo? {
    return try {
        val downloadResponse = jsonParser.decodeFromString<YandexTrackDownloadInfoResponse>(jsonText)
        val info = downloadResponse.downloadInfo
        ParsedTrackInfo(url = info.url, hex = info.key, type = info.codec)
    } catch (e: Exception) {
        null
    }
}

expect class NativeTrackSigner() {
    fun signTrackUrl(trackId: String, quality: String = "2", timestamp: String? = null): String?
    fun signBatchUrl(trackIds: String, quality: String = "2", timestamp: String? = null): String?
}
