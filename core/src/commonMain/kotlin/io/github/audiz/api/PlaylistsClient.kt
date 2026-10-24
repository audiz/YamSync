package io.github.audiz.api

import io.github.audiz.models.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * 📂 Клиент плейлистов, альбомов, отметок мне нравится/не нравится и истории прослушиваний
 */
class PlaylistsClient(
    private val context: ApiSessionContext,
    private val searchClient: SearchClient? = null
) {
    private val client get() = context.client
    private val playlistJson get() = context.json

    private fun HttpRequestBuilder.applyAuthHeaders() {
        with(context) { applyAuthHeaders() }
    }

    /**
     * ❤️ Добавить трек в избранное (лайк)
     * POST https://api.music.yandex.ru/users/{userId}/likes/tracks/add?track-id={cleanKey}
     * Фолбэк: POST https://api.music.yandex.ru/users/{userId}/likes/tracks/add-multiple (с track-ids)
     */
    suspend fun likeTrack(trackIdWithAlbum: String): Boolean {
        val userId = context.getUserId()
        if (userId.isNullOrBlank()) {
            println("PlaylistsClient: Не удалось поставить лайк: userId неизвестен")
            return false
        }
        val cleanTrackId = trackIdWithAlbum.removePrefix("local:").substringBefore(":")
        val albumPart = trackIdWithAlbum.removePrefix("local:").substringAfter(":", "").toLongOrNull()
        val trackKey = if (albumPart != null && albumPart > 0) "$cleanTrackId:$albumPart" else cleanTrackId

        val url = "https://api.music.yandex.ru/users/$userId/likes/tracks/add"
        return try {
            val response: HttpResponse = client.post(url) {
                applyAuthHeaders()
                parameter("track-id", trackKey)
            }
            if (response.status.value in 200..299) {
                println("PlaylistsClient: Лайк отправлен (POST $url?track-id=$trackKey) -> статус ${response.status}")
                true
            } else {
                val fallbackUrl = "https://api.music.yandex.ru/users/$userId/likes/tracks/add-multiple"
                val fallbackResponse: HttpResponse = client.post(fallbackUrl) {
                    applyAuthHeaders()
                    parameter("track-ids", cleanTrackId)
                }
                println("PlaylistsClient: Фолбэк лайка (POST $fallbackUrl?track-ids=$cleanTrackId) -> статус ${fallbackResponse.status}")
                fallbackResponse.status.value in 200..299
            }
        } catch (e: Exception) {
            println("PlaylistsClient: Ошибка при отправке лайка: ${e.message}")
            false
        }
    }

    /**
     * 💔 Удалить трек из избранного
     * POST https://api.music.yandex.ru/users/{userId}/likes/tracks/remove?track-ids={cleanTrackId}
     * Фолбэк: POST https://api.music.yandex.ru/users/{userId}/likes/tracks/{cleanTrackId}/remove
     */
    suspend fun unlikeTrack(trackIdWithAlbum: String): Boolean {
        val userId = context.getUserId()
        if (userId.isNullOrBlank()) {
            println("PlaylistsClient: Не удалось снять лайк: userId неизвестен")
            return false
        }
        val cleanTrackId = trackIdWithAlbum.removePrefix("local:").substringBefore(":")
        val removeUrl = "https://api.music.yandex.ru/users/$userId/likes/tracks/remove"
        return try {
            val response: HttpResponse = client.post(removeUrl) {
                applyAuthHeaders()
                parameter("track-ids", cleanTrackId)
                parameter("track-id", cleanTrackId)
            }
            if (response.status.value in 200..299) {
                println("PlaylistsClient: Лайк снят (POST $removeUrl?track-ids=$cleanTrackId) -> статус ${response.status}")
                true
            } else {
                val pathUrl = "https://api.music.yandex.ru/users/$userId/likes/tracks/$cleanTrackId/remove"
                val pathResponse: HttpResponse = client.post(pathUrl) {
                    applyAuthHeaders()
                }
                println("PlaylistsClient: Фолбэк снятия лайка (POST $pathUrl) -> статус ${pathResponse.status}")
                pathResponse.status.value in 200..299
            }
        } catch (e: Exception) {
            println("PlaylistsClient: Ошибка при снятии лайка: ${e.message}")
            try {
                val pathUrl = "https://api.music.yandex.ru/users/$userId/likes/tracks/$cleanTrackId/remove"
                val pathResponse: HttpResponse = client.post(pathUrl) {
                    applyAuthHeaders()
                }
                pathResponse.status.value in 200..299
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 💔 Добавить трек в дизлайки
     * POST https://api.music.yandex.ru/users/{userId}/dislikes/tracks/add?track-id={trackKey}
     */
    suspend fun dislikeTrack(trackIdWithAlbum: String): Boolean {
        val userId = context.getUserId()
        if (userId.isNullOrBlank()) {
            println("PlaylistsClient: Не удалось поставить дизлайк: userId неизвестен")
            return false
        }
        val cleanTrackId = trackIdWithAlbum.removePrefix("local:").substringBefore(":")
        val albumPart = trackIdWithAlbum.removePrefix("local:").substringAfter(":", "").toLongOrNull()
        val trackKey = if (albumPart != null && albumPart > 0) "$cleanTrackId:$albumPart" else cleanTrackId

        val url = "https://api.music.yandex.ru/users/$userId/dislikes/tracks/add"
        return try {
            val response: HttpResponse = client.post(url) {
                applyAuthHeaders()
                parameter("track-id", trackKey)
            }
            if (response.status.value in 200..299) {
                println("PlaylistsClient: Дизлайк отправлен (POST $url?track-id=$trackKey) -> статус ${response.status}")
                true
            } else {
                val fallbackResponse: HttpResponse = client.post(url) {
                    applyAuthHeaders()
                    parameter("track-id", cleanTrackId)
                }
                println("PlaylistsClient: Фолбэк дизлайка (POST $url?track-id=$cleanTrackId) -> статус ${fallbackResponse.status}")
                fallbackResponse.status.value in 200..299
            }
        } catch (e: Exception) {
            println("PlaylistsClient: Ошибка при отправке дизлайка: ${e.message}")
            false
        }
    }

    /**
     * 🤍 Снять дизлайк с трека
     * POST https://api.music.yandex.ru/users/{userId}/dislikes/tracks/remove?track-ids={cleanTrackId}
     * Фолбэк: POST https://api.music.yandex.ru/users/{userId}/dislikes/tracks/{cleanTrackId}/remove
     */
    suspend fun undislikeTrack(trackIdWithAlbum: String): Boolean {
        val userId = context.getUserId()
        if (userId.isNullOrBlank()) {
            println("PlaylistsClient: Не удалось снять дизлайк: userId неизвестен")
            return false
        }
        val cleanTrackId = trackIdWithAlbum.removePrefix("local:").substringBefore(":")
        val removeUrl = "https://api.music.yandex.ru/users/$userId/dislikes/tracks/remove"
        return try {
            val response: HttpResponse = client.post(removeUrl) {
                applyAuthHeaders()
                parameter("track-ids", cleanTrackId)
                parameter("track-id", cleanTrackId)
            }
            if (response.status.value in 200..299) {
                println("PlaylistsClient: Дизлайк снят (POST $removeUrl?track-ids=$cleanTrackId) -> статус ${response.status}")
                true
            } else {
                val pathUrl = "https://api.music.yandex.ru/users/$userId/dislikes/tracks/$cleanTrackId/remove"
                val pathResponse: HttpResponse = client.post(pathUrl) {
                    applyAuthHeaders()
                }
                println("PlaylistsClient: Фолбэк снятия дизлайка (POST $pathUrl) -> статус ${pathResponse.status}")
                pathResponse.status.value in 200..299
            }
        } catch (e: Exception) {
            println("PlaylistsClient: Ошибка при снятии дизлайка: ${e.message}")
            try {
                val pathUrl = "https://api.music.yandex.ru/users/$userId/dislikes/tracks/$cleanTrackId/remove"
                val pathResponse: HttpResponse = client.post(pathUrl) {
                    applyAuthHeaders()
                }
                pathResponse.status.value in 200..299
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 🔥 Получаем playlistUuid из landing-blocks/collection/playlist-with-likes
     */
    suspend fun getLikesPlaylistUuid(): String {
        val rawText: String = client.get(ApiUrls.LANDING_LIKES_URL) {
            applyAuthHeaders()
        }.bodyAsText()

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
                    println("PlaylistsClient: Не удалось распарсить rich-трек: ${e.message}")
                }
            }
        }
        val rawTrackIds = playlistObj["trackIds"]?.jsonArray
        val allIdsFromTrackIds = rawTrackIds?.mapNotNull { elem ->
            when (elem) {
                is JsonPrimitive -> elem.content.takeIf { it.isNotBlank() }
                is JsonObject -> elem["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        val finalAllIds = if (!allIdsFromTrackIds.isNullOrEmpty()) allIdsFromTrackIds else allIds
        return Pair(finalAllIds, richTracks)
    }

    /**
     * Получаем список ID треков из /playlist/{uuid}
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
                    println("PlaylistsClient: Не удалось распарсить rich-трек: ${e.message}")
                }
            }
        }
        val rawTrackIds = playlistObj["trackIds"]?.jsonArray
        val allIdsFromTrackIds = rawTrackIds?.mapNotNull { elem ->
            when (elem) {
                is JsonPrimitive -> elem.content.takeIf { it.isNotBlank() }
                is JsonObject -> elem["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        val finalAllIds = if (!allIdsFromTrackIds.isNullOrEmpty()) allIdsFromTrackIds else allIds
        return Pair(finalAllIds, richTracks)
    }

    /**
     * 💿 Получение треков альбома
     * GET https://api.music.yandex.ru/albums/{albumId}/with-tracks
     */
    suspend fun getAlbumWithTracks(albumId: Long): Pair<List<String>, List<FullTrackInfo>> {
        val url = "https://api.music.yandex.ru/albums/$albumId/with-tracks"
        val rawText: String = client.get(url) {
            applyAuthHeaders()
        }.bodyAsText()

        val root = playlistJson.parseToJsonElement(rawText).jsonObject
        val albumObj = root["result"]?.jsonObject ?: root
        val volumesArray = albumObj["volumes"]?.jsonArray
        val directTracksArray = albumObj["tracks"]?.jsonArray
        val allTrackElements = mutableListOf<JsonObject>()

        if (volumesArray != null) {
            for (v in volumesArray) {
                (v as? JsonArray)?.forEach { elem ->
                    (elem as? JsonObject)?.let { allTrackElements.add(it) }
                }
            }
        } else if (directTracksArray != null) {
            for (elem in directTracksArray) {
                (elem as? JsonObject)?.let { allTrackElements.add(it) }
            }
        }

        val allIds = mutableListOf<String>()
        val richTracks = mutableListOf<FullTrackInfo>()

        for (obj in allTrackElements) {
            val trackObj = obj["track"] as? JsonObject ?: obj
            val id = trackObj["id"]?.jsonPrimitive?.content ?: obj["id"]?.jsonPrimitive?.content
            if (!id.isNullOrBlank()) {
                allIds.add(id)
            }
            try {
                val fullTrack = playlistJson.decodeFromJsonElement(FullTrackInfo.serializer(), trackObj)
                val effectiveId = if (fullTrack.id.isNotBlank()) fullTrack.id else (id ?: "")
                if (effectiveId.isNotBlank()) {
                    richTracks.add(if (fullTrack.id.isBlank()) fullTrack.copy(id = effectiveId) else fullTrack)
                }
            } catch (e: Exception) {
                println("PlaylistsClient: Не удалось распарсить трек альбома: ${e.message}")
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
                println("PlaylistsClient: Ошибка получения персональных плейлистов: статус ${response.status}")
                return emptyList()
            }
            val rawText = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val parsed = json.decodeFromString<PersonalPlaylistsResponse>(rawText)
            parsed.items.mapNotNull { it.data }
        } catch (e: Exception) {
            println("PlaylistsClient: Исключение при получении персональных плейлистов: ${e.message}")
            emptyList()
        }
    }

    /**
     * 🎵 Получаем пользовательские плейлисты из аккаунта Яндекс Музыки
     * GET https://api.music.yandex.ru/users/{uid}/playlists/list
     */
    suspend fun getUserPlaylists(): List<PlaylistInfo> {
        val uid = context.getAccountUid() ?: context.cachedUserId ?: return emptyList()
        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$uid/playlists/list"
        return try {
            val response: HttpResponse = client.get(url) {
                applyAuthHeaders()
            }
            if (response.status.value !in 200..299) {
                println("PlaylistsClient: Ошибка получения пользовательских плейлистов: статус ${response.status}")
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
                    println("PlaylistsClient: Ошибка разбора плейлиста: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            println("PlaylistsClient: Исключение при получении плейлистов пользователя: ${e.message}")
            emptyList()
        }
    }

    /**
     * 📋 Получить детальную информацию о плейлисте пользователя (с актуальной ревизией и списком треков)
     * GET https://api.music.yandex.ru/users/{uid}/playlists/{kind}?richTracks=true
     */
    suspend fun getPlaylistDetails(uid: Long, kind: Long): YandexPlaylistDetails? {
        val effectiveUid = if (uid > 0L) uid.toString() else (context.getAccountUid() ?: context.cachedUserId ?: uid.toString())
        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$effectiveUid/playlists/$kind"
        return try {
            val response: HttpResponse = client.get(url) {
                applyAuthHeaders()
                parameter("resumeStream", false)
                parameter("richTracks", true)
            }
            if (response.status.value !in 200..299) {
                println("PlaylistsClient: Ошибка getPlaylistDetails $url: статус ${response.status}")
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
            println("PlaylistsClient: Ошибка getPlaylistDetails: ${e.message}")
            null
        }
    }

    /**
     * ➕ Добавить трек в персональный плейлист Яндекс Музыки
     * POST https://api.music.yandex.ru/users/{uid}/playlists/{kind}/change
     */
    suspend fun addTrackToPlaylist(kind: Long, trackId: String, albumId: Long? = null): Boolean {
        val uid = context.getAccountUid() ?: context.cachedUserId ?: return false
        val numericUid = uid.toLongOrNull() ?: 0L
        var cleanTrackId = trackId.removePrefix("local:")
        var resolvedAlbumId = albumId

        if (cleanTrackId.contains(":")) {
            val parts = cleanTrackId.split(":")
            cleanTrackId = parts[0]
            if (resolvedAlbumId == null || resolvedAlbumId <= 0L) {
                resolvedAlbumId = parts.getOrNull(1)?.toLongOrNull()
            }
        }

        if (cleanTrackId.toLongOrNull() != null && (resolvedAlbumId == null || resolvedAlbumId <= 0L)) {
            try {
                val sc = searchClient ?: SearchClient(context)
                val details = sc.getTracksDetails(listOf(cleanTrackId))
                val firstTrack = details.result.firstOrNull()
                resolvedAlbumId = firstTrack?.albums?.firstOrNull()?.id
            } catch (e: Exception) {
                println("PlaylistsClient: Не удалось получить albumId для трека $cleanTrackId: ${e.message}")
            }
        }

        val details = getPlaylistDetails(numericUid, kind)
        val revision = details?.revision ?: 1

        if (details != null && details.tracks.any { it.id == cleanTrackId }) {
            println("PlaylistsClient: Трек $cleanTrackId уже есть в плейлисте $kind")
            return true
        }

        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$uid/playlists/$kind/change"
        val albumPart = if (resolvedAlbumId != null && resolvedAlbumId > 0L) """, "albumId": $resolvedAlbumId""" else ""
        val idValue = if (cleanTrackId.toLongOrNull() != null) cleanTrackId else "\"$cleanTrackId\""
        val diff = """[{"op": "insert", "at": 0, "tracks": [{"id": $idValue$albumPart}]}]"""
        println("PlaylistsClient: Добавление трека в плейлист $kind (rev: $revision, diff: $diff)")

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
            println("PlaylistsClient: Ответ добавления в плейлист: ${response.status} ($body)")

            if (!status && body.contains("revision", ignoreCase = true)) {
                val freshDetails = getPlaylistDetails(numericUid, kind)
                val freshRevision = freshDetails?.revision
                if (freshRevision != null && freshRevision != revision) {
                    println("PlaylistsClient: Повторяем добавление с актуальной ревизией: $freshRevision")
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
                    println("PlaylistsClient: Ответ повторного добавления: ${retryResp.status} ($retryBody)")
                    return retryStatus
                }
            }
            status
        } catch (e: Exception) {
            println("PlaylistsClient: Ошибка добавления трека в плейлист: ${e.message}")
            false
        }
    }

    /**
     * 🗑️ Удалить трек из персонального плейлиста Яндекс Музыки
     * POST https://api.music.yandex.ru/users/{uid}/playlists/{kind}/change
     */
    suspend fun removeTrackFromPlaylist(kind: Long, trackId: String): Boolean {
        val uid = context.getAccountUid() ?: context.cachedUserId ?: return false
        val numericUid = uid.toLongOrNull() ?: 0L
        val cleanTrackId = trackId.removePrefix("local:").substringBefore(":")

        val details = getPlaylistDetails(numericUid, kind) ?: return false
        val index = details.tracks.indexOfFirst { it.id == cleanTrackId }
        if (index < 0) {
            println("PlaylistsClient: Трек $cleanTrackId не найден в плейлисте $kind")
            return false
        }

        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$uid/playlists/$kind/change"
        val diff = """[{"op": "delete", "from": $index, "to": ${index + 1}}]"""
        println("PlaylistsClient: Удаление трека $cleanTrackId (индекс $index) из плейлиста $kind (rev: ${details.revision}, diff: $diff)")

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
            println("PlaylistsClient: Ответ удаления из плейлиста: ${response.status} ($body)")

            if (!status && body.contains("revision", ignoreCase = true)) {
                val freshDetails = getPlaylistDetails(numericUid, kind)
                val freshIndex = freshDetails?.tracks?.indexOfFirst { it.id == cleanTrackId } ?: -1
                val freshRev = freshDetails?.revision
                if (freshRev != null && freshIndex >= 0) {
                    println("PlaylistsClient: Повторяем удаление с ревизией $freshRev (индекс $freshIndex)")
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
                    println("PlaylistsClient: Ответ повторного удаления: ${retryResp.status} ($retryBody)")
                    return retryStatus
                }
            }
            status
        } catch (e: Exception) {
            println("PlaylistsClient: Ошибка удаления трека из плейлиста: ${e.message}")
            false
        }
    }

    /**
     * ➕ Создать новый плейлист в аккаунте Яндекс Музыки
     * POST https://api.music.yandex.ru/users/{uid}/playlists/create
     */
    suspend fun createPlaylist(title: String, visibility: String = "public"): PlaylistInfo? {
        val uid = context.getAccountUid() ?: context.cachedUserId ?: return null
        val cleanTitle = title.trim().ifBlank { "Новый плейлист" }
        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$uid/playlists/create"
        println("PlaylistsClient: Создание плейлиста в аккаунте '$cleanTitle'")
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
                println("PlaylistsClient: Ошибка создания плейлиста: статус ${response.status}")
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
            println("PlaylistsClient: Ошибка создания плейлиста: ${e.message}")
            null
        }
    }

    /**
     * ✏️ Переименовать персональный плейлист в аккаунте Яндекс Музыки
     * POST https://api.music.yandex.ru/users/{uid}/playlists/{kind}/name?value={new_title}
     */
    suspend fun renamePlaylist(kind: Long, newTitle: String, uid: Long? = null): PlaylistInfo? {
        val targetUid = (if (uid != null && uid > 0L) uid.toString() else null)
            ?: context.getAccountUid() ?: context.cachedUserId ?: return null
        val cleanTitle = newTitle.trim().ifBlank { "Плейлист" }
        val url = "${ApiUrls.USER_PLAYLISTS_BASE_URL}/$targetUid/playlists/$kind/name"
        println("PlaylistsClient: Переименование плейлиста $kind в '$cleanTitle' (uid: $targetUid)")
        return try {
            val response: HttpResponse = client.post(url) {
                parameter("value", cleanTitle)
                applyAuthHeaders()
            }
            if (response.status.value !in 200..299) {
                println("PlaylistsClient: Ошибка переименования плейлиста: статус ${response.status} (${response.bodyAsText()})")
                return null
            }
            val rawText = response.bodyAsText()
            println("PlaylistsClient: Ответ переименования плейлиста: $rawText")
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
            println("PlaylistsClient: Ошибка переименования плейлиста: ${e.message}")
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
}
