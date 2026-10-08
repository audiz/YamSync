package io.github.audiz.dsp

import io.github.audiz.volumeToGain
import kotlin.math.roundToInt

/**
 * 🎚️ Программное масштабирование амплитуды PCM 16-bit (little-endian).
 * Обеспечивает точную реакцию на громкость и плавное сведение (кроссфейд).
 * Полностью кросс-платформенная реализация (KMP).
 */
object PcmGainProcessor {

    fun applyGain(buffer: ByteArray, bytesRead: Int, volume: Float) {
        val gain = volumeToGain(volume)
        applyGainDirect(buffer, bytesRead, gain)
    }

    /**
     * Прямое масштабирование 16-bit PCM сэмплов линейным коэффициентом (0.0..1.0) без преобразования громкости.
     */
    fun applyGainDirect(buffer: ByteArray, bytesRead: Int, gain: Float) {
        if (gain >= 0.999f) return
        if (gain <= 0.0001f) {
            buffer.fill(0, 0, bytesRead)
            return
        }
        var i = 0
        while (i < bytesRead - 1) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val scaled = (sample * gain).roundToInt().coerceIn(-32768, 32767)
            buffer[i] = (scaled and 0xFF).toByte()
            buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /**
     * 🎛️ Сведение двух PCM 16-bit stereo потоков с индивидуальными коэффициентами усиления (gain).
     * Результат записывается в dst. Если dst длиннее src, остаток dst умножается на dstGain.
     */
    fun mixPcm(
        dst: ByteArray,
        dstLen: Int,
        src: ByteArray,
        srcLen: Int,
        dstGain: Float,
        srcGain: Float
    ) {
        val commonLen = minOf(dstLen, srcLen)
        var i = 0
        while (i < commonLen - 1) {
            val sDst = (dst[i + 1].toInt() shl 8) or (dst[i].toInt() and 0xFF)
            val sSrc = (src[i + 1].toInt() shl 8) or (src[i].toInt() and 0xFF)
            val mixed = (sDst * dstGain + sSrc * srcGain).roundToInt().coerceIn(-32768, 32767)
            dst[i] = (mixed and 0xFF).toByte()
            dst[i + 1] = ((mixed shr 8) and 0xFF).toByte()
            i += 2
        }
        if (commonLen < dstLen) {
            while (i < dstLen - 1) {
                val sDst = (dst[i + 1].toInt() shl 8) or (dst[i].toInt() and 0xFF)
                val scaled = (sDst * dstGain).roundToInt().coerceIn(-32768, 32767)
                dst[i] = (scaled and 0xFF).toByte()
                dst[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                i += 2
            }
        }
    }
}
