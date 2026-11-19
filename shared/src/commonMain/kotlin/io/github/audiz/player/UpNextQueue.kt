package io.github.audiz.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.models.FullTrackInfo
import kotlinx.serialization.Serializable

@Serializable
data class UpNextEntry(val key: Long, val track: FullTrackInfo, val playNext: Boolean = false)

@Serializable
data class QueueVisit(val track: FullTrackInfo, val inserted: Boolean = false)

@Serializable
data class UpNextState(
    val pending: List<UpNextEntry> = emptyList(),
    val history: List<QueueVisit> = emptyList(),
    val forward: List<QueueVisit> = emptyList(),
    val current: QueueVisit? = null,
    val started: Boolean = false,
    val suspended: Boolean = false,
    val resumeTrack: FullTrackInfo? = null,
    val resumePrepared: Boolean = false,
    val anchorId: String? = null,
    val shuffleCycle: Set<String> = emptySet(),
    val nextKey: Long = 1
)

/** Временные вставки между треками исходного списка и история их посещений. */
class UpNextQueue {
    var state by mutableStateOf(UpNextState())
        private set

    fun restore(saved: UpNextState) { state = saved }

    fun add(track: FullTrackInfo, first: Boolean = false) {
        val entry = UpNextEntry(state.nextKey, track, playNext = first)
        state = state.copy(
            pending = if (first) listOf(entry) + state.pending else state.pending + entry,
            nextKey = state.nextKey + 1
        )
    }

    fun remove(key: Long) {
        state = state.copy(pending = state.pending.filterNot { it.key == key })
        if (state.pending.isEmpty()) clearPending()
    }
    fun clearPending() {
        state = if (state.suspended) state.copy(pending = emptyList())
        else state.copy(pending = emptyList(), resumePrepared = false, resumeTrack = null, anchorId = null)
    }

    fun prepareResume(track: FullTrackInfo?, anchorId: String?, shuffleCycle: Set<String>) {
        state = state.copy(resumeTrack = track, anchorId = anchorId, resumePrepared = true, shuffleCycle = shuffleCycle.toSet())
    }
    fun reset() { state = UpNextState() }

    fun move(key: Long, offset: Int) {
        val items = state.pending.toMutableList()
        val index = items.indexOfFirst { it.key == key }
        if (index < 0) return
        val target = (index + offset).coerceIn(0, items.lastIndex)
        items.add(target, items.removeAt(index))
        state = state.copy(pending = items)
    }

    fun peek(): FullTrackInfo? = state.pending.firstOrNull()?.takeIf { it.playNext }?.track
        ?: state.forward.lastOrNull()?.track
        ?: state.pending.firstOrNull()?.track
        ?: state.resumeTrack.takeIf { state.suspended }

    fun captureShuffleCycle(ids: Set<String>) {
        if (state.started) state = state.copy(shuffleCycle = ids.toSet())
    }

    /** Кандидат исходного списка вычисляется только перед первой вставкой. */
    fun next(
        current: FullTrackInfo?,
        resume: () -> FullTrackInfo?,
        shuffleCycle: () -> Set<String>,
        past: List<FullTrackInfo> = emptyList()
    ): FullTrackInfo? {
        if (!state.started && state.pending.isEmpty()) return null
        if (!state.started) state = state.copy(
            current = current?.let { QueueVisit(it) }, started = true,
            history = past.takeLast(PlaybackQueueManager.MAX_HISTORY_SIZE).map { QueueVisit(it) }
        )
        val future = state.forward.lastOrNull()
        if (future != null && state.pending.firstOrNull()?.playNext != true) {
            visit(future, state.copy(forward = state.forward.dropLast(1)))
            return future.track
        }
        val entry = state.pending.firstOrNull()
        if (entry != null) {
            if (!state.suspended && state.forward.isEmpty()) {
                val prepared = state.resumePrepared && state.anchorId == state.current?.track?.id
                val candidate = if (prepared) state.resumeTrack else resume()
                state = state.copy(
                    suspended = true, resumeTrack = candidate, anchorId = state.current?.track?.id,
                    shuffleCycle = if (prepared) state.shuffleCycle else shuffleCycle().toSet(),
                    resumePrepared = false
                )
            }
            visit(QueueVisit(entry.track, inserted = true), state.copy(pending = state.pending.drop(1)))
            return entry.track
        }
        if (state.suspended) {
            val candidate = state.resumeTrack
            state = state.copy(suspended = false, resumeTrack = null)
            if (candidate != null) {
                visit(QueueVisit(candidate))
                return candidate
            }
        }
        return null
    }

    fun previous(): FullTrackInfo? {
        val previous = state.history.lastOrNull() ?: return null
        state = state.copy(
            history = state.history.dropLast(1),
            forward = (state.forward + listOfNotNull(state.current)).takeLast(PlaybackQueueManager.MAX_HISTORY_SIZE),
            current = previous
        )
        return previous.track
    }

    /** Сессия воспроизведения определяет текущий трек исходного списка после перезапуска. */
    fun restoreOriginalTrack(track: FullTrackInfo) {
        if (state.started && state.current?.inserted != true) {
            state = state.copy(current = QueueVisit(track))
        }
    }

    fun normalTrackSelected(track: FullTrackInfo) {
        if (state.started && (state.current?.track?.id != track.id || state.current?.inserted == true)) {
            visit(QueueVisit(track))
        }
    }

    fun discardFailedCurrent() { state = state.copy(current = null) }

    fun findTrack(id: String): FullTrackInfo? {
        fun FullTrackInfo.matches() = PlaybackQueueManager.isSameTrack(this, id)
        return state.current?.track?.takeIf { it.matches() }
            ?: state.resumeTrack?.takeIf { it.matches() }
            ?: state.pending.firstOrNull { it.track.matches() }?.track
            ?: state.history.lastOrNull { it.track.matches() }?.track
            ?: state.forward.lastOrNull { it.track.matches() }?.track
    }

    private fun visit(next: QueueVisit, base: UpNextState = state) {
        state = base.copy(
            history = (base.history + listOfNotNull(base.current)).takeLast(PlaybackQueueManager.MAX_HISTORY_SIZE),
            current = next
        )
    }
}
