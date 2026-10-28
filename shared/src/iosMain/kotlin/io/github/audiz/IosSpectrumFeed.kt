package io.github.audiz

import io.github.audiz.dsp.AudioVisualizer
import io.github.audiz.dsp.PcmSpectrumAnalyzer
import io.github.audiz.dsp.TwentyFiveBandVisualizer
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * 🎛 Подача аудиопотока iOS в 25-полосный спектроанализатор.
 *
 * Swift-обработчик аудио копирует моно-сэмплы и вызывает [submit] на своей последовательной
 * фоновой очереди анализа. Здесь блок режется на шаги по [HOP_FRAMES] сэмплов, по каждому шагу
 * считается спектр, а результаты передаются в [AudioVisualizer] на главном потоке с интервалом
 * длительности шага — визуализатор получает ровный поток кадров вместо пачки раз в ~90 мс.
 */
object IosSpectrumFeed {
    private const val HOP_FRAMES = 1024

    private val analyzer = PcmSpectrumAnalyzer()
    private val hop = FloatArray(HOP_FRAMES)
    private var lastSampleRate = 0f
    private var latestSpectrum: FloatArray? = null

    init {
        analyzer.onSpectrumReady = { latestSpectrum = it.copyOf() }
    }

    // Поколение потока: кадры, запланированные до сброса (перемотка, смена трека), отбрасываются
    private val generation = AtomicInt(0)

    @Volatile
    private var pendingReset = false

    /** Вызывается только с последовательной очереди анализа. */
    fun submit(samples: FloatArray, count: Int, sampleRate: Float) {
        if (pendingReset) {
            pendingReset = false
            analyzer.reset()
        }
        if (sampleRate > 0f && sampleRate != lastSampleRate) {
            lastSampleRate = sampleRate
            analyzer.setSampleRate(sampleRate)
        }
        val n = minOf(count, samples.size)
        if (n <= 0) return

        val rate = if (lastSampleRate > 0f) lastSampleRate else 44100f
        val hopNs = (HOP_FRAMES / rate * NSEC_PER_SEC.toDouble()).toLong()
        val gen = generation.value
        var offset = 0
        var hopIndex = 0
        while (offset < n) {
            val len = minOf(HOP_FRAMES, n - offset)
            samples.copyInto(hop, 0, offset, offset + len)
            latestSpectrum = null
            analyzer.processMonoFloat(hop, len)
            latestSpectrum?.let { scheduleOnMain(it, hopIndex * hopNs, gen) }
            offset += len
            hopIndex++
        }
    }

    /** Сбросить анализ при перемотке, паузе или смене трека. Можно вызывать с любого потока. */
    fun reset() {
        generation.incrementAndGet()
        pendingReset = true
    }

    private fun scheduleOnMain(spectrum: FloatArray, delayNs: Long, gen: Int) {
        if (spectrum.size != TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT) return
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayNs), dispatch_get_main_queue()) {
            if (generation.value == gen) {
                AudioVisualizer.updateSpectrum(spectrum)
            }
        }
    }
}
