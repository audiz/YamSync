package io.github.audiz.player

import io.github.audiz.models.FullTrackInfo
import kotlinx.serialization.json.Json
import kotlin.test.*

class UpNextQueueTest {
    private val a = FullTrackInfo(id = "a", title = "A")
    private val b = FullTrackInfo(id = "b", title = "B")
    private val c = FullTrackInfo(id = "c", title = "C")
    private val x = FullTrackInfo(id = "local:/x.wav", title = "X")
    private val y = FullTrackInfo(id = "y", title = "Y")
    private fun UpNextQueue.next(current: FullTrackInfo? = a, resume: FullTrackInfo? = b) =
        next(current, { resume }, { setOf(a.id) })

    @Test
    fun insertionPlaysInOrderAndResumesFrozenPlaylistCandidate() {
        val queue = UpNextQueue()
        queue.add(x)
        queue.add(y)
        assertEquals(x, queue.peek())
        assertEquals(x, queue.next())
        assertEquals(y, queue.next(x, c))
        assertEquals(b, queue.next(y, c))
        assertTrue(queue.state.pending.isEmpty())
        assertNull(queue.next(b, c))
        queue.normalTrackSelected(c)
        assertEquals(listOf(a, x, y, b), queue.state.history.map { it.track })
    }

    @Test
    fun backAndForwardReplayConsumedEntriesBeforeContinuingPlaylist() {
        val queue = UpNextQueue()
        queue.add(x)
        queue.add(y)
        queue.next()
        queue.next(x)
        queue.next(y)
        queue.normalTrackSelected(c)
        assertEquals(b, queue.previous())
        assertEquals(y, queue.previous())
        assertEquals(x, queue.previous())
        assertEquals(y, queue.next(x))
        assertEquals(b, queue.next(y))
        assertEquals(c, queue.next(b))
        assertNull(queue.next(c))
        assertTrue(queue.state.pending.isEmpty())
    }

    @Test
    fun duplicateEntriesHaveIndependentKeysAndCanBeReplayed() {
        val queue = UpNextQueue()
        queue.add(x)
        queue.add(x)
        assertEquals(2, queue.state.pending.map { it.key }.toSet().size)
        assertEquals(x, queue.next())
        assertEquals(x, queue.next(x))
        assertEquals(x, queue.previous())
        assertEquals(x, queue.next(x))
        assertEquals(b, queue.next(x))
    }

    @Test
    fun playNextReorderRemoveAndClearPreserveReturnPoint() {
        val queue = UpNextQueue()
        queue.add(x)
        queue.add(y, first = true)
        assertEquals(y, queue.next())
        queue.add(c)
        val cKey = queue.state.pending.last().key
        queue.move(cKey, -1)
        assertEquals(c, queue.peek())
        queue.remove(cKey)
        assertEquals(x, queue.peek())
        queue.clearPending()
        assertEquals(b, queue.next(y))
    }

    @Test
    fun serializedStateRestoresPendingHistoryAndOriginalCursor() {
        val queue = UpNextQueue()
        queue.add(x)
        queue.add(y)
        queue.next()
        val raw = Json.encodeToString(UpNextState.serializer(), queue.state)
        val restored = UpNextQueue()
        restored.restore(Json.decodeFromString(UpNextState.serializer(), raw))
        assertEquals(setOf(a.id), restored.state.shuffleCycle)
        assertEquals(a.id, restored.state.anchorId)
        assertEquals(x, restored.state.current?.track)
        assertEquals(a, restored.previous())
        assertEquals(x, restored.next(a, c))
        assertEquals(y, restored.next(x, c))
        assertEquals(b, restored.next(y, c))
    }

    @Test
    fun failedTrackIsSkippedWithoutRepeatingOrEnteringHistory() {
        val queue = UpNextQueue()
        queue.add(x)
        queue.add(y)
        queue.next()
        queue.discardFailedCurrent()
        assertEquals(y, queue.next(null))
        assertEquals(b, queue.next(y))
        assertEquals(listOf(a, y), queue.state.history.map { it.track })
    }

    @Test
    fun playNextTakesPriorityOverForwardHistoryWithoutLosingIt() {
        val queue = UpNextQueue()
        queue.add(x)
        queue.next()
        queue.next(x)
        assertEquals(x, queue.previous())
        queue.add(y, first = true)
        assertEquals(y, queue.peek())
        assertEquals(y, queue.next(x))
        assertEquals(b, queue.next(y))
        assertNull(queue.next(b))
    }

    @Test
    fun earlierPlaylistHistoryRemainsReachable() {
        val queue = UpNextQueue()
        queue.add(x)
        queue.next(a, { b }, { emptySet() }, past = listOf(c))
        assertEquals(a, queue.previous())
        assertEquals(c, queue.previous())
    }

    @Test
    fun returningToSameTrackAfterPaginationEndsInsertion() {
        val queue = UpNextQueue()
        queue.add(a)
        queue.next(a, null)
        assertNull(queue.next(a, null))
        queue.normalTrackSelected(a)
        assertEquals(false, queue.state.current?.inserted)
        assertEquals(listOf(a, a), queue.state.history.map { it.track })
    }

    @Test
    fun emptyQueueLeavesPlaylistNavigationUntouchedAndResetDropsSession() {
        val queue = UpNextQueue()
        assertNull(queue.next())
        queue.add(x)
        queue.next()
        queue.reset()
        assertEquals(UpNextState(), queue.state)
        assertNull(queue.next())
    }

    @Test
    fun shuffleCandidateIsComputedOnlyOnceForEntireInsertion() {
        val queue = UpNextQueue()
        var calculations = 0
        queue.add(x)
        queue.add(y)
        fun advance(current: FullTrackInfo) = queue.next(current, { calculations++; c }, { setOf(a.id) })
        assertEquals(x, advance(a))
        assertEquals(y, advance(x))
        assertEquals(c, advance(y))
        assertEquals(1, calculations)
    }
}
