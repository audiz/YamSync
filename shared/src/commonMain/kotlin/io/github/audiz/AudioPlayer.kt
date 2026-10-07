package io.github.audiz

/**
 * 🎵 Кросс-платформенный аудиоплеер
 */
expect class AudioPlayer() {
    /** Воспроизвести из байтов (после скачивания) */
    fun playFromBytes(bytes: ByteArray)
    fun playFromBytes(bytes: ByteArray, crossfadeMs: Long)

    /** Воспроизвести файл с диска */
    fun playFromFile(filePath: String)
    fun playFromFile(filePath: String, crossfadeMs: Long)

    /** Воспроизвести аудиопоток по HTTP URL */
    fun playFromUrl(url: String)
    fun playFromUrl(url: String, crossfadeMs: Long)

    fun pause()
    fun resume()
    fun stop()
    fun isPlaying(): Boolean
    fun getDurationMs(): Long
    fun getCurrentPositionMs(): Long
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun setOnCompletionListener(listener: (() -> Unit)?)
    fun setOnInterruptionListener(listener: ((shouldPause: Boolean, canResume: Boolean) -> Unit)?)
    fun setOnNearEndListener(listener: (() -> Unit)?)
    fun setOnErrorListener(listener: ((error: String) -> Unit)?)
    fun setOnPlaybackStartedListener(listener: (() -> Unit)?)
    fun updateNearEndThreshold(thresholdMs: Long, expectedDurationMs: Long)
    fun release()
}

/**
 * 🎚 Преобразование линейного положения ползунка громкости [0..1]
 * в реальный коэффициент мощности/усиления (Gain) по кубической кривой (Audio Taper).
 * 
 * Человеческий слух воспринимает звук логарифмически. При линейной шкале
 * весь комфортный диапазон тихой музыки спрессован в 3-5 пикселей внизу.
 * Кубическая кривая (v^3) растягивает нижний диапазон, обеспечивая мягкую
 * и точную настройку тихой громкости (особенно в наушниках).
 */
fun volumeToGain(volume: Float): Float {
    val v = volume.coerceIn(0f, 1f)
    return v * v * v
}

