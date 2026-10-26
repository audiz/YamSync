package io.github.audiz.dsp

import io.github.audiz.synchronized
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 🎛 Прецизионный спектральный анализатор PCM-потока на базе быстрого преобразования Фурье (FFT).
 *
 * Особенности:
 * - Выполняет частотное разложение в 25 логарифмических полос прямо из 16-битного PCM (LE Stereo/Mono).
 * - Алгоритм: Radix-2 Cooley-Tukey FFT (2048 точек) с окном Ханна.
 * - Частотное разрешение: ~21.5 Гц на бин (при 44.1 кГц).
 * - Zero allocations в горячем цикле воспроизведения: все рабочие буферы и таблицы предрассчитаны.
 * - Потокобезопасен (synchronized lock).
 */
class PcmSpectrumAnalyzer(
    private val fftSize: Int = FFT_SIZE,
    private val sampleRate: Float = 44100.0f
) {
    companion object {
        const val FFT_SIZE = 2048
        private const val NUM_BINS = FFT_SIZE / 2
        private const val THRESHOLD_DB = -60.0f
        private const val MAX_TEMP_SAMPLES = 8192
    }

    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) {
            "Размер FFT должен быть степенью двойки, получено: $fftSize"
        }
    }

    private val lock = Any()

    // Скользящее окно сэмплов (2048 точек)
    private val sampleBuffer = FloatArray(fftSize)
    private var isBufferFilled = false

    // Временный буфер для извлечения сэмплов из чанка байт
    private val tempExtracted = FloatArray(MAX_TEMP_SAMPLES)

    // Буферы для быстрого преобразования Фурье
    private val windowedReal = FloatArray(fftSize)
    private val windowedImag = FloatArray(fftSize)
    private val magnitudesDb = FloatArray(NUM_BINS)
    private val outputSpectrum25 = FloatArray(TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT)

    // Предрассчитанное окно Ханна (Hann Window)
    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
    }

    // Предрассчитанная битреверсивная перестановка индексов (Bit-Reversal Permutation)
    private val logN = 31 - fftSize.countLeadingZeroBits()
    private val bitReversedIndices = IntArray(fftSize) { i ->
        var rev = 0
        var temp = i
        for (j in 0 until logN) {
            rev = (rev shl 1) or (temp and 1)
            temp = temp shr 1
        }
        rev
    }

    // Предрассчитанные поворотные множители (Twiddle Factors)
    private val twiddleCos = FloatArray(fftSize / 2) { i ->
        cos(-2.0 * PI * i / fftSize).toFloat()
    }
    private val twiddleSin = FloatArray(fftSize / 2) { i ->
        sin(-2.0 * PI * i / fftSize).toFloat()
    }

    private val binHz = sampleRate / fftSize.toFloat()

    var onSpectrumReady: ((spectrum: FloatArray) -> Unit)? = null
    var onAudibleEnergyDetected: (() -> Unit)? = null

    /**
     * 🛑 Сброс состояния и очистка буфера сэмплов
     */
    fun reset() {
        synchronized(lock) {
            sampleBuffer.fill(0f)
            isBufferFilled = false
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
            if (extractedCount >= fftSize) {
                // Если сэмплов больше размера окна — берем последние fftSize сэмплов
                tempExtracted.copyInto(
                    destination = sampleBuffer,
                    destinationOffset = 0,
                    startIndex = extractedCount - fftSize,
                    endIndex = extractedCount
                )
                isBufferFilled = true
            } else {
                // Сдвигаем старые сэмплы влево на extractedCount
                sampleBuffer.copyInto(
                    destination = sampleBuffer,
                    destinationOffset = 0,
                    startIndex = extractedCount,
                    endIndex = fftSize
                )
                // Дописываем новые сэмплы в конец
                tempExtracted.copyInto(
                    destination = sampleBuffer,
                    destinationOffset = fftSize - extractedCount,
                    startIndex = 0,
                    endIndex = extractedCount
                )
                isBufferFilled = true
            }

            // Накладываем окно Ханна
            for (k in 0 until fftSize) {
                windowedReal[k] = sampleBuffer[k] * hannWindow[k]
                windowedImag[k] = 0f
            }

            // Выполняем Radix-2 FFT
            computeFft(windowedReal, windowedImag)

            // Расчет магнитуд в дБ
            val normFactor = 4.0f / fftSize.toFloat()
            for (k in 1 until NUM_BINS) {
                val r = windowedReal[k]
                val im = windowedImag[k]
                val mag = sqrt(r * r + im * im) * normFactor
                magnitudesDb[k] = if (mag > 1e-4f) {
                    (20.0f * log10(mag)).coerceIn(THRESHOLD_DB, 0.0f)
                } else {
                    THRESHOLD_DB
                }
            }

            // Формируем 25 логарифмических полос частот
            for (k in 0 until TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT) {
                val (fStart, fEnd) = VisualizerConstants.FREQ_RANGES_25[k]
                val startBin = (fStart / binHz).toInt().coerceIn(1, NUM_BINS - 1)
                val endBin = (fEnd / binHz).toInt().coerceIn(startBin, NUM_BINS - 1)

                var sum = 0f
                var maxDb = THRESHOLD_DB
                for (b in startBin..endBin) {
                    val v = magnitudesDb[b]
                    sum += v
                    if (v > maxDb) maxDb = v
                }
                val avgDb = sum / (endBin - startBin + 1)
                val progress = k.toFloat() / (TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT - 1).toFloat()
                val tiltDb = -7.0f + progress * 23.0f
                val combinedDb = (maxDb * 0.75f + avgDb * 0.25f) + tiltDb
                outputSpectrum25[k] = ((combinedDb - THRESHOLD_DB) / (-THRESHOLD_DB)).coerceIn(0.0f, 1.0f)
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

    /**
     * In-place Radix-2 Cooley-Tukey Decimation-in-Time FFT
     */
    private fun computeFft(real: FloatArray, imag: FloatArray) {
        // Битреверсивная перестановка
        for (i in 0 until fftSize) {
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
        while (len <= fftSize) {
            val halfLen = len shr 1
            val step = fftSize / len
            var k = 0
            while (k < fftSize) {
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
