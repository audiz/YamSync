package io.github.audiz.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.AppConfigKeys
import io.github.audiz.currentTimeMillis
import io.github.audiz.loadAppConfig
import io.github.audiz.models.LastPlaybackSession
import io.github.audiz.models.LastPlaybackType
import io.github.audiz.saveAppConfig
import kotlinx.serialization.json.Json

/**
 * 💾 Менеджер сохранения и восстановления последней сессии воспроизведения.
 * Отвечает за:
 * - Запоминание того, что играло последним («Моя Волна» или конкретный плейлист/папка).
 * - Обновление текущего играющего трека.
 * - Загрузку последней сессии при старте приложения.
 */
class PlaybackSessionManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    var currentSession by mutableStateOf<LastPlaybackSession?>(null)
        private set

    init {
        currentSession = loadSession()
    }

    /**
     * 📥 Загрузить сохраненную сессию из настроек
     */
    fun loadSession(): LastPlaybackSession? {
        return try {
            val raw = loadAppConfig(AppConfigKeys.LAST_PLAYBACK_SESSION)
            if (!raw.isNullOrBlank()) {
                json.decodeFromString(LastPlaybackSession.serializer(), raw)
            } else {
                null
            }
        } catch (e: Exception) {
            println("PlaybackSessionManager: ⚠️ Ошибка десериализации последней сессии: ${e.message}")
            null
        }
    }

    /**
     * 💾 Записать сессию воспроизведения
     */
    fun recordSession(session: LastPlaybackSession) {
        val updated = session.copy(timestamp = currentTimeMillis())
        currentSession = updated
        persist(updated)
    }

    /**
     * 🌊 Записать запуск Моей Волны
     */
    fun recordWave(title: String? = null, seeds: List<String> = emptyList()) {
        val session = LastPlaybackSession(
            type = LastPlaybackType.WAVE,
            waveTitle = title,
            waveSeeds = seeds,
            timestamp = currentTimeMillis()
        )
        currentSession = session
        persist(session)
    }

    /**
     * 🎵 Обновить информацию о последнем воспроизводимом треке в активной сессии
     */
    fun updateLastTrack(
        trackId: String,
        trackTitle: String,
        artistName: String,
        coverUri: String? = null,
        durationMs: Long = 0L
    ) {
        val cur = currentSession ?: return
        val updated = cur.copy(
            lastTrackId = trackId,
            lastTrackTitle = trackTitle,
            lastArtistName = artistName,
            lastCoverUri = coverUri ?: cur.lastCoverUri,
            lastDurationMs = if (durationMs > 0) durationMs else cur.lastDurationMs,
            timestamp = currentTimeMillis()
        )
        currentSession = updated
        persist(updated)
    }

    private fun persist(session: LastPlaybackSession) {
        try {
            val str = json.encodeToString(LastPlaybackSession.serializer(), session)
            saveAppConfig(AppConfigKeys.LAST_PLAYBACK_SESSION, str)
        } catch (e: Exception) {
            println("PlaybackSessionManager: ❌ Ошибка сохранения сессии: ${e.message}")
        }
    }
}
