package io.github.audiz.dsp

import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import io.github.audiz.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

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
 * 🎵 Адаптивный движок спектрального анализа и баллистики Winamp Pro в реальном времени.
 *
 * Поддерживает два режима:
 * 1. 5 полос (BAND_COUNT = 5) — для ультракомпактных микро-индикаторов (NowPlayingIndicator 16x16 dp)
 * 2. 25 полос (SPECTRUM_BAND_COUNT = 25) — спектроанализатор (20 Гц .. 20 кГц) для обложки альбома
 *
 * Особенности:
 * - Студийная калибровка Winamp Pro с плотным упругим басом
 * - Адаптивный детектор темпа и динамики (Onset / Spectral Flux Tracker):
 *   плавное обволакивающее движение на медленных вступлениях и хлесткий панч на быстром темпе/дропе
 * - Частотно-зависимая баллистика (высокие частоты осыпаются быстрее и легче тяжелого саб-баса)
 * - Адаптивная чувствительность (Dynamic Range Tracking)
 * - Срез инфразвука (< 40 Гц) и разделение бочки и баса (Anti-Bleed Decoupling)
 */
object AudioVisualizer {

    const val BAND_COUNT = 5
    const val SPECTRUM_BAND_COUNT = 25
    const val RESTING_LEVEL = 0.15f

    // Базовые границы адаптивной физики (Медленный темп <-> Быстрый темп)
    private const val SLOW_BAR_HOLD_SEC = 0.095f    // 95 мс мягкое зависание на плавных частях
    private const val FAST_BAR_HOLD_SEC = 0.030f    // 30 мс четкая отсечка на быстром ритме
    private const val SLOW_BAR_GRAVITY = 1.85f      // Тягучее плавное падение на интро/эмбиенте
    private const val FAST_BAR_GRAVITY = 6.40f      // Хлесткий возврат на быстром темпе/дропе

    private const val SLOW_PEAK_HOLD_SEC = 0.380f   // Долгое парение засечек на медленном темпе
    private const val FAST_PEAK_HOLD_SEC = 0.210f   // Быстрый сброс засечек на активном бите
    private const val SLOW_PEAK_GRAVITY = 1.60f
    private const val FAST_PEAK_GRAVITY = 3.60f

    // Трекер пиковой громкости трека
    @Volatile
    private var trackPeakEma = 0.5f

    // --- Трекер темпа и динамики (Adaptive Tempo / Pace Engine) ---
    // 0.0f = спокойное плавное течение (интро/эмбиент), 1.0f = быстрый/интенсивный ритм (дроп/быстрый бит)
    @Volatile
    var currentPaceFactor: Float = 0.35f
        private set

    private var onsetDensityEma = 0f
    private var rmsEnergyEma = 0f
    private val prevRawSpectrum = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private val prevRaw5 = FloatArray(BAND_COUNT) { 0f }
    private var lastFluxTimeMs: Long = 0L

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

