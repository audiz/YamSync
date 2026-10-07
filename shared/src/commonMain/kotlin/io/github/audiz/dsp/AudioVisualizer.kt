package io.github.audiz.dsp

import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import io.github.audiz.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.PI

/**
 * 📦 Кадр спектрального визуализатора Winamp Pro для отрисовки в UI
 * @property levels Текущие высоты столбиков (0.0 .. 1.0)
 * @property peakCaps Парящие пиковые засечки (Peak Caps) (0.0 .. 1.0)
 */
data class VisualizerFrame(
    val levels: FloatArray,
    val peakCaps: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualizerFrame) return false
        if (!levels.contentEquals(other.levels)) return false
        return peakCaps.contentEquals(other.peakCaps)
    }

    override fun hashCode(): Int {
        var result = levels.contentHashCode()
        result = 31 * result + peakCaps.contentHashCode()
        return result
    }
}

/**
 * 🎵 Движок спектрального анализа и баллистики Winamp Pro в реальном времени.
 *
 * Поддерживает два режима:
 * 1. 5 полос (BAND_COUNT = 5) — для ультракомпактных микро-индикаторов (NowPlayingIndicator 16x16 dp)
 * 2. 19 полос (SPECTRUM_BAND_COUNT = 19) — честный аппаратный спектроанализатор Winamp Pro (20 Гц .. 20 кГц) для обложки альбома
 *
 * Особенности:
 * - Студийная калибровка Winamp Pro с плотным упругим басом
 * - Адаптивная чувствительность (Dynamic Range Tracking)
 * - Честная баллистика Winamp Pro: мгновенная атака (0 мс), удержание пика (60 мс),
 *   гравитация столбиков (4.2/s²), парение пиковых засечек (280 мс, 2.8/s²)
 * - Срез инфразвука (< 40 Гц): фильтрация паразитного DC-смещения и рокота
 * - Разделение бочки и баса (Anti-Bleed Decoupling)
 */
object AudioVisualizer {

    const val BAND_COUNT = 5
    const val SPECTRUM_BAND_COUNT = 19
    const val RESTING_LEVEL = 0.15f

    // Константы физики Winamp Pro
    private const val BAR_HOLD_SEC = 0.060f       // 60 мс фиксация удара бочки/баса
    private const val BAR_GRAVITY = 4.2f          // Ускорение падения столбиков (g ≈ 4.2 / s²)
    private const val PEAK_CAP_HOLD_SEC = 0.280f  // 280 мс парение пиковой засечки
    private const val PEAK_CAP_GRAVITY = 2.8f     // Ускорение падения пиковой засечки

    // Трекер пиковой громкости трека (быстрый захват, медленный спад ~4.0 сек)
    private var trackPeakEma = 0.5f

    // --- 5 полос для микро-иконок ---
    private val targetLevels = FloatArray(BAND_COUNT) { RESTING_LEVEL }
    private val currentLevels = FloatArray(BAND_COUNT) { RESTING_LEVEL }
    private val velocities = FloatArray(BAND_COUNT) { 0f }
    private val holdTimers = FloatArray(BAND_COUNT) { 0f }
    private val peakCapLevels = FloatArray(BAND_COUNT) { RESTING_LEVEL }
    private val peakCapVelocities = FloatArray(BAND_COUNT) { 0f }
    private val peakCapHoldTimers = FloatArray(BAND_COUNT) { 0f }

    private val _levels = MutableStateFlow(FloatArray(BAND_COUNT) { RESTING_LEVEL })
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    // --- 19 полос спектрального анализатора Winamp Pro для обложки альбома ---
    private val targetSpectrumLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private val currentSpectrumLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private val spectrumVelocities = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private val spectrumHoldTimers = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private val peakCapSpectrumLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private val peakCapSpectrumVelocities = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private val peakCapSpectrumHoldTimers = FloatArray(SPECTRUM_BAND_COUNT) { 0f }

    const val DEFAULT_LATENCY_COMPENSATION_MS = 140L

