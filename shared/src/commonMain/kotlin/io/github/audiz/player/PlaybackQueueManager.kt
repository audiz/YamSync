package io.github.audiz.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.models.FullTrackInfo

/**
 * 🔀 Менеджер очереди воспроизведения.
 * Инкапсулирует:
 * - Управление режимом случайного выбора (Shuffle).
 * - Историю воспроизведения (playbackHistory, forwardHistory).
 * - Расчет кандидата на следующий и предыдущий трек.
 * - Планирование предзагрузки следующего трека (plannedNextTrack).
 */
class PlaybackQueueManager {
    companion object {
        const val MAX_HISTORY_SIZE = 100
        const val RECENT_HISTORY_SIZE = 5

        fun isSameTrack(track: FullTrackInfo, targetId: String?): Boolean {
            if (targetId == null) return false
            val clean = targetId.removePrefix("local:")
            return track.id == targetId || track.realId == targetId ||
                   track.id.removePrefix("local:") == clean ||
                   track.realId?.removePrefix("local:") == clean
        }

        fun isSameTrackId(id1: String?, id2: String?): Boolean {
            if (id1 == null || id2 == null) return false
            return id1 == id2 || id1.removePrefix("local:") == id2.removePrefix("local:")
        }
    }

    var isShuffleEnabled by mutableStateOf(false)
    var plannedNextTrack by mutableStateOf<FullTrackInfo?>(null)

    val playbackHistory = mutableListOf<String>()
    val forwardHistory = mutableListOf<String>()

    fun toggleShuffle(): Boolean {
        isShuffleEnabled = !isShuffleEnabled
        return isShuffleEnabled
    }

    fun setShuffle(enabled: Boolean) {
        isShuffleEnabled = enabled
    }

    fun recordPlayed(trackId: String) {
        playbackHistory.add(trackId)
        if (playbackHistory.size > MAX_HISTORY_SIZE) {
            playbackHistory.removeAt(0)
        }
    }

    fun recordForward(trackId: String) {
        forwardHistory.add(trackId)
        if (forwardHistory.size > MAX_HISTORY_SIZE) {
            forwardHistory.removeAt(0)
        }
    }

    fun clearForwardHistory() {
        forwardHistory.clear()
    }

    fun clearAll() {
        playbackHistory.clear()
        forwardHistory.clear()
        plannedNextTrack = null
    }

    /**
     * Извлечь следующий трек из истории перемотки вперед (если пользователь нажимал ⏮, а затем ⏭).
     */
    fun popNextFromForwardHistory(
        activeId: String?,
        loadedTracks: List<FullTrackInfo>,
        searchTracks: List<FullTrackInfo>
    ): FullTrackInfo? {
        while (forwardHistory.isNotEmpty()) {
            val nextId = forwardHistory.removeLast()
            if (!isSameTrackId(nextId, activeId)) {
                if (activeId != null) {
                    recordPlayed(activeId)
                }
                val foundLoaded = loadedTracks.firstOrNull { isSameTrack(it, nextId) }
                if (foundLoaded != null) return foundLoaded

                val foundSearch = searchTracks.firstOrNull { isSameTrack(it, nextId) }
                if (foundSearch != null) return foundSearch
            }
        }
        return null
    }

    /**
     * Извлечь предыдущий трек из истории воспроизведения (при нажатии ⏮).
     */
    fun popPrevFromPlaybackHistory(
        activeId: String?,
        loadedTracks: List<FullTrackInfo>,
        searchTracks: List<FullTrackInfo>
    ): FullTrackInfo? {
        while (playbackHistory.isNotEmpty()) {
            val prevId = playbackHistory.removeLast()
            if (!isSameTrackId(prevId, activeId)) {
                if (activeId != null) {
                    recordForward(activeId)
                }
                val foundLoaded = loadedTracks.firstOrNull { isSameTrack(it, prevId) }
                if (foundLoaded != null) return foundLoaded

                val foundSearch = searchTracks.firstOrNull { isSameTrack(it, prevId) }
                if (foundSearch != null) return foundSearch
            }
        }
        return null
    }

    /**
     * Получить список идентификаторов последних воспроизведенных треков (до [limit] штук).
     * Включает текущий активный трек [activeId] (если задан) и предшествующие треки из [playbackHistory].
     * Возвращает уникальные идентификаторы треков в порядке от самых недавних к более ранним.
     */
    fun getRecentTrackIds(activeId: String?, limit: Int = RECENT_HISTORY_SIZE): List<String> {
        val recent = mutableListOf<String>()

        fun addIfNew(id: String?) {
            if (id.isNullOrBlank()) return
            if (recent.none { isSameTrackId(it, id) }) {
                recent.add(id)
            }
        }

        addIfNew(activeId)

        for (i in playbackHistory.indices.reversed()) {
            if (recent.size >= limit) break
            addIfNew(playbackHistory[i])
        }

        return recent
    }

