package io.github.audiz.wave

import io.github.audiz.AppConfigKeys
import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import io.github.audiz.player.UpNextState
import io.github.audiz.player.QueueVisit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import io.github.audiz.player.PlaybackQueueSource
import io.github.audiz.player.LocalTrackResolver
import io.github.audiz.player.PlaybackManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFalse
import io.github.audiz.api.MusicRepository
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.LastPlaybackSession
import io.github.audiz.models.LastPlaybackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveSessionRestoreTest {
    private fun manager() = WaveManager(
        scope = CoroutineScope(Dispatchers.Unconfined),
        repository = MusicRepository(),
        getAccessToken = { "" }
    )

    private val session = LastPlaybackSession(
        type = LastPlaybackType.WAVE,
        lastTrackId = "saved",
        lastTrackTitle = "Last song",
        lastArtistName = "Last artist",
        lastCoverUri = "cover",
        lastDurationMs = 180_000L
    )

    @Test
    fun restoresSavedMetadataEvenWithoutNetwork() {
        val wave = manager()
        wave.restoreSession(session)
        val track = wave.currentWaveTrack!!
        assertEquals("saved", track.id)
        assertEquals("Last song", track.title)
        assertEquals("Last artist", track.artists.single().name)
        assertEquals("cover", track.coverUri)
        assertEquals(180_000L, track.durationMs)
        assertTrue(wave.isWaveMode)
        assertEquals(0, wave.waveCurrentIndex)
    }

    @Test
    fun keepsSavedTrackBeforeNewRecommendations() {
        val wave = manager()
        wave.restoreSession(session)
        val saved = wave.currentWaveTrack!!
        wave.installInitialTracks(listOf(FullTrackInfo(id = "first"), FullTrackInfo(id = "second")), saved)
        assertEquals(listOf("saved", "first", "second"), wave.waveTracks.map { it.id })
        assertEquals("saved", wave.currentWaveTrack?.id)
        assertEquals("first", wave.getNextWaveTrackCandidate()?.id)
    }

    @Test
    fun recommendationContainingSavedTrackDoesNotDuplicateIt() {
        val wave = manager()
        wave.restoreSession(session)
        val saved = wave.currentWaveTrack!!
        val refreshed = saved.copy(title = "Fresh metadata")
        wave.installInitialTracks(listOf(FullTrackInfo(id = "first"), refreshed, FullTrackInfo(id = "second")), saved)
        assertEquals(listOf("saved", "first", "second"), wave.waveTracks.map { it.id })
        assertEquals("Fresh metadata", wave.currentWaveTrack?.title)
    }

    @Test
    fun restoresThematicWaveWithoutLosingLastTrack() {
        val wave = manager()
        wave.restoreSession(session.copy(waveTitle = "Метал", waveSeeds = listOf("genre:metal")))
        assertEquals("saved", wave.currentWaveTrack?.id)
        assertEquals("Метал", wave.currentWaveTitle)
        assertEquals(listOf("genre:metal"), wave.currentWaveSeeds)
    }

    @Test
    fun restoresPlayerWithoutAutoplayAndNextKeepsRecommendationOrder() {
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val repository = MusicRepository()
        val wave = WaveManager(scope, repository, getAccessToken = { "" })
        val player = PlaybackManager(scope, repository, localTrackResolver = LocalTrackResolver { "" },
            queueSource = object : PlaybackQueueSource {
                override fun getLoadedTracks(): List<FullTrackInfo> = emptyList()
                override fun getSearchTracks(): List<FullTrackInfo> = emptyList()
                override suspend fun loadMoreTracks(): Boolean = false
            }, getSelectedQuality = { "1" }, isRecordToDisk = { false }, waveBridge = wave)
        wave.attachPlayback(player)
        try {
            wave.restoreSession(session)
            assertEquals("saved", player.trackId)
            assertFalse(player.isPlaying)
            assertFalse(player.isPaused)
            wave.installInitialTracks(listOf(FullTrackInfo(id = "first"), FullTrackInfo(id = "second")), wave.currentWaveTrack)
            player.togglePlayPause()
            assertEquals("saved", wave.currentWaveTrack?.id)
            player.playNextTrack()
            assertEquals("first", wave.currentWaveTrack?.id)
            player.playPrevTrack()
            assertEquals("saved", wave.currentWaveTrack?.id)
        } finally {
            player.release()
            scope.cancel()
        }
    }

    private fun withRestoredQueue(saved: UpNextState, check: (WaveManager, PlaybackManager) -> Unit) = runBlocking {
        val previousQueue = loadAppConfig(AppConfigKeys.PLAYER_UP_NEXT)
        val scope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        })
        val repository = MusicRepository()
        val wave = WaveManager(scope, repository, getAccessToken = { "" })
        val player = PlaybackManager(scope, repository, localTrackResolver = LocalTrackResolver { "" },
            queueSource = object : PlaybackQueueSource {
                override fun getLoadedTracks(): List<FullTrackInfo> = emptyList()
                override fun getSearchTracks(): List<FullTrackInfo> = emptyList()
                override suspend fun loadMoreTracks(): Boolean = false
            }, getSelectedQuality = { "1" }, isRecordToDisk = { false }, waveBridge = wave)
        wave.attachPlayback(player)
        try {
            saveAppConfig(AppConfigKeys.PLAYER_UP_NEXT, Json.encodeToString(UpNextState.serializer(), saved))
            player.restoreUpNextQueue()
            wave.restoreSession(session)
            check(wave, player)
        } finally {
            player.release()
            scope.cancel()
            saveAppConfig(AppConfigKeys.PLAYER_UP_NEXT, previousQueue.orEmpty())
        }
    }

    @Test
    fun staleOriginalTrackInQueueCannotOverrideLastSession() {
        val stale = FullTrackInfo(id = "old", title = "Old song")
        withRestoredQueue(UpNextState(started = true, current = QueueVisit(stale))) { wave, player ->
            assertEquals("saved", player.trackId)
            assertEquals("saved", wave.currentWaveTrack?.id)
            assertEquals("saved", player.upNextQueue.state.current?.track?.id)
            assertFalse(player.upNextQueue.state.current!!.inserted)
            assertFalse(player.isPlaying)
        }
    }

    @Test
    fun activeInsertedTrackStillRestoresBeforeOriginalWave() {
        val inserted = FullTrackInfo(id = "queued", title = "Queued song")
        val next = FullTrackInfo(id = "next", title = "Next original song")
        withRestoredQueue(UpNextState(started = true, suspended = true,
            current = QueueVisit(inserted, inserted = true), resumeTrack = next, anchorId = "saved")) { wave, player ->
            assertEquals("queued", player.trackId)
            assertEquals("saved", wave.currentWaveTrack?.id)
            assertEquals(next, player.upNextQueue.state.resumeTrack)
            assertTrue(player.upNextQueue.state.current!!.inserted)
            assertFalse(player.isPlaying)
        }
    }

    @Test
    fun legacySessionUsesFirstRecommendation() {
        val wave = manager()
        wave.restoreSession(LastPlaybackSession(type = LastPlaybackType.WAVE))
        wave.installInitialTracks(listOf(FullTrackInfo(id = "first"), FullTrackInfo(id = "second")))
        assertEquals("first", wave.currentWaveTrack?.id)
        assertEquals("second", wave.getNextWaveTrackCandidate()?.id)
    }
}