    // --- 25 полос спектрального анализатора для обложки альбома ---
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
    private val pcmLock = Any()
    @Volatile
    private var subCutFilter: Float = 0f
    @Volatile
    private var kickFilter: Float = 0f
    @Volatile
    private var bassFilter: Float = 0f
    @Volatile
    private var midFilter: Float = 0f
    @Volatile
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
     * 🧠 Анализ спектрального потока (Spectral Flux) и плотности транзиентов (ударов)
     * Вычисляет currentPaceFactor (0.0 - медленный/плавный, 1.0 - быстрый/ритмичный)
     */
    private fun updateTempoTrackerFromSpectrum(normalizedBands: FloatArray) {
        val now = currentTime()
        val dtSec = if (lastFluxTimeMs > 0L) ((now - lastFluxTimeMs) / 1000f).coerceIn(0.005f, 0.25f) else 0.033f
        lastFluxTimeMs = now

        var bassFlux = 0f
        var midHighFlux = 0f
        var totalEnergy = 0f

        for (i in 0 until SPECTRUM_BAND_COUNT) {
            val cur = normalizedBands[i]
            val delta = max(0f, cur - prevRawSpectrum[i])
            prevRawSpectrum[i] = cur
            totalEnergy += cur

            // Особый фокус на бочку/бас (0..5) и снейр/хэты (6..24)
            if (i <= 5) {
                bassFlux += delta
            } else {
                midHighFlux += delta
            }
        }

        val avgBassFlux = bassFlux / 6f
        val avgMidHighFlux = midHighFlux / (SPECTRUM_BAND_COUNT - 6).toFloat()
        val avgEnergy = totalEnergy / SPECTRUM_BAND_COUNT

        // Положительный скачок энергии с отсечкой мелкого шума
        val rawFlux = max(0f, avgBassFlux * 1.65f + avgMidHighFlux * 0.85f - 0.025f)
        // Нормализуем относительно dt, чтобы частота вызовов не искажала оценку темпа
        val instantOnsetVelocity = (rawFlux / (dtSec * 30f)).coerceIn(0f, 1.5f)

        // Быстрый подхват при начале бита (~180 мс) и плавное остывание при переходе в яму (~1.4 сек)
        val attackAlpha = 1f - exp(-dtSec / 0.18f)
        val releaseAlpha = 1f - exp(-dtSec / 1.40f)
        val fluxAlpha = if (instantOnsetVelocity > onsetDensityEma) attackAlpha else releaseAlpha
        onsetDensityEma += fluxAlpha * (instantOnsetVelocity - onsetDensityEma)

        val energyAlpha = 1f - exp(-dtSec / 0.80f)
        rmsEnergyEma += energyAlpha * (avgEnergy - rmsEnergyEma)

        // Итоговый коэффициент темпа: на 75% зависит от резкости/частоты ударов и на 25% от плотности звука
        val targetPace = (onsetDensityEma * 2.4f + rmsEnergyEma * 0.35f).coerceIn(0f, 1f)
        val paceSmoothAlpha = if (targetPace > currentPaceFactor) {
            1f - exp(-dtSec / 0.22f) // Быстрый переход в драйв на дропе
        } else {
            1f - exp(-dtSec / 1.25f) // Красивое, плавное успокоение на бридже/аутро
        }
        currentPaceFactor = (currentPaceFactor + paceSmoothAlpha * (targetPace - currentPaceFactor)).coerceIn(0f, 1f)
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
        synchronized(currentLevels) {
            val raw0 = b1.coerceIn(0f, 1f)
            val softCappedB1 = if (raw0 > 0.85f) 0.85f + (raw0 - 0.85f) * 0.35f else raw0
            val raw1 = b2.coerceIn(0f, 1f)
            val raw2 = b3.coerceIn(0f, 1f)
            val raw3 = b4.coerceIn(0f, 1f)
            val raw4 = b5.coerceIn(0f, 1f)

            val raw = floatArrayOf(softCappedB1, raw1, raw2, raw3, raw4)

            var currentMax = 0f
            for (i in 0 until BAND_COUNT) {
                if (raw[i] > currentMax) currentMax = raw[i]
            }

            if (currentMax > trackPeakEma) {
                trackPeakEma = currentMax
            }

            val autoGain = (0.85f / max(0.18f, trackPeakEma)).coerceIn(1.0f, 2.5f)
            // На медленном темпе кривая мягче (1.45..1.65), на быстром бите — контрастнее (до 2.05) для панча
            val baseExp = (1.60f + currentPaceFactor * 0.45f) - (autoGain - 1.0f) * 0.30f
            val dynamicExp = baseExp.coerceIn(1.40f, 2.05f)

            for (i in 0 until BAND_COUNT) {
                val normalized = (raw[i] * autoGain).coerceIn(0f, 1f)
                val t = normalized.pow(dynamicExp).coerceIn(0f, 1f)
                prevRaw5[i] = t
                targetLevels[i] = t
            }

            // Если прямой 25-полосный спектр не поступает (например, fallback PCM), синтезируем 25 полос
            if (currentTime() - lastSpectrum19UpdateTimeMs > 350L) {
                val synthesized = FloatArray(SPECTRUM_BAND_COUNT)
                for (k in 0 until SPECTRUM_BAND_COUNT) {
                    val t = (k.toFloat() / (SPECTRUM_BAND_COUNT - 1)) * 4f
                    val idx = t.toInt().coerceIn(0, 3)
                    val frac = t - idx
                    val smoothFrac = (1f - cos(frac * PI.toFloat())) / 2f
                    val interp = raw[idx] * (1f - smoothFrac) + raw[idx + 1] * smoothFrac
                    val norm = (interp * autoGain).coerceIn(0f, 1f)
                    synthesized[k] = norm.pow(dynamicExp).coerceIn(0f, 1f)
                }
                updateTempoTrackerFromSpectrum(synthesized)
                applyTargetsWithSpatialCohesion(synthesized)
            }

            lastUpdateTimeMs = currentTime()
            _levels.value = currentLevels.copyOf()
        }
    }

