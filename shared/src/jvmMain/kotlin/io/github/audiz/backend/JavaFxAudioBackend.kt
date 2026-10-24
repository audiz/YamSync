package io.github.audiz.backend

import io.github.audiz.dsp.AudioVisualizer
import io.github.audiz.volumeToGain
import kotlinx.coroutines.*
import java.io.File

/**
 * ☕ Аудио-бэкенд на базе JavaFX Media (основной плеер для MP3, AAC/M4A, WAV на Windows, macOS и Linux)
 */
class JavaFxAudioBackend(
    private val callback: AudioBackendCallback
) : AudioBackend {

    override val name: String = "JavaFX Media"

    object JavaFxInitializer {
        @Volatile private var isInitialized = false
        @Volatile private var available = false
        private val lock = Any()

        fun isAvailable(): Boolean {
            if (!isInitialized) {
                synchronized(lock) {
                    if (!isInitialized) {
                        try {
                            javafx.application.Platform.startup {}
                            available = true
                            println("JavaFxAudioBackend: JavaFX Media успешно инициализирован.")
                        } catch (_: IllegalStateException) {
                            available = true
                        } catch (t: Throwable) {
                            println("JavaFxAudioBackend: JavaFX Platform.startup недоступен: ${t.message}")
                            available = false
                        }
                        isInitialized = true
                    }
                }
            }
            return available
        }
    }

    override fun isAvailable(): Boolean = JavaFxInitializer.isAvailable()

    @Volatile private var fxPlayer: javafx.scene.media.MediaPlayer? = null
    @Volatile private var fadingFxPlayer: javafx.scene.media.MediaPlayer? = null
    private var crossfadeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var currentSessionId: Long = 0L
    @Volatile private var durationMs: Long = 0L
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var isPlayingState: Boolean = false
    @Volatile private var isPausedState: Boolean = false
    @Volatile private var currentVolume: Float = 1f

    @Volatile private var pauseStartTimeNano: Long = 0L
    @Volatile private var pauseOffsetMs: Long = 0L

    override fun play(
        source: PlaybackSource,
        sessionId: Long,
        seekPosMs: Long,
        crossfadeMs: Long
    ) {
        currentSessionId = sessionId
        isPlayingState = true
        isPausedState = false
        currentPositionMs = seekPosMs
        pauseOffsetMs = seekPosMs
        pauseStartTimeNano = System.nanoTime()

        val uri = when (source) {
            is PlaybackSource.Url -> source.url
            is PlaybackSource.LocalFile -> source.file.toURI().toString()
        }

        runOnFxThread {
            if (sessionId != currentSessionId) return@runOnFxThread
            try {
                val media = javafx.scene.media.Media(uri)
                val player = javafx.scene.media.MediaPlayer(media)
                val outgoing = fxPlayer
                val canCrossfade = outgoing != null &&
                        outgoing.status == javafx.scene.media.MediaPlayer.Status.PLAYING &&
                        crossfadeMs > 0L

                if (canCrossfade) {
                    player.volume = 0.0
                    fadingFxPlayer = outgoing
                } else {
                    player.volume = volumeToGain(currentVolume).toDouble()
                    try {
                        outgoing?.stop()
                        outgoing?.dispose()
                    } catch (_: Exception) {}
                    fadingFxPlayer = null
                }
                fxPlayer = player
                setupAudioSpectrum(player, sessionId)

                player.setOnReady {
                    if (sessionId == currentSessionId && fxPlayer == player) {
                        val dur = media.duration
                        if (dur != null && !dur.isUnknown) {
                            durationMs = dur.toMillis().toLong()
                        }
                        if (seekPosMs > 0L) {
                            player.seek(javafx.util.Duration.millis(seekPosMs.toDouble()))
                        }
                        player.play()
                        isPlayingState = true
                        isPausedState = false
                        pauseStartTimeNano = System.nanoTime()
                        pauseOffsetMs = seekPosMs
                        callback.onPlaybackStarted(sessionId)

                        if (canCrossfade && fadingFxPlayer != null) {
                            startFxCrossfade(player, crossfadeMs)
                        }
                    } else {
                        player.stop()
                        player.dispose()
                    }
                }

                player.setOnPlaying {
                    if (sessionId == currentSessionId && fxPlayer == player) {
                        isPlayingState = true
                        isPausedState = false
                    }
                }

                player.setOnPaused {
                    if (sessionId == currentSessionId && fxPlayer == player) {
                        isPlayingState = false
                        isPausedState = true
                    }
                }

                player.setOnEndOfMedia {
                    if (sessionId == currentSessionId && fxPlayer == player) {
                        isPlayingState = false
                        isPausedState = false
                        currentPositionMs = durationMs
                        callback.onCompletion(sessionId)
                    }
                }

                player.setOnError {
                    if (sessionId == currentSessionId && fxPlayer == player) {
                        val err = player.error?.message ?: "Unknown JavaFX error"
                        println("JavaFxAudioBackend: Ошибка JavaFX: $err. Вызываем fallback...")
                        fxPlayer = null
                        player.dispose()
                        isPlayingState = false
                        callback.onError(sessionId, err, canFallback = true)
                    }
                }

                player.play()
            } catch (e: Throwable) {
                if (sessionId == currentSessionId) {
                    val msg = e.message ?: "JavaFX initialization error"
                    println("JavaFxAudioBackend: Исключение запуска JavaFX: $msg. Вызываем fallback...")
                    fxPlayer = null
                    isPlayingState = false
                    callback.onError(sessionId, msg, canFallback = true)
                }
            }
        }
    }

    private fun startFxCrossfade(newPlayer: javafx.scene.media.MediaPlayer, crossfadeMs: Long) {
        crossfadeJob?.cancel()
        val stepIntervalMs = 40L
        val steps = (crossfadeMs / stepIntervalMs).toInt().coerceAtLeast(1)

        crossfadeJob = scope.launch {
            for (step in 1..steps) {
                delay(stepIntervalMs)
                val t = step.toFloat() / steps
                val outFactor = kotlin.math.cos(t * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)
                val inFactor = kotlin.math.sin(t * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)

                val outGain = volumeToGain(currentVolume * outFactor).toDouble()
                val inGain = volumeToGain(currentVolume * inFactor).toDouble()

                runOnFxThread {
                    try { fadingFxPlayer?.volume = outGain } catch (_: Exception) {}
                    try { newPlayer.volume = inGain } catch (_: Exception) {}
                }
            }

            runOnFxThread {
                try {
                    fadingFxPlayer?.stop()
                    fadingFxPlayer?.dispose()
                } catch (_: Exception) {}
                fadingFxPlayer = null
                newPlayer.volume = volumeToGain(currentVolume).toDouble()
            }
        }
    }

    private fun setupAudioSpectrum(player: javafx.scene.media.MediaPlayer, sessionId: Long) {
        try {
            player.audioSpectrumInterval = 0.033 // ~30 кадров/сек от аудиодекодера
            player.audioSpectrumNumBands = 128
            player.audioSpectrumThreshold = -60
            player.setAudioSpectrumListener { _, _, magnitudes, _ ->
                if (sessionId == currentSessionId && fxPlayer == player && isPlayingState && !isPausedState) {
                    if (magnitudes != null && magnitudes.size >= 64) {
                        val threshold = -60f

                        fun calcBandEnergy(start: Int, end: Int, tiltDb: Float, peakWeight: Float = 0.65f): Float {
                            val s = start.coerceIn(0, magnitudes.size - 1)
                            val e = end.coerceIn(0, magnitudes.size - 1)
                            if (s > e) return 0f
                            var sum = 0f
                            var maxDb = -120f
                            for (k in s..e) {
                                val v = magnitudes[k]
                                sum += v
                                if (v > maxDb) maxDb = v
                            }
                            val avgDb = sum / (e - s + 1)
                            val combinedDb = (maxDb * peakWeight + avgDb * (1f - peakWeight)) + tiltDb
                            return ((combinedDb - threshold) / (-threshold)).coerceIn(0f, 1f)
                        }

                        // 🎛️ 25 частотных полос (20 Гц .. 20 кГц) для обложки
                        val bandRanges25 = arrayOf(
                            0 to 0,   1 to 1,   2 to 2,   3 to 3,   4 to 4,
                            5 to 5,   6 to 6,   7 to 7,   8 to 8,   9 to 10,
                            11 to 12, 13 to 15, 16 to 18, 19 to 22, 23 to 27,
                            28 to 33, 34 to 40, 41 to 48, 49 to 57, 58 to 67,
                            68 to 78, 79 to 89, 90 to 100, 101 to 111, 112 to 124
                        )
                        val spectrum25 = FloatArray(25)
                        for (k in 0 until 25) {
                            val (startBin, endBin) = bandRanges25[k]
                            val progress = k.toFloat() / 24f
                            val tilt = -7.0f + progress * 23.0f
                            spectrum25[k] = calcBandEnergy(startBin, endBin, tiltDb = tilt, peakWeight = 0.75f)
                        }

                        // 5 полос для микро-иконок
                        val b1 = calcBandEnergy(0, 0, tiltDb = -7.0f)
                        val b2 = calcBandEnergy(1, 3, tiltDb = 4.5f, peakWeight = 0.85f)
                        val b3 = calcBandEnergy(4, 12, tiltDb = 3.0f)
                        val b4 = calcBandEnergy(13, 37, tiltDb = 9.0f)
                        val b5 = calcBandEnergy(38, 87, tiltDb = 15.5f)

                        AudioVisualizer.updateBands(b1, b2, b3, b4, b5)
                        AudioVisualizer.updateSpectrum(spectrum25)
                    }
                }
            }
        } catch (e: Throwable) {
            println("JavaFxAudioBackend: Не удалось настроить AudioSpectrumListener: ${e.message}")
        }
    }

    override fun pause() {
        val pausedPos = getCurrentPositionMs()
        currentPositionMs = pausedPos
        pauseOffsetMs = pausedPos
        isPausedState = true
        isPlayingState = false
        AudioVisualizer.reset()

        runOnFxThread {
            try {
                fxPlayer?.pause()
                fadingFxPlayer?.pause()
            } catch (_: Exception) {}
        }
    }

    override fun resume(resumePosMs: Long) {
        pauseOffsetMs = resumePosMs
        pauseStartTimeNano = System.nanoTime()
        isPlayingState = true
        isPausedState = false

        runOnFxThread {
            try {
                fadingFxPlayer?.play()
                val player = fxPlayer
                if (player != null) {
                    val fxTime = player.currentTime
                    if (fxTime != null && !fxTime.isUnknown) {
                        val fxMs = fxTime.toMillis().toLong()
                        if (kotlin.math.abs(fxMs - resumePosMs) > 1500) {
                            player.seek(javafx.util.Duration.millis(resumePosMs.toDouble()))
                        }
                    }
                    player.play()
                }
            } catch (_: Exception) {}
        }
    }

    override fun stop() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        AudioVisualizer.reset()

        val fading = fadingFxPlayer
        fadingFxPlayer = null
        val oldPlayer = fxPlayer
        fxPlayer = null

        runOnFxThread {
            try {
                fading?.stop()
                fading?.dispose()
            } catch (_: Exception) {}
            try {
                oldPlayer?.stop()
                oldPlayer?.dispose()
            } catch (_: Exception) {}
        }

        isPlayingState = false
        isPausedState = false
        currentPositionMs = 0L
        durationMs = 0L
        pauseOffsetMs = 0L
        pauseStartTimeNano = 0L
    }

    override fun seekTo(positionMs: Long) {
        val targetMs = positionMs.coerceAtLeast(0L)
        currentPositionMs = targetMs
        pauseOffsetMs = targetMs
        pauseStartTimeNano = System.nanoTime()
        AudioVisualizer.clearDelayQueue()

        runOnFxThread {
            try {
                fxPlayer?.seek(javafx.util.Duration.millis(targetMs.toDouble()))
            } catch (_: Exception) {}
        }
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        val gain = volumeToGain(currentVolume)
        if (crossfadeJob?.isActive != true) {
            runOnFxThread {
                try {
                    fxPlayer?.volume = gain.toDouble()
                } catch (_: Exception) {}
            }
        }
    }

    override fun getDurationMs(): Long {
        val player = fxPlayer
        if (player != null) {
            val dur = player.media.duration
            if (dur != null && !dur.isUnknown) {
                return dur.toMillis().toLong()
            }
        }
        return durationMs
    }

    override fun getCurrentPositionMs(): Long {
        if (isPausedState) return currentPositionMs

        val player = fxPlayer
        if (player != null) {
            val time = player.currentTime
            if (time != null && !time.isUnknown) {
                val fxMs = time.toMillis().toLong()
                if (pauseStartTimeNano > 0L) {
                    val elapsedSinceResume = (System.nanoTime() - pauseStartTimeNano) / 1_000_000
                    val maxExpectedPos = pauseOffsetMs + elapsedSinceResume + 2500
                    // Protect against Linux GStreamer clock jumping forward by pause duration
                    if (fxMs > maxExpectedPos + 3000) {
                        val correctedPos = (pauseOffsetMs + elapsedSinceResume).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
                        currentPositionMs = correctedPos
                        runOnFxThread {
                            try {
                                player.seek(javafx.util.Duration.millis(correctedPos.toDouble()))
                            } catch (_: Exception) {}
                        }
                        return correctedPos
                    }
                }
                currentPositionMs = fxMs
                return fxMs
            }
        }
        return currentPositionMs
    }

    override fun isPlaying(): Boolean = isPlayingState && !isPausedState

    override fun release() {
        stop()
        scope.cancel()
    }

    private fun runOnFxThread(action: () -> Unit) {
        try {
            if (javafx.application.Platform.isFxApplicationThread()) {
                action()
            } else {
                javafx.application.Platform.runLater(action)
            }
        } catch (_: Throwable) {
            action()
        }
    }
}
