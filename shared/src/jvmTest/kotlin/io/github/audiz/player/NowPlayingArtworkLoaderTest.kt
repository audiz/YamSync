package io.github.audiz.player

import kotlinx.coroutines.*
import kotlin.test.*

class NowPlayingArtworkLoaderTest {
    private fun fixture(block: (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { block(scope) } finally { scope.cancel() }
    }

    @Test
    fun metadataRefreshDoesNotCancelPendingCoverOrReloadCompletedCover() = fixture { scope ->
        val pending = CompletableDeferred<String>()
        var calls = 0
        val published = mutableListOf<String>()
        val loader = NowPlayingArtworkLoader(scope, load = { calls++; pending.await() }, publish = { published.add(it) })
        assertNull(loader.update("track", listOf("cover")))
        repeat(10) { assertNull(loader.update("track", listOf("cover"))) }
        assertEquals(1, calls)
        pending.complete("image")
        repeat(10) { assertEquals("image", loader.update("track", listOf("cover"))) }
        assertEquals(1, calls)
        assertEquals(listOf("image"), published)
    }

    @Test
    fun albumCoverIsCachedAcrossTracksAndSurvivesMissingUri() = fixture { scope ->
        var calls = 0
        val loader = NowPlayingArtworkLoader(scope, load = { calls++; "image:$it" }, publish = {})
        loader.update("first", listOf("album"))
        assertEquals("image:album", loader.update("first", emptyList()))
        assertEquals("image:album", loader.update("second", listOf("album")))
        assertEquals(1, calls)
        assertNull(loader.update("third", emptyList()))
    }

    @Test
    fun temporaryFailuresRetryAndDoNotCacheFailure() = fixture { scope ->
        var calls = 0
        val published = mutableListOf<String>()
        val loader = NowPlayingArtworkLoader(scope, load = { if (++calls < 3) null else "image" },
            publish = { published.add(it) }, retryDelayMs = 0)
        loader.update("track", listOf("cover"))
        assertEquals(3, calls)
        assertEquals(listOf("image"), published)
    }

    @Test
    fun failedNetworkCoverFallsBackToEmbeddedLocalArtwork() = fixture { scope ->
        val calls = mutableListOf<String>()
        val loader = NowPlayingArtworkLoader(scope, load = { source ->
            calls.add(source)
            if (source.startsWith("local:")) "embedded" else null
        }, publish = {})
        assertEquals("embedded", loader.update("track", listOf("https://cover", "local:/music.mp3")))
        assertEquals(listOf("https://cover", "local:/music.mp3"), calls)
    }

    @Test
    fun latePreviousCoverCannotReplaceNewTrackOrPublishAfterClear() = fixture { scope ->
        val pending = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        val loader = NowPlayingArtworkLoader(scope, load = { source ->
            if (source == "old") withContext(NonCancellable) { pending.await() } else "new image"
        }, publish = { published.add(it) })
        loader.update("first", listOf("old"))
        loader.update("second", listOf("new"))
        pending.complete("old image")
        assertEquals(listOf("new image"), published)
        loader.clear()
        assertNull(loader.update("third", emptyList()))
    }

    @Test
    fun clearPreventsLateArtworkFromRepublishingStoppedPlayer() = fixture { scope ->
        val pending = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        val loader = NowPlayingArtworkLoader(scope,
            load = { withContext(NonCancellable) { pending.await() } }, publish = { published.add(it) })
        loader.update("track", listOf("cover"))
        loader.clear()
        pending.complete("late image")
        assertTrue(published.isEmpty())
    }

    @Test
    fun artworkCacheEvictsOldImages() = fixture { scope ->
        var calls = 0
        val loader = NowPlayingArtworkLoader(scope, load = { calls++; "image:$it" }, publish = {}, cacheLimit = 2)
        loader.update("a", listOf("a"))
        loader.update("b", listOf("b"))
        loader.update("c", listOf("c"))
        loader.update("a", listOf("a"))
        assertEquals(4, calls)
    }

    @Test
    fun retriesAreBoundedAndCancelledRequestsAreNotRetried() = fixture { scope ->
        var calls = 0
        val loader = NowPlayingArtworkLoader<String>(scope, load = { calls++; null }, publish = {}, retryDelayMs = 0)
        loader.update("track", listOf("missing"))
        assertEquals(3, calls)
        loader.update("track", listOf("missing"))
        assertEquals(3, calls)
        val cancelled = NowPlayingArtworkLoader<String>(scope, load = { calls++; throw CancellationException() },
            publish = {}, retryDelayMs = 0)
        cancelled.update("new", listOf("cover"))
        assertEquals(4, calls)
    }
}
