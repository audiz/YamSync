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
            if (nextId != activeId) {
                if (activeId != null) {
                    recordPlayed(activeId)
                }
                val foundLoaded = loadedTracks.firstOrNull { it.id == nextId }
                if (foundLoaded != null) return foundLoaded

                val foundSearch = searchTracks.firstOrNull { it.id == nextId }
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
            if (prevId != activeId) {
                if (activeId != null) {
                    recordForward(activeId)
                }
                val foundLoaded = loadedTracks.firstOrNull { it.id == prevId }
                if (foundLoaded != null) return foundLoaded

                val foundSearch = searchTracks.firstOrNull { it.id == prevId }
                if (foundSearch != null) return foundSearch
            }
        }
        return null
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
            val candidateId = forwardHistory.lastOrNull { it != activeId }
            if (candidateId != null) {
                val foundLoaded = loadedTracks.firstOrNull { it.id == candidateId }
                if (foundLoaded != null) return foundLoaded
                val searchTracks = searchTracks
                val foundSearch = searchTracks.firstOrNull { it.id == candidateId }
                if (foundSearch != null) return foundSearch
            }
        }

        // 2. Текущий плейлист (loadedTracks)
        if (loadedTracks.isNotEmpty()) {
            if (isShuffleEnabled) {
                val candidates = if (loadedTracks.size > 1 && activeId != null) loadedTracks.filter { it.id != activeId } else loadedTracks
                val existing = plannedNextTrack
                if (existing != null && candidates.any { it.id == existing.id }) {
                    return existing
                }
                return candidates.randomOrNull() ?: loadedTracks.firstOrNull { it.id != activeId }
            } else {
                val currentIndex = if (activeId != null) loadedTracks.indexOfFirst { it.id == activeId } else -1
                if (currentIndex != -1) {
                    for (i in (currentIndex + 1) until loadedTracks.size) {
                        if (loadedTracks[i].id != activeId) return loadedTracks[i]
                    }
                }
                val candidate = loadedTracks.firstOrNull { it.id != activeId }
                if (candidate != null) return candidate
            }
        }

        // 3. Результаты поиска
        if (searchTracks.isNotEmpty()) {
            if (isShuffleEnabled) {
                val candidates = if (searchTracks.size > 1 && activeId != null) searchTracks.filter { it.id != activeId } else searchTracks
                val existing = plannedNextTrack
                if (existing != null && candidates.any { it.id == existing.id }) {
                    return existing
                }
                return candidates.randomOrNull() ?: searchTracks.firstOrNull { it.id != activeId }
            } else {
                val currentIndex = if (activeId != null) searchTracks.indexOfFirst { it.id == activeId } else -1
                if (currentIndex != -1) {
                    for (i in (currentIndex + 1) until searchTracks.size) {
                        if (searchTracks[i].id != activeId) return searchTracks[i]
                    }
                }
                val candidate = searchTracks.firstOrNull { it.id != activeId }
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
            val candidates = if (tracks.size > 1 && activeId != null) tracks.filter { it.id != activeId } else tracks
            return (plannedNextTrack?.takeIf { pt -> candidates.any { it.id == pt.id } })
                ?: candidates.randomOrNull()
                ?: tracks.first()
        } else {
            val currentIndex = if (activeId != null) tracks.indexOfFirst { it.id == activeId } else -1
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
        val currentIndex = if (activeId != null) tracks.indexOfFirst { it.id == activeId } else -1
        return if (currentIndex > 0) {
            tracks[currentIndex - 1]
        } else {
            tracks.last()
        }
    }
}
