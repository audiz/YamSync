package io.github.audiz.backend

import io.github.audiz.dsp.AudioVisualizer
import javax.sound.sampled.SourceDataLine

/**
 * Объём аудио (мс), уже записанный в буфер линии и ещё не воспроизведённый; -1 если формат неизвестен.
 */
internal fun SourceDataLine.bufferedMs(): Float {
    val bytesPerSec = format.frameRate * format.frameSize
    if (bytesPerSec <= 0f) return -1f
    val queuedBytes = (bufferSize - available()).coerceAtLeast(0)
    return queuedBytes * 1000f / bytesPerSec
}

/**
 * Передать чанк в спектроанализатор вместе с текущей задержкой вывода линии.
 */
internal fun SourceDataLine.analyzePcmChunk(chunk: ByteArray, size: Int) {
    AudioVisualizer.reportOutputBufferedMs(bufferedMs())
    AudioVisualizer.processPcmChunk(chunk, size)
}
