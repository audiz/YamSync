package io.github.audiz.tracklist

import io.github.audiz.models.FullTrackInfo
import io.github.audiz.player.PlaybackQueueManager
import io.github.audiz.models.LastPlaybackSession
import io.github.audiz.models.LastPlaybackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrackListNavigationTest {
    private fun artistSession(id: String) = LastPlaybackSession(
        type = LastPlaybackType.ARTIST,
        artistId = id,
        title = "Треки: $id"
    )

    @Test
    fun browsingArtistKeepsPlayingQueueAndItsPagination() {
        val playlist = mutableListOf("playing", "next")
        val artistTracks = mutableListOf("artist-one", "artist-two")
        val navigation = TrackListNavigation(playlist)

        navigation.browse(artistTracks, artistSession("42"))
        playlist.add("next-page")
        artistTracks.add("artist-next-page")

        assertSame(artistTracks, navigation.displayed)
        assertSame(playlist, navigation.active)
        assertEquals(listOf("playing", "next", "next-page"), navigation.active)
        assertTrue(navigation.isPreview)
    }

    @Test
    fun closingOrChangingArtistDoesNotReplacePlayingPlaylist() {
        val playlist = listOf("playing", "next")
        val navigation = TrackListNavigation(playlist)
        navigation.browse(listOf("first-artist"), artistSession("42"))
        val secondArtist = listOf("second-artist")
        navigation.browse(secondArtist, artistSession("43"))

        assertSame(playlist, navigation.active)
        assertSame(secondArtist, navigation.closePreview())
        assertSame(playlist, navigation.displayed)
        assertFalse(navigation.isPreview)
        assertNull(navigation.selectTrack())
    }

    @Test
    fun selectingTrackActivatesArtistAndReturnsItsSessionOnlyOnce() {
        val navigation = TrackListNavigation(listOf("playing", "next"))
        navigation.browse(listOf("first-artist"), artistSession("42"))
        val selectedArtist = listOf("selected", "artist-next")
        val selectedSession = artistSession("43")
        navigation.browse(selectedArtist, selectedSession)

        assertEquals(selectedSession, navigation.selectTrack())
        assertSame(selectedArtist, navigation.active)
        assertSame(selectedArtist, navigation.displayed)
        assertFalse(navigation.isPreview)
        assertNull(navigation.selectTrack())
        assertNull(navigation.closePreview())
        assertSame(selectedArtist, navigation.active)
    }

    @Test
    fun nextTrackUsesOriginalPlaylistUntilArtistTrackIsSelected() {
        val current = FullTrackInfo(id = "current")
        val playlistNext = FullTrackInfo(id = "playlist-next")
        val artistNext = FullTrackInfo(id = "artist-next")
        val navigation = TrackListNavigation(listOf(current, playlistNext))
        val queue = PlaybackQueueManager().apply { isShuffleEnabled = false }
        fun next() = queue.calculateNextCandidate(
            activeId = current.id,
            isWaveMode = false,
            nextWaveTrack = null,
            loadedTracks = navigation.active,
            searchTracks = emptyList()
        )
        navigation.browse(listOf(current, artistNext), artistSession("42"))

        assertEquals(playlistNext.id, next()?.id)
        navigation.closePreview()
        assertEquals(playlistNext.id, next()?.id)
        navigation.browse(listOf(current, artistNext), artistSession("42"))
        navigation.selectTrack()
        assertEquals(artistNext.id, next()?.id)
    }

}