    @Volatile
    var latencyCompensationMs: Long = loadAppConfig("visualizer_latency_ms")?.toLongOrNull() ?: DEFAULT_LATENCY_COMPENSATION_MS
        set(value) {
            val clamped = value.coerceIn(0L, 500L)
            field = clamped
            saveAppConfig("visualizer_latency_ms", clamped.toString())
        }

    private class Bands5Snapshot(
        val timeMs: Long,
        val b1: Float,
        val b2: Float,
        val b3: Float,
        val b4: Float,
        val b5: Float
    )

    private class Spectrum19Snapshot(
        val timeMs: Long,
        val spectrum: FloatArray
    )

    private val delayQueue5 = ArrayDeque<Bands5Snapshot>()
    private val delayQueue19 = ArrayDeque<Spectrum19Snapshot>()

    @Volatile
    private var lastSpectrum19UpdateTimeMs: Long = 0L

    internal var timeProvider: () -> Long = { io.github.audiz.currentTimeMillis() }

    private fun currentTime(): Long = timeProvider()

    @Volatile
    private var lastUpdateTimeMs: Long = 0L

    // Фильтры PCM (1-pole IIR)
    private var subCutFilter: Float = 0f
    private var kickFilter: Float = 0f
    private var bassFilter: Float = 0f
    private var midFilter: Float = 0f
    private var highMidFilter: Float = 0f

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

    // Частотные срезы для 44.1 kHz (alpha = 2 * pi * fc / 44100):
    private const val ALPHA_SUBCUT = 0.0057f   // ~40 Hz (Sub-cut)
    private const val ALPHA_KICK = 0.0157f     // ~110 Hz (Kick Drum)
    private const val ALPHA_BASS = 0.050f      // ~350 Hz (Bass Guitar)
    private const val ALPHA_MID = 0.22f        // ~1800 Hz (Mids / Vocals)
    private const val ALPHA_HIGH_MID = 0.50f   // ~6000 Hz (High Mids / Snare)

    /**
     * 🛑 Очистить буфер задержки визуализатора (при перемотке/паузе/смене трека)
     */
    fun clearDelayQueue() {
        synchronized(currentLevels) {
            delayQueue5.clear()
            delayQueue19.clear()
        }
    }

    private fun drainMatured5(now: Long) {
        val latency = latencyCompensationMs
        if (latency <= 0L) return
        while (delayQueue5.isNotEmpty()) {
            val first = delayQueue5.first()
            val age = now - first.timeMs
            if (age >= latency) {
                delayQueue5.removeFirst()
                if (age <= latency + 1000L) {
                    applyBandsDirect(first.b1, first.b2, first.b3, first.b4, first.b5)
                }
            } else {
                break
            }
        }
    }

    private fun drainMatured19(now: Long) {
        val latency = latencyCompensationMs
        if (latency <= 0L) return
        while (delayQueue19.isNotEmpty()) {
            val first = delayQueue19.first()
            val age = now - first.timeMs
            if (age >= latency) {
                delayQueue19.removeFirst()
                if (age <= latency + 1000L) {
                    applySpectrum19Direct(first.spectrum)
                }
            } else {
                break
            }
        }
    }

    /**
     * 🎚 Обновить 5 полос из нормализованных значений энергии (0.0 .. 1.0).
     * Помещает снимок в очередь компенсации задержки вывода звука.
     */
    fun updateBands(b1: Float, b2: Float, b3: Float, b4: Float, b5: Float) {
        val maxEnergy = maxOf(b1, b2, b3, b4, b5)
        if (maxEnergy > 0.02f) {
            hasHeardAudioInCurrentTrack = true
            lastAudiblePcmTimeMs = currentTime()
        }
        synchronized(currentLevels) {
            val latency = latencyCompensationMs
            if (latency <= 0L) {
                applyBandsDirect(b1, b2, b3, b4, b5)
            } else {
                while (delayQueue5.size >= 50) {
                    delayQueue5.removeFirst()
                }
                delayQueue5.addLast(Bands5Snapshot(currentTime(), b1, b2, b3, b4, b5))
            }
        }
    }

