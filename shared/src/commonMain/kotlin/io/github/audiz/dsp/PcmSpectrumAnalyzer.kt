package io.github.audiz.dsp

import io.github.audiz.synchronized
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 🎛 Прецизионный спектральный анализатор PCM-потока на базе быстрого преобразования Фурье (FFT).
 *
 * Особенности:
 * - Выполняет частотное разложение в 25 логарифмических полос прямо из 16-битного PCM (LE Stereo/Mono).
 * - Мультиразрешающий анализ: низкие полосы (до [LOW_RES_CROSSOVER_HZ]) считаются по длинному окну
 *   (2 × [fftSize], ~10.8 Гц на бин), остальные — по короткому окну [fftSize] (~21.5 Гц на бин)
 *   для быстрой реакции на транзиенты.
 * - Алгоритм: Radix-2 Cooley-Tukey FFT с окном Ханна.
 * - Zero allocations в горячем цикле воспроизведения: все рабочие буферы и таблицы предрассчитаны.
 * - Потокобезопасен (synchronized lock).
 */
class PcmSpectrumAnalyzer(
    private val fftSize: Int = FFT_SIZE,
    private val sampleRate: Float = 44100.0f
) {
    companion object {
        const val FFT_SIZE = 2048
        const val LOW_RES_CROSSOVER_HZ = 250f
        private const val MAX_TEMP_SAMPLES = 8192
    }

    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) {
            "Размер FFT должен быть степенью двойки, получено: $fftSize"
        }
    }

    /**
     * Предрассчитанный план FFT фиксированного размера с собственными рабочими буферами.
     */
    private class FftPlan(val size: Int) {
        val numBins = size / 2
        private val real = FloatArray(size)
        private val imag = FloatArray(size)

        /** Линейные амплитуды бинов после [transform] (1.0 = синус полной шкалы). */
        val amplitudes = FloatArray(numBins)

        private val hannWindow = FloatArray(size) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }

        private val logN = 31 - size.countLeadingZeroBits()
        private val bitReversedIndices = IntArray(size) { i ->
            var rev = 0
            var temp = i
            for (j in 0 until logN) {
                rev = (rev shl 1) or (temp and 1)
                temp = temp shr 1
            }
            rev
        }

        private val twiddleCos = FloatArray(size / 2) { i -> cos(-2.0 * PI * i / size).toFloat() }
        private val twiddleSin = FloatArray(size / 2) { i -> sin(-2.0 * PI * i / size).toFloat() }

        /** Окно Ханна + FFT над `samples[offset until offset + size]`, результат в [amplitudes]. */
        fun transform(samples: FloatArray, offset: Int) {
            for (k in 0 until size) {
                real[k] = samples[offset + k] * hannWindow[k]
                imag[k] = 0f
            }
            computeFft()
            val normFactor = 4.0f / size.toFloat()
            amplitudes[0] = 0f
            for (k in 1 until numBins) {
                val r = real[k]
                val im = imag[k]
                amplitudes[k] = sqrt(r * r + im * im) * normFactor
            }
        }

        /**
         * In-place Radix-2 Cooley-Tukey Decimation-in-Time FFT
         */
        private fun computeFft() {
            // Битреверсивная перестановка
            for (i in 0 until size) {
                val j = bitReversedIndices[i]
                if (j > i) {
                    val tempR = real[i]
                    real[i] = real[j]
                    real[j] = tempR

                    val tempI = imag[i]
                    imag[i] = imag[j]
                    imag[j] = tempI
                }
            }

            // Бабочки FFT (DIT)
            var len = 2
            while (len <= size) {
                val halfLen = len shr 1
                val step = size / len
                var k = 0
                while (k < size) {
                    var wIndex = 0
                    for (j in 0 until halfLen) {
                        val cosW = twiddleCos[wIndex]
                        val sinW = twiddleSin[wIndex]

                        val tr = real[k + j + halfLen] * cosW - imag[k + j + halfLen] * sinW
                        val ti = real[k + j + halfLen] * sinW + imag[k + j + halfLen] * cosW

                        real[k + j + halfLen] = real[k + j] - tr
                        imag[k + j + halfLen] = imag[k + j] - ti

                        real[k + j] += tr
                        imag[k + j] += ti

                        wIndex += step
                    }
                    k += len
                }
                len = len shl 1
            }
        }
    }

    private val lock = Any()

    private val highResPlan = FftPlan(fftSize)
    private val lowResPlan = FftPlan(fftSize * 2)

    // Скользящее окно сэмплов под длинное FFT; короткое FFT берёт его хвост
    private val sampleBuffer = FloatArray(lowResPlan.size)

    // Временный буфер для извлечения сэмплов из чанка байт
    private val tempExtracted = FloatArray(MAX_TEMP_SAMPLES)

    private val outputSpectrum25 = FloatArray(TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT)

    private val highResBinHz = sampleRate / highResPlan.size.toFloat()
    private val lowResBinHz = sampleRate / lowResPlan.size.toFloat()

    var onSpectrumReady: ((spectrum: FloatArray) -> Unit)? = null
    var onAudibleEnergyDetected: (() -> Unit)? = null

    /**
     * 🛑 Сброс состояния и очистка буфера сэмплов
     */
    fun reset() {
        synchronized(lock) {
            sampleBuffer.fill(0f)
        }
    }

    /**
     * 📊 Обработать 16-битный PCM-буфер (LE Stereo или Mono) и рассчитать 25-полосный спектр
     */
    fun processPcmChunk(buffer: ByteArray, readBytes: Int) {
        if (readBytes < 4) return

        var maxSampleAbs = 0f
        var energySum = 0f
        var extractedCount = 0

        synchronized(lock) {
            var i = 0
            while (i + 1 < readBytes && extractedCount < MAX_TEMP_SAMPLES) {
                val s1 = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
                val left = s1 / 32768.0f
                i += 2

                val mono = if (i + 1 < readBytes) {
                    val s2 = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
                    val right = s2 / 32768.0f
                    i += 2
                    (left + right) * 0.5f
                } else {
                    left
                }

                val absVal = abs(mono)
                if (absVal > maxSampleAbs) maxSampleAbs = absVal
                energySum += absVal

                tempExtracted[extractedCount++] = mono
            }

            if (extractedCount == 0) return

            // Обновляем скользящий буфер новыми сэмплами
            val windowSize = sampleBuffer.size
            if (extractedCount >= windowSize) {
                // Если сэмплов больше размера окна — берем последние windowSize сэмплов
                tempExtracted.copyInto(
                    destination = sampleBuffer,
                    destinationOffset = 0,
                    startIndex = extractedCount - windowSize,
                    endIndex = extractedCount
                )
            } else {
                // Сдвигаем старые сэмплы влево на extractedCount
                sampleBuffer.copyInto(
                    destination = sampleBuffer,
                    destinationOffset = 0,
                    startIndex = extractedCount,
                    endIndex = windowSize
                )
                // Дописываем новые сэмплы в конец
                tempExtracted.copyInto(
                    destination = sampleBuffer,
                    destinationOffset = windowSize - extractedCount,
                    startIndex = 0,
                    endIndex = extractedCount
                )
            }

            lowResPlan.transform(sampleBuffer, 0)
            highResPlan.transform(sampleBuffer, windowSize - highResPlan.size)

            // Формируем 25 логарифмических полос частот
            for (k in 0 until TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT) {
                val (fStart, fEnd) = VisualizerConstants.FREQ_RANGES_25[k]
                val useLowRes = fEnd <= LOW_RES_CROSSOVER_HZ
                val plan = if (useLowRes) lowResPlan else highResPlan
                outputSpectrum25[k] = SpectrumBands.bandLevel(
                    amplitudes = plan.amplitudes,
                    numBins = plan.numBins,
                    binHz = if (useLowRes) lowResBinHz else highResBinHz,
                    fStart = fStart,
                    fEnd = fEnd,
                    tiltDb = SpectrumBands.tiltDb(k)
                )
            }
        }

        // Детекция слышимой энергии
        val avgEnergy = energySum / extractedCount
        if (maxSampleAbs > 0.015f || avgEnergy > 0.005f) {
            onAudibleEnergyDetected?.invoke()
        }

        // Оповещаем подписчиков о новом 25-полосном спектре
        onSpectrumReady?.invoke(outputSpectrum25)
    }
}
