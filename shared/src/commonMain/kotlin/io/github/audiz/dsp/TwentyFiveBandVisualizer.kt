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
 * - 25 аппаратных спектральных полос (20 Гц .. 20 кГц)
 * - Трекинг спектрального потока (Spectral Flux) и адаптивную подстройку темпа (Pace Factor)
 * - Межполосную связность (Neighbor Cohesion)
 * - Студийную баллистику Winamp Pro (Hold + Gravity)
 * - Синтез 25 полос из 5 полос при отсутствии прямого FFT
 * - Очередь компенсации задержки аудио (Latency Compensation)
 */
class TwentyFiveBandVisualizer(
    var timeProvider: () -> Long = { io.github.audiz.currentTimeMillis() }
) {
    companion object {
        const val SPECTRUM_BAND_COUNT = 25
        const val RESTING_LEVEL = VisualizerConstants.RESTING_LEVEL
    }

    @Volatile
    var latencyCompensationMs: Long = 0L

    @Volatile
    var trackPeakEma: Float = 0.5f

    // --- Трекер темпа и динамики (Adaptive Tempo / Pace Engine) ---
    @Volatile
    var currentPaceFactor: Float = 0.35f
        private set

    @Volatile
    var spectralCentroidEma: Float = 0.42f
        private set

    private var onsetDensityEma = 0f
    private var rmsEnergyEma = 0f
    private val prevRawSpectrum = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private var lastFluxTimeMs: Long = 0L

    @Volatile
    var lastSpectrumUpdateTimeMs: Long = 0L
        private set

    @Volatile
    var lastUpdateTimeMs: Long = 0L
        private set

    private class Spectrum19Snapshot(
        val timeMs: Long,
        val spectrum: FloatArray
    )

    private val delayQueue19 = ArrayDeque<Spectrum19Snapshot>()

    // Состояние 25 полос
    private val targetSpectrumLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private val currentSpectrumLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private val spectrumVelocities = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private val spectrumHoldTimers = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private val peakCapSpectrumLevels = FloatArray(SPECTRUM_BAND_COUNT) { RESTING_LEVEL }
    private val peakCapSpectrumVelocities = FloatArray(SPECTRUM_BAND_COUNT) { 0f }
    private val peakCapSpectrumHoldTimers = FloatArray(SPECTRUM_BAND_COUNT) { 0f }

    var onDrainFallback: ((now: Long) -> Unit)? = null
    var hasPendingData: ((now: Long) -> Boolean)? = null

    private fun currentTime(): Long = timeProvider()

    /**
     * 🛑 Очистить буфер задержки
     */
    fun clearDelayQueue() {
        synchronized(currentSpectrumLevels) {
            delayQueue19.clear()
        }
    }

    internal fun drainMatured19(now: Long) {
        val latency = latencyCompensationMs
        if (latency <= 0L) return
        while (delayQueue19.isNotEmpty()) {
            val first = delayQueue19.first()
            val age = now - first.timeMs
            if (age >= latency) {
                delayQueue19.removeFirst()
                if (age <= latency + 1000L) {
                    applySpectrumDirect(first.spectrum)
                }
            } else {
                break
            }
        }
    }

    /**
     * 🧠 Анализ спектрального потока (Spectral Flux) и плотности транзиентов (ударов)
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

        val targetPace = (onsetDensityEma * 2.4f + rmsEnergyEma * 0.35f).coerceIn(0f, 1f)
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
                while (delayQueue19.size >= 50) {
                    delayQueue19.removeFirst()
                }
                delayQueue19.addLast(Spectrum19Snapshot(currentTime(), spectrum.copyOf()))
            }
        }
    }

    internal fun applySpectrumDirect(spectrum: FloatArray) {
        synchronized(currentSpectrumLevels) {
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
                val t = normalized.pow(dynamicExp).coerceIn(0f, 1f)
                prepared[i] = t
                targetSpectrumLevels[i] = t

                if (t > currentSpectrumLevels[i]) {
                    currentSpectrumLevels[i] = t
                    spectrumVelocities[i] = 0f
                    spectrumHoldTimers[i] = VisualizerConstants.BAR_HOLD_SEC
                }

                if (t > peakCapSpectrumLevels[i]) {
                    peakCapSpectrumLevels[i] = t
                    peakCapSpectrumVelocities[i] = 0f
                    peakCapSpectrumHoldTimers[i] = VisualizerConstants.PEAK_CAP_HOLD_SEC
                }
            }

            updateTempoTrackerFromSpectrum(prepared)
            applyTargetsWithSpatialCohesion(prepared)

            lastSpectrumUpdateTimeMs = currentTime()
            lastUpdateTimeMs = currentTime()
        }
    }

    /**
     * 🎼 Синтезировать 25 полос из 5 полос при отсутствии прямого FFT-сигнала
     */
    fun synthesizeFrom5Bands(raw5: FloatArray, autoGain: Float, dynamicExp: Float) {
        synchronized(currentSpectrumLevels) {
            if (currentTime() - lastSpectrumUpdateTimeMs <= 350L) return

            val synthesized = FloatArray(SPECTRUM_BAND_COUNT)
            for (k in 0 until SPECTRUM_BAND_COUNT) {
                val t = (k.toFloat() / (SPECTRUM_BAND_COUNT - 1)) * 4f
                val idx = t.toInt().coerceIn(0, 3)
                val frac = t - idx
                val smoothFrac = (1f - cos(frac * PI.toFloat())) / 2f
                val interp = raw5[idx] * (1f - smoothFrac) + raw5[idx + 1] * smoothFrac
                val norm = (interp * autoGain).coerceIn(0f, 1f)
                synthesized[k] = norm.pow(dynamicExp).coerceIn(0f, 1f)
            }
            updateTempoTrackerFromSpectrum(synthesized)
            applyTargetsWithSpatialCohesion(synthesized)

            for (k in 0 until SPECTRUM_BAND_COUNT) {
                val lvl = targetSpectrumLevels[k]
                if (lvl > currentSpectrumLevels[k]) {
                    currentSpectrumLevels[k] = lvl
                    spectrumVelocities[k] = 0f
                    spectrumHoldTimers[k] = VisualizerConstants.BAR_HOLD_SEC
                }

                if (lvl > peakCapSpectrumLevels[k]) {
                    peakCapSpectrumLevels[k] = lvl
                    peakCapSpectrumVelocities[k] = 0f
                    peakCapSpectrumHoldTimers[k] = VisualizerConstants.PEAK_CAP_HOLD_SEC
                }
            }

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
                onDrainFallback?.invoke(now)
            }
            val isDataRecent = (lastUpdateTimeMs > 0L && (now - lastUpdateTimeMs) < (350L + latencyCompensationMs)) ||
                delayQueue19.isNotEmpty() ||
                (hasPendingData?.invoke(now) == true)

            val peakDecay = exp(-safeDt / 4.0f)
            trackPeakEma = max(0.18f, trackPeakEma * peakDecay)

            if (!isDataRecent) {
                for (k in 0 until SPECTRUM_BAND_COUNT) {
                    targetSpectrumLevels[k] = RESTING_LEVEL
                }
            }

            for (i in 0 until SPECTRUM_BAND_COUNT) {
                val target = targetSpectrumLevels[i]
                var current = currentSpectrumLevels[i]

                if (target >= current) {
                    current = target
                    spectrumVelocities[i] = 0f
                    spectrumHoldTimers[i] = VisualizerConstants.BAR_HOLD_SEC
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
                        spectrumVelocities[i] += VisualizerConstants.BAR_GRAVITY * remainingDt
                        current = max(target, current - spectrumVelocities[i] * remainingDt)
                    }
                }

                currentSpectrumLevels[i] = current.coerceIn(RESTING_LEVEL, 1f)

                var cap = peakCapSpectrumLevels[i]
                if (target >= cap) {
                    cap = target
                    peakCapSpectrumVelocities[i] = 0f
                    peakCapSpectrumHoldTimers[i] = VisualizerConstants.PEAK_CAP_HOLD_SEC
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
                        peakCapSpectrumVelocities[i] += VisualizerConstants.PEAK_CAP_GRAVITY * remainingCapDt
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
                spectrumVelocities[j] = 0f
                spectrumHoldTimers[j] = 0f
                peakCapSpectrumLevels[j] = RESTING_LEVEL
                peakCapSpectrumVelocities[j] = 0f
                peakCapSpectrumHoldTimers[j] = 0f
                prevRawSpectrum[j] = 0f
            }
            trackPeakEma = 0.5f
            currentPaceFactor = 0.35f
            onsetDensityEma = 0f
            rmsEnergyEma = 0f
            lastFluxTimeMs = 0L
            lastSpectrumUpdateTimeMs = 0L
            lastUpdateTimeMs = 0L
        }
    }
}