    /**
     * 🎚 Обновить 25 аппаратных полос спектроанализатора напрямую из FFT.
     * Помещает снимок в очередь компенсации задержки вывода звука.
     */
    fun updateSpectrum(spectrum: FloatArray) {
        synchronized(currentLevels) {
            lastSpectrum19UpdateTimeMs = currentTime()
            val latency = latencyCompensationMs
            if (latency <= 0L) {
                applySpectrumDirect(spectrum)
            } else {
                while (delayQueue19.size >= 50) {
                    delayQueue19.removeFirst()
                }
                delayQueue19.addLast(Spectrum19Snapshot(currentTime(), spectrum.copyOf()))
            }
        }
    }

    fun updateSpectrum19(spectrum: FloatArray) = updateSpectrum(spectrum)

    private fun applySpectrumDirect(spectrum: FloatArray) {
        synchronized(currentLevels) {
            val count = minOf(spectrum.size, SPECTRUM_BAND_COUNT)
            var currentMax = 0f
            for (i in 0 until count) {
                if (spectrum[i] > currentMax) currentMax = spectrum[i]
            }
            if (currentMax > trackPeakEma) {
                trackPeakEma = currentMax
            }

            val autoGain = (0.85f / max(0.18f, trackPeakEma)).coerceIn(1.0f, 2.5f)
            val baseExp = (1.60f + currentPaceFactor * 0.45f) - (autoGain - 1.0f) * 0.30f
            val dynamicExp = baseExp.coerceIn(1.40f, 2.05f)

            val inSize = spectrum.size
            val prepared = FloatArray(SPECTRUM_BAND_COUNT)

            for (i in 0 until SPECTRUM_BAND_COUNT) {
                val rawVal = if (inSize == SPECTRUM_BAND_COUNT) {
                    spectrum[i].coerceIn(0f, 1f)
                } else if (inSize > 0) {
                    val progress = (i.toFloat() / (SPECTRUM_BAND_COUNT - 1)) * (inSize - 1)
                    val idx = progress.toInt().coerceIn(0, inSize - 1)
                    val frac = progress - idx
                    val v1 = spectrum[idx]
                    val v2 = if (idx + 1 < inSize) spectrum[idx + 1] else v1
                    (v1 * (1f - frac) + v2 * frac).coerceIn(0f, 1f)
                } else {
                    RESTING_LEVEL
                }
                val softVal = if (i <= 2 && rawVal > 0.85f) 0.85f + (rawVal - 0.85f) * 0.35f else rawVal
                val normalized = (softVal * autoGain).coerceIn(0f, 1f)
                prepared[i] = normalized.pow(dynamicExp).coerceIn(0f, 1f)
            }

            // 1. Обновляем трекер темпа и переходов по свежим данным спектра
            updateTempoTrackerFromSpectrum(prepared)

            // 2. Применяем пространственную связность (на медленном темпе соседние полосы мягко поддерживают друг друга)
            applyTargetsWithSpatialCohesion(prepared)

            lastUpdateTimeMs = currentTime()
        }
    }

    /**
     * 🌊 На медленных/плавных участках слегка связывает соседние полосы для пластичной волны,
     * а на быстром темпе оставляет 100% дискретную резкость каждой полосы.
     */
    private fun applyTargetsWithSpatialCohesion(prepared: FloatArray) {
        val neighborBlend = (1f - currentPaceFactor) * 0.16f // до 16% влияния соседей на самом плавном интро
        for (i in 0 until SPECTRUM_BAND_COUNT) {
            val center = prepared[i]
            val left = if (i > 0) prepared[i - 1] else center
            val right = if (i < SPECTRUM_BAND_COUNT - 1) prepared[i + 1] else center
            val blended = center * (1f - neighborBlend * 2f) + (left + right) * neighborBlend
            targetSpectrumLevels[i] = blended.coerceIn(RESTING_LEVEL, 1f)
        }
    }

