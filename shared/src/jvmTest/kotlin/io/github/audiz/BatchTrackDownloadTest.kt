package io.github.audiz

import io.github.audiz.api.ApiSessionContext
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
private data class BatchDownloadResponse(
    val downloadInfos: List<BatchDownloadInfoItem> = emptyList()
)

@Serializable
private data class BatchDownloadInfoItem(
    val trackId: String,
    val codec: String,
    val bitrate: Int? = null,
    val key: String,
    val url: String,
    val realId: String? = null
)

class BatchTrackDownloadTest {

    private val jsonParser = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun testBatchWithTenTracks() = runBlocking {
        val context = ApiSessionContext()
        val signer = context.trackSigner

        // Ровно 10 треков из одного официального альбома Queen (A Night At The Opera)
        val trackIds = listOf(
            "4485055",
            "4485056",
            "4485057",
            "4485058",
            "4485059",
            "4485060",
            "4485061",
            "4485062",
            "4485063",
            "4485064"
        )
        println("=== Requesting Batch for ${trackIds.size} tracks ===")

        val currentTs = (System.currentTimeMillis() / 1000).toString()
        val trackIdsParam = trackIds.joinToString(",")

        var batchUrl = signer.signBatchUrl(trackIdsParam, "1", currentTs)
        assertNotNull(batchUrl, "Batch URL for 10 tracks must be generated")

        if (batchUrl.contains("sign=")) {
            val urlParts = batchUrl.split("sign=")
            val baseUrl = urlParts[0]
            val sign = urlParts[1]
            val encodedSign = sign.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")
            batchUrl = "${baseUrl}sign=$encodedSign"
        }

        val client = context.client
        val batchResponse: HttpResponse = client.get(batchUrl) {
            headers["x-yandex-music-client"] = "YandexMusicWebNext/1.0.0"
            header("x-yandex-music-without-invocation-info", "1")
        }

        println("=== Server Response Status: ${batchResponse.status} ===")
        val rawJson = batchResponse.bodyAsText()
        println("=== Raw JSON response: $rawJson ===")

        val batchResult = jsonParser.decodeFromString<BatchDownloadResponse>(rawJson)

        println("=== Batch returned downloadInfos count: ${batchResult.downloadInfos.size} out of ${trackIds.size} requested ===")
        for (info in batchResult.downloadInfos) {
            println("   -> Received trackId: ${info.trackId}, codec: ${info.codec}, bitrate: ${info.bitrate}")
        }

        // Проверяем, сколько треков возвращается: больше 3?
        assertTrue(batchResult.downloadInfos.size > 3, "Batch should return more than 3 tracks! (returned ${batchResult.downloadInfos.size})")
    }
}
