package io.github.audiz.backend

import io.github.audiz.dsp.AudioVisualizer
import io.github.audiz.dsp.EqualizerEngine
import io.github.audiz.dsp.PcmGainProcessor
import io.github.audiz.util.AudioHeaderParser
import io.github.audiz.volumeToGain
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import javazoom.jl.player.FactoryRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Arrays
import javax.sound.sampled.*

/**
 * 📻 Запасной аудио-бэкенд для прямого воспроизведения MP3 и WAV/FLAC
 * через встроенный JLayer и JavaSound API при отсутствии системных кодеков.
 */
class JLayerAudioBackend(
    private val callback: AudioBackendCallback
) : AudioBackend {

    override val name: String = "JLayer + JavaSound"

    override fun isAvailable(): Boolean = true

    @Volatile private var currentThread: Thread? = null
    @Volatile private var currentLine: SourceDataLine? = null
    @Volatile private var currentSessionId: Long = 0L

    @Volatile private var isPlayingState: Boolean = false
    @Volatile private var isPausedState: Boolean = false
    @Volatile private var currentVolume: Float = 1f

    @Volatile private var durationMs: Long = 0L
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var startTimeNano: Long = 0L
    @Volatile private var pauseOffsetMs: Long = 0L

    override fun play(
        source: PlaybackSource,
        sessionId: Long,
        seekPosMs: Long,
        crossfadeMs: Long
    ) {
        val file = when (source) {
            is PlaybackSource.LocalFile -> source.file
            is PlaybackSource.Url -> {
                callback.onError(sessionId, "JLayer does not support direct streaming URLs", canFallback = true)
                return
            }
        }

        stop()
        currentSessionId = sessionId
        isPlayingState = true
        isPausedState = false
        currentPositionMs = seekPosMs
        pauseOffsetMs = seekPosMs
        startTimeNano = System.nanoTime()

        if (FfmpegUtils.isMp3File(file)) {
            playMp3(file, sessionId, seekPosMs)
        } else {
            playJavaSound(file, sessionId, seekPosMs)
        }
    }

    private fun playMp3(file: File, sessionId: Long, seekPosMs: Long) {
        val bytes = file.readBytes()
        if (durationMs <= 0L) {
            durationMs = AudioHeaderParser.extractAudioDuration(file)
        }

        currentThread = Thread {
            if (sessionId != currentSessionId) return@Thread
            try {
                val stream = ByteArrayInputStream(bytes)
                val bitstream = Bitstream(stream)
                val decoder = Decoder()
                val audioDevice = FactoryRegistry.systemRegistry().createAudioDevice()

                val firstHeader = bitstream.readFrame() ?: return@Thread
                val msPerFrame = firstHeader.ms_per_frame()

                var header: Header? = firstHeader
                if (seekPosMs > 0 && msPerFrame > 0) {
                    val framesToSkip = (seekPosMs / msPerFrame).toInt()
                    for (i in 0 until framesToSkip) {
                        bitstream.closeFrame()
                        header = bitstream.readFrame()
                        if (header == null) break
                    }
                }

                if (sessionId != currentSessionId) return@Thread

                audioDevice.open(decoder)
                isPlayingState = true
                isPausedState = false
                startTimeNano = System.nanoTime()
                pauseOffsetMs = seekPosMs
                currentPositionMs = seekPosMs
                callback.onPlaybackStarted(sessionId)

                while (header != null && !isPausedState && !Thread.currentThread().isInterrupted && sessionId == currentSessionId) {
                    val sampleBuffer = decoder.decodeFrame(header, bitstream) as SampleBuffer
                    val gain = volumeToGain(currentVolume)
                    if (gain < 0.999f) {
                        val sBuf = sampleBuffer.buffer
                        val sLen = sampleBuffer.bufferLength
                        if (gain <= 0.0001f) {
                            Arrays.fill(sBuf, 0, sLen, 0.toShort())
                        } else {
                            for (idx in 0 until sLen) {
                                sBuf[idx] = (sBuf[idx] * gain).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        }
                    }
                    audioDevice.write(sampleBuffer.buffer, 0, sampleBuffer.bufferLength)
                    bitstream.closeFrame()
                    header = bitstream.readFrame()
                }

                audioDevice.flush()
                audioDevice.close()
                if (header == null && sessionId == currentSessionId) {
                    callback.onCompletion(sessionId)
                }
            } catch (_: Exception) {
            } finally {
                if (sessionId == currentSessionId && Thread.currentThread() == currentThread) {
                    isPlayingState = false
                }
            }
        }.apply {
            isDaemon = true
            name = "AudioPlayer-JLayer-$sessionId"
            start()
        }
    }

    private fun playJavaSound(file: File, sessionId: Long, seekPosMs: Long) {
        if (durationMs <= 0L) {
            val detected = AudioHeaderParser.getExactFlacDurationMs(file)
            durationMs = if (detected > 0) detected else AudioHeaderParser.estimateFlacDurationMs(file)
        }

        currentThread = Thread {
            if (sessionId != currentSessionId) return@Thread
            var line: SourceDataLine? = null
            var inStream: AudioInputStream? = null
            try {
                inStream = AudioSystem.getAudioInputStream(file)
                val baseFormat = inStream.format

                val frames = inStream.frameLength
                if (frames > 0 && baseFormat.frameRate > 0) {
                    durationMs = (frames * 1000.0 / baseFormat.frameRate).toLong()
                }

                val decodedFormat = if (baseFormat.encoding == AudioFormat.Encoding.PCM_SIGNED ||
                    baseFormat.encoding == AudioFormat.Encoding.PCM_UNSIGNED) {
                    baseFormat
                } else {
                    val pcmFormat = AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.sampleRate,
                        16,
                        baseFormat.channels,
                        baseFormat.channels * 2,
                        baseFormat.sampleRate,
                        false
                    )
                    inStream = AudioSystem.getAudioInputStream(pcmFormat, inStream)
                    pcmFormat
                }

                val info = DataLine.Info(SourceDataLine::class.java, decodedFormat)
                line = AudioSystem.getLine(info) as SourceDataLine

                if (sessionId != currentSessionId) {
                    inStream.close()
                    return@Thread
                }

                currentLine = line
                var opened = false
                var openAttempts = 0
                val bufSize = (decodedFormat.sampleRate * decodedFormat.frameSize * 0.15f).toInt()
                while (!opened && openAttempts < 5 && sessionId == currentSessionId) {
                    try {
                        line.open(decodedFormat, bufSize)
                        opened = true
                    } catch (_: Exception) {
                        try {
                            line.open(decodedFormat)
                            opened = true
                        } catch (_: Exception) {
                            openAttempts++
                            Thread.sleep(60)
                        }
                    }
                }

                if (sessionId != currentSessionId || !opened) {
                    try { line.close() } catch (_: Exception) {}
                    try { inStream.close() } catch (_: Exception) {}
                    return@Thread
                }

                if (seekPosMs > 0) {
                    val frameRate = decodedFormat.frameRate
                    val frameSize = decodedFormat.frameSize
                    val framesToSkip = (seekPosMs * frameRate / 1000f).toLong()
                    val bytesToSkip = framesToSkip * frameSize
                    var skipped = 0L
                    val discardBuf = ByteArray(8192)
                    while (skipped < bytesToSkip && sessionId == currentSessionId && !Thread.currentThread().isInterrupted) {
                        val needed = bytesToSkip - skipped
                        val toRead = if (needed > discardBuf.size) discardBuf.size else needed.toInt()
                        val r = inStream.read(discardBuf, 0, toRead)
                        if (r <= 0) break
                        skipped += r
                    }
                }

                if (sessionId != currentSessionId) {
                    try { line.close() } catch (_: Exception) {}
                    try { inStream.close() } catch (_: Exception) {}
                    return@Thread
                }

                line.start()
                isPlayingState = true
                isPausedState = false
                startTimeNano = System.nanoTime()
                pauseOffsetMs = seekPosMs
                currentPositionMs = seekPosMs
                callback.onPlaybackStarted(sessionId)

                val buffer = ByteArray(4096)
                var readBytes = 0
                val is16BitLe = decodedFormat.sampleSizeInBits == 16 && !decodedFormat.isBigEndian
                while (!Thread.currentThread().isInterrupted && sessionId == currentSessionId) {
                    if (isPausedState) {
                        Thread.sleep(50)
                        continue
                    }
                    readBytes = inStream.read(buffer, 0, buffer.size)
                    if (readBytes == -1) break
                    if (readBytes > 0) {
                        if (is16BitLe) {
                            if (EqualizerEngine.isEnabled) {
                                EqualizerEngine.processPcm(buffer, readBytes)
                            }
                            line.analyzePcmChunk(buffer, readBytes)
                            PcmGainProcessor.applyGain(buffer, readBytes, currentVolume)
                        }
                        line.write(buffer, 0, readBytes)
                    }
                }

                if (sessionId == currentSessionId && !Thread.currentThread().isInterrupted) {
                    line.drain()
                    callback.onCompletion(sessionId)
                }
                try { line.stop() } catch (_: Exception) {}
                try { line.close() } catch (_: Exception) {}
                try { inStream.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                try { line?.stop() } catch (_: Exception) {}
                try { line?.close() } catch (_: Exception) {}
                try { inStream?.close() } catch (_: Exception) {}
                if (sessionId == currentSessionId && Thread.currentThread() == currentThread) {
                    callback.onError(sessionId, e.message ?: "JavaSound error", canFallback = true)
                }
            } finally {
                if (sessionId == currentSessionId && Thread.currentThread() == currentThread) {
                    isPlayingState = false
                    currentLine = null
                }
            }
        }.apply {
            isDaemon = true
            name = "AudioPlayer-JavaSound-$sessionId"
            start()
        }
    }

    override fun pause() {
        isPausedState = true
        isPlayingState = false
        AudioVisualizer.reset()
        try {
            currentLine?.stop()
        } catch (_: Exception) {}
    }

    override fun resume(resumePosMs: Long) {
        val line = currentLine
        if (line != null && line.isOpen) {
            pauseOffsetMs = resumePosMs - (line.microsecondPosition / 1000L)
            try { line.start() } catch (_: Exception) {}
        } else {
            pauseOffsetMs = resumePosMs
        }
        startTimeNano = System.nanoTime()
        isPlayingState = true
        isPausedState = false
    }

    override fun stop() {
        AudioVisualizer.reset()
        try {
            currentLine?.stop()
            currentLine?.flush()
            currentLine?.close()
        } catch (_: Exception) {}
        currentLine = null

        val thread = currentThread
        currentThread = null
        try {
            thread?.interrupt()
        } catch (_: Exception) {}

        isPlayingState = false
        isPausedState = false
        currentPositionMs = 0L
    }

    override fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs
        pauseOffsetMs = positionMs
        startTimeNano = System.nanoTime()
        AudioVisualizer.clearDelayQueue()
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
    }

    override fun getDurationMs(): Long = durationMs

    override fun getCurrentPositionMs(): Long {
        if (isPausedState) return currentPositionMs
        val line = currentLine
        if (line != null && line.isOpen) {
            val pos = pauseOffsetMs + (line.microsecondPosition / 1000L)
            currentPositionMs = pos
            return pos
        }
        return if (isPlayingState && startTimeNano > 0L) {
            val pos = pauseOffsetMs + (System.nanoTime() - startTimeNano) / 1_000_000
            currentPositionMs = pos
            pos
        } else {
            currentPositionMs
        }
    }

    override fun isPlaying(): Boolean = isPlayingState && !isPausedState

    override fun release() {
        stop()
    }
}