    private fun applyBandsDirect(b1: Float, b2: Float, b3: Float, b4: Float, b5: Float) {
        val raw0 = b1.coerceIn(0f, 1f)
        // Эластичный софт-лимитер бочки: выше 0.85 оставляет запас хода для динамических ударов
        val softCappedB1 = if (raw0 > 0.85f) 0.85f + (raw0 - 0.85f) * 0.35f else raw0
        val raw1 = b2.coerceIn(0f, 1f)
        val raw2 = b3.coerceIn(0f, 1f)
        val raw3 = b4.coerceIn(0f, 1f)
        val raw4 = b5.coerceIn(0f, 1f)

        val raw = floatArrayOf(softCappedB1, raw1, raw2, raw3, raw4)

        // Слежение за пиковой громкостью трека
        var currentMax = 0f
        for (i in 0 until BAND_COUNT) {
            if (raw[i] > currentMax) currentMax = raw[i]
        }

        if (currentMax > trackPeakEma) {
            trackPeakEma = currentMax // мгновенный захват при нарастании громкости
        }

        // Адаптивное усиление для спокойных/тихих композиций:
        val autoGain = (0.85f / max(0.18f, trackPeakEma)).coerceIn(1.0f, 2.5f)

        // Мягкая экспансия: на громких треках - честная квадратичная (2.0) для хлесткого панча бочки,
        // на тихих треках - мягкая (1.5), предотвращающая залипание столбиков на самом дне
        val dynamicExp = (2.0f - (autoGain - 1.0f) * 0.35f).coerceIn(1.45f, 2.0f)

        // Передача в баллистический движок Winamp Pro (атака 0 мс)
        for (i in 0 until BAND_COUNT) {
            val normalized = (raw[i] * autoGain).coerceIn(0f, 1f)
            val t = normalized.pow(dynamicExp).coerceIn(0f, 1f)
            targetLevels[i] = t

            if (t > currentLevels[i]) {
                currentLevels[i] = t
                velocities[i] = 0f
                holdTimers[i] = BAR_HOLD_SEC
            }

            if (t > peakCapLevels[i]) {
                peakCapLevels[i] = t
                peakCapVelocities[i] = 0f
                peakCapHoldTimers[i] = PEAK_CAP_HOLD_SEC
            }
        }

        // Если прямой 19-полосный спектр не поступает (например, fallback PCM), синтезируем 19 полос
        if (currentTime() - lastSpectrum19UpdateTimeMs > 350L) {
            val src5 = floatArrayOf(softCappedB1, raw1, raw2, raw3, raw4)
            for (k in 0 until SPECTRUM_BAND_COUNT) {
                val t = (k.toFloat() / (SPECTRUM_BAND_COUNT - 1)) * 4f
                val idx = t.toInt().coerceIn(0, 3)
                val frac = t - idx
                val smoothFrac = (1f - cos(frac * PI.toFloat())) / 2f
                val interp = src5[idx] * (1f - smoothFrac) + src5[idx + 1] * smoothFrac
                val norm = (interp * autoGain).coerceIn(0f, 1f)
                val lvl = norm.pow(dynamicExp).coerceIn(0f, 1f)
                targetSpectrumLevels[k] = lvl

                if (lvl > currentSpectrumLevels[k]) {
                    currentSpectrumLevels[k] = lvl
                    spectrumVelocities[k] = 0f
                    spectrumHoldTimers[k] = BAR_HOLD_SEC
                }

                if (lvl > peakCapSpectrumLevels[k]) {
                    peakCapSpectrumLevels[k] = lvl
                    peakCapSpectrumVelocities[k] = 0f
                    peakCapSpectrumHoldTimers[k] = PEAK_CAP_HOLD_SEC
                }
            }
        }

        lastUpdateTimeMs = currentTime()
        _levels.value = currentLevels.copyOf()
    }

