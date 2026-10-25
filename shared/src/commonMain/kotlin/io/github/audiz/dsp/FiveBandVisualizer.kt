package io.github.audiz.dsp

import io.github.audiz.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 🎛 5-полосный микро-спектроанализатор Winamp Pro.
 *
 * Отвечает за:
 * - 1-pole IIR PCM-фильтрацию сырого аудио потока (Sub-cut, Kick, Bass, Mid, HighMid, Treble)
 * - Слежение за пиковой громкостью трека (Adaptive Auto-Gain)
 * - Софт-лимитер бочки
 * - Мгновенную атаку (0 мс) и студийную баллистику Winamp Pro (Hold + Gravity)
 * - Очередь компенсации задержки аудио (Latency Compensation)
 * - Реактивный [StateFlow] уровней для UI-иконок
 */
class FiveBandVisualizer(
    var timeProvider: () -> Long = { io.github.audiz.currentTimeMillis() }
) {
    companion object {
        const val BAND_COUNT = 5
        const val RESTING_LEVEL = VisualizerConstants.RESTING_LEVEL

        // Частотные срезы для 44.1 kHz (alpha = 2 * pi * fc / 44100):
        private const val ALPHA_SUBCUT = 0.0057f   // ~40 Hz (Sub-cut)
        private const val ALPHA_KICK = 0.0157f     // ~110 Hz (Kick Drum)
        private const val ALPHA_BASS = 0.050f      // ~350 Hz (Bass Guitar)
        private const val ALPHA_MID = 0.22f        // ~1800 Hz (Mids / Vocals)
        private const val ALPHA_HIGH_MID = 0.50f   // ~6000 Hz (High Mids / Snare)
    }

    @Volatile
    var latencyCompensationMs: Long = 0L

    @Volatile
    var currentPaceFactor: Float = 0.35f

    @Volatile
    var trackPeakEma: Float = 0.5f

    var onBandsUpdated: ((raw5: FloatArray, autoGain: Float, dynamicExp: Float) -> Unit)? = null
    var onAudibleEnergyDetected: (() -> Unit)? = null

    private class Bands5Snapshot(
        val timeMs: Long,
        val b1: Float,
        val b2: Float,
        val b3: Float,
        val b4: Float,
        val b5: Float
    )

    private val delayQueue5 = ArrayDeque<Bands5Snapshot>()

    @Volatile
    var lastUpdateTimeMs: Long = 0L
        private set

    // Состояние 5 полос
    private val targetLevels = FloatArray(BAND_COUNT) { RESTING_LEVEL }
    private val currentLevels = FloatArray(BAND_COUNT) { RESTING_LEVEL }
    private val velocities = FloatArray(BAND_COUNT) { 0f }
    private val holdTimers = FloatArray(BAND_COUNT) { 0f }
    private val peakCapLevels = FloatArray(BAND_COUNT) { RESTING_LEVEL }
    private val peakCapVelocities = FloatArray(BAND_COUNT) { 0f }
    private val peakCapHoldTimers = FloatArray(BAND_COUNT) { 0f }
    private val prevRaw5 = FloatArray(BAND_COUNT) { 0f }

    private val _levels = MutableStateFlow(FloatArray(BAND_COUNT) { RESTING_LEVEL })
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    // IIR-фильтры PCM
    private val pcmLock = Any()
    @Volatile private var subCutFilter: Float = 0f
    @Volatile private var kickFilter: Float = 0f
    @Volatile private var bassFilter: Float = 0f
    @Volatile private var midFilter: Float = 0f
    @Volatile private var highMidFilter: Float = 0f

    private fun currentTime(): Long = timeProvider()

    /**
     * 🛑 Очистить буфер задержки (при смене трека, паузе или перемотке)
     */
    fun clearDelayQueue() {
        synchronized(currentLevels) {
            delayQueue5.clear()
        }
    }

    internal fun drainMatured5(now: Long) {
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

    fun hasRecentData(now: Long = currentTime()): Boolean {
        synchronized(currentLevels) {
            return delayQueue5.isNotEmpty() || (lastUpdateTimeMs > 0L && (now - lastUpdateTimeMs < (350L + latencyCompensationMs)))
        }
    }

    /**
     * 🎚 Обновить 5 полос из нормализованных значений энергии (0.0 .. 1.0).
     */
    fun updateBands(b1: Float, b2: Float, b3: Float, b4: Float, b5: Float) {
        val maxEnergy = maxOf(b1, b2, b3, b4, b5)
        if (maxEnergy > 0.02f) {
            onAudibleEnergyDetected?.invoke()
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

    internal fun applyBandsDirect(b1: Float, b2: Float, b3: Float, b4: Float, b5: Float) {
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
            val dynamicExp = (2.0f - (autoGain - 1.0f) * 0.35f).coerceIn(1.45f, 2.0f)

            for (i in 0 until BAND_COUNT) {
                val normalized = (raw[i] * autoGain).coerceIn(0f, 1f)
                val t = normalized.pow(dynamicExp).coerceIn(0f, 1f)
                prevRaw5[i] = t
                targetLevels[i] = t

                if (t > currentLevels[i]) {
                    currentLevels[i] = t
                    velocities[i] = 0f
                    holdTimers[i] = VisualizerConstants.BAR_HOLD_SEC
                }

                if (t > peakCapLevels[i]) {
                    peakCapLevels[i] = t
                    peakCapVelocities[i] = 0f
                    peakCapHoldTimers[i] = VisualizerConstants.PEAK_CAP_HOLD_SEC
                }
            }

            onBandsUpdated?.invoke(raw, autoGain, dynamicExp)

            lastUpdateTimeMs = currentTime()
            _levels.value = currentLevels.copyOf()
        }
    }

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

                localSubCut += ALPHA_SUBCUT * (mono - localSubCut)
                val cleanMono = mono - localSubCut

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

            b1 = (avgKick * 3.0f).coerceIn(0f, 1f)
            b2 = (avgBass * 5.6f).coerceIn(0f, 1f)
            b3 = (avgMid * 6.5f).coerceIn(0f, 1f)
            b4 = (avgHighMid * 11.0f).coerceIn(0f, 1f)
            b5 = (avgTreble * 17.5f).coerceIn(0f, 1f)
        }

        updateBands(b1, b2, b3, b4, b5)
    }

    /**
     * ⏱ Рассчитать следующий кадр физики 5 полос для UI
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
            val isDataRecent = (now - lastUpdateTimeMs) < (350L + latencyCompensationMs) || delayQueue5.isNotEmpty()

            val peakDecay = exp(-safeDt / 4.0f)
            trackPeakEma = max(0.18f, trackPeakEma * peakDecay)

            if (!isDataRecent) {
                for (j in 0 until BAND_COUNT) {
                    targetLevels[j] = RESTING_LEVEL
                }
            }

            for (i in 0 until BAND_COUNT) {
                val target = targetLevels[i]
                var current = currentLevels[i]

                if (target >= current) {
                    current = target
                    velocities[i] = 0f
                    holdTimers[i] = VisualizerConstants.BAR_HOLD_SEC
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
                        velocities[i] += VisualizerConstants.BAR_GRAVITY * remainingDt
                        current = max(target, current - velocities[i] * remainingDt)
                    }
                }

                currentLevels[i] = current.coerceIn(RESTING_LEVEL, 1f)

                var cap = peakCapLevels[i]
                if (target >= cap) {
                    cap = target
                    peakCapVelocities[i] = 0f
                    peakCapHoldTimers[i] = VisualizerConstants.PEAK_CAP_HOLD_SEC
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
                        peakCapVelocities[i] += VisualizerConstants.PEAK_CAP_GRAVITY * remainingCapDt
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
     * 🛑 Сброс уровней в состояние покоя
     */
    fun reset() {
        synchronized(currentLevels) {
            clearDelayQueue()
            for (j in 0 until BAND_COUNT) {
                targetLevels[j] = RESTING_LEVEL
                currentLevels[j] = RESTING_LEVEL
                velocities[j] = 0f
                holdTimers[j] = 0f
                peakCapLevels[j] = RESTING_LEVEL
                peakCapVelocities[j] = 0f
                peakCapHoldTimers[j] = 0f
                prevRaw5[j] = 0f
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
            lastUpdateTimeMs = 0L
            val frame = FloatArray(BAND_COUNT) { RESTING_LEVEL }
            _levels.value = frame
        }
    }

    /**
     * 🌊 Получить текущий срез уровней для отображения
     */
    fun getLevelsForDisplay(isPlaying: Boolean): FloatArray {
        return advanceFrame(0.033f, isPlaying).levels
    }
}