    /**
     * Вычисление списка кандидатов для режима перемешивания (Shuffle).
     * Если общее число треков больше [RECENT_HISTORY_SIZE] (5):
     * исключаются последние [RECENT_HISTORY_SIZE] недавно игравших треков.
     * Если треков <= 5 или фильтр исключил все треки:
     * исключается только активный трек [activeId], чтобы избежать повторения одного и того же трека подряд.
     */
    fun getShuffleCandidates(
        activeId: String?,
        tracks: List<FullTrackInfo>
    ): List<FullTrackInfo> {
        if (tracks.isEmpty()) return emptyList()
        if (tracks.size <= 1) return tracks

        if (tracks.size > RECENT_HISTORY_SIZE) {
            val recentIds = getRecentTrackIds(activeId, RECENT_HISTORY_SIZE)
            val filtered = tracks.filter { track -> recentIds.none { isSameTrack(track, it) } }
            if (filtered.isNotEmpty()) {
                return filtered
            }
        }

        val fallback = if (activeId != null) tracks.filter { !isSameTrack(it, activeId) } else tracks
        return if (fallback.isNotEmpty()) fallback else tracks
    }

    /**
     * Вычисление кандидата на следующий трек в очереди.
     */
    fun calculateNextCandidate(
        activeId: String?,
        isWaveMode: Boolean,
        nextWaveTrack: FullTrackInfo?,
        loadedTracks: List<FullTrackInfo>,
        searchTracks: List<FullTrackInfo>
    ): FullTrackInfo? {
        if (isWaveMode) {
            return nextWaveTrack
        }

        // 1. Проверяем историю вперед
        if (forwardHistory.isNotEmpty()) {
            val candidateId = forwardHistory.lastOrNull { !isSameTrackId(it, activeId) }
            if (candidateId != null) {
                val foundLoaded = loadedTracks.firstOrNull { isSameTrack(it, candidateId) }
                if (foundLoaded != null) return foundLoaded
                val searchTracks = searchTracks
                val foundSearch = searchTracks.firstOrNull { isSameTrack(it, candidateId) }
                if (foundSearch != null) return foundSearch
            }
        }

        // 2. Текущий плейлист (loadedTracks)
        if (loadedTracks.isNotEmpty()) {
            if (isShuffleEnabled) {
                val candidates = getShuffleCandidates(activeId, loadedTracks)
                val existing = plannedNextTrack
                if (existing != null && candidates.any { isSameTrack(it, existing.id) }) {
                    return existing
                }
                return candidates.randomOrNull() ?: loadedTracks.firstOrNull { !isSameTrack(it, activeId) }
            } else {
                val currentIndex = if (activeId != null) loadedTracks.indexOfFirst { isSameTrack(it, activeId) } else -1
                if (currentIndex != -1) {
                    for (i in (currentIndex + 1) until loadedTracks.size) {
                        if (!isSameTrack(loadedTracks[i], activeId)) return loadedTracks[i]
                    }
                }
                val candidate = loadedTracks.firstOrNull { !isSameTrack(it, activeId) }
                if (candidate != null) return candidate
            }
        }

        // 3. Результаты поиска
        if (searchTracks.isNotEmpty()) {
            if (isShuffleEnabled) {
                val candidates = getShuffleCandidates(activeId, searchTracks)
                val existing = plannedNextTrack
                if (existing != null && candidates.any { isSameTrack(it, existing.id) }) {
                    return existing
                }
                return candidates.randomOrNull() ?: searchTracks.firstOrNull { !isSameTrack(it, activeId) }
            } else {
                val currentIndex = if (activeId != null) searchTracks.indexOfFirst { isSameTrack(it, activeId) } else -1
                if (currentIndex != -1) {
                    for (i in (currentIndex + 1) until searchTracks.size) {
                        if (!isSameTrack(searchTracks[i], activeId)) return searchTracks[i]
                    }
                }
                val candidate = searchTracks.firstOrNull { !isSameTrack(it, activeId) }
                if (candidate != null) return candidate
            }
        }

        return null
    }

    /**
     * Выбрать следующий трек из списка треков (с учетом шаффла или последовательного порядка).
     */
    fun getNextFromList(
        activeId: String?,
        tracks: List<FullTrackInfo>
    ): FullTrackInfo? {
        if (tracks.isEmpty()) return null
        if (isShuffleEnabled) {
            val candidates = getShuffleCandidates(activeId, tracks)
            return (plannedNextTrack?.takeIf { pt -> candidates.any { isSameTrack(it, pt.id) } })
                ?: candidates.randomOrNull()
                ?: tracks.first()
        } else {
            val currentIndex = if (activeId != null) tracks.indexOfFirst { isSameTrack(it, activeId) } else -1
            return if (currentIndex != -1 && currentIndex < tracks.size - 1) {
                tracks[currentIndex + 1]
            } else {
                tracks.first()
            }
        }
    }

    /**
     * Выбрать предыдущий трек из списка треков (последовательный порядок или возврат к последнему).
     */
    fun getPrevFromList(
        activeId: String?,
        tracks: List<FullTrackInfo>
    ): FullTrackInfo? {
        if (tracks.isEmpty()) return null
        val currentIndex = if (activeId != null) tracks.indexOfFirst { isSameTrack(it, activeId) } else -1
        return if (currentIndex > 0) {
            tracks[currentIndex - 1]
        } else {
            tracks.last()
        }
    }
}