    private fun applySpectrum19Direct(spectrum: FloatArray) = applySpectrumDirect(spectrum)

    /**
     * 📊 Обработать сырой 16-битный PCM-буфер (LE Stereo или Mono)
     */
    fun processPcmChunk(buffer: ByteArray, readBytes: Int) {
        if (readBytes < 4) return

        var b1 = 0f
        var b2 = 0f
        var b3 = 0f
        var b4 = 0f
        var b5 = 0f

        synchronized(pcmLock) {
            var sumKick = 0f
            var sumBass = 0f
            var sumMid = 0f
            var sumHighMid = 0f
            var sumTreble = 0f
            var sampleCount = 0

            var i = 0
            var localSubCut = subCutFilter
            var localKick = kickFilter
            var localBass = bassFilter
            var localMid = midFilter
            var localHighMid = highMidFilter

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
                localSubCut += ALPHA_SUBCUT * (mono - localSubCut)
                val cleanMono = mono - localSubCut

                // Каскад фильтров частотных полос
                localKick += ALPHA_KICK * (cleanMono - localKick)
                localBass += ALPHA_BASS * (cleanMono - localBass)
                localMid += ALPHA_MID * (cleanMono - localMid)
                localHighMid += ALPHA_HIGH_MID * (cleanMono - localHighMid)

                val kickEnergy = abs(localKick)
                val bassEnergy = abs(localBass - localKick)
                val midEnergy = abs(localMid - localBass)
                val highMidEnergy = abs(localHighMid - localMid)
                val trebleEnergy = abs(cleanMono - localHighMid)

                sumKick += kickEnergy
                sumBass += bassEnergy
                sumMid += midEnergy
                sumHighMid += highMidEnergy
                sumTreble += trebleEnergy
                sampleCount++
            }

            subCutFilter = localSubCut
            kickFilter = localKick
            bassFilter = localBass
            midFilter = localMid
            highMidFilter = localHighMid

            if (sampleCount == 0) return

            val avgKick = sumKick / sampleCount
            val avgBass = sumBass / sampleCount
            val avgMid = sumMid / sampleCount
            val avgHighMid = sumHighMid / sampleCount
            val avgTreble = sumTreble / sampleCount

            // Преамп-калибровка под кривую равной громкости:
            b1 = (avgKick * 3.0f).coerceIn(0f, 1f)
            b2 = (avgBass * 5.6f).coerceIn(0f, 1f)
            b3 = (avgMid * 6.5f).coerceIn(0f, 1f)
            b4 = (avgHighMid * 11.0f).coerceIn(0f, 1f)
            b5 = (avgTreble * 17.5f).coerceIn(0f, 1f)
        }

