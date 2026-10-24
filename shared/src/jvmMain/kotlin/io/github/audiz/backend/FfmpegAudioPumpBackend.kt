package io.github.audiz.backend

import io.github.audiz.dsp.AudioVisualizer
import io.github.audiz.dsp.EqualizerEngine
import io.github.audiz.dsp.PcmGainProcessor
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.*

/**
 * 🚀 Аудио-бэкенд потокового декодирования через FFmpeg с выводом через JavaSound SourceDataLine.
 * Обеспечивает:
 * - Бесшовный кроссфейд (радио-сведение) двух потоков в реальном времени
 * - Программный эквалайзер EqualizerEngine
 * - Потоковый спектроанализатор AudioVisualizer
 * - Регулировку громкости без артефактов через PcmGainProcessor
 * - Отслеживание порога окончания трека (onNearEnd)
 */
class FfmpegAudioPumpBackend(
    private val callback: AudioBackendCallback
) : AudioBackend {

    override val name: String = "FFmpeg + JavaSound AudioPump"

    override fun isAvailable(): Boolean = FfmpegUtils.hasFfmpeg()

    class AudioSession(
        val id: Long,
        @Volatile var seekPosMs: Long = 0L,
        @Volatile var lineStartMicrosecondPosition: Long = 0L,
        @Volatile var proc: Process? = null,
        @Volatile var thread: Thread? = null,
        @Volatile var isEofReached: Boolean = false,
        @Volatile var isClosed: Boolean = false,
        @Volatile var isFadingOut: Boolean = false,
        @Volatile var crossfadeDurationMs: Long = 0L,
        @Volatile var fadeStartTimeNano: Long = 0L,
        @Volatile var fadeDurationNano: Long = 0L,
        @Volatile var nearEndTriggered: Boolean = false
    ) {
        val pcmQueue = LinkedBlockingQueue<ByteArray>(96)

        fun close() {
            if (isClosed) return
            isClosed = true
            pcmQueue.clear()
            try { thread?.interrupt() } catch (_: Exception) {}
            try { proc?.inputStream?.close() } catch (_: Exception) {}
            try { proc?.destroyForcibly() } catch (_: Exception) {}
        }
    }

    @Volatile private var activeSession: AudioSession? = null
    @Volatile private var fadingSession: AudioSession? = null
    @Volatile private var currentSessionId: Long = 0L

    @Volatile private var javaSoundLine: SourceDataLine? = null
    @Volatile private var isPumpRunning: Boolean = false
    private val pumpLock = Any()

    @Volatile private var isPlayingState: Boolean = false
    @Volatile private var isPausedState: Boolean = false
    @Volatile private var currentVolume: Float = 1f
    @Volatile private var durationMs: Long = 0L
    @Volatile private var currentPositionMs: Long = 0L

    @Volatile private var nearEndThresholdMs: Long = 0L
    @Volatile private var nearEndExpectedDurationMs: Long = 0L

    override fun play(
        source: PlaybackSource,
        sessionId: Long,
        seekPosMs: Long,
        crossfadeMs: Long
    ) {
        currentSessionId = sessionId
        val ffmpeg = FfmpegUtils.getFfmpegPath()
        if (ffmpeg == null) {
            callback.onError(sessionId, "ffmpeg not found", canFallback = true)
            return
        }

        val sourceStr = when (source) {
            is PlaybackSource.Url -> source.url
            is PlaybackSource.LocalFile -> source.file.absolutePath
        }

        val session = AudioSession(
            id = sessionId,
            seekPosMs = seekPosMs,
            crossfadeDurationMs = crossfadeMs
        )

        val outgoingSession = activeSession
        val outgoingFading = fadingSession

        if (crossfadeMs > 0L && outgoingSession != null && !outgoingSession.isClosed) {
            outgoingFading?.close()
            fadingSession = outgoingSession
            outgoingSession.isFadingOut = true
            outgoingSession.crossfadeDurationMs = crossfadeMs
            activeSession = session
            println("FfmpegAudioPumpBackend: 📻 Кроссфейд (${crossfadeMs}мс) между сессиями ${outgoingSession.id} -> ${session.id}")
        } else {
            activeSession = session
            fadingSession = null
            outgoingSession?.close()
            outgoingFading?.close()
            try { javaSoundLine?.flush() } catch (_: Exception) {}
        }

        isPlayingState = true
        isPausedState = false
        currentPositionMs = seekPosMs

        ensureAudioPumpRunning()

        Thread {
            if (sessionId != currentSessionId || session.isClosed) return@Thread
            var proc: Process? = null
            try {
                val args = mutableListOf(ffmpeg, "-v", "error", "-nostdin", "-vn", "-sn")
                if (seekPosMs > 0L) {
                    args.addAll(listOf("-ss", String.format(java.util.Locale.US, "%.3f", seekPosMs / 1000.0)))
                }
                if (sourceStr.startsWith("http://", ignoreCase = true) || sourceStr.startsWith("https://", ignoreCase = true)) {
                    args.addAll(listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"))
                }
                args.addAll(listOf("-i", sourceStr, "-f", "s16le", "-ar", "44100", "-ac", "2", "-"))

                val pb = ProcessBuilder(args)
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                proc = pb.start()
                session.proc = proc

                val inStream = proc.inputStream
                val buffer = ByteArray(4096)
                var offset = 0
                while (!Thread.currentThread().isInterrupted && !session.isClosed && (session == activeSession || session == fadingSession)) {
                    val bytesRead = inStream.read(buffer, offset, buffer.size - offset)
                    if (bytesRead == -1) {
                        if (offset > 0) {
                            val alignedLen = offset - (offset % 4)
                            if (alignedLen > 0) {
                                val chunk = buffer.copyOf(alignedLen)
                                while (!session.isClosed && !Thread.currentThread().isInterrupted) {
                                    if (session.pcmQueue.offer(chunk, 100, TimeUnit.MILLISECONDS)) break
                                }
                            }
                        }
                        break
                    }
                    offset += bytesRead
                    if (offset == buffer.size) {
                        val chunk = buffer.clone()
                        offset = 0
                        while (!session.isClosed && !Thread.currentThread().isInterrupted) {
                            if (session.pcmQueue.offer(chunk, 100, TimeUnit.MILLISECONDS)) break
                        }
                    }
                }
                session.isEofReached = true
            } catch (e: Exception) {
                if (sessionId == currentSessionId && session == activeSession) {
                    println("FfmpegAudioPumpBackend: Ошибка потока ffmpeg: ${e.message}")
                }
            } finally {
                session.isEofReached = true
                try { proc?.destroyForcibly() } catch (_: Exception) {}
            }
        }.apply {
            isDaemon = true
            name = "AudioPlayer-FfmpegDecoder-$sessionId"
            session.thread = this
            start()
        }

        callback.onPlaybackStarted(sessionId)
    }

    @Synchronized
    private fun ensureSharedLine(): SourceDataLine? {
        val existing = javaSoundLine
        if (existing != null && existing.isOpen) {
            try {
                if (!existing.isActive) existing.start()
            } catch (_: Exception) {}
            return existing
        }
        val audioFormat = AudioFormat(44100f, 16, 2, true, false)
        val bufferSize = 44100 * 4 / 5 // ~35280 bytes = 200ms
        val info = DataLine.Info(SourceDataLine::class.java, audioFormat)

        var line: SourceDataLine? = null
        try {
            line = AudioSystem.getLine(info) as SourceDataLine
            line.open(audioFormat, bufferSize)
            line.start()
            javaSoundLine = line
            return line
        } catch (_: Exception) {
            try { line?.close() } catch (_: Exception) {}
        }

        try {
            for (mixerInfo in AudioSystem.getMixerInfo()) {
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(info)) {
                        val mLine = mixer.getLine(info) as SourceDataLine
                        mLine.open(audioFormat, bufferSize)
                        mLine.start()
                        javaSoundLine = mLine
                        return mLine
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        return null
    }

    private fun ensureAudioPumpRunning() {
        if (isPumpRunning) return
        synchronized(pumpLock) {
            if (isPumpRunning) return
            isPumpRunning = true
            Thread({
                audioPumpLoop()
            }, "AudioPlayer-JvmPump").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun audioPumpLoop() {
        while (isPumpRunning) {
            if (isPausedState) {
                try { Thread.sleep(20) } catch (_: Exception) {}
                continue
            }

            val active = activeSession
            val fading = fadingSession

            if (active == null && fading == null) {
                try { Thread.sleep(20) } catch (_: Exception) {}
                continue
            }

            val line = ensureSharedLine()
            if (line == null || !line.isOpen) {
                try { Thread.sleep(50) } catch (_: Exception) {}
                continue
            }

            // Предбуферизация
            if (active != null && fading != null && !fading.isClosed &&
                active.lineStartMicrosecondPosition == 0L && active.pcmQueue.size < 6 && !active.isEofReached) {
                val fadingChunk = fading.pcmQueue.poll(20, TimeUnit.MILLISECONDS)
                if (fadingChunk != null) {
                    if (EqualizerEngine.isEnabled) {
                        EqualizerEngine.processPcm(fadingChunk, fadingChunk.size)
                    }
                    AudioVisualizer.processPcmChunk(fadingChunk, fadingChunk.size)
                    PcmGainProcessor.applyGain(fadingChunk, fadingChunk.size, currentVolume)
                    line.write(fadingChunk, 0, fadingChunk.size)
                } else if (fading.isEofReached && fading.pcmQueue.isEmpty()) {
                    fading.close()
                    fadingSession = null
                }
                continue
            }

            val activeChunk = active?.pcmQueue?.poll()

            if (active != null && activeChunk != null) {
                if (active.lineStartMicrosecondPosition == 0L) {
                    active.lineStartMicrosecondPosition = line.microsecondPosition
                    if (active.crossfadeDurationMs > 0L) {
                        val nowNano = System.nanoTime()
                        active.fadeStartTimeNano = nowNano
                        active.fadeDurationNano = active.crossfadeDurationMs * 1_000_000L
                        if (fading != null && !fading.isClosed) {
                            fading.fadeStartTimeNano = nowNano
                            fading.fadeDurationNano = active.fadeDurationNano
                        }
                    }
                }

                val startNano = active.fadeStartTimeNano
                val durNano = active.fadeDurationNano
                val elapsedNano = if (startNano > 0L) System.nanoTime() - startNano else 0L

                if (durNano > 0L && elapsedNano < durNano) {
                    val p = (elapsedNano.toDouble() / durNano.toDouble()).coerceIn(0.0, 1.0)
                    val gainActive = kotlin.math.sin(p * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)
                    val gainFading = kotlin.math.cos(p * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)

                    val fadingChunk = if (fading != null && !fading.isClosed) {
                        fading.pcmQueue.poll(10, TimeUnit.MILLISECONDS)
                    } else null

                    if (fadingChunk != null) {
                        PcmGainProcessor.mixPcm(
                            activeChunk, activeChunk.size,
                            fadingChunk, fadingChunk.size,
                            gainActive, gainFading
                        )
                    } else {
                        PcmGainProcessor.applyGainDirect(activeChunk, activeChunk.size, gainActive)
                    }

                    if (fading != null && fading.isEofReached && fading.pcmQueue.isEmpty()) {
                        fading.close()
                        fadingSession = null
                    }
                } else if (fading != null) {
                    fading.close()
                    fadingSession = null
                }

                if (active.id == currentSessionId) {
                    if (EqualizerEngine.isEnabled) {
                        EqualizerEngine.processPcm(activeChunk, activeChunk.size)
                    }
                    AudioVisualizer.processPcmChunk(activeChunk, activeChunk.size)
                }

                PcmGainProcessor.applyGain(activeChunk, activeChunk.size, currentVolume)
                line.write(activeChunk, 0, activeChunk.size)

                if (active.id == currentSessionId) {
                    val elapsedMs = (line.microsecondPosition - active.lineStartMicrosecondPosition) / 1000L
                    val currentPos = (active.seekPosMs + elapsedMs).coerceAtLeast(0L)
                    currentPositionMs = currentPos

                    val expectedDur = nearEndExpectedDurationMs.takeIf { it > 0 } ?: durationMs
                    val threshold = nearEndThresholdMs
                    if (!active.nearEndTriggered && expectedDur > 6000L && threshold > 0L && currentPos >= (expectedDur - threshold)) {
                        active.nearEndTriggered = true
                        try {
                            callback.onNearEnd(active.id)
                        } catch (_: Exception) {}
                    }
                }

            } else if (active != null && active.isEofReached && active.pcmQueue.isEmpty()) {
                val finishedSessionId = active.id
                active.close()
                activeSession = null
                isPlayingState = false
                currentPositionMs = durationMs
                if (fadingSession == null) {
                    try { line.drain() } catch (_: Exception) {}
                }
                if (finishedSessionId == currentSessionId) {
                    try {
                        callback.onCompletion(finishedSessionId)
                    } catch (_: Exception) {}
                }
            } else if (fading != null && !fading.isClosed) {
                val fadingChunk = fading.pcmQueue.poll(20, TimeUnit.MILLISECONDS)
                if (fadingChunk != null) {
                    if (fading.fadeDurationNano > 0L) {
                        val elapsedNano = System.nanoTime() - fading.fadeStartTimeNano
                        val durNano = fading.fadeDurationNano
                        if (durNano > 0L && elapsedNano < durNano) {
                            val p = (elapsedNano.toDouble() / durNano.toDouble()).coerceIn(0.0, 1.0)
                            val gainFading = kotlin.math.cos(p * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)
                            PcmGainProcessor.applyGainDirect(fadingChunk, fadingChunk.size, gainFading)
                        }
                    }
                    if (EqualizerEngine.isEnabled) {
                        EqualizerEngine.processPcm(fadingChunk, fadingChunk.size)
                    }
                    AudioVisualizer.processPcmChunk(fadingChunk, fadingChunk.size)
                    PcmGainProcessor.applyGain(fadingChunk, fadingChunk.size, currentVolume)
                    line.write(fadingChunk, 0, fadingChunk.size)
                } else if (fading.isEofReached && fading.pcmQueue.isEmpty()) {
                    fading.close()
                    fadingSession = null
                }
            } else {
                try { Thread.sleep(10) } catch (_: Exception) {}
            }
        }
    }

    override fun pause() {
        isPausedState = true
        isPlayingState = false
        AudioVisualizer.reset()
    }

    override fun resume(resumePosMs: Long) {
        val session = activeSession
        val line = javaSoundLine
        if (session != null && line != null && line.isOpen) {
            session.seekPosMs = resumePosMs
            session.lineStartMicrosecondPosition = line.microsecondPosition
        }
        currentPositionMs = resumePosMs
        isPlayingState = true
        isPausedState = false
    }

    override fun stop() {
        fadingSession?.close()
        fadingSession = null
        activeSession?.close()
        activeSession = null
        AudioVisualizer.reset()

        try {
            javaSoundLine?.stop()
            javaSoundLine?.flush()
        } catch (_: Exception) {}

        isPlayingState = false
        isPausedState = false
        currentPositionMs = 0L
    }

    override fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs
        AudioVisualizer.clearDelayQueue()
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
    }

    override fun getDurationMs(): Long = durationMs

    fun setDurationMs(dur: Long) {
        durationMs = dur
    }

    override fun getCurrentPositionMs(): Long {
        if (isPausedState) return currentPositionMs

        val session = activeSession
        val line = javaSoundLine
        if (session != null && line != null && line.isOpen) {
            val startPos = session.lineStartMicrosecondPosition
            if (startPos > 0L) {
                val elapsedMs = (line.microsecondPosition - startPos) / 1000L
                val pos = (session.seekPosMs + elapsedMs).coerceAtLeast(0L)
                currentPositionMs = pos
                return pos
            }
            return session.seekPosMs
        }
        return currentPositionMs
    }

    override fun isPlaying(): Boolean = isPlayingState && !isPausedState

    override fun updateNearEndThreshold(thresholdMs: Long, expectedDurationMs: Long) {
        nearEndThresholdMs = thresholdMs
        nearEndExpectedDurationMs = expectedDurationMs
    }

    override fun release() {
        stop()
        isPumpRunning = false
        try {
            javaSoundLine?.close()
        } catch (_: Exception) {}
        javaSoundLine = null
    }
}
