package io.github.audiz.dsp

import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * 🎵 Координатор и фасад спектрального анализа и кинетической визуализации Winamp Pro.
 *
 * Архитектура данных:
 * 1. Первичный источник спектра — 25 логарифмических полос FFT ([TwentyFiveBandVisualizer]).
 * 2. Для PCM потока (Desktop FFmpeg / JLayer) 25 полос рассчитываются в реальном времени через [PcmSpectrumAnalyzer].
 * 3. 5-полосный микро-анализатор [FiveBandVisualizer] формируется нисходящей агрегацией (downsampling) из 25 полос.
 * 4. Единая компенсация задержки (Latency Compensation) и синхронизация времени.
 * 5. Детектор тишины и слышимости трека для системы автопропуска.
 */
object AudioVisualizer {

    const val BAND_COUNT = FiveBandVisualizer.BAND_COUNT
    const val SPECTRUM_BAND_COUNT = TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT
    const val RESTING_LEVEL = VisualizerConstants.RESTING_LEVEL
    const val DEFAULT_LATENCY_COMPENSATION_MS = 140L

    val fiveBand: FiveBandVisualizer = FiveBandVisualizer()
    val twentyFiveBand: TwentyFiveBandVisualizer = TwentyFiveBandVisualizer()
    val pcmAnalyzer: PcmSpectrumAnalyzer = PcmSpectrumAnalyzer()

    init {
        // Подключаем честный FFT-анализатор PCM: результат сразу передается в общий пайплайн
        pcmAnalyzer.onSpectrumReady = { spectrum25 ->
            updateSpectrum(spectrum25)
        }
        pcmAnalyzer.onAudibleEnergyDetected = {
            markAudible()
        }
        fiveBand.onAudibleEnergyDetected = {
            markAudible()
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

    // --- 5 полос для микро-иконок ---
    val levels: StateFlow<FloatArray>
        get() = fiveBand.levels

    // --- Детекция слышимости и тишины в хвосте трека ---
    @Volatile
    private var lastAudiblePcmTimeMs: Long = 0L

    @Volatile
    private var hasHeardAudioInCurrentTrack: Boolean = false

    private fun markAudible() {
        hasHeardAudioInCurrentTrack = true
        lastAudiblePcmTimeMs = currentTime()
    }

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
     * 🎚 Обновить 5 полос напрямую (для платформ/источников с готовыми 5 полосами)
     */
    fun updateBands(b1: Float, b2: Float, b3: Float, b4: Float, b5: Float) {
        val maxEnergy = maxOf(b1, b2, b3, b4, b5)
        if (maxEnergy > 0.02f) {
            markAudible()
        }
        fiveBand.updateBands(b1, b2, b3, b4, b5)
    }

    /**
     * 🎚 Обновить 25 полос напрямую из FFT (Android, JavaFX, либо [PcmSpectrumAnalyzer]).
     *
     * 25 полос являются первичным источником истины:
     * 5 полос для иконок автоматически выводятся нисходящей агрегацией.
     */
    fun updateSpectrum(spectrum: FloatArray) {
        twentyFiveBand.updateSpectrum(spectrum)
        fiveBand.trackPeakEma = twentyFiveBand.trackPeakEma

        // Формируем 5 полос из 25 полос:
        // 1. Sub-bass / Kick (~20 - 184 Гц, полосы 0..6)
        val b1 = groupEnergy(spectrum, 0, 6, peakWeight = 0.85f)
        // 2. Bass & Low-Mids (~184 - 736 Гц, полосы 7..11)
        val b2 = groupEnergy(spectrum, 7, 11, peakWeight = 0.80f)
        // 3. Mids / Vocals (~736 - 2232 Гц, полосы 12..16)
        val b3 = groupEnergy(spectrum, 12, 16, peakWeight = 0.75f)
        // 4. High-Mids (~2232 - 6766 Гц, полосы 17..20)
        val b4 = groupEnergy(spectrum, 17, 20, peakWeight = 0.75f)
        // 5. Treble / Air (~6766 - 20500 Гц, полосы 21..24)
        val b5 = groupEnergy(spectrum, 21, 24, peakWeight = 0.75f)

        val maxEnergy = maxOf(b1, b2, b3, b4, b5)
        if (maxEnergy > 0.02f) {
            markAudible()
        }
        fiveBand.updateBands(b1, b2, b3, b4, b5)
    }

    /**
     * Агрегация полос спектра с взвешиванием пика и среднего
     */
    private fun groupEnergy(spectrum: FloatArray, start: Int, end: Int, peakWeight: Float = 0.75f): Float {
        val size = spectrum.size
        if (size == 0) return RESTING_LEVEL
        val s = start.coerceIn(0, size - 1)
        val e = end.coerceIn(s, size - 1)
        var maxVal = 0f
        var sum = 0f
        val count = e - s + 1
        for (i in s..e) {
            val v = spectrum[i]
            if (v > maxVal) maxVal = v
            sum += v
        }
        val avg = sum / count.toFloat()
        return (maxVal * peakWeight + avg * (1f - peakWeight)).coerceIn(0f, 1f)
    }

    /**
     * 📊 Обработать сырой 16-битный PCM-буфер (LE Stereo или Mono) через прецизионный FFT-анализатор
     */
    fun processPcmChunk(buffer: ByteArray, readBytes: Int) {
        pcmAnalyzer.processPcmChunk(buffer, readBytes)
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
        fiveBand.trackPeakEma = twentyFiveBand.trackPeakEma
        return frame
    }

    /**
     * 🛑 Сброс уровней в состояние покоя при остановке или паузе
     */
    fun reset() {
        pcmAnalyzer.reset()
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