        updateBands(b1, b2, b3, b4, b5)
    }

    /**
     * ⏱ Рассчитать следующий кадр физики 5 полос на основе реального времени dtSeconds (для 60 FPS UI)
     */
    fun advanceFrame(dtSeconds: Float, isPlaying: Boolean): VisualizerFrame {
        synchronized(currentLevels) {
            val safeDt = dtSeconds.coerceIn(0.001f, 0.5f)

            if (!isPlaying) {
                clearDelayQueue()
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

            val peakDecay = exp(-safeDt / 4.0f)
            trackPeakEma = max(0.18f, trackPeakEma * peakDecay)

            if (!isDataRecent) {
                val t = (now % 10000L).toFloat() / 1000f
                targetLevels[0] = RESTING_LEVEL + 0.55f * ((sin(t * 6.5f) + 1f) / 2f)
                targetLevels[1] = RESTING_LEVEL + 0.45f * ((sin(t * 7.5f + 1.2f) + 1f) / 2f)
                targetLevels[2] = RESTING_LEVEL + 0.65f * ((sin(t * 8.5f + 2.4f) + 1f) / 2f)
                targetLevels[3] = RESTING_LEVEL + 0.48f * ((sin(t * 9.8f + 3.6f) + 1f) / 2f)
                targetLevels[4] = RESTING_LEVEL + 0.60f * ((sin(t * 11.2f + 4.8f) + 1f) / 2f)
            }

            val pace = currentPaceFactor
            val dynamicHold = SLOW_BAR_HOLD_SEC + (FAST_BAR_HOLD_SEC - SLOW_BAR_HOLD_SEC) * pace
            val dynamicGravity = SLOW_BAR_GRAVITY + (FAST_BAR_GRAVITY - SLOW_BAR_GRAVITY) * pace
            val dynamicPeakHold = SLOW_PEAK_HOLD_SEC + (FAST_PEAK_HOLD_SEC - SLOW_PEAK_HOLD_SEC) * pace
            val dynamicPeakGravity = SLOW_PEAK_GRAVITY + (FAST_PEAK_GRAVITY - SLOW_PEAK_GRAVITY) * pace

            // Скорость атаки: на медленном темпе (~14/с) плавная, на быстром — мгновенная
            val riseSpeed = 14f + pace * 65f
            val attackBlend = if (pace > 0.78f) 1f else (safeDt * riseSpeed).coerceIn(0.15f, 1f)

            for (i in 0 until BAND_COUNT) {
                val target = targetLevels[i]
                var current = currentLevels[i]

                if (target >= current) {
                    current += (target - current) * attackBlend
                    velocities[i] = 0f
                    holdTimers[i] = dynamicHold
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
                        velocities[i] += dynamicGravity * remainingDt
                        current = max(target, current - velocities[i] * remainingDt)
                    }
                }

                currentLevels[i] = current.coerceIn(RESTING_LEVEL, 1f)

                var cap = peakCapLevels[i]
                if (currentLevels[i] >= cap) {
                    cap = currentLevels[i]
                    peakCapVelocities[i] = 0f
                    peakCapHoldTimers[i] = dynamicPeakHold
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
                        peakCapVelocities[i] += dynamicPeakGravity * remainingCapDt
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
     * ⏱ Рассчитать следующий кадр физики 25 полос спектроанализатора (для спектра на обложке)
     */
    fun advanceSpectrumFrame(dtSeconds: Float, isPlaying: Boolean): VisualizerFrame {
        synchronized(currentLevels) {
            val safeDt = dtSeconds.coerceIn(0.001f, 0.5f)

            if (!isPlaying) {
                clearDelayQueue()
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

            // Поддерживаем плавный спад трекера громкости, даже если 5-полосный индикатор не активен
            val peakDecay = exp(-safeDt / 4.0f)
            trackPeakEma = max(0.18f, trackPeakEma * peakDecay)

            if (!isDataRecent) {
                // Плавное затухание темпа в режим спокойной волны при отсутствии данных
                currentPaceFactor = max(0.15f, currentPaceFactor - safeDt * 0.4f)
                val t = (now % 10000L).toFloat() / 1000f
                for (k in 0 until SPECTRUM_BAND_COUNT) {
                    val phase = k * 0.45f
                    val speed = 6.0f + k * 0.35f
                    val wave = (sin(t * speed + phase) + 1f) / 2f
                    val rollOff = 1f - 0.30f * (k.toFloat() / (SPECTRUM_BAND_COUNT - 1))
                    targetSpectrumLevels[k] = (RESTING_LEVEL + 0.55f * wave * rollOff).coerceIn(RESTING_LEVEL, 1f)
                }
            }

            val pace = currentPaceFactor

            // Базовые параметры баллистики для текущего темпа трека
            val baseHold = SLOW_BAR_HOLD_SEC + (FAST_BAR_HOLD_SEC - SLOW_BAR_HOLD_SEC) * pace
            val baseGravity = SLOW_BAR_GRAVITY + (FAST_BAR_GRAVITY - SLOW_BAR_GRAVITY) * pace
            val basePeakHold = SLOW_PEAK_HOLD_SEC + (FAST_PEAK_HOLD_SEC - SLOW_PEAK_HOLD_SEC) * pace
            val basePeakGravity = SLOW_PEAK_GRAVITY + (FAST_PEAK_GRAVITY - SLOW_PEAK_GRAVITY) * pace

            // Адаптивная атака:
            // На медленном вступлении (pace ~ 0.0..0.3) столбики мягко "выплывают" вверх без дерганья.
            // На разгоне и быстром ритме (pace > 0.75) включается мгновенная атака 0 мс (Winamp Pro punch).
            val riseSpeed = 12f + pace.pow(1.4f) * 75f
            val baseAttackAlpha = if (pace > 0.78f) 1.0f else (1f - exp(-safeDt * riseSpeed)).coerceIn(0.12f, 1.0f)

            // ==================== РАСЧЕТ АДАПТИВНОЙ БАЛЛИСТИКИ 25 СТОЛБИКОВ ====================
            for (i in 0 until SPECTRUM_BAND_COUNT) {
                val freqRatio = i.toFloat() / (SPECTRUM_BAND_COUNT - 1) // 0.0 (Sub-bass) .. 1.0 (Air/Treble)

                // Высокие частоты осыпаются легче и быстрее (+35% к гравитации наверху),
                // а басовые полосы сохраняют благородную массивность
                val freqGravityMult = 0.90f + 0.40f * freqRatio
                val bandGravity = baseGravity * freqGravityMult
                val bandHold = baseHold * (1.08f - 0.25f * freqRatio)

                // Высокие частоты чуть отзывчивее на атаку даже в медленном темпе
                val bandAttack = min(1.0f, baseAttackAlpha * (0.92f + 0.25f * freqRatio))

                val target = targetSpectrumLevels[i]
                var current = currentSpectrumLevels[i]

                if (target >= current) {
                    val diff = target - current
                    // Если прилетел сильный акцентированный удар (транзиент бочки/снейра > 0.28),
                    // пробиваем плавность для сохранения музыкального акцента
                    val transientBoost = if (diff > 0.28f) min(1.0f, bandAttack + 0.45f) else bandAttack
                    current += diff * transientBoost
                    spectrumVelocities[i] = 0f
                    spectrumHoldTimers[i] = bandHold
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
                        spectrumVelocities[i] += bandGravity * remainingDt
                        current = max(target, current - spectrumVelocities[i] * remainingDt)
                    }
                }

                currentSpectrumLevels[i] = current.coerceIn(RESTING_LEVEL, 1f)

                // ==================== РАСЧЕТ ПИКОВЫХ ЗАСЕЧЕК (PEAK CAPS) ====================
                var cap = peakCapSpectrumLevels[i]
                if (currentSpectrumLevels[i] >= cap) {
                    cap = currentSpectrumLevels[i]
                    peakCapSpectrumVelocities[i] = 0f
                    peakCapSpectrumHoldTimers[i] = basePeakHold * (1.05f - 0.20f * freqRatio)
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
                        peakCapSpectrumVelocities[i] += (basePeakGravity * freqGravityMult) * remainingCapDt
                        cap = max(currentSpectrumLevels[i], cap - peakCapSpectrumVelocities[i] * remainingCapDt)
                    }
                }
                peakCapSpectrumLevels[i] = cap.coerceIn(RESTING_LEVEL, 1f)
            }

            return VisualizerFrame(
                levels = currentSpectrumLevels.copyOf(),
                peakCaps = peakCapSpectrumLevels.copyOf()
            )
        }
    }

    fun advanceSpectrum19Frame(dtSeconds: Float, isPlaying: Boolean): VisualizerFrame =
        advanceSpectrumFrame(dtSeconds, isPlaying)

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
                prevRaw5[j] = 0f
            }
            for (j in 0 until SPECTRUM_BAND_COUNT) {
                targetSpectrumLevels[j] = RESTING_LEVEL
                currentSpectrumLevels[j] = RESTING_LEVEL
                peakCapSpectrumLevels[j] = RESTING_LEVEL
                spectrumVelocities[j] = 0f
                spectrumHoldTimers[j] = 0f
                peakCapSpectrumVelocities[j] = 0f
                peakCapSpectrumHoldTimers[j] = 0f
                prevRawSpectrum[j] = 0f
            }
            synchronized(pcmLock) {
                subCutFilter = 0f
                kickFilter = 0f
                bassFilter = 0f
                midFilter = 0f
                highMidFilter = 0f
            }
            trackPeakEma = 0.5f
            currentPaceFactor = 0.35f
            onsetDensityEma = 0f
            rmsEnergyEma = 0f
            lastFluxTimeMs = 0L
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