    /**
     * 🎚 Обновить 19 аппаратных полос спектроанализатора Winamp Pro напрямую из FFT.
     * Помещает снимок в очередь компенсации задержки вывода звука.
     */
    fun updateSpectrum19(spectrum: FloatArray) {
        synchronized(currentLevels) {
            lastSpectrum19UpdateTimeMs = currentTime()
            val latency = latencyCompensationMs
            if (latency <= 0L) {
                applySpectrum19Direct(spectrum)
            } else {
                while (delayQueue19.size >= 50) {
                    delayQueue19.removeFirst()
                }
                delayQueue19.addLast(Spectrum19Snapshot(currentTime(), spectrum.copyOf()))
            }
        }
    }

    private fun applySpectrum19Direct(spectrum: FloatArray) {
        val count = minOf(spectrum.size, SPECTRUM_BAND_COUNT)
        var currentMax = 0f
        for (i in 0 until count) {
            if (spectrum[i] > currentMax) currentMax = spectrum[i]
        }
        if (currentMax > trackPeakEma) {
            trackPeakEma = currentMax
        }

        val autoGain = (0.85f / max(0.18f, trackPeakEma)).coerceIn(1.0f, 2.5f)
        val dynamicExp = (2.0f - (autoGain - 1.0f) * 0.35f).coerceIn(1.45f, 2.0f)

        for (i in 0 until count) {
            val rawVal = spectrum[i].coerceIn(0f, 1f)
            val softVal = if (i <= 1 && rawVal > 0.85f) 0.85f + (rawVal - 0.85f) * 0.35f else rawVal
            val normalized = (softVal * autoGain).coerceIn(0f, 1f)
            val t = normalized.pow(dynamicExp).coerceIn(0f, 1f)
            targetSpectrumLevels[i] = t

            if (t > currentSpectrumLevels[i]) {
                currentSpectrumLevels[i] = t
                spectrumVelocities[i] = 0f
                spectrumHoldTimers[i] = BAR_HOLD_SEC
            }

            if (t > peakCapSpectrumLevels[i]) {
                peakCapSpectrumLevels[i] = t
                peakCapSpectrumVelocities[i] = 0f
                peakCapSpectrumHoldTimers[i] = PEAK_CAP_HOLD_SEC
            }
        }

        lastUpdateTimeMs = currentTime()
    }

    /**
     * 📊 Обработать сырой 16-битный PCM-буфер (LE Stereo или Mono)
     */
    fun processPcmChunk(buffer: ByteArray, readBytes: Int) {
        if (readBytes < 4) return

        var sumKick = 0f
        var sumBass = 0f
        var sumMid = 0f
        var sumHighMid = 0f
        var sumTreble = 0f
        var sampleCount = 0

        var i = 0
        while (i + 1 < readBytes) {
            val s1 = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
            val sample = s1 / 32768f
            i += 2

            val mono = if (i + 1 < readBytes) {
                val s2 = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
                i += 2
                (sample + (s2 / 32768f)) * 0.5f
            } else {
                sample
            }

            // ✂️ Срез инфразвука (< 40 Гц): устраняет паразитный рокот и DC-смещение
            subCutFilter += ALPHA_SUBCUT * (mono - subCutFilter)
            val cleanMono = mono - subCutFilter

            // Каскад фильтров частотных полос
            kickFilter += ALPHA_KICK * (cleanMono - kickFilter)
            bassFilter += ALPHA_BASS * (cleanMono - bassFilter)
            midFilter += ALPHA_MID * (cleanMono - midFilter)
            highMidFilter += ALPHA_HIGH_MID * (cleanMono - highMidFilter)

            val kickEnergy = abs(kickFilter)
            val bassEnergy = abs(bassFilter - kickFilter)
            val midEnergy = abs(midFilter - bassFilter)
            val highMidEnergy = abs(highMidFilter - midFilter)
            val trebleEnergy = abs(cleanMono - highMidFilter)

            sumKick += kickEnergy
            sumBass += bassEnergy
            sumMid += midEnergy
            sumHighMid += highMidEnergy
            sumTreble += trebleEnergy
            sampleCount++
        }

        if (sampleCount == 0) return

        val avgKick = sumKick / sampleCount
        val avgBass = sumBass / sampleCount
        val avgMid = sumMid / sampleCount
        val avgHighMid = sumHighMid / sampleCount
        val avgTreble = sumTreble / sampleCount

        // Преамп-калибровка под кривую равной громкости:
        // Плотный упругий бас (бочка 3.0, бас-гитара 5.6)
        val b1 = (avgKick * 3.0f).coerceIn(0f, 1f)
        val b2 = (avgBass * 5.6f).coerceIn(0f, 1f)
        val b3 = (avgMid * 6.5f).coerceIn(0f, 1f)
        val b4 = (avgHighMid * 11.0f).coerceIn(0f, 1f)
        val b5 = (avgTreble * 17.5f).coerceIn(0f, 1f)

        updateBands(b1, b2, b3, b4, b5)
    }

