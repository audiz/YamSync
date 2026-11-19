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
import io.github.audiz.DispatcherIO
import io.github.audiz.models.FullTrackInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 💾 Менеджер сохранения и восстановления последней сессии воспроизведения.
 * Отвечает за:
 * - Запоминание того, что играло последним («Моя Волна» или конкретный плейлист/папка).
 * - Обновление текущего играющего трека.
 * - Загрузку последней сессии при старте приложения.
 */
class PlaybackSessionManager(
    scope: CoroutineScope? = null,
    private val readSession: () -> String? = { loadAppConfig(AppConfigKeys.LAST_PLAYBACK_SESSION) },
    private val writeSession: (String) -> Unit = { saveAppConfig(AppConfigKeys.LAST_PLAYBACK_SESSION, it) }
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    var currentSession by mutableStateOf<LastPlaybackSession?>(null)
        private set

    private val pendingWrites = scope?.let { writerScope ->
        Channel<LastPlaybackSession>(Channel.CONFLATED).also { channel ->
            writerScope.launch(DispatcherIO) {
                for (session in channel) writeNow(session)
            }
        }
    }

    init {
        currentSession = loadSession()
    }

    /**
     * 📥 Загрузить сохраненную сессию из настроек
     */
    fun loadSession(): LastPlaybackSession? {
        return try {
            val raw = readSession()
            if (!raw.isNullOrBlank()) {
                json.decodeFromString(LastPlaybackSession.serializer(), raw)
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
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
        durationMs: Long = 0L,
        trackInfo: FullTrackInfo? = null
    ) {
        val cur = currentSession ?: return
        val sameTrack = cur.lastTrackId == trackId
        val info = trackInfo?.takeIf { it.id == trackId }
        val knownDuration = durationMs.takeIf { it > 0L } ?: info?.durationMs?.takeIf { it > 0L }
            ?: cur.lastDurationMs.takeIf { sameTrack } ?: 0L
        val updated = cur.copy(
            lastTrackId = trackId,
            lastTrackTitle = trackTitle,
            lastArtistName = artistName,
            lastCoverUri = coverUri ?: info?.coverUri ?: cur.lastCoverUri.takeIf { sameTrack },
            lastDurationMs = knownDuration,
            lastTrackInfo = (info ?: cur.lastTrackInfo.takeIf { sameTrack })
                ?.copy(durationMs = knownDuration),
            timestamp = currentTimeMillis()
        )
        currentSession = updated
        persist(updated)
    }

    fun updateTrackMetadata(track: FullTrackInfo) {
        val current = currentSession ?: return
        if (current.lastTrackId != track.id) return
        updateLastTrack(track.id, track.title, track.artists.joinToString { it.name },
            track.coverUri, track.durationMs, track)
    }

    private fun persist(session: LastPlaybackSession) {
        if (pendingWrites != null) pendingWrites.trySend(session)
        else writeNow(session)
    }

    private fun writeNow(session: LastPlaybackSession) {
        try {
            val str = json.encodeToString(LastPlaybackSession.serializer(), session)
            writeSession(str)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("PlaybackSessionManager: ❌ Ошибка сохранения сессии: ${e.message}")
        }
    }
}
