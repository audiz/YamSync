package io.github.audiz.dsp

import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.*

/**
 * 🎚 Движок графического эквалайзера и частотных срезов (DSP).
 *
 * Содержит:
 * - Срез низких частот (HPF / Low-Cut): 20 Гц (выкл) .. 250 Гц
 * - 10-полосный классический эквалайзер: 31, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Гц (±12 дБ)
 * - Срез высоких частот (LPF / High-Cut): 4 кГц .. 20 кГц (выкл)
 * - Набор пресетов (Rock, Pop, Bass Boost, Vocal и др.)
 * - Автосохранение в конфигурацию приложения
 */
object EqualizerEngine {

    val STANDARD_FREQUENCIES = floatArrayOf(
        31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    )

    data class Preset(
        val name: String,
        val gains: FloatArray,
        val hpfHz: Float = 20f,
        val lpfHz: Float = 20000f
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Preset) return false
            return name == other.name && gains.contentEquals(other.gains) && hpfHz == other.hpfHz && lpfHz == other.lpfHz
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + gains.contentHashCode()
            result = 31 * result + hpfHz.hashCode()
            result = 31 * result + lpfHz.hashCode()
            return result
        }
    }

    val PRESETS = listOf(
        Preset("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Bass Boost", floatArrayOf(6f, 5f, 3.5f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Bass Reducer", floatArrayOf(-6f, -5f, -3f, -1.5f, 0f, 0f, 0f, 0f, 0f, 0f), hpfHz = 40f),
        Preset("Rock", floatArrayOf(4f, 3f, 1.5f, -0.5f, -1f, 1f, 2.5f, 4f, 4.5f, 3f)),
        Preset("Pop", floatArrayOf(-1f, 1f, 3f, 4f, 3f, 1.5f, 0.5f, 1f, 0f, -1f)),
        Preset("Jazz", floatArrayOf(3f, 2f, 1f, -1f, -1f, 0f, 1.5f, 2.5f, 3f, 2.5f)),
        Preset("Classical", floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 2.5f)),
        Preset("Electronic", floatArrayOf(5.5f, 4.5f, 2f, 0f, -1.5f, 1.5f, 2.5f, 4f, 5f, 4f)),
        Preset("Vocal Boost", floatArrayOf(-3f, -2f, -1f, 1f, 3f, 4f, 3.5f, 2f, 0f, -2f), hpfHz = 80f),
        Preset("Treble Boost", floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5.5f, 6f)),
        Preset("Treble Tamer", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -1f, -3f, -5f, -6f), lpfHz = 14000f),
        Preset("Subwoofer Cut", floatArrayOf(-12f, 0f, 2.5f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f), hpfHz = 60f)
    )

    data class State(
        val isEnabled: Boolean = false,
        val hpfHz: Float = 20f,
        val lpfHz: Float = 20000f,
        val gains: FloatArray = FloatArray(10) { 0f },
        val presetName: String = "Flat"
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is State) return false
            return isEnabled == other.isEnabled && hpfHz == other.hpfHz && lpfHz == other.lpfHz &&
                    gains.contentEquals(other.gains) && presetName == other.presetName
        }

        override fun hashCode(): Int {
            var result = isEnabled.hashCode()
            result = 31 * result + hpfHz.hashCode()
            result = 31 * result + lpfHz.hashCode()
            result = 31 * result + gains.contentHashCode()
            result = 31 * result + presetName.hashCode()
            return result
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var onStateChanged: ((State) -> Unit)? = null

    @Volatile var isEnabled: Boolean = false
        private set

    @Volatile var hpfHz: Float = 20f
        private set

    @Volatile var lpfHz: Float = 20000f
        private set

    @Volatile private var sampleRate: Float = 44100f

    private val hpfFilter = BiquadFilter()
    private val bandFilters = Array(10) { BiquadFilter() }
    private val lpfFilter = BiquadFilter()

    init {
        loadSettings()
        recalculateFilters()
    }

    fun hasCutoffsActive(): Boolean {
        return isEnabled && (hpfHz > 21f || lpfHz < 19900f)
    }

    /**
     * Логарифмическая интерполяция гейна 10 полос эквалайзера для заданной частоты.
     */
    fun interpolateGain(freqHz: Float, gains: FloatArray): Float {
        if (gains.isEmpty()) return 0f
        if (freqHz <= STANDARD_FREQUENCIES.first()) return gains.first()
        if (freqHz >= STANDARD_FREQUENCIES.last()) return gains.last()

        for (i in 0 until STANDARD_FREQUENCIES.size - 1) {
            val fLow = STANDARD_FREQUENCIES[i]
            val fHigh = STANDARD_FREQUENCIES[i + 1]
            if (freqHz in fLow..fHigh) {
                val logCenter = log10(freqHz)
                val logLow = log10(fLow)
                val logHigh = log10(fHigh)
                val fraction = (logCenter - logLow) / (logHigh - logLow)
                return gains[i] + fraction * (gains[i + 1] - gains[i])
            }
        }
        return 0f
    }

    /**
     * Расчет результирующего гейна в дБ для заданной частоты freqHz.
     * Объединяет 10 полос эквалайзера и спады фильтров Low-Cut (HPF) и High-Cut (LPF).
     */
    fun calculateEffectiveGainDb(freqHz: Float): Float {
        if (!isEnabled) return 0f

        val safeFreq = freqHz.coerceAtLeast(1f)

        // 1. 10 полос эквалайзера
        val eqGain = interpolateGain(safeFreq, _state.value.gains)

        // 2. Срез НЧ (HPF / Low-Cut): спад 2-го порядка (-12 дБ/октава) ниже частоты среза
        val currentHpf = hpfHz
        val hpfAttenuation = if (currentHpf > 21f && safeFreq < currentHpf) {
            val octavesBelow = log2(currentHpf / safeFreq)
            (-12f * octavesBelow).coerceAtMost(0f)
        } else 0f

        // 3. Срез ВЧ (LPF / High-Cut): спад 2-го порядка (-12 дБ/октава) выше частоты среза
        val currentLpf = lpfHz
        val lpfAttenuation = if (currentLpf < 19900f && safeFreq > currentLpf) {
            val octavesAbove = log2(safeFreq / currentLpf)
            (-12f * octavesAbove).coerceAtMost(0f)
        } else 0f

        return (eqGain + hpfAttenuation + lpfAttenuation).coerceIn(-24f, 15f)
    }

    fun setSampleRate(rate: Float) {
        if (rate > 8000f && rate != sampleRate) {
            sampleRate = rate
            recalculateFilters()
        }
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        _state.value = _state.value.copy(isEnabled = enabled)
        saveAppConfig("eq_enabled", enabled.toString())
        recalculateFilters()
    }

    fun setHpfCutoff(freqHz: Float) {
        val clamped = freqHz.coerceIn(20f, 250f)
        hpfHz = clamped
        _state.value = _state.value.copy(hpfHz = clamped, presetName = "Custom")
        saveAppConfig("eq_hpf", clamped.toString())
        saveAppConfig("eq_preset", "Custom")
        recalculateFilters()
    }

    fun setLpfCutoff(freqHz: Float) {
        val clamped = freqHz.coerceIn(4000f, 20000f)
        lpfHz = clamped
        _state.value = _state.value.copy(lpfHz = clamped, presetName = "Custom")
        saveAppConfig("eq_lpf", clamped.toString())
        saveAppConfig("eq_preset", "Custom")
        recalculateFilters()
    }

    fun setBandGain(index: Int, gainDb: Float) {
        if (index !in 0..9) return
        val currentGains = _state.value.gains.copyOf()
        currentGains[index] = gainDb.coerceIn(-12f, 12f)
        _state.value = _state.value.copy(gains = currentGains, presetName = "Custom")
        saveAppConfig("eq_band_$index", currentGains[index].toString())
        saveAppConfig("eq_preset", "Custom")
        recalculateFilters()
    }

    fun applyPreset(preset: Preset) {
        val gainsCopy = preset.gains.copyOf()
        hpfHz = preset.hpfHz
        lpfHz = preset.lpfHz
        _state.value = _state.value.copy(
            gains = gainsCopy,
            hpfHz = preset.hpfHz,
            lpfHz = preset.lpfHz,
            presetName = preset.name
        )
        saveAppConfig("eq_preset", preset.name)
        saveAppConfig("eq_hpf", preset.hpfHz.toString())
        saveAppConfig("eq_lpf", preset.lpfHz.toString())
        for (i in 0..9) {
            saveAppConfig("eq_band_$i", gainsCopy[i].toString())
        }
        recalculateFilters()
    }

    fun resetToFlat() {
        val flatPreset = PRESETS.first { it.name == "Flat" }
        applyPreset(flatPreset)
    }

    fun resetCutoffs() {
        setHpfCutoff(20f)
        setLpfCutoff(20000f)
    }

    private fun recalculateFilters() {
        if (!isEnabled) {
            hpfFilter.setBypass()
            for (f in bandFilters) f.setBypass()
            lpfFilter.setBypass()
            onStateChanged?.invoke(_state.value)
            return
        }

        hpfFilter.configureHighPass(sampleRate, hpfHz)

        val gains = _state.value.gains
        for (i in 0..9) {
            val freq = STANDARD_FREQUENCIES[i]
            val gain = gains[i]
            bandFilters[i].configurePeaking(sampleRate, freq, gain)
        }

        lpfFilter.configureLowPass(sampleRate, lpfHz)
        onStateChanged?.invoke(_state.value)
    }

    /**
     * Потоковая фильтрация сырого 16-битного стерео PCM (little-endian).
     * Выполняется in-place без выделения памяти на куче.
     */
    fun processPcm(buffer: ByteArray, bytesRead: Int) {
        if (!isEnabled) return

        var i = 0
        while (i < bytesRead - 3) {
            val leftRaw = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val rightRaw = (buffer[i + 3].toInt() shl 8) or (buffer[i + 2].toInt() and 0xFF)

            var l = leftRaw / 32768f
            var r = rightRaw / 32768f

            // 1. Срез НЧ (HPF)
            l = hpfFilter.processLeft(l)
            r = hpfFilter.processRight(r)

            // 2. 10 полос эквалайзера
            for (k in 0..9) {
                val filter = bandFilters[k]
                l = filter.processLeft(l)
                r = filter.processRight(r)
            }

            // 3. Срез ВЧ (LPF)
            l = lpfFilter.processLeft(l)
            r = lpfFilter.processRight(r)

            // Ограничение амплитуды (soft clamp)
            val outL = (l * 32767f).roundToInt().coerceIn(-32768, 32767)
            val outR = (r * 32767f).roundToInt().coerceIn(-32768, 32767)

            buffer[i] = (outL and 0xFF).toByte()
            buffer[i + 1] = ((outL shr 8) and 0xFF).toByte()
            buffer[i + 2] = (outR and 0xFF).toByte()
            buffer[i + 3] = ((outR shr 8) and 0xFF).toByte()

            i += 4
        }
    }

    private fun loadSettings() {
        val enabled = loadAppConfig("eq_enabled")?.toBooleanStrictOrNull() ?: false
        val hpf = loadAppConfig("eq_hpf")?.toFloatOrNull() ?: 20f
        val lpf = loadAppConfig("eq_lpf")?.toFloatOrNull() ?: 20000f
        val preset = loadAppConfig("eq_preset") ?: "Flat"

        val gains = FloatArray(10) { index ->
            loadAppConfig("eq_band_$index")?.toFloatOrNull() ?: 0f
        }

        isEnabled = enabled
        hpfHz = hpf.coerceIn(20f, 250f)
        lpfHz = lpf.coerceIn(4000f, 20000f)

        _state.value = State(
            isEnabled = enabled,
            hpfHz = hpfHz,
            lpfHz = lpfHz,
            gains = gains,
            presetName = preset
        )
    }
}