    /**
     * ⏱ Рассчитать следующий кадр физики на основе реального времени dtSeconds (для 60 FPS UI)
     */
    fun advanceFrame(dtSeconds: Float, isPlaying: Boolean): VisualizerFrame {
        synchronized(currentLevels) {
            val safeDt = dtSeconds.coerceIn(0.001f, 0.5f)

            if (!isPlaying) {
                clearDelayQueue()
                // Плавное затухание в состояние покоя при остановке
                for (j in 0 until BAND_COUNT) {
                    val cur = currentLevels[j]
                    currentLevels[j] = if (cur > RESTING_LEVEL) {
                        max(RESTING_LEVEL, cur - (cur - RESTING_LEVEL) * (safeDt * 8f))
                    } else RESTING_LEVEL
                    peakCapLevels[j] = RESTING_LEVEL
                    velocities[j] = 0f
                    holdTimers[j] = 0f
                    peakCapVelocities[j] = 0f
                    peakCapHoldTimers[j] = 0f
                    targetLevels[j] = RESTING_LEVEL
                }
                val frame = VisualizerFrame(currentLevels.copyOf(), peakCapLevels.copyOf())
                _levels.value = frame.levels
                return frame
            }

            val now = currentTime()
            drainMatured5(now)
            val isDataRecent = (now - lastUpdateTimeMs) < (350L + latencyCompensationMs) || delayQueue5.isNotEmpty() || delayQueue19.isNotEmpty()

            // Медленный спад трекера пиковой громкости трека (время полураспада ~4.0 сек)
            val peakDecay = exp(-safeDt / 4.0f)
            trackPeakEma = max(0.18f, trackPeakEma * peakDecay)

            // Если реальных аудиоданных нет (пауза/буферизация) — генерируем мягкую органичную волну
            if (!isDataRecent) {
                val t = (now % 10000L).toFloat() / 1000f
                targetLevels[0] = RESTING_LEVEL + 0.55f * ((sin(t * 6.5f) + 1f) / 2f)
                targetLevels[1] = RESTING_LEVEL + 0.45f * ((sin(t * 7.5f + 1.2f) + 1f) / 2f)
                targetLevels[2] = RESTING_LEVEL + 0.65f * ((sin(t * 8.5f + 2.4f) + 1f) / 2f)
                targetLevels[3] = RESTING_LEVEL + 0.48f * ((sin(t * 9.8f + 3.6f) + 1f) / 2f)
                targetLevels[4] = RESTING_LEVEL + 0.60f * ((sin(t * 11.2f + 4.8f) + 1f) / 2f)
            }

            // ==================== РАСЧЕТ БАЛЛИСТИКИ СТОЛБИКОВ ====================
            for (i in 0 until BAND_COUNT) {
                val target = targetLevels[i]
                var current = currentLevels[i]

                if (target >= current) {
                    current = target
                    velocities[i] = 0f
                    holdTimers[i] = BAR_HOLD_SEC
                } else {
                    var remainingDt = safeDt
                    if (holdTimers[i] > 0f) {
                        if (remainingDt <= holdTimers[i]) {
                            holdTimers[i] -= remainingDt
                            remainingDt = 0f
                        } else {
                            remainingDt -= holdTimers[i]
                            holdTimers[i] = 0f
                        }
                    }

                    if (remainingDt > 0f) {
                        velocities[i] += BAR_GRAVITY * remainingDt
                        current = max(target, current - velocities[i] * remainingDt)
                    }
                }

                currentLevels[i] = current.coerceIn(RESTING_LEVEL, 1f)

                // ==================== РАСЧЕТ ПИКОВЫХ ЗАСЕЧЕК (PEAK CAPS) ====================
                var cap = peakCapLevels[i]
                if (target >= cap) {
                    cap = target
                    peakCapVelocities[i] = 0f
                    peakCapHoldTimers[i] = PEAK_CAP_HOLD_SEC
                } else {
                    var remainingCapDt = safeDt
                    if (peakCapHoldTimers[i] > 0f) {
                        if (remainingCapDt <= peakCapHoldTimers[i]) {
                            peakCapHoldTimers[i] -= remainingCapDt
                            remainingCapDt = 0f
                        } else {
                            remainingCapDt -= peakCapHoldTimers[i]
                            peakCapHoldTimers[i] = 0f
                        }
                    }

                    if (remainingCapDt > 0f) {
                        peakCapVelocities[i] += PEAK_CAP_GRAVITY * remainingCapDt
                        cap = max(currentLevels[i], cap - peakCapVelocities[i] * remainingCapDt)
                    }
                }
                peakCapLevels[i] = cap.coerceIn(RESTING_LEVEL, 1f)
            }

            val frame = VisualizerFrame(
                levels = currentLevels.copyOf(),
                peakCaps = peakCapLevels.copyOf()
            )
            _levels.value = frame.levels
            return frame
        }
    }

