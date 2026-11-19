package io.github.audiz.player

import io.github.audiz.AppConfigKeys
import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import io.github.audiz.api.MusicRepository
import io.github.audiz.models.FullTrackInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class PlaybackManagerUpNextTest {
    // Отложенный запуск аудиозадач позволяет проверить маршрутизацию без аудиоустройства и сети.
    private class DeferredDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private val a = FullTrackInfo(id = "a", title = "A")
    private val b = FullTrackInfo(id = "b", title = "B")
    private val c = FullTrackInfo(id = "c", title = "C")
    private val x = FullTrackInfo(id = "local:/queued.wav", title = "Queued", durationMs = 123456, coverUri = "cover")

    private fun fixture(tracks: List<FullTrackInfo> = listOf(a, b, c), block: (PlaybackManager) -> Unit) {
        val previousShuffle = loadAppConfig(AppConfigKeys.PLAYER_SHUFFLE)
        val scope = CoroutineScope(SupervisorJob() + DeferredDispatcher())
        val manager = PlaybackManager(scope, MusicRepository(), getMusicStoragePath = { "" },
            getSelectedQuality = { "1" }, isRecordToDisk = { false }, getLoadedTracks = { tracks })
        try {
            manager.queueManager.isShuffleEnabled = false
            manager.setInitialTrack(a)
            block(manager)
        } finally {
            manager.release()
            scope.cancel()
            saveAppConfig(AppConfigKeys.PLAYER_SHUFFLE, previousShuffle.orEmpty())
        }
    }

    @Test
    fun queueOverridesPreloadAndReturnsToOriginalNextTrack() = fixture { manager ->
        manager.addToUpNext(x)
        assertEquals(a.id, manager.trackId)
        assertEquals(x, manager.plannedNextTrack)
        manager.playNextTrack()
        assertEquals(x, manager.upNextQueue.state.current?.track)
        assertEquals(b, manager.calculateNextTrackCandidate())
        assertEquals(x, manager.findTrackInfo(x.id))
        manager.setInitialTrack(x)
        manager.playNextTrack()
        assertEquals(b, manager.upNextQueue.state.current?.track)
        assertTrue(manager.upNextQueue.state.pending.isEmpty())
    }

    @Test
    fun clearedPendingQueueStillReturnsToOriginalPlaylist() = fixture { manager ->
        manager.addToUpNext(x)
        manager.addToUpNext(c)
        manager.playNextTrack()
        manager.setInitialTrack(x)
        manager.upNextQueue.clearPending()
        manager.refreshUpNextPreload()
        assertEquals(b, manager.plannedNextTrack)
        manager.playNextTrack()
        assertEquals(b, manager.upNextQueue.state.current?.track)
    }

    @Test
    fun explicitSelectionClearsQueueAndRestartsNormalNavigation() = fixture { manager ->
        manager.addToUpNext(x)
        manager.playNextTrack()
        manager.playTrack(c.id, c.title, "")
        assertEquals(UpNextState(), manager.upNextQueue.state)
    }

    @Test
    fun shuffleDoesNotChangeInsertedOrderOrLoseOriginalCycle() = fixture { manager ->
        manager.queueManager.isShuffleEnabled = true
        manager.queueManager.recordPlayedInCycle(a.id)
        manager.queueManager.plannedNextTrack = c
        manager.addToUpNext(x)
        manager.addToUpNext(c)
        assertEquals(x, manager.calculateNextTrackCandidate())
        manager.playNextTrack()
        val resume = manager.upNextQueue.state.resumeTrack
        assertEquals(c, resume)
        assertEquals(c, manager.calculateNextTrackCandidate())
        manager.setInitialTrack(x)
        manager.playNextTrack()
        assertEquals(c, manager.upNextQueue.state.current?.track)
        assertEquals(resume, manager.calculateNextTrackCandidate())
        assertFalse(manager.queueManager.playedInCycleTrackIds.contains(x.id))
        assertTrue(manager.queueManager.playedInCycleTrackIds.contains(a.id))
        manager.queueManager.isShuffleEnabled = false
    }

    @Test
    fun endOfLoadedPageKeepsOriginalAnchorForPagination() = fixture(listOf(a)) { manager ->
        manager.addToUpNext(x)
        manager.playNextTrack()
        assertEquals(a.id, manager.upNextQueue.state.anchorId)
        assertNull(manager.upNextQueue.state.resumeTrack)
        manager.setInitialTrack(x)
        manager.playNextTrack()
        // Догрузка страницы запланирована на диспетчере, а исходный курсор сохранён.
        assertFalse(manager.upNextQueue.state.suspended)
        assertEquals(a.id, manager.upNextQueue.state.anchorId)
    }

    @Test
    fun waveTrackEndPlaysUpNextQueueBeforeResumingWave() {
        val w1 = FullTrackInfo(id = "w1", title = "W1")
        val w2 = FullTrackInfo(id = "w2", title = "W2")
        val w3 = FullTrackInfo(id = "w3", title = "W3")
        val waveTracks = listOf(w1, w2, w3)
        var waveIndex = 0
        var waveFinishedCalls = 0
        val reportedFinished = mutableListOf<String?>()
        val playedWaveIndexes = mutableListOf<Int>()
        val previousShuffle = loadAppConfig(AppConfigKeys.PLAYER_SHUFFLE)
        val scope = CoroutineScope(SupervisorJob() + DeferredDispatcher())
        val manager = PlaybackManager(scope, MusicRepository(), getMusicStoragePath = { "" },
            getSelectedQuality = { "1" }, isRecordToDisk = { false }, getLoadedTracks = { emptyList() },
            isWaveMode = { true },
            onPlayWaveByIndex = { index, _ -> playedWaveIndexes += index; waveIndex = index },
            onWaveTrackFinished = { waveFinishedCalls++ },
            getWaveTracks = { waveTracks },
            getWaveCurrentIndex = { waveIndex },
            getNextWaveTrack = { waveTracks.getOrNull(waveIndex + 1) },
            onWaveTrackFinishedReported = { reportedFinished += it })
        try {
            manager.queueManager.isShuffleEnabled = false
            manager.setInitialTrack(w1)
            manager.addToUpNext(x)
            manager.addToUpNext(c)

            // Конец трека Волны: следующим играет очередь, Волна не переключается.
            manager.advanceAfterTrackEnd(w1.id, 0L)
            assertEquals(x, manager.upNextQueue.state.current?.track)
            assertEquals(listOf<String?>(w1.id), reportedFinished)

            manager.setInitialTrack(x)
            manager.advanceAfterTrackEnd(x.id, 0L)
            assertEquals(c, manager.upNextQueue.state.current?.track)

            // Очередь исчерпана: возвращаемся ровно к следующему треку Волны.
            manager.setInitialTrack(c)
            manager.advanceAfterTrackEnd(c.id, 0L)
            assertEquals(listOf(1), playedWaveIndexes)
            assertEquals(0, waveFinishedCalls)
            // Треки очереди не учитываются как прослушивания Волны.
            assertEquals(listOf<String?>(w1.id), reportedFinished)
        } finally {
            manager.release()
            scope.cancel()
            saveAppConfig(AppConfigKeys.PLAYER_SHUFFLE, previousShuffle.orEmpty())
        }
    }
}
