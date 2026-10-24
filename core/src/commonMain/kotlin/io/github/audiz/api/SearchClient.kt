package io.github.audiz.api

import io.github.audiz.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * 🔍 Клиент поиска, артистов и пакетного получения информации о треках
 */
class SearchClient(
    private val context: ApiSessionContext
) {
    private val client get() = context.client
    private val json get() = context.json

    private fun HttpRequestBuilder.applyAuthHeaders() {
        with(context) { applyAuthHeaders() }
    }

    suspend fun searchInstant(query: String): YandexMusicResponse {
        val response = client.get(ApiUrls.SEARCH_URL) {
            applyAuthHeaders()

            parameter("text", query)
            parameter("type", "album,artist,playlist,track,wave,podcast,podcast_episode,clip,concert")
            parameter("page", 0)
            parameter("pageSize", 36)
            parameter("withLikesCount", true)
            parameter("withBestResults", true)
        }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw IllegalStateException("Требуется авторизация. Пожалуйста, укажите OAuth токен в настройках приложения (шестерёнка вверху).")
        }

        if (response.status == HttpStatusCode.Forbidden) {
            throw IllegalStateException("Доступ ограничен сервисом Яндекс Музыка (HTTP 403 / проверка безопасности или регион).")
        }

        if (response.status.value !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw IllegalStateException("Ошибка сервера Яндекс Музыки (HTTP ${response.status.value}): $body")
        }

        val parsed = response.body<YandexMusicResponse>()
        if (parsed.result == null) {
            val err = parsed.error?.message ?: parsed.error?.name
            if (!err.isNullOrBlank()) {
                throw IllegalStateException("Ошибка Яндекс Музыки: $err")
            }
            throw IllegalStateException("Ничего не найдено по запросу '$query'")
        }

        // Преобразуем bestResults (главный артист, волна по артисту и т.д.) в TypedResult и добавляем в начало выдачи
        val bestTypedResults = parsed.result.bestResults.mapNotNull { parseBestResultItem(it) }
        val allResults = if (bestTypedResults.isNotEmpty()) {
            val enrichedBestResults = bestTypedResults.map { item ->
                if (item.type == "artist" && item.artist != null) {
                    val matching = parsed.result.results.firstOrNull { it.type == "artist" && it.artist?.id == item.artist.id }?.artist
                    if (matching != null) {
                        val mergedGenres = if (item.artist.genres.isNotEmpty()) item.artist.genres else matching.genres
                        val mergedCounts = item.artist.counts ?: matching.counts
                        val mergedLikes = item.artist.likesCount ?: matching.likesCount
                        item.copy(artist = item.artist.copy(genres = mergedGenres, counts = mergedCounts, likesCount = mergedLikes))
                    } else item
                } else item
            }
            (enrichedBestResults + parsed.result.results).distinctBy { item ->
                when (item.type) {
                    "artist" -> "artist:${item.artist?.id}"
                    "track" -> "track:${item.track?.id}"
                    "album" -> "album:${item.album?.id}"
                    "playlist" -> "playlist:${item.playlist?.uid}:${item.playlist?.kind}"
                    "wave" -> "wave:${item.wave?.title}"
                    else -> item.toString()
                }
            }
        } else {
            parsed.result.results
        }
        val finalResult = parsed.result.copy(results = allResults)
        return parsed.copy(result = finalResult)
    }

    private fun parseBestResultItem(element: JsonElement): TypedResult? {
        val obj = element as? JsonObject ?: return null
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        return when (type) {
            "best_result_artist" -> {
                val wrapper = obj["best_result_artist"]?.jsonObject ?: return null
                val artistObj = wrapper["artist"]?.jsonObject ?: return null
                val id = artistObj["id"]?.jsonPrimitive?.content ?: return null
                val name = artistObj["name"]?.jsonPrimitive?.content ?: return null
                val likesCount = wrapper["likesCount"]?.jsonPrimitive?.content?.toIntOrNull()
                val genres = artistObj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                val trackCount = artistObj["counts"]?.jsonObject?.get("tracks")?.jsonPrimitive?.content?.toIntOrNull()
                TypedResult(
                    type = "artist",
                    artist = ArtistInfo(
                        id = id,
                        name = name,
                        likesCount = likesCount,
                        genres = genres,
                        counts = trackCount?.let { ArtistCounts(tracks = it) }
                    )
                )
            }
            "best_result_wave" -> {
                val wrapper = obj["best_result_wave"]?.jsonObject ?: return null
                val title = wrapper["title"]?.jsonPrimitive?.content ?: "Моя Волна"
                val header = wrapper["header"]?.jsonPrimitive?.content ?: "Моя волна по артисту"
                val seeds = wrapper["seeds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                val stationId = wrapper["stationId"]?.jsonPrimitive?.content
                TypedResult(
                    type = "wave",
                    wave = WaveSearchResultInfo(
                        id = WaveSearchResultId(tag = stationId, type = "artist"),
                        seeds = seeds,
                        title = title,
                        header = header
                    )
                )
            }
            "best_result_track" -> {
                val wrapper = obj["best_result_track"]?.jsonObject ?: return null
                val trackObj = wrapper["track"]?.jsonObject ?: return null
                try {
                    val track = json.decodeFromJsonElement(TrackInfo.serializer(), trackObj)
                    TypedResult(type = "track", track = track)
                } catch (_: Throwable) { null }
            }
            "best_result_album" -> {
                val wrapper = obj["best_result_album"]?.jsonObject ?: return null
                val albumObj = wrapper["album"]?.jsonObject ?: return null
                try {
                    val album = json.decodeFromJsonElement(AlbumInfo.serializer(), albumObj)
                    TypedResult(type = "album", album = album)
                } catch (_: Throwable) { null }
            }
            "best_result_playlist" -> {
                val wrapper = obj["best_result_playlist"]?.jsonObject ?: return null
                val playlistObj = wrapper["playlist"]?.jsonObject ?: return null
                try {
                    val playlist = json.decodeFromJsonElement(PlaylistInfo.serializer(), playlistObj)
                    TypedResult(type = "playlist", playlist = playlist)
                } catch (_: Throwable) { null }
            }
            else -> null
        }
    }

    suspend fun getTrackIds(artistId: String): YandexArtistTrackIdsResponse {
        val response = client.get {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.music.yandex.ru"
                path("artists", artistId, "track-ids")
            }
            applyAuthHeaders()
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw IllegalStateException("Требуется авторизация. Пожалуйста, укажите OAuth токен в настройках приложения.")
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ошибка получения треков артиста (HTTP ${response.status.value})")
        }
        return response.body()
    }

    suspend fun getTracksDetails(trackIds: List<String>): YandexTracksDetailsResponse {
        val response = client.post(ApiUrls.TRACKS_URL) {
            applyAuthHeaders()

            setBody(MultiPartFormDataContent(
                formData {
                    trackIds.forEach { id ->
                        append("trackIds", id)
                    }
                    append("removeDuplicates", "false")
                    append("withProgress", "true")
                }
            ))
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw IllegalStateException("Требуется авторизация. Пожалуйста, укажите OAuth токен в настройках приложения.")
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ошибка получения информации о треках (HTTP ${response.status.value})")
        }
        return response.body()
    }
}