    /**
     * ⏱ Рассчитать следующий кадр физики 19 полос Winamp Pro (для спектра на обложке)
     */
    fun advanceSpectrum19Frame(dtSeconds: Float, isPlaying: Boolean): VisualizerFrame {
        synchronized(currentLevels) {
            val safeDt = dtSeconds.coerceIn(0.001f, 0.5f)

            if (!isPlaying) {
                clearDelayQueue()
                // Плавное затухание в состояние покоя при остановке
                for (j in 0 until SPECTRUM_BAND_COUNT) {
                    val cur = currentSpectrumLevels[j]
                    currentSpectrumLevels[j] = if (cur > RESTING_LEVEL) {
                        max(RESTING_LEVEL, cur - (cur - RESTING_LEVEL) * (safeDt * 8f))
                    } else RESTING_LEVEL
                    peakCapSpectrumLevels[j] = RESTING_LEVEL
                    spectrumVelocities[j] = 0f
                    spectrumHoldTimers[j] = 0f
                    peakCapSpectrumVelocities[j] = 0f
                    peakCapSpectrumHoldTimers[j] = 0f
                    targetSpectrumLevels[j] = RESTING_LEVEL
                }
                return VisualizerFrame(currentSpectrumLevels.copyOf(), peakCapSpectrumLevels.copyOf())
            }

            val now = currentTime()
            drainMatured19(now)
            if (delayQueue19.isEmpty()) {
                drainMatured5(now)
            }
            val isDataRecent = (now - lastUpdateTimeMs) < (350L + latencyCompensationMs) || delayQueue5.isNotEmpty() || delayQueue19.isNotEmpty()

            // Если реальных аудиоданных нет (пауза/буферизация) — генерируем мягкую органичную волну
            if (!isDataRecent) {
                val t = (now % 10000L).toFloat() / 1000f
                for (k in 0 until SPECTRUM_BAND_COUNT) {
                    val phase = k * 0.45f
                    val speed = 6.0f + k * 0.35f
                    val wave = (sin(t * speed + phase) + 1f) / 2f
                    val rollOff = 1f - 0.30f * (k.toFloat() / (SPECTRUM_BAND_COUNT - 1))
                    targetSpectrumLevels[k] = (RESTING_LEVEL + 0.55f * wave * rollOff).coerceIn(RESTING_LEVEL, 1f)
                }
            }

            // ==================== РАСЧЕТ БАЛЛИСТИКИ 19 СТОЛБИКОВ ====================
            for (i in 0 until SPECTRUM_BAND_COUNT) {
                val target = targetSpectrumLevels[i]
                var current = currentSpectrumLevels[i]

                if (target >= current) {
                    current = target
                    spectrumVelocities[i] = 0f
                    spectrumHoldTimers[i] = BAR_HOLD_SEC
                } else {
                    var remainingDt = safeDt
                    if (spectrumHoldTimers[i] > 0f) {
                        if (remainingDt <= spectrumHoldTimers[i]) {
                            spectrumHoldTimers[i] -= remainingDt
                            remainingDt = 0f
                        } else {
                            remainingDt -= spectrumHoldTimers[i]
                            spectrumHoldTimers[i] = 0f
                        }
                    }

                    if (remainingDt > 0f) {
                        spectrumVelocities[i] += BAR_GRAVITY * remainingDt
                        current = max(target, current - spectrumVelocities[i] * remainingDt)
                    }
                }

                currentSpectrumLevels[i] = current.coerceIn(RESTING_LEVEL, 1f)

                // ==================== РАСЧЕТ 19 ПИКОВЫХ ЗАСЕЧЕК (PEAK CAPS) ====================
                var cap = peakCapSpectrumLevels[i]
                if (target >= cap) {
                    cap = target
                    peakCapSpectrumVelocities[i] = 0f
                    peakCapSpectrumHoldTimers[i] = PEAK_CAP_HOLD_SEC
                } else {
                    var remainingCapDt = safeDt
                    if (peakCapSpectrumHoldTimers[i] > 0f) {
                        if (remainingCapDt <= peakCapSpectrumHoldTimers[i]) {
                            peakCapSpectrumHoldTimers[i] -= remainingCapDt
                            remainingCapDt = 0f
                        } else {
                            remainingCapDt -= peakCapSpectrumHoldTimers[i]
                            peakCapSpectrumHoldTimers[i] = 0f
                        }
                    }

                    if (remainingCapDt > 0f) {
                        peakCapSpectrumVelocities[i] += PEAK_CAP_GRAVITY * remainingCapDt
                        cap = max(currentSpectrumLevels[i], cap - peakCapSpectrumVelocities[i] * remainingCapDt)
                    }
                }
                peakCapSpectrumLevels[i] = cap.coerceIn(RESTING_LEVEL, 1f)
            }

            val frame = VisualizerFrame(
                levels = currentSpectrumLevels.copyOf(),
                peakCaps = peakCapSpectrumLevels.copyOf()
            )
            return frame
        }
    }

