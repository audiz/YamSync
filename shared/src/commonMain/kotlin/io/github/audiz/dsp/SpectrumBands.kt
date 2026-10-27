package io.github.audiz.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 📐 Интегрирование линейного амплитудного спектра FFT в 25 логарифмических полос.
 *
 * Бин `k` покрывает интервал частот `[k - 0.5, k + 0.5] * binHz`. Каждый бин входит в полосу
 * с весом, равным доле перекрытия интервалов, поэтому узкие низкочастотные полосы (уже одного бина)
 * получают разные значения вместо дублирования одного и того же бина.
 */
object SpectrumBands {
    const val THRESHOLD_DB = -60.0f

    private const val MIN_AMPLITUDE = 1e-4f

    /** Наклон АЧХ для полосы `k` из 25: компенсирует естественный спад энергии к верхам. */
    fun tiltDb(bandIndex: Int): Float {
        val progress = bandIndex.toFloat() / (TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT - 1).toFloat()
        return -7.0f + progress * 23.0f
    }

    /**
     * Уровень полосы `[fStart, fEnd]` в диапазоне 0..1.
     *
     * @param amplitudes линейные амплитуды бинов (1.0 = полная шкала), индекс 0 — DC
     * @param numBins количество валидных бинов в [amplitudes]
     * @param peakWeight вклад пикового бина для широких полос (для полос уже 1 бина плавно уходит в 0)
     */
    fun bandLevel(
        amplitudes: FloatArray,
        numBins: Int,
        binHz: Float,
        fStart: Float,
        fEnd: Float,
        tiltDb: Float,
        peakWeight: Float = 0.75f
    ): Float {
        if (numBins < 2 || binHz <= 0f) return 0f
        val maxPos = numBins - 0.5f
        val lo = (fStart / binHz).coerceIn(0.5f, maxPos)
        val hi = (fEnd / binHz).coerceIn(lo, maxPos)
        val first = (lo + 0.5f).toInt().coerceIn(1, numBins - 1)
        val last = (hi + 0.5f).toInt().coerceIn(first, numBins - 1)

        var powerSum = 0f
        var weightSum = 0f
        var peak = 0f
        for (k in first..last) {
            val overlap = min(hi, k + 0.5f) - max(lo, k - 0.5f)
            if (overlap <= 0f) continue
            val a = amplitudes[k]
            powerSum += a * a * overlap
            weightSum += overlap
            if (a > peak) peak = a
        }
        val rms = if (weightSum > 0f) sqrt(powerSum / weightSum) else amplitudes[first]
        if (weightSum <= 0f) peak = rms

        val widthBins = hi - lo
        val pw = peakWeight * ((widthBins - 1f) / 2f).coerceIn(0f, 1f)
        val combinedDb = toDb(peak) * pw + toDb(rms) * (1f - pw) + tiltDb
        return ((combinedDb - THRESHOLD_DB) / (-THRESHOLD_DB)).coerceIn(0f, 1f)
    }

    private fun toDb(amplitude: Float): Float =
        if (amplitude > MIN_AMPLITUDE) (20.0f * log10(amplitude)).coerceIn(THRESHOLD_DB, 0f) else THRESHOLD_DB
}
