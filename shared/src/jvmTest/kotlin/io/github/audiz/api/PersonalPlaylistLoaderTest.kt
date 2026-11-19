package io.github.audiz.api

import io.github.audiz.models.PersonalPlaylistItemData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

class PersonalPlaylistLoaderTest {
    private val landing = """{"items":[{"type":"personal-playlist","data":{"playlistType":"recentTracks","description":"Новинки","playlist":{"playlistUuid":"premiere","title":"Премьера","uid":7,"kind":1}}}]}"""
    private val generated = """{"result":{"type":"missedLikes","ready":true,"previewDescription":"Забытые любимые","data":{"owner":{"uid":7},"kind":2,"title":"Тайник","trackCount":20,"cover":{"uri":"cover/%%"}}}}"""

    @Test fun parsesLandingAndFeedShapes() {
        val main = PersonalPlaylistCatalog.parseLanding(landing).single()
        assertEquals("recentTracks", main.playlistType)
        assertEquals("Премьера", main.displayTitle)
        val feed = PersonalPlaylistCatalog.parseFeed("""{"result":{"generatedPlaylists":[{"type":"podcasts","ready":true,"data":{"owner":{"uid":7},"kind":"3","playlistUuid":"podcasts"}}]}}""").single()
        assertEquals("Подкасты недели", feed.displayTitle)
        assertEquals(7L, feed.playlist?.uid)
        assertEquals(3L, feed.playlist?.kind)
    }

    @Test fun supportsPlaylistsWithoutUuidAndPreservesMetadata() {
        val item = PersonalPlaylistCatalog.parseGenerated(generated, "missedLikes").single()
        assertEquals("Тайник", item.displayTitle)
        assertTrue(item.playlist!!.canOpen)
        assertEquals("7:2", item.playlist!!.stableKey)
        assertEquals("cover/%%", item.playlist?.cover?.uri)
        assertEquals("Забытые любимые", item.description)
    }

    @Test fun ignoresUnreadyEmptyUnavailableAndUnaddressableCards() {
        val raw = """{"generatedPlaylists":[
            {"type":"missedLikes","ready":false,"data":{"playlistUuid":"not-ready"}},
            {"type":"origin","ready":true,"data":{"playlistUuid":"unavailable","available":false}},
            {"type":"podcasts","ready":true,"data":{"playlistUuid":"empty","trackCount":0}},
            {"type":"origin","ready":true,"data":{"title":"Placeholder"}},
            {"type":"origin","ready":true,"data":null},
            null,
            {"type":"missedLikes","ready":true,"data":{"playlistUuid":"valid"}}
        ]}"""
        assertEquals(listOf("valid"), PersonalPlaylistCatalog.parseFeed(raw).map { it.playlist?.playlistUuid })
    }

    @Test fun mergesDuplicatesByUuidOwnerAndTypeAndKeepsLandingOrder() {
        val main = PersonalPlaylistCatalog.parseLanding(landing)
        val supplemental = PersonalPlaylistCatalog.parseFeed("""{"generatedPlaylists":[
            {"type":"recentTracks","ready":true,"data":{"playlistUuid":"premiere","owner":{"uid":7},"kind":1,"cover":{"uri":"new-cover"}}},
            {"type":"missedLikes","ready":true,"data":{"playlistUuid":"stash","owner":{"uid":7},"kind":2}},
            {"type":"missedLikes","ready":true,"data":{"playlistUuid":"other-stash"}},
            {"type":"","ready":true,"data":{"owner":{"uid":7},"kind":2}}
        ]}""")
        val merged = PersonalPlaylistCatalog.merge(main + supplemental)
        assertEquals(listOf("recentTracks", "missedLikes"), merged.map { it.playlistType })
        assertEquals("Премьера", merged.first().displayTitle)
        assertEquals("new-cover", merged.first().playlist?.cover?.uri)
    }

    @Test fun preservesDistinctSeasonalPlaylistsAndSupportsCachedSerialization() {
        val items = PersonalPlaylistCatalog.parseFeed("""{"generatedPlaylists":[
            {"type":"annual","data":{"playlistUuid":"2024","title":"Мой 2024"}},
            {"type":"annual","data":{"playlistUuid":"2025","title":"Мой 2025"}}
        ]}""")
        val merged = PersonalPlaylistCatalog.merge(items)
        assertEquals(2, merged.size)
        val serializer = ListSerializer(PersonalPlaylistItemData.serializer())
        assertEquals(merged, Json.decodeFromString(serializer, Json.encodeToString(serializer, merged)))
    }

    @Test fun supplementsLandingWithAvailableGeneratedPlaylists() = runBlocking {
        val requested = mutableListOf<String>()
        val loader = PersonalPlaylistLoader { url ->
            requested.add(url)
            when (url) {
                ApiUrls.PERSONAL_PLAYLISTS_URL -> landing
                ApiUrls.PERSONAL_FEED_URL -> """{"result":{"generatedPlaylists":[]}}"""
                "${ApiUrls.GENERATED_PLAYLIST_BASE_URL}/missedLikes" -> generated
                else -> null
            }
        }
        val result = loader.load()
        assertEquals(listOf("recentTracks", "missedLikes"), result.map { it.playlistType })
        assertEquals(5, requested.size)
    }

    @Test fun skipsExtraRequestsForTypesAlreadyProvidedByFeed() = runBlocking {
        val requested = mutableListOf<String>()
        val loader = PersonalPlaylistLoader { url ->
            requested.add(url)
            when (url) {
                ApiUrls.PERSONAL_PLAYLISTS_URL -> landing
                ApiUrls.PERSONAL_FEED_URL -> """{"generatedPlaylists":[
                    {"type":"missedLikes","ready":true,"data":{"playlistUuid":"stash"}},
                    {"type":"podcasts","ready":true,"data":{"playlistUuid":"podcasts"}},
                    {"type":"origin","ready":true,"data":{"playlistUuid":"alice"}}
                ]}"""
                else -> error("Unexpected request")
            }
        }
        assertEquals(4, loader.load().size)
        assertEquals(2, requested.size)
    }

    @Test fun optionalFailureDoesNotDiscardExistingPlaylists() = runBlocking {
        val loader = PersonalPlaylistLoader { url ->
            if (url == ApiUrls.PERSONAL_PLAYLISTS_URL) landing else error("Source unavailable")
        }
        assertEquals("Премьера", loader.load().single().displayTitle)
    }

    @Test fun malformedSourceDoesNotDiscardOtherSources() = runBlocking {
        val loader = PersonalPlaylistLoader { url ->
            if (url.endsWith("/missedLikes")) generated else "not json"
        }
        assertEquals("Тайник", loader.load().single().displayTitle)
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        assertFailsWith<CancellationException> {
            PersonalPlaylistLoader { throw CancellationException("cancelled") }.load()
        }
        Unit
    }
}
