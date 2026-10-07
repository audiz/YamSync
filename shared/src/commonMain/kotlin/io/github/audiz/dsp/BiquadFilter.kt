package io.github.audiz.dsp

import kotlin.math.*

/**
 * 🎛 Цифровой биквадратный IIR-фильтр (Direct Form II Transposed).
 *
 * Реализует стандартные формулы Robert Bristow-Johnson Audio EQ Cookbook:
 * - Peaking EQ (полоса эквалайзера с усилением/ослаблением и добротностью Q)
 * - High-Pass (срез низких частот / Low-Cut)
 * - Low-Pass (срез высоких частот / High-Cut)
 *
 * Обработка стереоканалов (Left/Right) без динамических аллокаций памяти в audio-loop.
 */
class BiquadFilter {

    enum class Type {
        BYPASS,
        PEAKING,
        HIGH_PASS,
        LOW_PASS
    }

    var type: Type = Type.BYPASS
        private set

    // Коэффициенты фильтра
    private var b0: Float = 1f
    private var b1: Float = 0f
    private var b2: Float = 0f
    private var a1: Float = 0f
    private var a2: Float = 0f

    // Регистры задержки Direct Form II Transposed для левого канала
    private var d1L: Float = 0f
    private var d2L: Float = 0f

    // Регистры задержки для правого канала
    private var d1R: Float = 0f
    private var d2R: Float = 0f

    fun reset() {
        d1L = 0f
        d2L = 0f
        d1R = 0f
        d2R = 0f
    }

    fun setBypass() {
        type = Type.BYPASS
        b0 = 1f
        b1 = 0f
        b2 = 0f
        a1 = 0f
        a2 = 0f
    }

    /**
     * Настройка Peaking EQ (параметрическая/графическая полоса эквалайзера)
     * @param sampleRate частота дискретизации (обычно 44100.0)
     * @param centerFreq центральная частота полосы в Гц
     * @param gainDb усиление/ослабление в децибелах [-12.0 .. +12.0]
     * @param q добротность (обычно 1.4142f для 1 октавы)
     */
    fun configurePeaking(sampleRate: Float, centerFreq: Float, gainDb: Float, q: Float = 1.4142f) {
        if (abs(gainDb) < 0.05f) {
            setBypass()
            return
        }
        type = Type.PEAKING

        val clampedFreq = centerFreq.coerceIn(10f, sampleRate * 0.49f)
        val w0 = (2.0 * PI * clampedFreq / sampleRate).toFloat()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q.coerceAtLeast(0.1f))
        val a = 10f.pow(gainDb / 40f) // sqrt(10^(gainDb/20))

        val a0 = 1f + alpha / a
        b0 = (1f + alpha * a) / a0
        b1 = (-2f * cosW0) / a0
        b2 = (1f - alpha * a) / a0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha / a) / a0
    }

    /**
     * Настройка High-Pass Filter (срез низких частот / Low-Cut).
     * Подавляет частоты ниже [cutoffFreq].
     * @param q добротность фильтра Баттерворта (0.7071f для максимально гладкой АЧХ)
     */
    fun configureHighPass(sampleRate: Float, cutoffFreq: Float, q: Float = 0.7071f) {
        if (cutoffFreq <= 21f) {
            // Ниже 20-21 Гц срез не требуется (байпас)
            setBypass()
            return
        }
        type = Type.HIGH_PASS

        val clampedFreq = cutoffFreq.coerceIn(10f, sampleRate * 0.45f)
        val w0 = (2.0 * PI * clampedFreq / sampleRate).toFloat()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q.coerceAtLeast(0.1f))

        val a0 = 1f + alpha
        b0 = ((1f + cosW0) / 2f) / a0
        b1 = (-(1f + cosW0)) / a0
        b2 = ((1f + cosW0) / 2f) / a0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha) / a0
    }

    /**
     * Настройка Low-Pass Filter (срез высоких частот / High-Cut).
     * Подавляет частоты выше [cutoffFreq].
     * @param q добротность фильтра Баттерворта (0.7071f)
     */
    fun configureLowPass(sampleRate: Float, cutoffFreq: Float, q: Float = 0.7071f) {
        if (cutoffFreq >= 19900f) {
            // Выше 19.9 кГц срез не требуется (байпас)
            setBypass()
            return
        }
        type = Type.LOW_PASS

        val clampedFreq = cutoffFreq.coerceIn(100f, sampleRate * 0.45f)
        val w0 = (2.0 * PI * clampedFreq / sampleRate).toFloat()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q.coerceAtLeast(0.1f))

        val a0 = 1f + alpha
        b0 = ((1f - cosW0) / 2f) / a0
        b1 = (1f - cosW0) / a0
        b2 = ((1f - cosW0) / 2f) / a0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha) / a0
    }

    /**
     * Фильтрация сэмпла левого канала.
     */
    fun processLeft(input: Float): Float {
        if (type == Type.BYPASS) return input
        val output = b0 * input + d1L
        d1L = b1 * input - a1 * output + d2L
        d2L = b2 * input - a2 * output
        return output
    }

    /**
     * Фильтрация сэмпла правого канала.
     */
    fun processRight(input: Float): Float {
        if (type == Type.BYPASS) return input
        val output = b0 * input + d1R
        d1R = b1 * input - a1 * output + d2R
        d2R = b2 * input - a2 * output
        return output
    }
}
