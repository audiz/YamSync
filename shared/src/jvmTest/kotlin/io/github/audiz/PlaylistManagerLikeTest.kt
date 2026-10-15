package io.github.audiz

import io.github.audiz.api.MusicRepository
import io.github.audiz.download.DownloadManager
import io.github.audiz.playlist.PlaylistManager
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.isLocal
import io.github.audiz.models.isLocalTrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistManagerLikeTest {

    private fun createPlaylistManager(): PlaylistManager {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val downloadManager = DownloadManager(
            scope = scope,
            repository = MusicRepository(),
            getMusicStoragePath = { "" },
            getSelectedQuality = { "1" },
            getPlayingTrackId = { null },
            getPlayingTrackTitle = { "" },
            getPlayingArtistName = { "" },
            getPlayingFilePath = { null }
        )
        return PlaylistManager(
            scope = scope,
            repository = MusicRepository(),
            getMusicStoragePath = { "" },
            getAccessToken = { "" },
            downloadManager = downloadManager,
            onError = {}
        )
    }

    @Test
    fun testIsTrackLikedWithVariousFormats() {
        val manager = createPlaylistManager()
        val garbageTrackId = "4485064"
        val garbageWithAlbum = "4485064:6177488"
        val garbageLocal = "local:4485064"

        // Initially not liked
        assertFalse(manager.isTrackLiked(garbageTrackId))
        assertFalse(manager.isTrackLiked(garbageWithAlbum))
        assertFalse(manager.isTrackLiked(garbageLocal))
        assertFalse(manager.isTrackLiked(null))
        assertFalse(manager.isTrackLiked(""))

        // Case 1: likedTrackIds has clean ID
        manager.likedTrackIds.add(garbageTrackId)
        assertTrue(manager.isTrackLiked(garbageTrackId), "Clean ID should be recognized as liked")
        assertTrue(manager.isTrackLiked(garbageWithAlbum), "Composite ID from Yandex playlist should be recognized as liked")
        assertTrue(manager.isTrackLiked(garbageLocal), "Local ID should be recognized as liked")

        // Case 2: likedTrackIds has composite ID (e.g. legacy cache)
        manager.likedTrackIds.clear()
        manager.likedTrackIds.add(garbageWithAlbum)
        assertTrue(manager.isTrackLiked(garbageTrackId), "Clean ID should match composite entry")
        assertTrue(manager.isTrackLiked(garbageWithAlbum), "Composite ID should match composite entry")
    }

    @Test
    fun testIsTrackDislikedWithVariousFormats() {
        val manager = createPlaylistManager()
        val trackId = "999111"
        val trackWithAlbum = "999111:12345"

        assertFalse(manager.isTrackDisliked(trackId))
        manager.dislikedTrackIds.add(trackId)
        assertTrue(manager.isTrackDisliked(trackId))
        assertTrue(manager.isTrackDisliked(trackWithAlbum))
    }

    @Test
    fun testToggleLikeAddsAndRemovesCleanly() {
        val manager = createPlaylistManager()
        val trackIdFromPlaylist = "4485064:6177488"
        val albumId = 6177488L

        // 1. Like track from playlist
        manager.toggleLike(trackIdFromPlaylist, albumId)
        assertTrue(manager.isTrackLiked("4485064"), "Should be liked by clean ID")
        assertTrue(manager.isTrackLiked(trackIdFromPlaylist), "Should be liked by playlist ID")
        assertTrue(manager.likedTrackIds.contains("4485064"), "Clean ID must be stored in likedTrackIds")

        // 2. Unlike track from playlist
        manager.toggleLike(trackIdFromPlaylist, albumId)
        assertFalse(manager.isTrackLiked("4485064"), "Should no longer be liked")
        assertFalse(manager.isTrackLiked(trackIdFromPlaylist), "Should no longer be liked")
        assertFalse(manager.likedTrackIds.contains("4485064"))
        assertFalse(manager.likedTrackIds.contains(trackIdFromPlaylist))
    }

    @Test
    fun testToggleLikeRemovesDislike() {
        val manager = createPlaylistManager()
        val trackId = "12345"

        manager.dislikedTrackIds.add(trackId)
        assertTrue(manager.isTrackDisliked(trackId))

        // Liking the track must clear dislike
        manager.toggleLike(trackId)
        assertTrue(manager.isTrackLiked(trackId))
        assertFalse(manager.isTrackDisliked(trackId))
    }

    @Test
    fun testToggleDislikeRemovesLike() {
        val manager = createPlaylistManager()
        val trackId = "12345"

        manager.likedTrackIds.add(trackId)
        assertTrue(manager.isTrackLiked(trackId))

        // Disliking the track must clear like
        manager.toggleDislike(trackId)
        assertTrue(manager.isTrackDisliked(trackId))
        assertFalse(manager.isTrackLiked(trackId))
    }

    @Test
    fun testTrackKeyParsingPreventsDoubleColons() {
        val trackId = "4485064:6177488"
        val albumId = 6177488L

        val cleanTrackId = trackId.removePrefix("local:").substringBefore(":")
        val parsedAlbumId = trackId.removePrefix("local:").substringAfter(":", "").toLongOrNull()
        val finalAlbumId = albumId.takeIf { it > 0 } ?: parsedAlbumId
        val trackKey = if (finalAlbumId != null && finalAlbumId > 0) "$cleanTrackId:$finalAlbumId" else cleanTrackId

        assertEquals("4485064", cleanTrackId)
        assertEquals("4485064:6177488", trackKey)
        assertFalse(trackKey.contains("::"), "trackKey must not have double colons")
        assertEquals(1, trackKey.count { it == ':' }, "trackKey must have exactly one colon")
    }

    @Test
    fun testIsLocalTrackIdClassification() {
        // Yandex track IDs should NOT be considered local:
        assertFalse(isLocalTrackId("4485064"), "Numeric Yandex ID is not local")
        assertFalse(isLocalTrackId("4485064:6177488"), "Yandex composite ID is not local")
        assertFalse(isLocalTrackId("1:2345"), "Short 1-digit ID with colon is not local")
        assertFalse(isLocalTrackId(null), "Null ID is not local")
        assertFalse(isLocalTrackId(""), "Empty ID is not local")
        assertFalse(isLocalTrackId("   "), "Blank ID is not local")

        // Local paths and prefixes MUST be considered local:
        assertTrue(isLocalTrackId("local:/storage/emulated/0/Music/song.mp3"))
        assertTrue(isLocalTrackId("local:C:\\Music\\song.mp3"))
        assertTrue(isLocalTrackId("/home/nout/Music/song.mp3"))
        assertTrue(isLocalTrackId("~/Music/song.mp3"))
        assertTrue(isLocalTrackId("file:///storage/song.mp3"))
        assertTrue(isLocalTrackId("C:\\Music\\song.mp3"))
        assertTrue(isLocalTrackId("D:/Music/song.mp3"))

        // FullTrackInfo.isLocal:
        val yandexTrack = FullTrackInfo(id = "4485064", realId = null, title = "Song")
        assertFalse(yandexTrack.isLocal)

        val localScannedTrack = FullTrackInfo(id = "local:/music/song.mp3", realId = "/music/song.mp3", title = "Song")
        assertTrue(localScannedTrack.isLocal)

        val directFileTrack = FullTrackInfo(id = "/music/song.mp3", realId = null, title = "Song")
        assertTrue(directFileTrack.isLocal)
    }

    @Test
    fun testYandexTrackPlayingFromDiskIsNotBlockedFromLiking() {
        val manager = createPlaylistManager()
        val yandexTrackId = "4485064:6177488"

        // Liking a Yandex track even if playing from a downloaded file must succeed locally
        manager.toggleLike(yandexTrackId, 6177488L)
        assertTrue(manager.isTrackLiked(yandexTrackId))
        assertTrue(manager.isTrackLiked("4485064"))

        // Unliking must succeed
        manager.toggleLike(yandexTrackId, 6177488L)
        assertFalse(manager.isTrackLiked(yandexTrackId))
        assertFalse(manager.isTrackLiked("4485064"))
    }

    @Test
    fun testDislikeTrackUnconditionallyDislikesAndSkips() {
        val manager = createPlaylistManager()
        val trackId = "4485064"

        manager.likedTrackIds.add(trackId)
        assertTrue(manager.isTrackLiked(trackId))
        assertFalse(manager.isTrackDisliked(trackId))

        var callbackCalled = false
        manager.dislikeTrack(trackId, 6177488L) {
            callbackCalled = true
        }

        assertTrue(callbackCalled, "onDisliked callback must be invoked")
        assertTrue(manager.isTrackDisliked(trackId), "Track must be disliked")
        assertFalse(manager.isTrackLiked(trackId), "Track must not be liked")

        // Calling dislike again should still trigger callback and remain disliked (not toggle back!)
        var secondCallbackCalled = false
        manager.dislikeTrack(trackId, 6177488L) {
            secondCallbackCalled = true
        }
        assertTrue(secondCallbackCalled, "onDisliked callback must be invoked again")
        assertTrue(manager.isTrackDisliked(trackId), "Track must remain disliked")
    }
}
