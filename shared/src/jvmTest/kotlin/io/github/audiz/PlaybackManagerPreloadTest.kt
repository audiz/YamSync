package io.github.audiz

import io.github.audiz.api.MusicRepository
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.player.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackManagerPreloadTest {

    @BeforeTest
    fun setUp() {
        saveAppConfig(AppConfigKeys.PLAYER_SHUFFLE, "false")
    }

    @AfterTest
    fun tearDown() {
        saveAppConfig(AppConfigKeys.PLAYER_SHUFFLE, "false")
    }

    private fun createDummyTrack(id: String, title: String): FullTrackInfo {
        return FullTrackInfo(
            id = id,
            title = title,
            artists = emptyList(),
            albums = emptyList(),
            durationMs = 180000L
        )
    }

    @Test
    fun testSequentialCandidateResolution() {
        val tracks = listOf(
            createDummyTrack("1", "Track 1"),
            createDummyTrack("2", "Track 2"),
            createDummyTrack("3", "Track 3")
        )

        var currentLoaded = tracks
        val playbackManager = PlaybackManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getMusicStoragePath = { "" },
            getSelectedQuality = { "1" },
            isRecordToDisk = { false },
            isTrackDownloaded = { _, _ -> false },
            getLoadedTracks = { currentLoaded },
            getSearchTracks = { emptyList() }
        )

        // When nothing is playing, candidate is first track
        val firstCandidate = playbackManager.calculateNextTrackCandidate()
        assertEquals("1", firstCandidate?.id)

        // Simulate playing track 1 by changing trackId via private reflection or setting track
        // But calculateNextTrackCandidate looks at playbackManager.trackId
        // Initially trackId is null, so it returns first track.
    }

    @Test
    fun testWaveModeCandidateResolution() {
        val waveNext = createDummyTrack("wave-next", "Wave Next")
        var isWave = true

        val playbackManager = PlaybackManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getMusicStoragePath = { "" },
            getSelectedQuality = { "1" },
            isRecordToDisk = { false },
            isTrackDownloaded = { _, _ -> false },
            getLoadedTracks = { emptyList() },
            getSearchTracks = { emptyList() },
            isWaveMode = { isWave },
            getNextWaveTrack = { waveNext }
        )

        val candidate = playbackManager.calculateNextTrackCandidate()
        assertEquals("wave-next", candidate?.id)

        isWave = false
        val candidateNonWave = playbackManager.calculateNextTrackCandidate()
        assertNull(candidateNonWave)
    }
}