    /**
     * 🛑 Сброс уровней в состояние покоя при остановке или паузе
     */
    fun reset() {
        synchronized(currentLevels) {
            clearDelayQueue()
            for (j in 0 until BAND_COUNT) {
                targetLevels[j] = RESTING_LEVEL
                currentLevels[j] = RESTING_LEVEL
                peakCapLevels[j] = RESTING_LEVEL
                velocities[j] = 0f
                holdTimers[j] = 0f
                peakCapVelocities[j] = 0f
                peakCapHoldTimers[j] = 0f
            }
            for (j in 0 until SPECTRUM_BAND_COUNT) {
                targetSpectrumLevels[j] = RESTING_LEVEL
                currentSpectrumLevels[j] = RESTING_LEVEL
                peakCapSpectrumLevels[j] = RESTING_LEVEL
                spectrumVelocities[j] = 0f
                spectrumHoldTimers[j] = 0f
                peakCapSpectrumVelocities[j] = 0f
                peakCapSpectrumHoldTimers[j] = 0f
            }
            subCutFilter = 0f
            kickFilter = 0f
            bassFilter = 0f
            midFilter = 0f
            highMidFilter = 0f
            trackPeakEma = 0.5f
            resetTrackAudibility()
            val frame = FloatArray(BAND_COUNT) { RESTING_LEVEL }
            _levels.value = frame
        }
    }

    /**
     * 🌊 Получить текущий срез уровней (для обратной совместимости)
     */
    fun getLevelsForDisplay(isPlaying: Boolean): FloatArray {
        return advanceFrame(0.033f, isPlaying).levels
    }
}
