package io.github.audiz.backend

import java.io.File

/**
 * 🎵 Источник аудио для воспроизведения бэкендом
 */
sealed interface PlaybackSource {
    data class Url(val url: String) : PlaybackSource
    data class LocalFile(val file: File) : PlaybackSource
}

/**
 * 📢 Обратные вызовы событий воспроизведения бэкенда координатору AudioPlayer
 */
interface AudioBackendCallback {
    fun onPlaybackStarted(sessionId: Long)
    fun onCompletion(sessionId: Long)
    fun onError(sessionId: Long, error: String, canFallback: Boolean)
    fun onNearEnd(sessionId: Long)
}

/**
 * 🎛️ Унифицированный контракт аудио-движка на платформе JVM
 */
interface AudioBackend {
    val name: String

    fun isAvailable(): Boolean

    /**
     * Запустить воспроизведение источника
     */
    fun play(
        source: PlaybackSource,
        sessionId: Long,
        seekPosMs: Long = 0L,
        crossfadeMs: Long = 0L
    )

    fun pause()

    fun resume(resumePosMs: Long)

    fun stop()

    fun seekTo(positionMs: Long)

    fun setVolume(volume: Float)

    fun getDurationMs(): Long

    fun getCurrentPositionMs(): Long

    fun isPlaying(): Boolean

    fun updateNearEndThreshold(thresholdMs: Long, expectedDurationMs: Long) {}

    fun release()
}
