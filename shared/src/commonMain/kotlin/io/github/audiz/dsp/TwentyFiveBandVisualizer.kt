package io.github.audiz.dsp

import io.github.audiz.synchronized
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 🌊 25-полосный спектральный анализатор Winamp Pro для обложки альбома.
 *
 * Отвечает за:
 * - 25 спектральных полос (20 Гц .. 20.5 кГц)
 * - Трекинг спектрального потока (Spectral Flux) и адаптивную подстройку темпа (Pace Factor)
 * - Межполосную связность (Neighbor Cohesion)
 * - Поканальную автоматическую регулировку усиления (Per-band AGC): каждая полоса использует
 *   полную высоту относительно своей собственной недавней динамики
 * - Привязку к долям: трекер бита ([BeatTracker]) даёт общий импульс на каждую долю, а столбики
 *   рисуются как сглаженная форма спектра × огибающая импульса, поэтому вся линейка «дышит» в такт
 * - Баллистику, зависящую от темпа трека: сглаженная атака, удержание и экспоненциальный спад столбиков,
 *   гравитационное падение пиковых засечек
 * - Очередь компенсации задержки аудио (Latency Compensation)
 */
class TwentyFiveBandVisualizer(
    var timeProvider: () -> Long = { io.github.audiz.currentTimeMillis() }
) {
    companion object {
        const val SPECTRUM_BAND_COUNT = 25
        const val RESTING_LEVEL = VisualizerConstants.RESTING_LEVEL

        // Per-band AGC: доля локальной (поканальной) нормализации в итоговом уровне
        private const val AGC_MIX = 0.45f
        // Минимальный рабочий диапазон полосы: не даёт раздувать шум в тихих полосах
        private const val AGC_MIN_RANGE = 0.3f
        // Порог и ширина мягкого гейта локальной нормализации
        private const val AGC_GATE_START = 0.08f
        private const val AGC_GATE_WIDTH = 0.12f
        // Постоянные времени следящих огибающих (сек)
        private const val AGC_CEIL_ATTACK_SEC = 0.05f
        private const val AGC_CEIL_RELEASE_SEC = 3.5f
        private const val AGC_FLOOR_ATTACK_SEC = 0.4f
        private const val AGC_FLOOR_RELEASE_SEC = 2.5f
        private const val AGC_LOCAL_EXP = 1.25f

        // Форма спектра: медленно меняющийся контур, который модулируется импульсом доли
        private const val SHAPE_ATTACK_SEC = 0.06f
        private const val SHAPE_RELEASE_SEC = 0.30f
        // Глубина модуляции долей: базовая (без уверенного темпа) + добавка от уверенности
        private const val BEAT_DEPTH_BASE = 0.35f
        private const val BEAT_DEPTH_CONFIDENT = 0.35f
        // Насколько импульс доли поднимает столбик над формой спектра
        private const val BEAT_BOOST = 1.6f
        // Доля мгновенного спектра поверх модуляции: сохраняет мелкую живую детализацию
        private const val INSTANT_MIX = 0.15f
        // Итоговое усиление модулированной формы, чтобы удары доходили до верхней трети
        private const val OUTPUT_GAIN = 1.3f
        // Спад импульса доли как доля её длительности
        private const val BEAT_DECAY_PERIOD_RATIO = 0.32f
        private const val ACCENT_DECAY_SEC = 0.09f
        private const val BASS_BAND_END = 7
        private const val MIN_BPM_PACE = 70f
        private const val MAX_BPM_PACE = 160f
    }

    @Volatile
    var latencyCompensationMs: Long = 0L

    @Volatile
    var trackPeakEma: Float = 0.5f

    // --- Трекер темпа и динамики для пространственной связности полос ---
    private var currentPaceFactor: Float = 0.35f

    private var onsetDensityEma = 0f
    private var rmsEnergyEma = 0f
    private val prevRawSpectrum = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private var lastAnalysisTimeMs: Long = 0L

    // --- Per-band AGC: огибающие пола и потолка каждой полосы ---
    private val agcFloor = FloatArray(SPECTRUM_BAND_COUNT)
    private val agcCeil = FloatArray(SPECTRUM_BAND_COUNT)
    private var agcInitialized = false
    private val preparedSpectrum = FloatArray(SPECTRUM_BAND_COUNT)
    // Уровни до AGC: трекер темпа должен видеть реальную динамику, а не усиленные колебания
    private val globalSpectrum = FloatArray(SPECTRUM_BAND_COUNT)

    // --- Привязка к долям ---
    private val beatTracker = BeatTracker()
    private val shapeLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private var beatPulse = 0f
    private var accentPulse = 0f

    @Volatile
    var lastSpectrumUpdateTimeMs: Long = 0L
        private set

    @Volatile
    var lastUpdateTimeMs: Long = 0L
        private set

    // --- Баллистика, интерполированная по темпу (currentPaceFactor: 0 = медленно, 1 = быстро) ---
    private val barHoldSec: Float
        get() = lerp(VisualizerConstants.SLOW_BAR_HOLD_SEC, VisualizerConstants.FAST_BAR_HOLD_SEC, currentPaceFactor)
    private val barAttackSec: Float
        get() = lerp(VisualizerConstants.SLOW_BAR_ATTACK_SEC, VisualizerConstants.FAST_BAR_ATTACK_SEC, currentPaceFactor)
    private val barReleaseSec: Float
        get() = lerp(VisualizerConstants.SLOW_BAR_RELEASE_SEC, VisualizerConstants.FAST_BAR_RELEASE_SEC, currentPaceFactor)
    private val peakHoldSec: Float
        get() = lerp(VisualizerConstants.SLOW_PEAK_HOLD_SEC, VisualizerConstants.FAST_PEAK_HOLD_SEC, currentPaceFactor)
    private val peakGravity: Float
        get() = lerp(VisualizerConstants.SLOW_PEAK_GRAVITY, VisualizerConstants.FAST_PEAK_GRAVITY, currentPaceFactor)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    internal val paceFactor: Float
        get() = currentPaceFactor
    internal val detectedBpm: Float
        get() = beatTracker.bpm
    internal val beatConfidence: Float
        get() = beatTracker.confidence

    private class Spectrum25Snapshot(
        val timeMs: Long,
        val spectrum: FloatArray
    )

    private val delayQueue25 = ArrayDeque<Spectrum25Snapshot>()

    // Состояние 25 полос
    private val targetSpectrumLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private val currentSpectrumLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private val spectrumHoldTimers = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private val peakCapSpectrumLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private val peakCapSpectrumVelocities = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private val peakCapSpectrumHoldTimers = FloatArray(SPECTRUM_BAND_COUNT) { 0f }

    private fun currentTime(): Long = timeProvider()

    /**
     * 🛑 Очистить буфер задержки
     */
    fun clearDelayQueue() {
        synchronized(currentSpectrumLevels) {
            delayQueue25.clear()
        }
    }

    internal fun drainMatured25(now: Long) {
        val latency = latencyCompensationMs
        if (latency <= 0L) return
        synchronized(currentSpectrumLevels) {
            while (delayQueue25.isNotEmpty()) {
                val first = delayQueue25.firstOrNull() ?: break
                val age = now - first.timeMs
                if (age >= latency) {
                    delayQueue25.removeFirstOrNull() ?: break
                    if (age <= latency + 1000L) {
                        applySpectrumDirect(first.spectrum, first.timeMs)
                    }
                } else {
                    break
                }
            }
        }
    }

    /**
     * 🧠 Анализ спектрального потока (Spectral Flux) и плотности транзиентов (ударов)
     */
    private fun updateTempoTrackerFromSpectrum(normalizedBands: FloatArray, dtSec: Float) {
        var bassFlux = 0f
        var midHighFlux = 0f
        var totalEnergy = 0f

        for (i in 0 until SPECTRUM_BAND_COUNT) {
            val cur = normalizedBands[i]
            val delta = max(0f, cur - prevRawSpectrum[i])
            prevRawSpectrum[i] = cur
            totalEnergy += cur

            if (i <= 5) {
                bassFlux += delta
            } else {
                midHighFlux += delta
            }
        }

        val avgBassFlux = bassFlux / 6f
        val avgMidHighFlux = midHighFlux / (SPECTRUM_BAND_COUNT - 6).toFloat()
        val avgEnergy = totalEnergy / SPECTRUM_BAND_COUNT

        val rawFlux = max(0f, avgBassFlux * 1.65f + avgMidHighFlux * 0.85f - 0.025f)
        val instantOnsetVelocity = (rawFlux / (dtSec * 30f)).coerceIn(0f, 1.5f)

        val attackAlpha = 1f - exp(-dtSec / 0.18f)
        val releaseAlpha = 1f - exp(-dtSec / 1.40f)
        val fluxAlpha = if (instantOnsetVelocity > onsetDensityEma) attackAlpha else releaseAlpha
        onsetDensityEma += fluxAlpha * (instantOnsetVelocity - onsetDensityEma)

        val energyAlpha = 1f - exp(-dtSec / 0.80f)
        rmsEnergyEma += energyAlpha * (avgEnergy - rmsEnergyEma)

        val heuristicPace = (onsetDensityEma * 2.4f + rmsEnergyEma * 0.35f).coerceIn(0f, 1f)
        // При уверенном темпе скорость баллистики определяется реальным BPM
        val bpmPace = ((beatTracker.bpm - MIN_BPM_PACE) / (MAX_BPM_PACE - MIN_BPM_PACE)).coerceIn(0f, 1f)
        val targetPace = lerp(heuristicPace, bpmPace, beatTracker.confidence)
        val paceSmoothAlpha = if (targetPace > currentPaceFactor) {
            1f - exp(-dtSec / 0.22f)
        } else {
            1f - exp(-dtSec / 1.25f)
        }
        currentPaceFactor = (currentPaceFactor + paceSmoothAlpha * (targetPace - currentPaceFactor)).coerceIn(0f, 1f)
    }

    /**
     * 🌊 Межполосная связность: пластичная волна на плавных треках
     */
    private fun applyTargetsWithSpatialCohesion(prepared: FloatArray) {
        val neighborBlend = (1f - currentPaceFactor) * 0.16f
        for (i in 0 until SPECTRUM_BAND_COUNT) {
            val center = prepared[i]
            val left = if (i > 0) prepared[i - 1] else center
            val right = if (i < SPECTRUM_BAND_COUNT - 1) prepared[i + 1] else center
            val blended = center * (1f - neighborBlend * 2f) + (left + right) * neighborBlend
            targetSpectrumLevels[i] = blended.coerceIn(RESTING_LEVEL, 1f)
        }
    }

    /**
     * 🎚 Обновить полосы спектроанализатора напрямую из FFT.
     */
    fun updateSpectrum(spectrum: FloatArray) {
        synchronized(currentSpectrumLevels) {
            lastSpectrumUpdateTimeMs = currentTime()
            val latency = latencyCompensationMs
            if (latency <= 0L) {
                applySpectrumDirect(spectrum)
            } else {
                while (delayQueue25.size >= 50) {
                    delayQueue25.removeFirst()
                }
                delayQueue25.addLast(Spectrum25Snapshot(currentTime(), spectrum.copyOf()))
            }
        }
    }

    /**
     * 🎚 Per-band AGC: нормализует полосу относительно её собственных огибающих пола и потолка.
     * Медленные огибающие подстраиваются под динамику трека, поэтому удары в каждой полосе
     * используют почти полную высоту, а ровный фон полосы опускается вниз.
     */
    private fun applyBandAgc(i: Int, raw: Float, dtSec: Float): Float {
        if (!agcInitialized) {
            agcFloor[i] = 0f
            agcCeil[i] = max(raw, AGC_MIN_RANGE)
        }
        val ceilTau = if (raw > agcCeil[i]) AGC_CEIL_ATTACK_SEC else AGC_CEIL_RELEASE_SEC
        agcCeil[i] += (1f - exp(-dtSec / ceilTau)) * (raw - agcCeil[i])
        val floorTau = if (raw < agcFloor[i]) AGC_FLOOR_ATTACK_SEC else AGC_FLOOR_RELEASE_SEC
        agcFloor[i] += (1f - exp(-dtSec / floorTau)) * (raw - agcFloor[i])

        val range = max(agcCeil[i] - agcFloor[i], AGC_MIN_RANGE)
        val local = ((raw - agcFloor[i]) / range).coerceIn(0f, 1f)
        val gate = ((raw - AGC_GATE_START) / AGC_GATE_WIDTH).coerceIn(0f, 1f)
        return (local * gate).pow(AGC_LOCAL_EXP)
    }

    internal fun applySpectrumDirect(spectrum: FloatArray, timeMs: Long = currentTime()) {
        synchronized(currentSpectrumLevels) {
            val dtSec = if (lastAnalysisTimeMs > 0L) {
                ((timeMs - lastAnalysisTimeMs) / 1000f).coerceIn(0.005f, 0.25f)
            } else 0.033f
            lastAnalysisTimeMs = timeMs

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

            val inSize = spectrum.size
            val prepared = preparedSpectrum
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
                val global = normalized.pow(dynamicExp)
                globalSpectrum[i] = global
                val local = applyBandAgc(i, rawVal, dtSec)
                val t = (local * AGC_MIX + global * (1f - AGC_MIX)).coerceIn(0f, 1f)
                prepared[i] = t
            }
            agcInitialized = true

            updateTempoTrackerFromSpectrum(globalSpectrum, dtSec)
            applyTargetsWithSpatialCohesion(prepared)

            for (i in 0 until SPECTRUM_BAND_COUNT) {
                val instant = targetSpectrumLevels[i]
                val tau = if (instant > shapeLevels[i]) SHAPE_ATTACK_SEC else SHAPE_RELEASE_SEC
                shapeLevels[i] += (1f - exp(-dtSec / tau)) * (instant - shapeLevels[i])
            }

            val beatStrength = beatTracker.process(globalSpectrum, dtSec)
            if (beatStrength > 0f) beatPulse = max(beatPulse, beatStrength)
            accentPulse = max(accentPulse, beatTracker.lastOnsetStrength)

            lastSpectrumUpdateTimeMs = currentTime()
            lastUpdateTimeMs = currentTime()
        }
    }

    /**
     * ⏱ Рассчитать следующий кадр физики 25 полос спектроанализатора
     */
    fun advanceFrame(dtSeconds: Float, isPlaying: Boolean): VisualizerFrame {
        synchronized(currentSpectrumLevels) {
            val safeDt = dtSeconds.coerceIn(0.001f, 0.5f)

            if (!isPlaying) {
                clearDelayQueue()
                for (j in 0 until SPECTRUM_BAND_COUNT) {
                    val cur = currentSpectrumLevels[j]
                    currentSpectrumLevels[j] = if (cur > RESTING_LEVEL) {
                        max(RESTING_LEVEL, cur - (cur - RESTING_LEVEL) * (safeDt * 8f))
                    } else RESTING_LEVEL
                    peakCapSpectrumLevels[j] = RESTING_LEVEL
                    spectrumHoldTimers[j] = 0f
                    peakCapSpectrumVelocities[j] = 0f
                    peakCapSpectrumHoldTimers[j] = 0f
                    targetSpectrumLevels[j] = RESTING_LEVEL
                    shapeLevels[j] = RESTING_LEVEL
                }
                beatPulse = 0f
                accentPulse = 0f
                return VisualizerFrame(currentSpectrumLevels.copyOf(), peakCapSpectrumLevels.copyOf())
            }

            val now = currentTime()
            drainMatured25(now)
            val isDataRecent = (lastUpdateTimeMs > 0L && (now - lastUpdateTimeMs) < (350L + latencyCompensationMs)) ||
                delayQueue25.isNotEmpty()

            val peakDecay = exp(-safeDt / 4.0f)
            trackPeakEma = max(0.18f, trackPeakEma * peakDecay)

            if (!isDataRecent) {
                for (k in 0 until SPECTRUM_BAND_COUNT) {
                    targetSpectrumLevels[k] = RESTING_LEVEL
                    shapeLevels[k] = RESTING_LEVEL
                }
                beatPulse = 0f
                accentPulse = 0f
            }

            beatPulse *= exp(-safeDt / max(0.06f, beatTracker.beatPeriodSec * BEAT_DECAY_PERIOD_RATIO))
            accentPulse *= exp(-safeDt / ACCENT_DECAY_SEC)
            val depth = BEAT_DEPTH_BASE + BEAT_DEPTH_CONFIDENT * beatTracker.confidence

            val holdSec = barHoldSec
            val releaseSec = barReleaseSec
            val attackSec = barAttackSec
            val capHoldSec = peakHoldSec
            val capGravity = peakGravity

            for (i in 0 until SPECTRUM_BAND_COUNT) {
                // Бас идёт строго по долям, середина и верх дополнительно откликаются на акценты
                val envelope = if (i <= BASS_BAND_END) beatPulse else max(beatPulse * 0.85f, accentPulse * 0.8f)
                val pulsed = OUTPUT_GAIN * shapeLevels[i] * ((1f - depth) + depth * BEAT_BOOST * envelope)
                val target = (pulsed * (1f - INSTANT_MIX) + targetSpectrumLevels[i] * INSTANT_MIX)
                    .coerceIn(RESTING_LEVEL, 1f)
                var current = currentSpectrumLevels[i]

                if (target >= current) {
                    current += (target - current) * (1f - exp(-safeDt / attackSec))
                    spectrumHoldTimers[i] = holdSec
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
                        current = target + (current - target) * exp(-remainingDt / releaseSec)
                    }
                }

                currentSpectrumLevels[i] = current.coerceIn(RESTING_LEVEL, 1f)

                var cap = peakCapSpectrumLevels[i]
                if (currentSpectrumLevels[i] >= cap) {
                    cap = currentSpectrumLevels[i]
                    peakCapSpectrumVelocities[i] = 0f
                    peakCapSpectrumHoldTimers[i] = capHoldSec
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
                        peakCapSpectrumVelocities[i] += capGravity * remainingCapDt
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

    /**
     * 🛑 Сброс уровней в состояние покоя
     */
    fun reset() {
        synchronized(currentSpectrumLevels) {
            clearDelayQueue()
            for (j in 0 until SPECTRUM_BAND_COUNT) {
                targetSpectrumLevels[j] = RESTING_LEVEL
                currentSpectrumLevels[j] = RESTING_LEVEL
                spectrumHoldTimers[j] = 0f
                peakCapSpectrumLevels[j] = RESTING_LEVEL
                peakCapSpectrumVelocities[j] = 0f
                peakCapSpectrumHoldTimers[j] = 0f
                prevRawSpectrum[j] = 0f
                agcFloor[j] = 0f
                agcCeil[j] = 0f
            }
            agcInitialized = false
            shapeLevels.fill(RESTING_LEVEL)
            beatTracker.reset()
            beatPulse = 0f
            accentPulse = 0f
            trackPeakEma = 0.5f
            currentPaceFactor = 0.35f
            onsetDensityEma = 0f
            rmsEnergyEma = 0f
            lastAnalysisTimeMs = 0L
            lastSpectrumUpdateTimeMs = 0L
            lastUpdateTimeMs = 0L
        }
    }
}
