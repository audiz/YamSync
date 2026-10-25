package io.github.audiz.dsp

import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * 🎵 Координатор и фасад спектрального анализа и кинетической визуализации Winamp Pro.
 *
 * Объединяет:
 * - [FiveBandVisualizer]: 5-полосный микро-анализатор для иконок и кнопок плеера
 * - [TwentyFiveBandVisualizer]: 25-полосный спектроанализатор для обложек альбомов
 * - Синхронизацию времени и компенсацию задержки (Latency Compensation)
 * - Детектор тишины и слышимости трека для системы автопропуска
 */
object AudioVisualizer {

    const val BAND_COUNT = FiveBandVisualizer.BAND_COUNT
    const val SPECTRUM_BAND_COUNT = TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT
    const val RESTING_LEVEL = VisualizerConstants.RESTING_LEVEL
    const val DEFAULT_LATENCY_COMPENSATION_MS = 140L

    val fiveBand: FiveBandVisualizer = FiveBandVisualizer()
    val twentyFiveBand: TwentyFiveBandVisualizer = TwentyFiveBandVisualizer()

    init {
        // Связываем синтез 25 полос из 5 полос при отсутствии прямого FFT
        fiveBand.onBandsUpdated = { raw5, autoGain, dynamicExp ->
            twentyFiveBand.trackPeakEma = fiveBand.trackPeakEma
            twentyFiveBand.synthesizeFrom5Bands(raw5, autoGain, dynamicExp)
        }
        // Трекинг слышимости для детектора тишины в хвосте трека
        fiveBand.onAudibleEnergyDetected = {
            hasHeardAudioInCurrentTrack = true
            lastAudiblePcmTimeMs = currentTime()
        }
        // Fallback дренажа очередей
        twentyFiveBand.onDrainFallback = { now ->
            fiveBand.drainMatured5(now)
        }
    }

    var timeProvider: () -> Long = { io.github.audiz.currentTimeMillis() }
        set(value) {
            field = value
            fiveBand.timeProvider = value
            twentyFiveBand.timeProvider = value
        }

    private fun currentTime(): Long = timeProvider()

    @Volatile
    var latencyCompensationMs: Long = loadAppConfig("visualizer_latency_ms")?.toLongOrNull() ?: DEFAULT_LATENCY_COMPENSATION_MS
        set(value) {
            val clamped = value.coerceIn(0L, 500L)
            field = clamped
            fiveBand.latencyCompensationMs = clamped
            twentyFiveBand.latencyCompensationMs = clamped
            saveAppConfig("visualizer_latency_ms", clamped.toString())
        }

    init {
        fiveBand.latencyCompensationMs = latencyCompensationMs
        twentyFiveBand.latencyCompensationMs = latencyCompensationMs
    }

    // --- Трекер темпа и динамики ---
    val currentPaceFactor: Float
        get() = twentyFiveBand.currentPaceFactor

    // --- Трекер динамической частотной группировки ---
    val spectralCentroidEma: Float
        get() = twentyFiveBand.spectralCentroidEma

    // --- 5 полос для микро-иконок ---
    val levels: StateFlow<FloatArray>
        get() = fiveBand.levels

    // --- Детекция слышимости и тишины в хвосте трека ---
    @Volatile
    private var lastAudiblePcmTimeMs: Long = 0L

    @Volatile
    private var hasHeardAudioInCurrentTrack: Boolean = false

    fun resetTrackAudibility() {
        lastAudiblePcmTimeMs = 0L
        hasHeardAudioInCurrentTrack = false
    }

    fun isSilenceDetectedInTail(positionMs: Long, durationMs: Long): Boolean {
        if (!hasHeardAudioInCurrentTrack) return false
        if (durationMs <= 0L) return false
        val tailThresholdMs = (durationMs - 5000L).coerceAtLeast((durationMs * 0.96f).toLong())
        if (positionMs < tailThresholdMs) return false

        val silenceDuration = currentTime() - lastAudiblePcmTimeMs
        return lastAudiblePcmTimeMs > 0L && silenceDuration >= 2000L
    }

    /**
     * 🛑 Очистить буфер задержки визуализатора (при перемотке/паузе/смене трека)
     */
    fun clearDelayQueue() {
        fiveBand.clearDelayQueue()
        twentyFiveBand.clearDelayQueue()
    }

    /**
     * 🎚 Обновить 5 полос из нормализованных значений энергии (0.0 .. 1.0).
     */
    fun updateBands(b1: Float, b2: Float, b3: Float, b4: Float, b5: Float) {
        val maxEnergy = maxOf(b1, b2, b3, b4, b5)
        if (maxEnergy > 0.02f) {
            hasHeardAudioInCurrentTrack = true
            lastAudiblePcmTimeMs = currentTime()
        }
        fiveBand.updateBands(b1, b2, b3, b4, b5)
        twentyFiveBand.trackPeakEma = fiveBand.trackPeakEma
    }

    /**
     * 🎚 Обновить полосы спектроанализатора напрямую из FFT.
     */
    fun updateSpectrum(spectrum: FloatArray) {
        twentyFiveBand.updateSpectrum(spectrum)
        fiveBand.trackPeakEma = twentyFiveBand.trackPeakEma
    }

    fun updateSpectrum19(spectrum: FloatArray) = updateSpectrum(spectrum)

    /**
     * 📊 Обработать сырой 16-битный PCM-буфер (LE Stereo или Mono)
     */
    fun processPcmChunk(buffer: ByteArray, readBytes: Int) {
        fiveBand.processPcmChunk(buffer, readBytes)
    }

    /**
     * ⏱ Рассчитать следующий кадр физики 5 полос (для 60 FPS UI)
     */
    fun advanceFrame(dtSeconds: Float, isPlaying: Boolean): VisualizerFrame {
        val frame = fiveBand.advanceFrame(dtSeconds, isPlaying)
        twentyFiveBand.trackPeakEma = fiveBand.trackPeakEma
        return frame
    }

    /**
     * ⏱ Рассчитать следующий кадр физики 25 полос спектроанализатора
     */
    fun advanceSpectrumFrame(dtSeconds: Float, isPlaying: Boolean): VisualizerFrame {
        val frame = twentyFiveBand.advanceFrame(dtSeconds, isPlaying)
        fiveBand.currentPaceFactor = twentyFiveBand.currentPaceFactor
        fiveBand.trackPeakEma = twentyFiveBand.trackPeakEma
        return frame
    }

    fun advanceSpectrum19Frame(dtSeconds: Float, isPlaying: Boolean): VisualizerFrame =
        advanceSpectrumFrame(dtSeconds, isPlaying)

    /**
     * 🛑 Сброс уровней в состояние покоя при остановке или паузе
     */
    fun reset() {
        fiveBand.reset()
        twentyFiveBand.reset()
        resetTrackAudibility()
    }

    /**
     * 🌊 Получить текущий срез уровней (для обратной совместимости)
     */
    fun getLevelsForDisplay(isPlaying: Boolean): FloatArray {
        return fiveBand.getLevelsForDisplay(isPlaying)
    }
}