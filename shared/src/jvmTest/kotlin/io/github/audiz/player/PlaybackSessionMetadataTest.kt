package io.github.audiz.player

import io.github.audiz.models.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import kotlin.test.*

class PlaybackSessionMetadataTest {
    private val track = FullTrackInfo(id = "track", title = "Song", durationMs = 214_000L,
        artists = listOf(FullArtistInfo(id = 1L, name = "Artist")),
        albums = listOf(FullAlbumInfo(id = 2L, title = "Album")))

    @Test
    fun completeMetadataSurvivesRestart() {
        var saved: String? = null
        val manager = PlaybackSessionManager(readSession = { saved }, writeSession = { saved = it })
        manager.recordWave()
        manager.updateLastTrack(track.id, track.title, "Artist", trackInfo = track)
        val restarted = PlaybackSessionManager(readSession = { saved }, writeSession = {})
        assertEquals(track, restarted.currentSession?.lastTrackInfo)
        assertEquals(214_000L, restarted.currentSession?.lastDurationMs)
    }

    @Test
    fun durationDiscoveredAfterStartIsPersisted() {
        var saved: String? = null
        val manager = PlaybackSessionManager(readSession = { saved }, writeSession = { saved = it })
        manager.recordWave()
        manager.updateLastTrack(track.id, track.title, "Artist", trackInfo = track.copy(durationMs = 0))
        manager.updateTrackMetadata(track)
        assertEquals(214_000L, Json.decodeFromString(LastPlaybackSession.serializer(), saved!!).lastDurationMs)
        manager.updateTrackMetadata(track.copy(id = "previous", durationMs = 1L))
        assertEquals(214_000L, manager.currentSession?.lastDurationMs)
    }

    @Test
    fun backgroundWriterPersistsLatestEnrichedMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val written = CompletableDeferred<LastPlaybackSession>()
        val manager = PlaybackSessionManager(scope, readSession = { null }, writeSession = { raw ->
            val state = Json.decodeFromString(LastPlaybackSession.serializer(), raw)
            if (state.lastDurationMs == track.durationMs) written.complete(state)
        })
        try {
            manager.recordWave()
            manager.updateLastTrack(track.id, track.title, "Artist")
            manager.updateTrackMetadata(track)
            assertEquals(track, withTimeout(5_000L) { written.await() }.lastTrackInfo)
        } finally { scope.cancel() }
    }

    @Test
    fun newTrackDoesNotInheritPreviousDuration() {
        val manager = PlaybackSessionManager(readSession = { null }, writeSession = {})
        manager.recordWave()
        manager.updateLastTrack(track.id, track.title, "Artist", trackInfo = track)
        manager.updateLastTrack("next", "Next song", "Next artist")
        assertEquals(0L, manager.currentSession?.lastDurationMs)
        assertNull(manager.currentSession?.lastTrackInfo)
    }
}
