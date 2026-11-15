package io.github.audiz.player

import io.github.audiz.models.ArtistInfo
import io.github.audiz.models.FullArtistInfo
import io.github.audiz.models.FullTrackInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class PlayerArtistResolverTest {
    @Test
    fun localTrackWithZeroArtistIdResolvesByName() = runBlocking {
        val track = FullTrackInfo(
            id = "local:/music/Lana Del Rey — West Coast.m4a",
            artists = listOf(FullArtistInfo(id = 0, name = "Lana Del Rey"))
        )
        val artist = resolvePlayerArtist(track, "Lana Del Rey", { fail("Local paths must not be sent as track IDs") }) { query ->
            assertEquals("Lana Del Rey", query)
            listOf(ArtistInfo("9", "Unrelated artist"), ArtistInfo("111", "Lana Del Rey"))
        }
        assertEquals(111L, artist?.id)
        assertEquals("Lana Del Rey", artist?.name)
    }

    @Test
    fun knownArtistDoesNotRequireNetworkLookup() = runBlocking {
        val expected = FullArtistInfo(id = 111, name = "Artist")
        val artist = resolvePlayerArtist(
            FullTrackInfo(id = "1", artists = listOf(expected)), "Artist",
            { fail("Metadata already contains artist ID") }, { fail("Metadata already contains artist ID") }
        )
        assertEquals(expected, artist)
    }

    @Test
    fun remoteTrackCanRecoverArtistFromTrackDetails() = runBlocking {
        val expected = FullArtistInfo(id = 222, name = "Artist")
        val artist = resolvePlayerArtist(FullTrackInfo(id = "123"), "Artist", { id ->
            assertEquals("123", id)
            FullTrackInfo(id = id, artists = listOf(expected))
        }, { fail("Track details should resolve the artist") })
        assertEquals(expected, artist)
    }

    @Test
    fun unrelatedSearchResultDoesNotOpenAnotherArtist() = runBlocking {
        val artist = resolvePlayerArtist(
            FullTrackInfo(id = "local:/music/song.m4a", artists = listOf(FullArtistInfo(name = "Artist"))),
            "Artist", { fail("Local track") }, { listOf(ArtistInfo("111", "Other Artist")) }
        )
        assertNull(artist)
    }

    @Test
    fun missingArtistNameUsesPlayerMetadataAndIgnoresCase() = runBlocking {
        val artist = resolvePlayerArtist(FullTrackInfo(id = "local:/music/song.m4a"), " Artist ", { fail("Local track") }) {
            assertEquals("Artist", it)
            listOf(ArtistInfo("111", "ARTIST"))
        }
        assertEquals(111L, artist?.id)
    }
}
