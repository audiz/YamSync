package io.github.audiz.api

import io.github.audiz.models.PersonalPlaylistCover
import io.github.audiz.models.PersonalPlaylistInfo
import io.github.audiz.models.PersonalPlaylistItemData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/** Объединяет витрину и доступные аккаунту дополнительные персональные подборки. */
class PersonalPlaylistLoader(private val fetch: suspend (String) -> String?) {
    suspend fun load(): List<PersonalPlaylistItemData> = coroutineScope {
        val sources = listOf(
            async { read(ApiUrls.PERSONAL_PLAYLISTS_URL, PersonalPlaylistCatalog::parseLanding) },
            async { read(ApiUrls.PERSONAL_FEED_URL, PersonalPlaylistCatalog::parseFeed) }
        ).awaitAll()
        val initial = PersonalPlaylistCatalog.merge(sources.flatten())
        val presentTypes = initial.map { it.playlistType }.toSet()
        // Всего три необязательных источника; число запросов не зависит от числа треков.
        val additional = listOf("missedLikes", "podcasts", "origin")
            .filterNot { it in presentTypes }
            .map { type -> async {
                read("${ApiUrls.GENERATED_PLAYLIST_BASE_URL}/$type") { PersonalPlaylistCatalog.parseGenerated(it, type) }
            } }.awaitAll().flatten()
        PersonalPlaylistCatalog.merge(initial + additional)
    }

    private suspend fun read(url: String, parse: (String) -> List<PersonalPlaylistItemData>): List<PersonalPlaylistItemData> =
        try {
            fetch(url)?.let(parse).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
}

/** Нормализует разные ответы API в общую модель карточки и убирает заглушки. */
object PersonalPlaylistCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val generatedTypes = setOf("playlistOfTheDay", "recentTracks", "neverHeard", "missedLikes", "podcasts", "origin")

    fun parseLanding(raw: String): List<PersonalPlaylistItemData> =
        root(raw).array("items").mapNotNull { (it as? JsonObject)?.let { item -> parseItem(item) } }

    fun parseFeed(raw: String): List<PersonalPlaylistItemData> =
        root(raw).array("generatedPlaylists").mapNotNull { (it as? JsonObject)?.let { item -> parseItem(item) } }

    fun parseGenerated(raw: String, type: String): List<PersonalPlaylistItemData> = listOfNotNull(parseItem(root(raw), type))

    fun merge(items: List<PersonalPlaylistItemData>): List<PersonalPlaylistItemData> {
        val result = mutableListOf<PersonalPlaylistItemData>()
        val byUuid = mutableMapOf<String, Int>()
        val byOwner = mutableMapOf<String, Int>()
        val byType = mutableMapOf<String, Int>()
        for (item in items) {
            val playlist = item.playlist?.takeIf { it.canOpen } ?: continue
            val ownerKey = if (playlist.uid > 0 && playlist.kind > 0) "${playlist.uid}:${playlist.kind}" else null
            val index = byUuid[playlist.playlistUuid].takeIf { playlist.playlistUuid.isNotBlank() }
                ?: ownerKey?.let { byOwner[it] }
                ?: byType[item.playlistType].takeIf { item.playlistType in generatedTypes }
            val target = index ?: result.size
            if (index == null) {
                result.add(item)
            } else {
                val existing = result[index]
                val previous = existing.playlist!!
                result[index] = existing.copy(
                    playlistType = existing.playlistType.ifBlank { item.playlistType },
                    description = existing.description?.takeIf { it.isNotBlank() } ?: item.description,
                    playlist = previous.copy(
                        uid = previous.uid.takeIf { it > 0 } ?: playlist.uid,
                        kind = previous.kind.takeIf { it > 0 } ?: playlist.kind,
                        playlistUuid = previous.playlistUuid.ifBlank { playlist.playlistUuid },
                        title = previous.title.ifBlank { playlist.title },
                        cover = previous.cover ?: playlist.cover
                    )
                )
            }
            if (playlist.playlistUuid.isNotBlank()) byUuid[playlist.playlistUuid] = target
            if (ownerKey != null) byOwner[ownerKey] = target
            if (item.playlistType in generatedTypes) byType[item.playlistType] = target
        }
        return result
    }

    private fun root(raw: String): JsonObject {
        val obj = json.parseToJsonElement(raw).jsonObject
        return obj.obj("result") ?: obj
    }

    private fun parseItem(wrapper: JsonObject, fallbackType: String = ""): PersonalPlaylistItemData? {
        if (wrapper.boolean("ready") == false) return null
        val data = wrapper.obj("data") ?: wrapper
        if (data.boolean("ready") == false) return null
        val playlist = data.obj("playlist") ?: data
        if (playlist.boolean("available") == false || playlist.number("trackCount") == 0L ||
            playlist["playlistAbsence"]?.let { it !is JsonNull } == true) return null
        val info = PersonalPlaylistInfo(
            uid = playlist.number("uid") ?: playlist.obj("owner")?.number("uid") ?: 0L,
            kind = playlist.number("kind") ?: 0L,
            playlistUuid = playlist.string("playlistUuid").orEmpty(),
            title = playlist.string("title").orEmpty(),
            cover = (playlist.obj("cover")?.string("uri") ?: playlist.string("ogImage"))
                ?.let { PersonalPlaylistCover(it) }
        )
        if (!info.canOpen) return null
        val rawType = data.string("playlistType") ?: playlist.string("generatedPlaylistType")
            ?: wrapper.string("type") ?: fallbackType
        return PersonalPlaylistItemData(
            playlist = info,
            playlistType = if (rawType == "daily") "playlistOfTheDay" else rawType,
            description = data.string("description") ?: wrapper.string("previewDescription")
                ?: playlist.string("description"),
            notify = wrapper.boolean("notify") ?: data.boolean("notify") ?: false,
            idForFrom = data.string("idForFrom")
        )
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
