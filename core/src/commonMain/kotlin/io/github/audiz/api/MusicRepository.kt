package io.github.audiz.api

import io.github.audiz.YandexCookieService
import io.github.audiz.core.CoreLogger
import io.github.audiz.core.NativeTrackSigner
import io.github.audiz.core.parseTrackDownloadInfo
import io.github.audiz.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import io.ktor.client.plugins.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*

import io.ktor.client.plugins.websocket.*
import kotlinx.coroutines.launch

class MusicRepository {

    private val trackSigner = NativeTrackSigner()

    private val client = HttpClient {
        followRedirects = false
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; coerceInputValues = true })
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }
    val ynisonService = YnisonService(client)

    private val playlistJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val clientHeader = "YandexMusicAndroid/24023621"
    private val deviceModels = listOf(
        "Pixel 7",
        "Pixel 8",
        "Pixel 8 Pro",
        "SM-S918B",
        "SM-S928B",
        "SM-A546B",
        "Xiaomi 2210132G"
    )
    private val baseUserAgent: String
        get() {
            val key = cachedUserId ?: accessToken.ifBlank { "default" }
            val idx = (key.hashCode() and 0x7FFFFFFF) % deviceModels.size
            return "YandexMusic/24023621 (Linux; U; Android 14; ru_RU; ${deviceModels[idx]})"
        }

    // 🔥 OAuth access_token для авторизации
    private var accessToken: String = ""
    private var cachedUserId: String? = null

    fun getAccessToken(): String {
        return accessToken
    }

    fun getSessionToken(): String {
        return accessToken
    }

    fun updateAccessToken(newToken: String) {
        val extracted = YandexCookieService.extractAccessToken(newToken)
        if (extracted != this.accessToken) {
            cachedUserId = null
        }
        this.accessToken = extracted
    }

    fun updateSessionCookie(newCookie: String) {
        updateAccessToken(newCookie)
    }

    /**
     * 👤 Получить ID пользователя (запрашивается 1 раз и кэшируется)
     * GET https://api.music.yandex.net/account/status
     */
    suspend fun getUserId(): String? {
        cachedUserId?.let { return it }
        val uid = getAccountUid()
        if (uid != null) {
            cachedUserId = uid
        }
        return uid
    }

    /**
     * ❤️ Добавить трек в избранное (лайк)
     * POST https://api.music.yandex.ru/users/{userId}/likes/tracks/add?track-id={trackIdWithAlbum}
     */
    suspend fun likeTrack(trackIdWithAlbum: String): Boolean {
        val userId = getUserId()
        if (userId.isNullOrBlank()) {
            println("MusicRepository: Не удалось поставить лайк: userId неизвестен")
            return false
        }
        val url = "https://api.music.yandex.ru/users/$userId/likes/tracks/add"
        return try {
            val response: HttpResponse = client.post(url) {
                applyAuthHeaders()
                parameter("track-id", trackIdWithAlbum)
            }
            println("MusicRepository: Лайк отправлен (POST $url?track-id=$trackIdWithAlbum) -> статус ${response.status}")
            response.status.value in 200..299
        } catch (e: Exception) {
            println("MusicRepository: Ошибка при отправке лайка: ${e.message}")
            false
        }
    }

    /**
     * 💔 Удалить трек из избранного (дизлайк)
     * POST https://api.music.yandex.ru/users/{userId}/likes/tracks/{trackIdWithAlbum}/remove
     */
    suspend fun unlikeTrack(trackIdWithAlbum: String): Boolean {
        val userId = getUserId()
        if (userId.isNullOrBlank()) {
            println("MusicRepository: Не удалось снять лайк: userId неизвестен")
            return false
        }
        val url = "https://api.music.yandex.ru/users/$userId/likes/tracks/$trackIdWithAlbum/remove"
        return try {
            val response: HttpResponse = client.post(url) {
                applyAuthHeaders()
            }
            println("MusicRepository: Лайк снят (POST $url) -> статус ${response.status}")
            response.status.value in 200..299
        } catch (e: Exception) {
            println("MusicRepository: Ошибка при снятии лайка: ${e.message}")
            false
        }
    }

    /**
     * 💔 Добавить трек в дизлайки
     * POST https://api.music.yandex.ru/users/{userId}/dislikes/tracks/add?track-id={trackIdWithAlbum}
     */
    suspend fun dislikeTrack(trackIdWithAlbum: String): Boolean {
        val userId = getUserId()
        if (userId.isNullOrBlank()) {
            println("MusicRepository: Не удалось поставить дизлайк: userId неизвестен")
            return false
        }
        val url = "https://api.music.yandex.ru/users/$userId/dislikes/tracks/add"
        return try {
            val response: HttpResponse = client.post(url) {
                applyAuthHeaders()
                parameter("track-id", trackIdWithAlbum)
            }
            println("MusicRepository: Дизлайк отправлен (POST $url?track-id=$trackIdWithAlbum) -> статус ${response.status}")
            response.status.value in 200..299
        } catch (e: Exception) {
            println("MusicRepository: Ошибка при отправке дизлайка: ${e.message}")
            false
        }
    }

    /**
     * 🤍 Снять дизлайк с трека
     * POST https://api.music.yandex.ru/users/{userId}/dislikes/tracks/{trackIdWithAlbum}/remove
     */
    suspend fun undislikeTrack(trackIdWithAlbum: String): Boolean {
        val userId = getUserId()
        if (userId.isNullOrBlank()) {
            println("MusicRepository: Не удалось снять дизлайк: userId неизвестен")
            return false
        }
        val url = "https://api.music.yandex.ru/users/$userId/dislikes/tracks/$trackIdWithAlbum/remove"
        return try {
            val response: HttpResponse = client.post(url) {
                applyAuthHeaders()
            }
            println("MusicRepository: Дизлайк снят (POST $url) -> статус ${response.status}")
            response.status.value in 200..299
        } catch (e: Exception) {
            println("MusicRepository: Ошибка при снятии дизлайка: ${e.message}")
            false
        }
    }

    private fun getAuthHeader(): String? {
        val token = accessToken.trim()
        if (token.isBlank()) return null
        return if (token.startsWith("OAuth ", ignoreCase = true)) token else "OAuth $token"
    }

    private fun HttpRequestBuilder.applyAuthHeaders() {
        header("Accept", "*/*")
        header("User-Agent", baseUserAgent)
        header("X-Yandex-Music-Client", clientHeader)
        getAuthHeader()?.let { auth ->
            header("Authorization", auth)
        }
    }

    suspend fun searchInstant(query: String): YandexMusicResponse {
        val response = client.get(ApiUrls.SEARCH_URL) {
            applyAuthHeaders()

            // Query параметры запроса
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

        // 🔥 Преобразуем bestResults (главный артист, волна по артисту и т.д.) в TypedResult и добавляем в начало выдачи
        val bestTypedResults = parsed.result.bestResults.mapNotNull { parseBestResultItem(it) }
        val allResults = if (bestTypedResults.isNotEmpty()) {
            (bestTypedResults + parsed.result.results).distinctBy { item ->
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
                TypedResult(
                    type = "artist",
                    artist = ArtistInfo(id = id, name = name, likesCount = likesCount)
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
                    val track = playlistJson.decodeFromJsonElement(TrackInfo.serializer(), trackObj)
                    TypedResult(type = "track", track = track)
                } catch (_: Throwable) { null }
            }
            "best_result_album" -> {
                val wrapper = obj["best_result_album"]?.jsonObject ?: return null
                val albumObj = wrapper["album"]?.jsonObject ?: return null
                try {
                    val album = playlistJson.decodeFromJsonElement(AlbumInfo.serializer(), albumObj)
                    TypedResult(type = "album", album = album)
                } catch (_: Throwable) { null }
            }
            "best_result_playlist" -> {
                val wrapper = obj["best_result_playlist"]?.jsonObject ?: return null
                val playlistObj = wrapper["playlist"]?.jsonObject ?: return null
                try {
                    val playlist = playlistJson.decodeFromJsonElement(PlaylistInfo.serializer(), playlistObj)
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

            // Формируем тело запроса multipart/form-data
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

    /**
     * 🔥 Шаг 1: Получаем playlistUuid из landing-blocks/collection/playlist-with-likes
     */
    suspend fun getLikesPlaylistUuid(): String {
        val rawText: String = client.get(ApiUrls.LANDING_LIKES_URL) {
            applyAuthHeaders()
        }.bodyAsText()

        println("Landing-blocks raw response:\n$rawText")

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(rawText).jsonObject
        val result = root["result"]?.jsonObject
            ?: throw Exception("Нет поля 'result' в ответе landing-blocks")
        val playlist = result["playlist"]?.jsonObject
            ?: throw Exception("Нет поля 'playlist' в result. Ключи: ${result.keys}")
        val uuid = playlist["playlistUuid"]?.jsonPrimitive?.content
            ?: throw Exception("Нет поля 'playlistUuid' в playlist. Ключи: ${playlist.keys}")
        return uuid
    }

    /**
     * ⚡ Быстрая загрузка плейлиста по UUID с получением полных метаданных треков за один запрос
     * GET https://api.music.yandex.ru/playlist/{uuid}?richTracks=true
     */
    suspend fun getPlaylistWithTracksByUuid(uuid: String): Pair<List<String>, List<FullTrackInfo>> {
        val rawText: String = client.get("${ApiUrls.PLAYLIST_BY_UUID_URL}/$uuid") {
            applyAuthHeaders()
            parameter("resumeStream", false)
            parameter("richTracks", true)
        }.bodyAsText()

        val root = playlistJson.parseToJsonElement(rawText).jsonObject
        val playlistObj = root["result"]?.jsonObject ?: root
        val tracksArray = playlistObj["tracks"]?.jsonArray
            ?: throw Exception("Нет поля 'tracks' в ответе playlist/$uuid. Ключи: ${playlistObj.keys}")

        val allIds = mutableListOf<String>()
        val richTracks = mutableListOf<FullTrackInfo>()

        for (elem in tracksArray) {
            val obj = elem.jsonObject
            val trackElem = obj["track"]
            val id = obj["id"]?.jsonPrimitive?.content
                ?: (trackElem as? JsonObject)?.get("id")?.jsonPrimitive?.content
            if (!id.isNullOrBlank()) {
                allIds.add(id)
            }
            if (trackElem is JsonObject) {
                try {
                    val fullTrack = playlistJson.decodeFromJsonElement(FullTrackInfo.serializer(), trackElem)
                    val effectiveId = if (fullTrack.id.isNotBlank()) fullTrack.id else (id ?: "")
                    if (effectiveId.isNotBlank()) {
                        richTracks.add(if (fullTrack.id.isBlank()) fullTrack.copy(id = effectiveId) else fullTrack)
                    }
                } catch (e: Exception) {
                    println("MusicRepository: Не удалось распарсить rich-трек: ${e.message}")
                }
            }
        }
        return Pair(allIds, richTracks)
    }

    /**
     * 🔥 Шаг 2: Получаем список ID треков из /playlist/{uuid}
     */
    suspend fun getPlaylistTrackIdsByUuid(uuid: String): List<String> {
        val rawText: String = client.get("${ApiUrls.PLAYLIST_BY_UUID_URL}/$uuid") {
            applyAuthHeaders()
            parameter("resumeStream", false)
            parameter("richTracks", false)
        }.bodyAsText()

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(rawText).jsonObject
        val playlistObj = root["result"]?.jsonObject ?: root
        val tracks = playlistObj["tracks"]?.jsonArray
            ?: throw Exception("Нет поля 'tracks' в ответе playlist/$uuid. Ключи: ${playlistObj.keys}")
        return tracks.mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.content
                ?: it.jsonObject["track"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        }
    }

    /**
     * ⚡ Быстрая загрузка плейлиста пользователя с получением полных метаданных треков за один запрос
     * GET https://api.music.yandex.ru/users/{uid}/playlists/{kind}?richTracks=true
     */
    suspend fun getPlaylistWithTracksByUidKind(uid: Long, kind: Long): Pair<List<String>, List<FullTrackInfo>> {
        val url = "https://api.music.yandex.ru/users/$uid/playlists/$kind"
        val rawText: String = client.get(url) {
            applyAuthHeaders()
            parameter("resumeStream", false)
            parameter("richTracks", true)
        }.bodyAsText()

        val root = playlistJson.parseToJsonElement(rawText).jsonObject
        val playlistObj = root["result"]?.jsonObject ?: root
        val tracksArray = playlistObj["tracks"]?.jsonArray
            ?: throw Exception("Нет поля 'tracks' в ответе $url. Ключи: ${playlistObj.keys}")

        val allIds = mutableListOf<String>()
        val richTracks = mutableListOf<FullTrackInfo>()

        for (elem in tracksArray) {
            val obj = elem.jsonObject
            val trackElem = obj["track"]
            val id = obj["id"]?.jsonPrimitive?.content
                ?: (trackElem as? JsonObject)?.get("id")?.jsonPrimitive?.content
            if (!id.isNullOrBlank()) {
                allIds.add(id)
            }
            if (trackElem is JsonObject) {
                try {
                    val fullTrack = playlistJson.decodeFromJsonElement(FullTrackInfo.serializer(), trackElem)
                    val effectiveId = if (fullTrack.id.isNotBlank()) fullTrack.id else (id ?: "")
                    if (effectiveId.isNotBlank()) {
                        richTracks.add(if (fullTrack.id.isBlank()) fullTrack.copy(id = effectiveId) else fullTrack)
                    }
                } catch (e: Exception) {
                    println("MusicRepository: Не удалось распарсить rich-трек: ${e.message}")
                }
            }
        }
        return Pair(allIds, richTracks)
    }

    /**
     * 🎵 Получаем список ID треков плейлиста по uid владельца и kind
     * GET https://api.music.yandex.ru/users/{uid}/playlists/{kind}
     */
    suspend fun getPlaylistTrackIds(uid: Long, kind: Long): List<String> {
        val url = "https://api.music.yandex.ru/users/$uid/playlists/$kind"
        val rawText: String = client.get(url) {
            applyAuthHeaders()
            parameter("resumeStream", false)
            parameter("richTracks", false)
        }.bodyAsText()

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(rawText).jsonObject
        val playlistObj = root["result"]?.jsonObject ?: root
        val tracks = playlistObj["tracks"]?.jsonArray
            ?: throw Exception("Нет поля 'tracks' в ответе $url. Ключи: ${playlistObj.keys}")
        return tracks.mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.content
                ?: it.jsonObject["track"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        }
    }

    /**
     * 🎵 Получаем персональные плейлисты пользователя (Плейлист дня, Премьера, Дежавю и др.)
     * GET https://api.music.yandex.ru/landing-blocks/personal-playlists
     */
    suspend fun getPersonalPlaylists(): List<PersonalPlaylistItemData> {
        return try {
            val response: HttpResponse = client.get(ApiUrls.PERSONAL_PLAYLISTS_URL) {
                applyAuthHeaders()
            }
            if (response.status.value !in 200..299) {
                println("MusicRepository: Ошибка получения персональных плейлистов: статус ${response.status}")
                return emptyList()
            }
            val rawText = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val parsed = json.decodeFromString<PersonalPlaylistsResponse>(rawText)
            parsed.items.mapNotNull { it.data }
        } catch (e: Exception) {
            println("MusicRepository: Исключение при получении персональных плейлистов: ${e.message}")
            emptyList()
        }
    }

    /**
     * 🎵 Получаем пользовательские плейлисты из аккаунта Яндекс Музыки
     * GET https://api.music.yandex.ru/users/{uid}/playlists/list
     */
    suspend fun getUserPlaylists(): List<PlaylistInfo> {
        val uid = getAccountUid() ?: cachedUserId ?: return emptyList()
        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$uid/playlists/list"
        return try {
            val response: HttpResponse = client.get(url) {
                applyAuthHeaders()
            }
            if (response.status.value !in 200..299) {
                println("MusicRepository: Ошибка получения пользовательских плейлистов: статус ${response.status}")
                return emptyList()
            }
            val rawText = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val root = json.parseToJsonElement(rawText).jsonObject
            val array = root["result"]?.jsonArray ?: return emptyList()
            array.mapNotNull { elem ->
                try {
                    val obj = elem.jsonObject
                    val ownerObj = obj["owner"]?.jsonObject
                    val pUid = obj["uid"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: ownerObj?.get("uid")?.jsonPrimitive?.content?.toLongOrNull()
                        ?: uid.toLongOrNull()
                        ?: 0L
                    val kind = obj["kind"]?.jsonPrimitive?.content?.toLongOrNull()
                    val title = obj["title"]?.jsonPrimitive?.content ?: "Без названия"
                    val description = obj["description"]?.jsonPrimitive?.content
                    val trackCount = obj["trackCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val uuid = obj["playlistUuid"]?.jsonPrimitive?.content
                    val coverObj = obj["cover"]?.jsonObject
                    val coverUri = coverObj?.get("uri")?.jsonPrimitive?.content
                    val revision = obj["revision"]?.jsonPrimitive?.content?.toIntOrNull()
                    PlaylistInfo(
                        uid = pUid,
                        title = title,
                        description = description,
                        trackCount = trackCount,
                        kind = kind,
                        playlistUuid = uuid,
                        coverUri = coverUri,
                        revision = revision
                    )
                } catch (e: Exception) {
                    println("MusicRepository: Ошибка разбора плейлиста: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            println("MusicRepository: Исключение при получении плейлистов пользователя: ${e.message}")
            emptyList()
        }
    }

    /**
     * 📋 Получить детальную информацию о плейлисте пользователя (с актуальной ревизией и списком треков)
     * GET https://api.music.yandex.ru/users/{uid}/playlists/{kind}?richTracks=true
     */
    suspend fun getPlaylistDetails(uid: Long, kind: Long): YandexPlaylistDetails? {
        val effectiveUid = if (uid > 0L) uid.toString() else (getAccountUid() ?: cachedUserId ?: uid.toString())
        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$effectiveUid/playlists/$kind"
        return try {
            val response: HttpResponse = client.get(url) {
                applyAuthHeaders()
                parameter("resumeStream", false)
                parameter("richTracks", true)
            }
            if (response.status.value !in 200..299) {
                println("MusicRepository: Ошибка getPlaylistDetails $url: статус ${response.status}")
                return null
            }
            val rawText = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val root = json.parseToJsonElement(rawText).jsonObject
            val pObj = root["result"]?.jsonObject ?: root
            val revision = pObj["revision"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val trackCount = pObj["trackCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val title = pObj["title"]?.jsonPrimitive?.content ?: "Плейлист"
            val tracksArray = pObj["tracks"]?.jsonArray ?: JsonArray(emptyList())
            val items = tracksArray.mapNotNull { elem ->
                try {
                    val obj = elem.jsonObject
                    val trackObj = obj["track"]?.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content
                        ?: trackObj?.get("id")?.jsonPrimitive?.content
                        ?: return@mapNotNull null
                    val albumId = obj["albumId"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: trackObj?.get("albums")?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content?.toLongOrNull()
                    val trackTitle = trackObj?.get("title")?.jsonPrimitive?.content ?: ""
                    val artistName = trackObj?.get("artists")?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
                    YandexPlaylistTrackItem(
                        id = id,
                        albumId = albumId,
                        title = trackTitle,
                        artist = artistName
                    )
                } catch (e: Exception) {
                    null
                }
            }
            YandexPlaylistDetails(
                uid = uid,
                kind = kind,
                title = title,
                revision = revision,
                trackCount = trackCount,
                tracks = items
            )
        } catch (e: Exception) {
            println("MusicRepository: Ошибка getPlaylistDetails: ${e.message}")
            null
        }
    }

    /**
     * ➕ Добавить трек в персональный плейлист Яндекс Музыки
     * POST https://api.music.yandex.ru/users/{uid}/playlists/{kind}/change
     */
    suspend fun addTrackToPlaylist(kind: Long, trackId: String, albumId: Long? = null): Boolean {
        val uid = getAccountUid() ?: cachedUserId ?: return false
        val numericUid = uid.toLongOrNull() ?: 0L
        var cleanTrackId = trackId.removePrefix("local:")
        var resolvedAlbumId = albumId

        // Если cleanTrackId передан как "trackId:albumId"
        if (cleanTrackId.contains(":")) {
            val parts = cleanTrackId.split(":")
            cleanTrackId = parts[0]
            if (resolvedAlbumId == null || resolvedAlbumId <= 0L) {
                resolvedAlbumId = parts.getOrNull(1)?.toLongOrNull()
            }
        }

        // Если albumId отсутствует, получаем его через запрос деталей трека
        if (cleanTrackId.toLongOrNull() != null && (resolvedAlbumId == null || resolvedAlbumId <= 0L)) {
            try {
                val details = getTracksDetails(listOf(cleanTrackId))
                val firstTrack = details.result.firstOrNull()
                resolvedAlbumId = firstTrack?.albums?.firstOrNull()?.id
            } catch (e: Exception) {
                println("MusicRepository: Не удалось получить albumId для трека $cleanTrackId: ${e.message}")
            }
        }

        // Получаем актуальную ревизию плейлиста
        val details = getPlaylistDetails(numericUid, kind)
        val revision = details?.revision ?: 1

        // Проверяем: возможно, трек уже есть в плейлисте
        if (details != null && details.tracks.any { it.id == cleanTrackId }) {
            println("MusicRepository: Трек $cleanTrackId уже есть в плейлисте $kind")
            return true
        }

        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$uid/playlists/$kind/change"
        val albumPart = if (resolvedAlbumId != null && resolvedAlbumId > 0L) """, "albumId": $resolvedAlbumId""" else ""
        val idValue = if (cleanTrackId.toLongOrNull() != null) cleanTrackId else "\"$cleanTrackId\""
        val diff = """[{"op": "insert", "at": 0, "tracks": [{"id": $idValue$albumPart}]}]"""
        println("MusicRepository: Добавление трека в плейлист $kind (rev: $revision, diff: $diff)")

        return try {
            val response: HttpResponse = client.submitForm(
                url = url,
                formParameters = Parameters.build {
                    append("kind", kind.toString())
                    append("revision", revision.toString())
                    append("diff", diff)
                }
            ) {
                applyAuthHeaders()
            }
            val body = response.bodyAsText()
            val status = response.status.value in 200..299
            println("MusicRepository: Ответ добавления в плейлист: ${response.status} ($body)")

            // Если вернулся revision-mismatch, пробуем получить свежую ревизию и повторить один раз
            if (!status && body.contains("revision", ignoreCase = true)) {
                val freshDetails = getPlaylistDetails(numericUid, kind)
                val freshRevision = freshDetails?.revision
                if (freshRevision != null && freshRevision != revision) {
                    println("MusicRepository: Повторяем добавление с актуальной ревизией: $freshRevision")
                    val retryResp: HttpResponse = client.submitForm(
                        url = url,
                        formParameters = Parameters.build {
                            append("kind", kind.toString())
                            append("revision", freshRevision.toString())
                            append("diff", diff)
                        }
                    ) {
                        applyAuthHeaders()
                    }
                    val retryStatus = retryResp.status.value in 200..299
                    val retryBody = retryResp.bodyAsText()
                    println("MusicRepository: Ответ повторного добавления: ${retryResp.status} ($retryBody)")
                    return retryStatus
                }
            }
            status
        } catch (e: Exception) {
            println("MusicRepository: Ошибка добавления трека в плейлист: ${e.message}")
            false
        }
    }

    /**
     * 🗑️ Удалить трек из персонального плейлиста Яндекс Музыки
     * POST https://api.music.yandex.ru/users/{uid}/playlists/{kind}/change
     */
    suspend fun removeTrackFromPlaylist(kind: Long, trackId: String): Boolean {
        val uid = getAccountUid() ?: cachedUserId ?: return false
        val numericUid = uid.toLongOrNull() ?: 0L
        val cleanTrackId = trackId.removePrefix("local:").substringBefore(":")

        // Получаем актуальный список треков и ревизию
        val details = getPlaylistDetails(numericUid, kind) ?: return false
        val index = details.tracks.indexOfFirst { it.id == cleanTrackId }
        if (index < 0) {
            println("MusicRepository: Трек $cleanTrackId не найден в плейлисте $kind")
            return false
        }

        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$uid/playlists/$kind/change"
        val diff = """[{"op": "delete", "from": $index, "to": ${index + 1}}]"""
        println("MusicRepository: Удаление трека $cleanTrackId (индекс $index) из плейлиста $kind (rev: ${details.revision}, diff: $diff)")

        return try {
            val response: HttpResponse = client.submitForm(
                url = url,
                formParameters = Parameters.build {
                    append("kind", kind.toString())
                    append("revision", details.revision.toString())
                    append("diff", diff)
                }
            ) {
                applyAuthHeaders()
            }
            val body = response.bodyAsText()
            val status = response.status.value in 200..299
            println("MusicRepository: Ответ удаления из плейлиста: ${response.status} ($body)")

            // Если ошибка ревизии — пробуем обновить ревизию и повторить
            if (!status && body.contains("revision", ignoreCase = true)) {
                val freshDetails = getPlaylistDetails(numericUid, kind)
                val freshIndex = freshDetails?.tracks?.indexOfFirst { it.id == cleanTrackId } ?: -1
                val freshRev = freshDetails?.revision
                if (freshRev != null && freshIndex >= 0) {
                    println("MusicRepository: Повторяем удаление с ревизией $freshRev (индекс $freshIndex)")
                    val retryDiff = """[{"op": "delete", "from": $freshIndex, "to": ${freshIndex + 1}}]"""
                    val retryResp: HttpResponse = client.submitForm(
                        url = url,
                        formParameters = Parameters.build {
                            append("kind", kind.toString())
                            append("revision", freshRev.toString())
                            append("diff", retryDiff)
                        }
                    ) {
                        applyAuthHeaders()
                    }
                    val retryStatus = retryResp.status.value in 200..299
                    val retryBody = retryResp.bodyAsText()
                    println("MusicRepository: Ответ повторного удаления: ${retryResp.status} ($retryBody)")
                    return retryStatus
                }
            }
            status
        } catch (e: Exception) {
            println("MusicRepository: Ошибка удаления трека из плейлиста: ${e.message}")
            false
        }
    }

    /**
     * ➕ Создать новый плейлист в аккаунте Яндекс Музыки
     * POST https://api.music.yandex.ru/users/{uid}/playlists/create
     */
    suspend fun createPlaylist(title: String, visibility: String = "public"): PlaylistInfo? {
        val uid = getAccountUid() ?: cachedUserId ?: return null
        val cleanTitle = title.trim().ifBlank { "Новый плейлист" }
        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$uid/playlists/create"
        println("MusicRepository: Создание плейлиста в аккаунте '$cleanTitle'")
        return try {
            val response: HttpResponse = client.submitForm(
                url = url,
                formParameters = Parameters.build {
                    append("title", cleanTitle)
                    append("visibility", visibility)
                }
            ) {
                applyAuthHeaders()
            }
            if (response.status.value !in 200..299) {
                println("MusicRepository: Ошибка создания плейлиста: статус ${response.status}")
                return null
            }
            val rawText = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val root = json.parseToJsonElement(rawText).jsonObject
            val obj = root["result"]?.jsonObject ?: return null
            val kind = obj["kind"]?.jsonPrimitive?.content?.toLongOrNull()
            val pTitle = obj["title"]?.jsonPrimitive?.content ?: cleanTitle
            val desc = obj["description"]?.jsonPrimitive?.content
            val uuid = obj["playlistUuid"]?.jsonPrimitive?.content
            val coverUri = obj["cover"]?.jsonObject?.get("uri")?.jsonPrimitive?.content
            val revision = obj["revision"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            PlaylistInfo(
                uid = uid.toLongOrNull() ?: 0L,
                title = pTitle,
                description = desc,
                trackCount = 0,
                kind = kind,
                playlistUuid = uuid,
                coverUri = coverUri,
                revision = revision
            )
        } catch (e: Exception) {
            println("MusicRepository: Ошибка создания плейлиста: ${e.message}")
            null
        }
    }

    /**
     * ✏️ Переименовать персональный плейлист в аккаунте Яндекс Музыки
     * POST https://api.music.yandex.ru/users/{uid}/playlists/{kind}/name?value={new_title}
     */
    suspend fun renamePlaylist(kind: Long, newTitle: String, uid: Long? = null): PlaylistInfo? {
        val targetUid = (if (uid != null && uid > 0L) uid.toString() else null)
            ?: getAccountUid() ?: cachedUserId ?: return null
        val cleanTitle = newTitle.trim().ifBlank { "Плейлист" }
        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$targetUid/playlists/$kind/name"
        println("MusicRepository: Переименование плейлиста $kind в '$cleanTitle' (uid: $targetUid)")
        return try {
            val response: HttpResponse = client.post(url) {
                parameter("value", cleanTitle)
                applyAuthHeaders()
            }
            if (response.status.value !in 200..299) {
                println("MusicRepository: Ошибка переименования плейлиста: статус ${response.status} (${response.bodyAsText()})")
                return null
            }
            val rawText = response.bodyAsText()
            println("MusicRepository: Ответ переименования плейлиста: $rawText")
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val root = json.parseToJsonElement(rawText).jsonObject
            val obj = root["result"]?.jsonObject ?: root
            val pKind = obj["kind"]?.jsonPrimitive?.content?.toLongOrNull() ?: kind
            val pTitle = obj["title"]?.jsonPrimitive?.content ?: cleanTitle
            val desc = obj["description"]?.jsonPrimitive?.content
            val uuid = obj["playlistUuid"]?.jsonPrimitive?.content
            val coverObj = obj["cover"]?.jsonObject
            val coverUri = coverObj?.get("uri")?.jsonPrimitive?.content
                ?: coverObj?.get("itemsUri")?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                ?: obj["ogImage"]?.jsonPrimitive?.content
            val revision = obj["revision"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val trackCount = obj["trackCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val pUid = obj["uid"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: targetUid.toLongOrNull()
                ?: 0L
            PlaylistInfo(
                uid = pUid,
                title = pTitle,
                description = desc,
                trackCount = trackCount,
                kind = pKind,
                playlistUuid = uuid,
                coverUri = coverUri,
                revision = revision
            )
        } catch (e: Exception) {
            println("MusicRepository: Ошибка переименования плейлиста: ${e.message}")
            null
        }
    }

    /**
     * 🔥 Получаем историю прослушивания (уникальные ID треков)
     */
    suspend fun getHistoryTrackIds(): List<String> {
        val rawText: String = client.get(ApiUrls.HISTORY_URL) {
            applyAuthHeaders()
            parameter("fullModelsCount", 25)
        }.bodyAsText()

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(rawText).jsonObject
        val result = root["result"]?.jsonObject
            ?: throw Exception("Нет поля 'result' в ответе music-history")
        val historyTabs = result["historyTabs"]?.jsonArray
            ?: throw Exception("Нет поля 'historyTabs' в result")

        val trackIds = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (tab in historyTabs) {
            val items = tab.jsonObject["items"]?.jsonArray ?: continue
            for (item in items) {
                val tracks = item.jsonObject["tracks"]?.jsonArray ?: continue
                for (track in tracks) {
                    val trackId = track.jsonObject["data"]
                        ?.jsonObject?.get("itemId")
                        ?.jsonObject?.get("trackId")
                        ?.jsonPrimitive?.content ?: continue
                    if (seen.add(trackId)) {
                        trackIds.add(trackId)
                    }
                }
            }
        }
        return trackIds
    }

    /**
     * Загрузка аудиоданных трека.
     */
    suspend fun downloadTrackAudio(trackId: String, quality: String = "1", customTimestamp: String? = null): DownloadedTrackAudio {
        val timestamp = customTimestamp ?: ((io.ktor.util.date.getTimeMillis() / 1000)).toString()

        var fileInfoUrl = trackSigner.signTrackUrl(trackId, quality, timestamp)
            ?: throw Exception("Не удалось получить ссылку на трек")

        if (fileInfoUrl.contains("sign=")) {
            val urlParts = fileInfoUrl.split("sign=")
            val baseUrl = urlParts[0]
            val sign = urlParts[1]
            val encodedSign = sign.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")
            fileInfoUrl = "${baseUrl}sign=$encodedSign"
        }

        try {
            // 2. Делаем запрос и получаем ответ
            val response: HttpResponse = client.get(fileInfoUrl) {
                applyAuthHeaders()
                headers["x-yandex-music-client"] = "YandexMusicWebNext/1.0.0"
                header("x-yandex-music-without-invocation-info", "1")
            }

            CoreLogger.i("MusicRepository", "Сервер ответил get-file-info. Статус: ${response.status}")

            val rawJsonText = response.bodyAsText()
            if (response.status.value !in 200..299) {
                CoreLogger.e("MusicRepository", "Ошибка получения get-file-info: ${response.status} ($rawJsonText)")
                throw Exception("Сервер вернул ошибку ${response.status}: $rawJsonText")
            }

            // 3. Парсим метаданные скачивания
            val parsedInfo = parseTrackDownloadInfo(rawJsonText)
                ?: throw Exception("Не удалось распарсить JSON метаданных: $rawJsonText")

            // 4. Загрузка аудиоданных
            CoreLogger.d("MusicRepository", "Старт загрузки аудиопотока...")
            val encryptedBytes: ByteArray = client.get(parsedInfo.url).bodyAsBytes()
            CoreLogger.i("MusicRepository", "Аудиопоток успешно загружен. Размер: ${encryptedBytes.size} байт")

            // 5. Обработка аудиоданных
            val decryptedAudio = decryptAesCtr(encryptedBytes, io.ktor.util.hex(parsedInfo.hex), ByteArray(16))

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

            return DownloadedTrackAudio(decryptedAudio, parsedInfo.type, bitrate)

        } catch (networkException: Throwable) {
            println("Core API Debug: КРИТИЧЕСКАЯ СЕТЕВАЯ ОШИБКА ИЛИ ПАДЕНИЕ КОРУТИНЫ!")
            println("Тип ошибки: ${networkException::class.simpleName}")
            println("Сообщение: ${networkException.message}")
            networkException.printStackTrace()
            throw networkException
        }
    }

    /**
     * 🌐 Получение информации о прямом потоке трека для локального стриминг-прокси.
     * Получает метаданные и размер аудиофайла.
     */
    suspend fun getTrackStreamMeta(trackId: String, quality: String = "1", customTimestamp: String? = null): TrackStreamMeta {
        val timestamp = customTimestamp ?: ((io.ktor.util.date.getTimeMillis() / 1000)).toString()
        var fileInfoUrl = trackSigner.signTrackUrl(trackId, quality, timestamp)
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

        // Запрашиваем 1 байт (Range: bytes=0-0) для быстрого получения точного размера файла из Content-Range
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
            println("Core API: Ошибка определения размера трека: ${e.message}")
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
     * 📥 Загрузка фрагмента зашифрованного аудиопотока по HTTP Range
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
            client.get(cleanUrl).bodyAsBytes()
        } catch (e: Exception) {
            println("MusicRepository: Ошибка загрузки изображения $url: ${e.message}")
            null
        }
    }

    /**
     * 👤 Получить UID текущего авторизованного пользователя
     */
    suspend fun getAccountUid(): String? {
        return try {
            val response: String = client.get("https://api.music.yandex.net/account/status") {
                applyAuthHeaders()
            }.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(response).jsonObject
            root["result"]?.jsonObject?.get("account")?.jsonObject?.get("uid")?.jsonPrimitive?.content
        } catch (e: Exception) {
            println("MusicRepository: Не удалось получить UID аккаунта: ${e.message}")
            null
        }
    }

    /**
     * 🌊 Получить session_id Моей волны через Ynison WebSocket
     */
    suspend fun fetchYnisonWaveSessionId(): String? {
        val token = accessToken
        if (token.isBlank()) {
            println("MusicRepository: токен пуст, пропускаем Ynison")
            return null
        }
        return try {
            val uid = getAccountUid() ?: ""
            ynisonService.getWaveSessionId(token, uid)
        } catch (t: Throwable) {
            println("MusicRepository: Не удалось получить сессию Ynison: ${t.message}")
            null
        }
    }

    /**
     * 🔄 Клонировать Rotor-сессию по waveSessionId
     */
    suspend fun cloneRotorSessionRaw(waveSessionId: String): String {
        val url = "https://api.music.yandex.ru/rotor/session/$waveSessionId/clone"
        println("MusicRepository: POST $url")
        val response: HttpResponse = client.post(url) {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val text = response.bodyAsText()
        println("MusicRepository: Ответ clone:\n$text")
        return text
    }

    /**
     * 📦 Парсер ответов Rotor (обрабатывает и корневой JSON, и вложенный в 'result')
     */
    fun parseRotorResponse(jsonStr: String): RotorSessionResponse {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val element = json.parseToJsonElement(jsonStr).jsonObject
        val targetObj = if (element.containsKey("result") && element["result"] is JsonObject) {
            element["result"]!!.jsonObject
        } else {
            element
        }
        return json.decodeFromJsonElement(RotorSessionResponse.serializer(), targetObj)
    }

    /**
     * 🔄 Клонировать Rotor-сессию и получить начальные 5 треков
     */
    suspend fun cloneRotorSession(waveSessionId: String): RotorSessionResponse {
        val raw = cloneRotorSessionRaw(waveSessionId)
        return parseRotorResponse(raw)
    }

    /**
     * 🌊 Создать Rotor-сессию по списку seeds (например, для жанровой Волны: ["genre:metal"])
     */
    suspend fun createRotorSessionRaw(seeds: List<String>): String {
        val url = "https://api.music.yandex.ru/rotor/session/new"
        println("MusicRepository: POST $url with seeds: $seeds")
        val seedsJson = seeds.joinToString(",") { "\"$it\"" }
        val payload = """{"seeds":[$seedsJson],"includeTracksInResponse":true}"""
        val response: HttpResponse = client.post(url) {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val text = response.bodyAsText()
        println("MusicRepository: Ответ session/new:\n$text")
        return text
    }

    /**
     * 🌊 Создать Rotor-сессию с треками по списку seeds
     */
    suspend fun createRotorSession(seeds: List<String>): RotorSessionResponse {
        val raw = createRotorSessionRaw(seeds)
        var parsed = parseRotorResponse(raw)
        val sid = parsed.radioSessionId
        if (parsed.sequence.isEmpty() && !sid.isNullOrBlank()) {
            println("MusicRepository: sequence пуст в ответе session/new, запрашиваем tracks напрямую...")
            parsed = getRotorNextTracks(sid, feedbacks = emptyList())
        }
        return parsed
    }

    /**
     * ⏭ Получить следующие треки Волны (список фидбеков: skip или накопленные trackFinished)
     */
    suspend fun getRotorNextTracks(
        radioSessionId: String,
        feedbacks: List<RotorFeedbackItem>,
        remainingQueue: List<String> = emptyList()
    ): RotorSessionResponse {
        val raw = getRotorNextTracksRaw(
            radioSessionId = radioSessionId,
            feedbacks = feedbacks,
            remainingQueue = remainingQueue
        )
        return parseRotorResponse(raw)
    }

    /**
     * Перегрузка для отправки одиночного фидбека (обратная совместимость)
     */
    suspend fun getRotorNextTracks(
        radioSessionId: String,
        batchId: String,
        currentTrackIdWithAlbum: String,
        playedSeconds: Double,
        remainingQueue: List<String> = emptyList(),
        eventType: String = "skip"
    ): RotorSessionResponse {
        return getRotorNextTracks(
            radioSessionId = radioSessionId,
            feedbacks = listOf(
                RotorFeedbackItem(
                    batchId = batchId,
                    trackId = currentTrackIdWithAlbum,
                    eventType = eventType,
                    totalPlayedSeconds = playedSeconds
                )
            ),
            remainingQueue = remainingQueue
        )
    }

    /**
     * ⏭ Получить следующие треки / отправить фидбеки (сырой JSON)
     */
    suspend fun getRotorNextTracksRaw(
        radioSessionId: String,
        feedbacks: List<RotorFeedbackItem>,
        remainingQueue: List<String> = emptyList()
    ): String {
        val url = "https://api.music.yandex.ru/rotor/session/$radioSessionId/tracks"
        val defaultTime = currentIsoUtcTimestamp()

        val feedbacksJson = feedbacks.joinToString(",") { fb ->
            val time = fb.timestamp ?: defaultTime
            val lengthField = if (fb.trackLengthSeconds != null) ",\"trackLengthSeconds\":${fb.trackLengthSeconds}" else ""
            """{"batchId":"${fb.batchId}","event":{"type":"${fb.eventType}","timestamp":"$time","trackId":"${fb.trackId}","totalPlayedSeconds":${fb.totalPlayedSeconds}$lengthField},"from":"${fb.from}"}"""
        }
        val queueJson = remainingQueue.joinToString(",") { "\"$it\"" }
        val payload = """{"queue":[$queueJson],"feedbacks":[$feedbacksJson]}"""

        val eventTypes = feedbacks.map { it.eventType }
        println("MusicRepository: POST ${url} ($eventTypes, queue: ${remainingQueue.size} items)")
        val response: HttpResponse = client.post(url) {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val text = response.bodyAsText()
        println("MusicRepository: Ответ next tracks ($eventTypes):\n$text")
        return text
    }

    /**
     * 🚀 Отправить фидбек trackStarted при старте трека в Моей волне
     * POST https://api.music.yandex.ru/rotor/session/{radioSessionId}/feedback/
     */
    suspend fun sendRotorFeedbackTrackStarted(
        radioSessionId: String,
        batchId: String,
        trackIdWithAlbum: String,
        timestamp: String = currentIsoUtcTimestamp()
    ): Boolean {
        val url = "https://api.music.yandex.ru/rotor/session/$radioSessionId/feedback/"
        val payload = """{"event":{"type":"trackStarted","timestamp":"$timestamp","trackId":"$trackIdWithAlbum"},"batchId":"$batchId","from":"web-wave_landing_screen-my_wave-radio-default"}"""
        println("MusicRepository: POST $url (trackStarted: $trackIdWithAlbum)")
        return try {
            val response: HttpResponse = client.post(url) {
                applyAuthHeaders()
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val status = response.status.value in 200..299
            println("MusicRepository: trackStarted response status: ${response.status}")
            status
        } catch (e: Exception) {
            println("MusicRepository: Ошибка при отправке trackStarted: ${e.message}")
            false
        }
    }

    /**
     * 📊 Отправить отчет о прослушивании трека в Яндекс Музыку
     * POST https://api.music.yandex.ru/plays?clientNow={ISO}
     */
    suspend fun sendPlayReport(
        trackId: String,
        albumId: String,
        radioSessionId: String,
        batchId: String,
        totalPlayedSeconds: Double,
        endPositionSeconds: Double,
        trackLengthSeconds: Double,
        startTimestamp: String,
        endTimestamp: String = currentIsoUtcTimestamp(),
        changeReason: String = "finish", // "finish" | "skip"
        playId: String = generatePlayUuid()
    ): Boolean {
        val url = "https://api.music.yandex.ru/plays?clientNow=$endTimestamp"
        val addTracksTime = "${kotlin.random.Random.nextLong(1000000000000000L, 9999999999999999L)}-${kotlin.random.Random.nextInt(1000000000, 2000000000)}"
        val payload = """{"plays":[{"playId":"$playId","from":"web-wave_landing_screen-my_wave-radio-default","totalPlayedSeconds":$totalPlayedSeconds,"endPositionSeconds":$endPositionSeconds,"trackLengthSeconds":$trackLengthSeconds,"timestamp":"$endTimestamp","albumId":"$albumId","context":"radio","contextItem":"user:onyourwave","addTracksToPlayerTime":"$addTracksTime","fromCache":false,"isRestored":false,"audioAuto":"none","audioOutputName":"Динамик","audioOutputType":"Speaker","trackId":"$trackId","radioSessionId":"$radioSessionId","batchId":"$batchId","isFromAutoflow":false,"isFromPumpkin":false,"seek":true,"pause":false,"startTimestamp":"$startTimestamp","maxPlayerStage":"play","isRepeated":false,"changeReason":"$changeReason","isLivePlayableIndex":true}]}"""
        println("MusicRepository: POST /plays ($changeReason, trackId: $trackId, played: ${totalPlayedSeconds}s)")
        return try {
            val response: HttpResponse = client.post(url) {
                applyAuthHeaders()
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val status = response.status.value in 200..299
            println("MusicRepository: /plays response status: ${response.status}")
            status
        } catch (e: Exception) {
            println("MusicRepository: Ошибка при отправке /plays: ${e.message}")
            false
        }
    }
}

/**
 * 🕒 Получить текущую дату-время в формате ISO-8601 UTC с миллисекундами
 */
fun currentIsoUtcTimestamp(): String {
    val date = io.ktor.util.date.GMTDate()
    val millis = (io.ktor.util.date.getTimeMillis() % 1000).toString().padStart(3, '0')
    val y = date.year
    val m = (date.month.ordinal + 1).toString().padStart(2, '0')
    val d = date.dayOfMonth.toString().padStart(2, '0')
    val h = date.hours.toString().padStart(2, '0')
    val min = date.minutes.toString().padStart(2, '0')
    val s = date.seconds.toString().padStart(2, '0')
    return "${y}-${m}-${d}T${h}:${min}:${s}.${millis}Z"
}

/**
 * 🔑 Сгенерировать случайный UUID v4
 */
fun generatePlayUuid(): String {
    val chars = "0123456789abcdef"
    val randomBytes = kotlin.random.Random.nextBytes(16)
    randomBytes[6] = ((randomBytes[6].toInt() and 0x0f) or 0x40).toByte()
    randomBytes[8] = ((randomBytes[8].toInt() and 0x3f) or 0x80).toByte()
    return buildString(36) {
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            val b = randomBytes[i].toInt() and 0xff
            append(chars[b ushr 4])
            append(chars[b and 0x0f])
        }
    }
}

/**
 * 🎵 Элемент отзыва (фидбека) для Моей Волны (Rotor)
 */
data class RotorFeedbackItem(
    val batchId: String,
    val trackId: String,
    val eventType: String, // "skip" | "trackFinished"
    val totalPlayedSeconds: Double,
    val trackLengthSeconds: Double? = null,
    val timestamp: String? = null,
    val from: String = "web-wave_landing_screen-my_wave-radio-default"
)
