package io.github.audiz

import javazoom.jl.player.advanced.AdvancedPlayer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 🎵 Кросс-платформенная JVM-реализация аудиоплеера для Windows и Linux (и macOS).
 *
 * Поддерживает:
 * - AAC в MP4/M4A контейнере (основной формат Яндекса: isom / mp4a)
 * - MP3
 * - WAV
 *
 * Архитектура:
 * 1. JavaFX Media (основной движок для Win / Linux / Mac)
 * 2. Fallback на JLayer (для MP3 при отсутствии системных кодеков)
 * 3. Fallback на системные плееры ffplay/mpv (на Linux при отсутствии JavaFX Media)
 */
actual class AudioPlayer actual constructor() {

    private object JavaFxInitializer {
        private val started = AtomicBoolean(false)
        private var available = false

        fun isAvailable(): Boolean {
            ensureStarted()
            return available
        }

        private fun ensureStarted() {
            if (started.compareAndSet(false, true)) {
                try {
                    javafx.application.Platform.startup {}
                    available = true
                    println("AudioPlayer JVM: JavaFX Media успешно инициализирован.")
                } catch (e: IllegalStateException) {
                    available = true
                } catch (t: Throwable) {
                    println("AudioPlayer JVM: JavaFX Platform.startup недоступен: ${t.message}")
                    available = false
                }
            }
        }
    }

    // --- Состояние JavaFX MediaPlayer ---
    @Volatile private var fxPlayer: javafx.scene.media.MediaPlayer? = null
    @Volatile private var fadingFxPlayer: javafx.scene.media.MediaPlayer? = null
    private var crossfadeJob: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var durationMs: Long = 0L
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var isPlayingState: Boolean = false
    @Volatile private var isPausedState: Boolean = false
    @Volatile private var isSeekingFlag: Boolean = false
    @Volatile private var isFallbackMode: Boolean = false
    @Volatile private var cachedWavFile: File? = null
    private val tempPlaybackFiles = java.util.Collections.synchronizedList(mutableListOf<File>())

    private fun registerTempFile(file: File): File {
        tempPlaybackFiles.add(file)
        return file
    }

    // --- Сессии воспроизведения для JavaSound / ffmpeg с поддержкой программного кроссфейда ---
    private class JvmAudioSession(
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
        val pcmQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(24)

        fun close() {
            if (isClosed) return
            isClosed = true
            pcmQueue.clear()
            try { thread?.interrupt() } catch (_: Exception) {}
            try { proc?.inputStream?.close() } catch (_: Exception) {}
            try { proc?.destroyForcibly() } catch (_: Exception) {}
        }
    }

    @Volatile private var activeSession: JvmAudioSession? = null
    @Volatile private var fadingSession: JvmAudioSession? = null
    @Volatile private var isPumpRunning: Boolean = false
    private val pumpLock = Any()

    @Volatile private var onNearEndListener: (() -> Unit)? = null
    @Volatile private var nearEndThresholdMs: Long = 0L
    @Volatile private var nearEndExpectedDurationMs: Long = 0L

    // --- Fallback состояние (JLayer / Process) ---
    private var jlayerThread: Thread? = null
    private var jlayerPlayer: AdvancedPlayer? = null
    private var fallbackProcess: Process? = null
    @Volatile private var jlayerStartTimeNano: Long = 0L
    @Volatile private var jlayerPauseOffsetMs: Long = 0L
    @Volatile private var javaSoundLine: javax.sound.sampled.SourceDataLine? = null
    @Volatile private var currentSessionId: Long = 0L
    private var currentFile: File? = null
    @Volatile private var currentStreamUrl: String? = null
    @Volatile private var onCompletionListener: (() -> Unit)? = null
    @Volatile private var onInterruptionListener: ((shouldPause: Boolean, canResume: Boolean) -> Unit)? = null
    @Volatile private var onErrorListener: ((String) -> Unit)? = null
    @Volatile private var onPlaybackStartedListener: (() -> Unit)? = null

    private fun startFxCrossfade(newPlayer: javafx.scene.media.MediaPlayer, crossfadeMs: Long) {
        crossfadeJob?.cancel()
        val stepIntervalMs = 40L
        val steps = (crossfadeMs / stepIntervalMs).toInt().coerceAtLeast(1)

        crossfadeJob = playerScope.launch {
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

    private fun stopJvmPlayback() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        val fading = fadingFxPlayer
        fadingFxPlayer = null
        val oldPlayer = fxPlayer
        fxPlayer = null
        if (fading != null || oldPlayer != null) {
            runOnFxThread {
                try { fading?.stop(); fading?.dispose() } catch (_: Exception) {}
                try { oldPlayer?.stop(); oldPlayer?.dispose() } catch (_: Exception) {}
            }
        }
        val outgoingActive = activeSession
        activeSession = null
        val outgoingFading = fadingSession
        fadingSession = null
        outgoingActive?.close()
        outgoingFading?.close()

        try { fallbackProcess?.destroyForcibly() } catch (_: Exception) {}
        fallbackProcess = null
        try { jlayerPlayer?.close() } catch (_: Exception) {}
        jlayerPlayer = null

        val oldThread = jlayerThread
        jlayerThread = null
        try { oldThread?.interrupt() } catch (_: Exception) {}

        try {
            javaSoundLine?.stop()
            javaSoundLine?.flush()
        } catch (_: Exception) {}

        durationMs = 0L
        currentPositionMs = 0L
        jlayerPauseOffsetMs = 0L
        jlayerStartTimeNano = 0L
        cachedWavFile = null
    }

    private fun prepareForNewPlayback(crossfadeMs: Long) {
        crossfadeJob?.cancel()
        crossfadeJob = null
        if (crossfadeMs <= 0L) {
            stopJvmPlayback()
        } else {
            // Закрываем предыдущую увядающую сессию, если она еще активна
            val prevFading = fadingSession
            fadingSession = null
            prevFading?.close()

            val prevFadingFx = fadingFxPlayer
            fadingFxPlayer = null
            if (prevFadingFx != null) {
                runOnFxThread {
                    try { prevFadingFx.stop(); prevFadingFx.dispose() } catch (_: Exception) {}
                }
            }

            durationMs = 0L
            currentPositionMs = 0L
            jlayerPauseOffsetMs = 0L
            jlayerStartTimeNano = 0L
            cachedWavFile = null
        }
    }

    actual fun playFromBytes(bytes: ByteArray) = playFromBytes(bytes, 0L)

    actual fun playFromBytes(bytes: ByteArray, crossfadeMs: Long) {
        prepareForNewPlayback(crossfadeMs)
        val ext = when {
            bytes.size >= 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                    bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() -> ".m4a"
            bytes.size >= 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                    bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte() -> ".flac"
            else -> ".mp3"
        }
        try {
            val tmp = File.createTempFile("ya_stream_", ext)
            tmp.deleteOnExit()
            tmp.writeBytes(bytes)
            registerTempFile(tmp)
            playFileInternal(tmp, crossfadeMs)
        } catch (e: Exception) {
            println("AudioPlayer: Ошибка сохранения потока во временный файл: ${e.message}")
            isPlayingState = false
        }
    }

    actual fun playFromFile(filePath: String) = playFromFile(filePath, 0L)

    actual fun playFromFile(filePath: String, crossfadeMs: Long) {
        prepareForNewPlayback(crossfadeMs)
        val file = File(filePath)
        if (!file.exists()) {
            println("AudioPlayer: Файл не найден: $filePath")
            isPlayingState = false
            return
        }
        playFileInternal(file, crossfadeMs)
    }

    actual fun playFromUrl(url: String) = playFromUrl(url, 0L)

    actual fun playFromUrl(url: String, crossfadeMs: Long) {
        prepareForNewPlayback(crossfadeMs)
        val sessionId = ++currentSessionId
        isPlayingState = true
        isPausedState = false
        currentFile = null
        currentStreamUrl = url
        currentPositionMs = 0L
        durationMs = 0L
        jlayerPauseOffsetMs = 0L
        jlayerStartTimeNano = System.nanoTime()
        onPlaybackStartedListener?.invoke()

        if (hasFfmpeg() || url.contains(".flac", ignoreCase = true) || io.github.audiz.dsp.EqualizerEngine.isEnabled) {
            playStreamWithFfmpeg(url, sessionId, crossfadeMs = crossfadeMs)
            return
        }

        if (!isFallbackMode && JavaFxInitializer.isAvailable()) {
            playUrlWithJavaFx(url, sessionId, crossfadeMs)
        } else {
            playStreamWithFfmpeg(url, sessionId, crossfadeMs = crossfadeMs)
        }
    }

    private fun playUrlWithJavaFx(url: String, sessionId: Long, crossfadeMs: Long = 0L) {
        runOnFxThread {
            if (sessionId != currentSessionId) return@runOnFxThread
            try {
                val media = javafx.scene.media.Media(url)
                val player = javafx.scene.media.MediaPlayer(media)
                val outgoing = fxPlayer
                val canCrossfade = outgoing != null && outgoing.status == javafx.scene.media.MediaPlayer.Status.PLAYING && crossfadeMs > 0L

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
                        player.play()
                        isPlayingState = true
                        isPausedState = false
                        jlayerStartTimeNano = System.nanoTime()
                        jlayerPauseOffsetMs = 0L
                        println("AudioPlayer: JavaFX стриминг начат ($url)")
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
                        onCompletionListener?.invoke()
                    }
                }

                player.setOnError {
                    if (sessionId == currentSessionId && fxPlayer == player) {
                        val err = player.error
                        println("AudioPlayer: Ошибка JavaFX при стриминге: ${err?.message}. Пробуем fallback...")
                        fxPlayer = null
                        player.dispose()
                        playStreamWithFfmpeg(url, sessionId)
                    }
                }

                player.play()
            } catch (e: Throwable) {
                if (sessionId == currentSessionId) {
                    println("AudioPlayer: Исключение запуска JavaFX стриминга: ${e.message}. Пробуем fallback...")
                    fxPlayer = null
                    playStreamWithFfmpeg(url, sessionId)
                }
            }
        }
    }

    private fun playFileInternal(file: File, crossfadeMs: Long = 0L) {
        val sessionId = ++currentSessionId
        isPlayingState = true
        isPausedState = false
        currentFile = file
        currentStreamUrl = null
        currentPositionMs = 0L
        durationMs = 0L
        jlayerPauseOffsetMs = 0L
        jlayerStartTimeNano = System.nanoTime()
        onPlaybackStartedListener?.invoke()

        // Если файл назван .aac, но физически это контейнер MP4 (isom / M4A),
        // JavaFX Media требует расширение .m4a для корректного демультиплексирования.
        val playFile = preparePlayableFile(file)

        if (hasFfmpeg() || isFlacFile(playFile) || io.github.audiz.dsp.EqualizerEngine.isEnabled) {
            isFallbackMode = true
            playWithFallback(playFile, sessionId, crossfadeMs = crossfadeMs)
        } else if (!isFallbackMode && JavaFxInitializer.isAvailable()) {
            playWithJavaFx(playFile, sessionId, crossfadeMs)
        } else {
            isFallbackMode = true
            playWithFallback(playFile, sessionId, crossfadeMs = crossfadeMs)
        }
    }

    private fun playWithJavaFx(file: File, sessionId: Long, crossfadeMs: Long = 0L) {
        runOnFxThread {
            if (sessionId != currentSessionId) return@runOnFxThread
            try {
                val uri = file.toURI().toString()
                val media = javafx.scene.media.Media(uri)
                val player = javafx.scene.media.MediaPlayer(media)
                val outgoing = fxPlayer
                val canCrossfade = outgoing != null && outgoing.status == javafx.scene.media.MediaPlayer.Status.PLAYING && crossfadeMs > 0L

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
                        player.play()
                        isPlayingState = true
                        isPausedState = false
                        jlayerStartTimeNano = System.nanoTime()
                        jlayerPauseOffsetMs = 0L
                        println("AudioPlayer: JavaFX воспроизведение начато (${durationMs / 1000}s): ${file.name}")
                        if (canCrossfade && fadingFxPlayer != null) {
                            startFxCrossfade(player, crossfadeMs)
                        }
                    } else {
                        // The user already switched to another track, stop this old player instance
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
                        onCompletionListener?.invoke()
                    }
                }

                player.setOnError {
                    if (sessionId == currentSessionId && fxPlayer == player) {
                        val err = player.error
                        println("AudioPlayer: Ошибка JavaFX Media: ${err?.message}. Пробуем fallback...")
                        isFallbackMode = true
                        fxPlayer = null
                        player.dispose()
                        playWithFallback(file, sessionId, crossfadeMs = crossfadeMs)
                    }
                }

                player.play()
            } catch (e: Throwable) {
                if (sessionId == currentSessionId) {
                    println("AudioPlayer: Исключение запуска JavaFX: ${e.message}. Пробуем fallback...")
                    isFallbackMode = true
                    fxPlayer = null
                    playWithFallback(file, sessionId, crossfadeMs = crossfadeMs)
                }
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

                        // Функция расчета линейной энергии полосы (0.0 .. 1.0)
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

                        // 🎛️ 19 аппаратных частотных полос Winamp Pro (20 Гц .. 20 кГц) для спектроанализатора на обложке:
                        val bandRanges19 = arrayOf(
                            0 to 0,   1 to 1,   2 to 2,   3 to 3,   4 to 4,
                            5 to 6,   7 to 8,   9 to 10,  11 to 13, 14 to 16,
                            17 to 20, 21 to 25, 26 to 31, 32 to 38, 39 to 47,
                            48 to 58, 59 to 71, 72 to 86, 87 to 105
                        )
                        val spectrum19 = FloatArray(19)
                        for (k in 0 until 19) {
                            val (startBin, endBin) = bandRanges19[k]
                            val progress = k.toFloat() / 18f
                            val tilt = -7.0f + progress * 23.0f
                            spectrum19[k] = calcBandEnergy(startBin, endBin, tiltDb = tilt, peakWeight = 0.75f)
                        }

                        // 5 полос для микро-иконок
                        // 1. Sub-bass / Kick (~30 - 170 Гц): -7.0 дБ (упругий удар бочки с динамическим запасом)
                        val b1 = calcBandEnergy(0, 0, tiltDb = -7.0f)
                        // 2. Bass & Mid-bass (~170 - 680 Гц): +4.5 дБ (активная, читаемая бас-партия, 85% пик)
                        val b2 = calcBandEnergy(1, 3, tiltDb = 4.5f, peakWeight = 0.85f)
                        // 3. Mids (~680 - 2200 Гц): +3.0 дБ (вокал, малый барабан)
                        val b3 = calcBandEnergy(4, 12, tiltDb = 3.0f)
                        // 4. High-Mids (~2200 - 6500 Гц): +9.0 дБ (атака рабочего барабана)
                        val b4 = calcBandEnergy(13, 37, tiltDb = 9.0f)
                        // 5. Treble (~6500 - 15000 Гц): +15.5 дБ (хай-хеты и тарелки)
                        val b5 = calcBandEnergy(38, 87, tiltDb = 15.5f)

                        io.github.audiz.dsp.AudioVisualizer.updateBands(b1, b2, b3, b4, b5)
                        io.github.audiz.dsp.AudioVisualizer.updateSpectrum19(spectrum19)
                    }
                }
            }
        } catch (e: Throwable) {
            println("AudioPlayer: Не удалось настроить AudioSpectrumListener: ${e.message}")
        }
    }

    private fun playWithFallback(file: File, sessionId: Long, seekPosMs: Long = 0L, crossfadeMs: Long = 0L) {
        if (sessionId != currentSessionId) return

        if (hasFfmpeg()) {
            if (durationMs <= 0L) {
                if (isFlacFile(file)) {
                    durationMs = getExactFlacDurationMs(file).takeIf { it > 0 } ?: estimateFlacDurationMs(file)
                } else if (file.name.endsWith(".m4a", ignoreCase = true) || file.name.endsWith(".aac", ignoreCase = true) || isMp4Container(file)) {
                    durationMs = getExactM4aDurationMs(file).takeIf { it > 0 } ?: estimateM4aDurationMs(file)
                } else if (isMp3File(file)) {
                    durationMs = estimateMp3DurationMs(file)
                }
            }
            println("AudioPlayer Fallback: Воспроизведение через ffmpeg: ${file.name} (crossfade=${crossfadeMs}ms)")
            playStreamWithFfmpeg(file.absolutePath, sessionId, seekPosMs, crossfadeMs = crossfadeMs)
            return
        }

        if (isMp3File(file)) {
            println("AudioPlayer Fallback: Воспроизведение MP3 через JLayer: ${file.name}")
            playMp3WithJLayer(file, sessionId, seekPosMs)
            return
        }

        // Try extracting accurate duration using container headers
        if (durationMs <= 0L) {
            if (isFlacFile(file)) {
                durationMs = getExactFlacDurationMs(file).takeIf { it > 0 } ?: estimateFlacDurationMs(file)
            } else if (file.name.endsWith(".m4a", ignoreCase = true) || file.name.endsWith(".aac", ignoreCase = true) || isMp4Container(file)) {
                durationMs = getExactM4aDurationMs(file).takeIf { it > 0 } ?: estimateM4aDurationMs(file)
            } else if (isMp3File(file)) {
                durationMs = estimateMp3DurationMs(file)
            }
        }

        // For FLAC, M4A, AAC or other formats: decode to WAV with ffmpeg if available
        // This provides standard Java Sound playback with full volume slider control and exact seek/duration
        if (hasFfmpeg()) {
            val existingWav = cachedWavFile
            if (existingWav != null && existingWav.exists() && existingWav.length() > 0) {
                println("AudioPlayer Fallback: Переиспользуем декодированный WAV: ${existingWav.name}")
                playWithJavaSound(existingWav, sessionId, seekPosMs)
                return
            }

            val formatDesc = when {
                isFlacFile(file) -> "FLAC"
                file.name.endsWith(".m4a", ignoreCase = true) || file.name.endsWith(".aac", ignoreCase = true) || isMp4Container(file) -> "M4A/AAC"
                else -> "audio"
            }
            println("AudioPlayer Fallback: Декодируем $formatDesc в WAV с помощью ffmpeg...")
            val extracted = decodeToWavWithFfmpeg(file)
            if (sessionId != currentSessionId) {
                extracted?.delete()
                return
            }
            if (extracted != null) {
                cachedWavFile = extracted
                registerTempFile(extracted)
                println("AudioPlayer Fallback: Воспроизведение через Java Sound API: ${extracted.name}")
                playWithJavaSound(extracted, sessionId, seekPosMs)
                return
            }
        }

        if (isFlacFile(file)) {
            println("AudioPlayer Fallback: Воспроизведение FLAC напрямую через Java Sound API: ${file.name}")
            playWithJavaSound(file, sessionId, seekPosMs)
            return
        }

        if (hasFfmpeg()) {
            println("AudioPlayer Fallback: Воспроизведение через ffmpeg: ${file.name}")
            playStreamWithFfmpeg(file.absolutePath, sessionId, seekPosMs)
            return
        }

        val systemPlayer = findSystemPlayer()
        if (systemPlayer != null) {
            if (durationMs == 0L) {
                if (file.name.endsWith(".m4a", ignoreCase = true) || file.name.endsWith(".aac", ignoreCase = true)) {
                    durationMs = getExactM4aDurationMs(file).takeIf { it > 0 } ?: estimateM4aDurationMs(file)
                } else if (isFlacFile(file)) {
                    durationMs = estimateFlacDurationMs(file)
                } else if (isMp3File(file)) {
                    durationMs = estimateMp3DurationMs(file)
                }
            }
            println("AudioPlayer Fallback: Воспроизведение через $systemPlayer: ${file.name}")
            playWithSystemProcess(systemPlayer, file, sessionId, seekPosMs)
            return
        }

        println("AudioPlayer Fallback: Не удалось воспроизвести ${file.name}. Установите ffplay/mpv или ffmpeg.")
        if (sessionId == currentSessionId) {
            isPlayingState = false
        }
    }

    private fun playMp3WithJLayer(file: File, sessionId: Long, seekPosMs: Long = 0L) {
        val bytes = file.readBytes()
        durationMs = estimateMp3DurationMs(file)
        jlayerThread = Thread {
            if (sessionId != currentSessionId) return@Thread
            try {
                val stream = ByteArrayInputStream(bytes)
                val bitstream = javazoom.jl.decoder.Bitstream(stream)
                val decoder = javazoom.jl.decoder.Decoder()
                val audioDevice = javazoom.jl.player.FactoryRegistry.systemRegistry().createAudioDevice()

                val firstHeader = bitstream.readFrame() ?: return@Thread
                val msPerFrame = firstHeader.ms_per_frame()

                var header: javazoom.jl.decoder.Header? = firstHeader
                
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
                jlayerStartTimeNano = System.nanoTime()
                jlayerPauseOffsetMs = seekPosMs
                currentPositionMs = seekPosMs

                while (header != null && !isPausedState && !Thread.currentThread().isInterrupted && sessionId == currentSessionId) {
                    val sampleBuffer = decoder.decodeFrame(header, bitstream) as javazoom.jl.decoder.SampleBuffer
                    val gain = volumeToGain(currentVolume)
                    if (gain < 0.999f) {
                        val sBuf = sampleBuffer.buffer
                        val sLen = sampleBuffer.bufferLength
                        if (gain <= 0.0001f) {
                            java.util.Arrays.fill(sBuf, 0, sLen, 0.toShort())
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
            } catch (e: Exception) {
                // Прерывание потока
            } finally {
                if (sessionId == currentSessionId && Thread.currentThread() == jlayerThread) {
                    isPlayingState = false
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun playWithJavaSound(file: File, sessionId: Long, seekPosMs: Long = 0L) {
        // Try getting exact duration from FLAC or WAV file if not already set
        if (durationMs <= 0L) {
            val detectedDuration = getExactFlacDurationMs(file)
            if (detectedDuration > 0) {
                durationMs = detectedDuration
            } else {
                durationMs = estimateFlacDurationMs(file)
            }
        }
        jlayerThread = Thread {
            if (sessionId != currentSessionId) return@Thread
            var line: javax.sound.sampled.SourceDataLine? = null
            var inStream: javax.sound.sampled.AudioInputStream? = null
            try {
                inStream = javax.sound.sampled.AudioSystem.getAudioInputStream(file)
                var baseFormat = inStream.format
                
                // If stream has frameLength, calculate exact duration
                val frames = inStream.frameLength
                if (frames > 0 && baseFormat.frameRate > 0) {
                    durationMs = (frames * 1000.0 / baseFormat.frameRate).toLong()
                }
                
                // If it's already PCM (like WAV extracted by ffmpeg), we don't need to convert
                val decodedFormat = if (baseFormat.encoding == javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED || 
                                      baseFormat.encoding == javax.sound.sampled.AudioFormat.Encoding.PCM_UNSIGNED) {
                    baseFormat
                } else {
                    // It's encoded (like FLAC), try to convert it (requires JFlac SPI to be working)
                    val pcmFormat = javax.sound.sampled.AudioFormat(
                        javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.sampleRate,
                        16,
                        baseFormat.channels,
                        baseFormat.channels * 2,
                        baseFormat.sampleRate,
                        false
                    )
                    inStream = javax.sound.sampled.AudioSystem.getAudioInputStream(pcmFormat, inStream)
                    pcmFormat
                }
                
                val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, decodedFormat)
                line = javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
                
                if (sessionId != currentSessionId) {
                    inStream.close()
                    return@Thread
                }

                javaSoundLine = line
                // Open with a short retry if the audio device is still releasing from the previous seek
                var opened = false
                var openAttempts = 0
                val jlayerBufSize = (decodedFormat.sampleRate * decodedFormat.frameSize * 0.15f).toInt()
                while (!opened && openAttempts < 5 && sessionId == currentSessionId) {
                    try {
                        line.open(decodedFormat, jlayerBufSize)
                        opened = true
                    } catch (lineErr: Exception) {
                        try {
                            line.open(decodedFormat)
                            opened = true
                        } catch (_: Exception) {
                            openAttempts++
                            Thread.sleep(60)
                        }
                    }
                }
                if (sessionId != currentSessionId) {
                    try { line.close() } catch (_: Exception) {}
                    try { inStream.close() } catch (_: Exception) {}
                    return@Thread
                }
                if (!opened) {
                    throw IllegalStateException("Cannot open audio line after multiple attempts")
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
                jlayerStartTimeNano = System.nanoTime()
                jlayerPauseOffsetMs = seekPosMs
                currentPositionMs = seekPosMs
                
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
                            if (io.github.audiz.dsp.EqualizerEngine.isEnabled) {
                                io.github.audiz.dsp.EqualizerEngine.processPcm(buffer, readBytes)
                            }
                            io.github.audiz.dsp.AudioVisualizer.processPcmChunk(buffer, readBytes)
                            applyPcmGain(buffer, readBytes, currentVolume)
                        }
                        line.write(buffer, 0, readBytes)
                    }
                }
                
                if (sessionId == currentSessionId && !Thread.currentThread().isInterrupted) {
                    line.drain()
                }
                try { line.stop() } catch (_: Exception) {}
                try { line.close() } catch (_: Exception) {}
                try { inStream.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                try { line?.stop() } catch (_: Exception) {}
                try { line?.close() } catch (_: Exception) {}
                try { inStream?.close() } catch (_: Exception) {}
                if (sessionId == currentSessionId && Thread.currentThread() == jlayerThread) {
                    println("AudioPlayer Fallback FLAC Error: ${e.message}")
                    if (hasFfmpeg()) {
                        playStreamWithFfmpeg(file.absolutePath, sessionId, seekPosMs)
                    } else {
                        val systemPlayer = findSystemPlayer()
                        if (systemPlayer != null) {
                            println("AudioPlayer Fallback: Переключаемся на $systemPlayer...")
                            playWithSystemProcess(systemPlayer, file, sessionId, seekPosMs)
                        }
                    }
                }
            } finally {
                if (sessionId == currentSessionId && Thread.currentThread() == jlayerThread && fallbackProcess == null) {
                    isPlayingState = false
                }
                if (Thread.currentThread() == jlayerThread) {
                    javaSoundLine = null
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Программное масштабирование амплитуды PCM 16-bit (little-endian).
     * Делегируется в кросс-платформенный PcmGainProcessor.
     */
    private fun applyPcmGain(buffer: ByteArray, bytesRead: Int, volume: Float) {
        io.github.audiz.dsp.PcmGainProcessor.applyGain(buffer, bytesRead, volume)
    }

    /**
     * Потоковое декодирование и воспроизведение аудио через ffmpeg и JavaSound SourceDataLine.
     * Поддерживает любые форматы (FLAC, M4A, MP3, OPUS), сетевые потоки и локальные файлы.
     * Ползунок громкости регулируется в реальном времени благодаря applyPcmGain.
     */
    private fun playStreamWithFfmpeg(source: String, sessionId: Long, seekPosMs: Long = 0L, crossfadeMs: Long = 0L) {
        if (sessionId != currentSessionId) return
        val ffmpeg = getFfmpegPath()
        if (ffmpeg == null) {
            println("AudioPlayer: ffmpeg не найден, пробуем системный плеер...")
            val systemPlayer = findSystemPlayer()
            if (systemPlayer != null) {
                playWithSystemProcess(systemPlayer, source, sessionId, seekPosMs)
            } else {
                isPlayingState = false
            }
            return
        }

        val session = JvmAudioSession(
            id = sessionId,
            seekPosMs = seekPosMs,
            crossfadeDurationMs = crossfadeMs
        )

        val outgoingSession = activeSession
        val outgoingFading = fadingSession

        if (crossfadeMs > 0L && outgoingSession != null && !outgoingSession.isClosed) {
            // 📻 Радио-сведение (Crossfade): переводим текущий трек в режим плавного увядания
            outgoingFading?.close()
            fadingSession = outgoingSession
            outgoingSession.isFadingOut = true
            outgoingSession.crossfadeDurationMs = crossfadeMs
            activeSession = session
            println("AudioPlayer: 📻 Запуск программного кроссфейда (${crossfadeMs}мс) между сессиями ${outgoingSession.id} -> ${session.id}")
        } else {
            activeSession = session
            fadingSession = null
            outgoingSession?.close()
            outgoingFading?.close()
            try {
                javaSoundLine?.flush()
            } catch (_: Exception) {}
        }

        isPlayingState = true
        isPausedState = false
        jlayerStartTimeNano = System.nanoTime()
        jlayerPauseOffsetMs = seekPosMs
        currentPositionMs = seekPosMs

        // Убеждаемся, что единый аудио-насос запущен
        ensureAudioPumpRunning()

        // Запускаем фоновый поток декодирования ffmpeg в очередь PCM чанков
        val decodeThread = Thread {
            if (sessionId != currentSessionId || session.isClosed) return@Thread
            var proc: Process? = null
            try {
                val args = mutableListOf(ffmpeg, "-v", "error", "-nostdin")
                if (seekPosMs > 0L) {
                    args.addAll(listOf("-ss", String.format(java.util.Locale.US, "%.3f", seekPosMs / 1000.0)))
                }
                if (source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)) {
                    args.addAll(listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"))
                }
                args.addAll(listOf("-i", source, "-f", "s16le", "-ar", "44100", "-ac", "2", "-"))

                val pb = ProcessBuilder(args)
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                proc = pb.start()
                session.proc = proc
                if (session == activeSession && sessionId == currentSessionId) {
                    fallbackProcess = proc
                }

                val inStream = proc.inputStream
                val buffer = ByteArray(4096)
                while (!Thread.currentThread().isInterrupted && !session.isClosed && (session == activeSession || session == fadingSession)) {
                    val bytesRead = inStream.read(buffer, 0, buffer.size)
                    if (bytesRead == -1) break
                    if (bytesRead > 0) {
                        val chunk = if (bytesRead == buffer.size) buffer.clone() else buffer.copyOf(bytesRead)
                        while (!session.isClosed && !Thread.currentThread().isInterrupted) {
                            if (session.pcmQueue.offer(chunk, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                break
                            }
                        }
                    }
                }
                session.isEofReached = true
            } catch (e: Exception) {
                if (sessionId == currentSessionId && session == activeSession) {
                    println("AudioPlayer: Ошибка потока ffmpeg: ${e.message}")
                }
            } finally {
                session.isEofReached = true
                try { proc?.destroyForcibly() } catch (_: Exception) {}
            }
        }.apply {
            isDaemon = true
            name = "AudioPlayer-FfmpegDecoder-$sessionId"
            session.thread = this
            if (session == activeSession) {
                jlayerThread = this
            }
            start()
        }
    }

    @Synchronized
    private fun ensureSharedLine(): javax.sound.sampled.SourceDataLine? {
        val existing = javaSoundLine
        if (existing != null && existing.isOpen) {
            try {
                if (!existing.isActive) existing.start()
            } catch (_: Exception) {}
            return existing
        }
        val audioFormat = javax.sound.sampled.AudioFormat(44100f, 16, 2, true, false)
        val bufferSize = 44100 * 4 / 5 // ~35280 bytes = 200ms
        val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, audioFormat)

        var line: javax.sound.sampled.SourceDataLine? = null
        try {
            line = javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
            line.open(audioFormat, bufferSize)
            line.start()
            javaSoundLine = line
            return line
        } catch (_: Exception) {
            try { line?.close() } catch (_: Exception) {}
        }

        try {
            for (mixerInfo in javax.sound.sampled.AudioSystem.getMixerInfo()) {
                try {
                    val mixer = javax.sound.sampled.AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(info)) {
                        val mLine = mixer.getLine(info) as javax.sound.sampled.SourceDataLine
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

            val activeChunk = active?.pcmQueue?.poll()

            if (active != null && activeChunk != null) {
                // Первый чанк активной сессии: фиксируем точку отсчета воспроизведения
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

                // Сведение с уходящей сессией (радио-кроссфейд)
                val fadingChunk = fading?.pcmQueue?.poll()
                if (fading != null && fadingChunk != null) {
                    val startNano = active.fadeStartTimeNano
                    val durNano = active.fadeDurationNano
                    val elapsedNano = System.nanoTime() - startNano

                    if (durNano > 0L && elapsedNano < durNano) {
                        val p = (elapsedNano.toDouble() / durNano.toDouble()).coerceIn(0.0, 1.0)
                        val gainActive = kotlin.math.sin(p * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)
                        val gainFading = kotlin.math.cos(p * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)
                        io.github.audiz.dsp.PcmGainProcessor.mixPcm(
                            activeChunk, activeChunk.size,
                            fadingChunk, fadingChunk.size,
                            gainActive, gainFading
                        )
                    } else {
                        fading.close()
                        fadingSession = null
                    }
                } else if (fading != null && fading.isEofReached && fading.pcmQueue.isEmpty()) {
                    fading.close()
                    fadingSession = null
                }

                // DSP цепочка
                if (active.id == currentSessionId) {
                    if (io.github.audiz.dsp.EqualizerEngine.isEnabled) {
                        io.github.audiz.dsp.EqualizerEngine.processPcm(activeChunk, activeChunk.size)
                    }
                    io.github.audiz.dsp.AudioVisualizer.processPcmChunk(activeChunk, activeChunk.size)
                }

                applyPcmGain(activeChunk, activeChunk.size, currentVolume)
                line.write(activeChunk, 0, activeChunk.size)

                // Позиция и нативный порог конца трека
                if (active.id == currentSessionId) {
                    val elapsedMs = (line.microsecondPosition - active.lineStartMicrosecondPosition) / 1000L
                    val currentPos = (active.seekPosMs + elapsedMs).coerceAtLeast(0L)
                    currentPositionMs = currentPos

                    val expectedDur = nearEndExpectedDurationMs.takeIf { it > 0 } ?: durationMs
                    val threshold = nearEndThresholdMs
                    if (!active.nearEndTriggered && expectedDur > 6000L && threshold > 0L && currentPos >= (expectedDur - threshold)) {
                        active.nearEndTriggered = true
                        println("AudioPlayer JVM: 📻 Достигнут порог конца трека ($currentPos / $expectedDur мс), вызываем onNearEndListener")
                        try {
                            onNearEndListener?.invoke()
                        } catch (_: Exception) {}
                    }
                }

            } else if (active != null && active.isEofReached && active.pcmQueue.isEmpty()) {
                // Активная сессия физически закончилась
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
                        onCompletionListener?.invoke()
                    } catch (_: Exception) {}
                }
            } else if (fading != null) {
                // Новая сессия еще готовится: продолжаем бесшовно играть уходящую сессию
                val fadingChunk = fading.pcmQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (fadingChunk != null) {
                    if (io.github.audiz.dsp.EqualizerEngine.isEnabled) {
                        io.github.audiz.dsp.EqualizerEngine.processPcm(fadingChunk, fadingChunk.size)
                    }
                    io.github.audiz.dsp.AudioVisualizer.processPcmChunk(fadingChunk, fadingChunk.size)
                    applyPcmGain(fadingChunk, fadingChunk.size, currentVolume)
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

    private fun playWithSystemProcess(playerCmd: String, file: File, sessionId: Long, seekPosMs: Long = 0L) {
        playWithSystemProcess(playerCmd, file.absolutePath, sessionId, seekPosMs)
    }

    private fun playWithSystemProcess(playerCmd: String, source: String, sessionId: Long, seekPosMs: Long = 0L) {
        if (sessionId != currentSessionId) return
        try {
            val args = mutableListOf<String>()
            val gain = volumeToGain(currentVolume)
            when (playerCmd) {
                "ffplay" -> {
                    val volPercent = (gain * 100).toInt().coerceIn(0, 100)
                    args.addAll(listOf("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", "-volume", "$volPercent"))
                    if (seekPosMs > 0) {
                        args.addAll(listOf("-ss", "${seekPosMs / 1000f}"))
                    }
                    args.add(source)
                }
                "mpv" -> {
                    val volPercent = (gain * 100).toInt().coerceIn(0, 100)
                    args.addAll(listOf("mpv", "--no-video", "--really-quiet", "--volume=$volPercent"))
                    if (seekPosMs > 0) {
                        args.addAll(listOf("--start=${seekPosMs / 1000f}"))
                    }
                    args.add(source)
                }
                else -> args.addAll(listOf(playerCmd, source))
            }
            val proc = ProcessBuilder(args).start()
            if (sessionId != currentSessionId) {
                proc.destroyForcibly()
                return
            }
            fallbackProcess = proc
            isPlayingState = true
            isPausedState = false
            jlayerStartTimeNano = System.nanoTime()
            jlayerPauseOffsetMs = seekPosMs
            currentPositionMs = seekPosMs
            Thread {
                val exitCode = proc.waitFor()
                if (sessionId == currentSessionId && fallbackProcess == proc) {
                    isPlayingState = false
                    isPausedState = false
                    fallbackProcess = null
                    if (exitCode == 0) {
                        onCompletionListener?.invoke()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            println("AudioPlayer Fallback: Ошибка запуска системного плеера: ${e.message}")
            if (sessionId == currentSessionId) {
                isPlayingState = false
            }
        }
    }

    private fun findSystemPlayer(): String? {
        val isWin = System.getProperty("os.name", "").lowercase().contains("win")
        val cmdsToTry = if (isWin) listOf("ffplay.exe", "ffplay", "mpv.exe", "mpv") else listOf("ffplay", "mpv")
        
        for (cmd in cmdsToTry) {
            try {
                // Try executing the command directly to see if it exists in PATH
                val p = ProcessBuilder(cmd, "-version").redirectErrorStream(true).start()
                val exited = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                if (exited) {
                    return cmd
                }
            } catch (_: Exception) {}
        }
        
        // On Windows, if PATH is stale, try PowerShell
        if (isWin) {
            for (cmd in listOf("ffplay", "mpv")) {
                try {
                    val p = ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Command $cmd).Source").start()
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                    val path = reader.readLine()?.trim()
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    if (!path.isNullOrEmpty() && File(path).exists()) {
                        return path
                    }
                } catch (_: Exception) {}
            }
            
            // If ffplay is still not found, but we know where ffmpeg is, they are usually in the same folder
            val ffmpegPath = getFfmpegPath()
            if (ffmpegPath != null) {
                val assumedFfplay = File(ffmpegPath).parentFile?.resolve("ffplay.exe")
                if (assumedFfplay != null && assumedFfplay.exists()) {
                    return assumedFfplay.absolutePath
                }
            }
        }
        
        return null
    }

    companion object {
        @Volatile private var cachedFfmpegPath: String? = null
        @Volatile private var ffmpegPathChecked: Boolean = false
    }

    private fun getFfmpegPath(): String? {
        if (ffmpegPathChecked) return cachedFfmpegPath
        synchronized(AudioPlayer::class.java) {
            if (ffmpegPathChecked) return cachedFfmpegPath
            val path = detectFfmpegPath()
            cachedFfmpegPath = path
            ffmpegPathChecked = true
            return path
        }
    }

    private fun detectFfmpegPath(): String? {
        val isWin = System.getProperty("os.name", "").lowercase().contains("win")
        val cmds = if (isWin) listOf("ffmpeg.exe", "ffmpeg") else listOf("ffmpeg")
        
        // 1. Check standard PATH
        for (cmd in cmds) {
            try {
                val p = ProcessBuilder(cmd, "-version").start()
                if (p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) return cmd
            } catch (_: Exception) {}
        }
        
        // 2. On Windows, try finding it via PowerShell if PATH is stale
        if (isWin) {
            try {
                val p = ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Command ffmpeg).Source").start()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                val path = reader.readLine()?.trim()
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                if (!path.isNullOrEmpty() && File(path).exists()) {
                    return path
                }
            } catch (_: Exception) {}
        }
        
        return null
    }

    private fun hasFfmpeg(): Boolean {
        return getFfmpegPath() != null
    }

    private fun decodeToWavWithFfmpeg(file: File): File? {
        return try {
            val ffmpeg = getFfmpegPath() ?: return null
            val tempWav = File.createTempFile("ya_extract_", ".wav")
            tempWav.deleteOnExit()
            
            // decode to wav for native Java Sound API
            val proc = ProcessBuilder(ffmpeg, "-y", "-i", file.absolutePath, tempWav.absolutePath)
                .redirectErrorStream(true)
                .start()
            proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            
            if (tempWav.exists() && tempWav.length() > 0) {
                tempWav
            } else {
                null
            }
        } catch (e: Exception) {
            println("AudioPlayer Fallback: Ошибка декодирования через ffmpeg - ${e.message}")
            null
        }
    }

    private fun getDurationWithFfmpeg(file: File): Long {
        try {
            // First try ffprobe if present
            val isWin = System.getProperty("os.name", "").lowercase().contains("win")
            val ffprobeCmds = if (isWin) listOf("ffprobe.exe", "ffprobe") else listOf("ffprobe")
            var ffprobePath: String? = null
            for (cmd in ffprobeCmds) {
                try {
                    val p = ProcessBuilder(cmd, "-version").start()
                    if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        ffprobePath = cmd
                        break
                    }
                } catch (_: Exception) {}
            }
            if (ffprobePath == null && isWin) {
                val ffmpeg = getFfmpegPath()
                if (ffmpeg != null) {
                    val probeCandidate = File(ffmpeg).parentFile?.resolve("ffprobe.exe")
                    if (probeCandidate != null && probeCandidate.exists()) {
                        ffprobePath = probeCandidate.absolutePath
                    }
                }
            }

            if (ffprobePath != null) {
                val pb = ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.absolutePath
                ).redirectErrorStream(true)
                val proc = pb.start()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
                val output = reader.readLine()?.trim()
                proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                val durSec = output?.toDoubleOrNull()
                if (durSec != null && durSec > 0.0) {
                    return (durSec * 1000).toLong()
                }
            }

            // Fallback: run ffmpeg -i and parse Duration: HH:MM:SS.xx
            val ffmpeg = getFfmpegPath()
            if (ffmpeg != null) {
                val pb = ProcessBuilder(ffmpeg, "-i", file.absolutePath).redirectErrorStream(true)
                val proc = pb.start()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
                var line: String?
                var durMs = 0L
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    if (text.contains("Duration:")) {
                        // Duration: 00:03:45.12, start: ...
                        val match = Regex("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(text)
                        if (match != null) {
                            val (h, m, s) = match.destructured
                            val totalSec = h.toLong() * 3600 + m.toLong() * 60 + s.toDouble()
                            durMs = (totalSec * 1000).toLong()
                            break
                        }
                    }
                }
                proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (durMs > 0L) return durMs
            }
        } catch (_: Exception) {}
        return 0L
    }

    private fun preparePlayableFile(file: File): File {
        if (file.name.endsWith(".aac", ignoreCase = true) && isMp4Container(file)) {
            try {
                val tempM4a = File.createTempFile("ya_play_", ".m4a")
                tempM4a.deleteOnExit()
                file.inputStream().use { input ->
                    tempM4a.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                registerTempFile(tempM4a)
                return tempM4a
            } catch (e: Exception) {
                println("AudioPlayer: Не удалось создать временный .m4a файл: ${e.message}")
            }
        }
        return file
    }

    private fun isMp4Container(file: File): Boolean = io.github.audiz.util.AudioHeaderParser.isMp4Container(file)
    private fun isMp3File(file: File): Boolean = io.github.audiz.util.AudioHeaderParser.isMp3File(file)
    private fun isFlacFile(file: File): Boolean = io.github.audiz.util.AudioHeaderParser.isFlacFile(file)
    private fun estimateMp3DurationMs(file: File): Long = io.github.audiz.util.AudioHeaderParser.estimateMp3DurationMs(file)
    private fun getExactFlacDurationMs(file: File): Long = io.github.audiz.util.AudioHeaderParser.getExactFlacDurationMs(file)
    private fun estimateFlacDurationMs(file: File): Long = io.github.audiz.util.AudioHeaderParser.estimateFlacDurationMs(file)
    private fun getExactM4aDurationMs(file: File): Long = io.github.audiz.util.AudioHeaderParser.getExactM4aDurationMs(file)
    private fun estimateM4aDurationMs(file: File): Long = io.github.audiz.util.AudioHeaderParser.estimateM4aDurationMs(file)

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

    actual fun pause() {
        val pausedPos = getCurrentPositionMs()
        currentPositionMs = pausedPos
        jlayerPauseOffsetMs = pausedPos
        isPausedState = true
        isPlayingState = false
        io.github.audiz.dsp.AudioVisualizer.reset()

        runOnFxThread {
            try {
                fxPlayer?.pause()
                fadingFxPlayer?.pause()
            } catch (_: Exception) {}
        }

        // JLayer MP3
        try {
            jlayerPlayer?.close()
        } catch (_: Exception) {}
        jlayerPlayer = null

        // Only interrupt jlayerThread if it's NOT JavaSoundLine
        // (playWithJavaSound stays alive and sleeps in loop while isPausedState is true)
        if (javaSoundLine == null) {
            val oldThread = jlayerThread
            jlayerThread = null
            try {
                oldThread?.interrupt()
            } catch (_: Exception) {}
        }
        
        // JavaSound
        try {
            javaSoundLine?.stop()
        } catch (_: Exception) {}
    }

    actual fun resume() {
        val resumePos = currentPositionMs
        val session = activeSession
        val line = javaSoundLine
        if (session != null && line != null && line.isOpen) {
            session.seekPosMs = resumePos
            session.lineStartMicrosecondPosition = line.microsecondPosition
        } else if (line != null && line.isOpen) {
            jlayerPauseOffsetMs = resumePos - (line.microsecondPosition / 1000L)
        } else {
            jlayerPauseOffsetMs = resumePos
        }
        jlayerStartTimeNano = System.nanoTime()
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
                        if (kotlin.math.abs(fxMs - resumePos) > 1500) {
                            println("AudioPlayer: Корректировка времени JavaFX перед resume ($fxMs -> $resumePos ms)")
                            player.seek(javafx.util.Duration.millis(resumePos.toDouble()))
                        }
                    }
                    player.play()
                }
            } catch (_: Exception) {}
        }

        // JavaSound
        try {
            javaSoundLine?.start()
        } catch (_: Exception) {}

        // JLayer fallback restart if MP3 thread finished
        if (fxPlayer == null && javaSoundLine == null && fallbackProcess == null) {
            val file = currentFile
            if (file != null && file.exists() && isMp3File(file)) {
                val sessionId = currentSessionId
                playMp3WithJLayer(file, sessionId, resumePos)
            } else {
                val url = currentStreamUrl
                if (url != null) {
                    val sessionId = currentSessionId
                    playStreamWithFfmpeg(url, sessionId, resumePos)
                }
            }
        }
    }

    actual fun stop() {
        currentSessionId++
        crossfadeJob?.cancel()
        crossfadeJob = null
        val fading = fadingFxPlayer
        fadingFxPlayer = null
        fadingSession?.close()
        fadingSession = null
        activeSession?.close()
        activeSession = null
        io.github.audiz.dsp.AudioVisualizer.reset()
        val oldPlayer = fxPlayer
        fxPlayer = null

        val filesToDelete = if (isSeekingFlag) {
            emptyList()
        } else {
            cachedWavFile = null
            synchronized(tempPlaybackFiles) {
                val list = tempPlaybackFiles.toList()
                tempPlaybackFiles.clear()
                list
            }
        }

        fun deleteFiles() {
            for (f in filesToDelete) {
                try {
                    if (f.exists()) f.delete()
                } catch (_: Exception) {}
            }
        }

        deleteFiles()

        runOnFxThread {
            try {
                fading?.stop()
                fading?.dispose()
            } catch (_: Exception) {}
            try {
                oldPlayer?.stop()
                oldPlayer?.dispose()
            } catch (_: Exception) {}
            deleteFiles()
        }
        try {
            fallbackProcess?.destroyForcibly()
        } catch (_: Exception) {}
        fallbackProcess = null
        try {
            jlayerPlayer?.close()
        } catch (_: Exception) {}
        jlayerPlayer = null
        
        // JavaSound
        try {
            javaSoundLine?.stop()
            javaSoundLine?.flush()
            if (!isSeekingFlag) {
                javaSoundLine?.close()
                javaSoundLine = null
            }
        } catch (_: Exception) {}

        val oldThread = jlayerThread
        jlayerThread = null
        try {
            oldThread?.interrupt()
            oldThread?.join(300)
        } catch (_: Exception) {}

        isPlayingState = false
        isPausedState = false
        currentPositionMs = 0L
        if (!isSeekingFlag) {
            durationMs = 0L
            currentStreamUrl = null
        }
        jlayerPauseOffsetMs = 0L
        jlayerStartTimeNano = 0L
    }

    actual fun isPlaying(): Boolean {
        return isPlayingState && !isPausedState
    }

    actual fun getDurationMs(): Long {
        val player = fxPlayer
        if (player != null) {
            val dur = player.media.duration
            if (dur != null && !dur.isUnknown) {
                return dur.toMillis().toLong()
            }
        }
        return durationMs
    }

    actual fun getCurrentPositionMs(): Long {
        if (isPausedState) {
            return currentPositionMs
        }

        val player = fxPlayer
        if (player != null) {
            val time = player.currentTime
            if (time != null && !time.isUnknown) {
                val fxMs = time.toMillis().toLong()
                if (jlayerStartTimeNano > 0L) {
                    val elapsedSinceResume = (System.nanoTime() - jlayerStartTimeNano) / 1_000_000
                    val maxExpectedPos = jlayerPauseOffsetMs + elapsedSinceResume + 2500
                    // Protect against Linux GStreamer clock jumping forward by the pause duration
                    if (fxMs > maxExpectedPos + 3000) {
                        val correctedPos = (jlayerPauseOffsetMs + elapsedSinceResume).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
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

        return if (isPlayingState && jlayerStartTimeNano > 0L) {
            val pos = jlayerPauseOffsetMs + (System.nanoTime() - jlayerStartTimeNano) / 1_000_000
            currentPositionMs = pos
            pos
        } else {
            currentPositionMs
        }
    }

    actual fun seekTo(positionMs: Long) {
        val targetMs = positionMs.coerceAtLeast(0L)
        currentPositionMs = targetMs
        jlayerPauseOffsetMs = targetMs
        jlayerStartTimeNano = System.nanoTime()
        io.github.audiz.dsp.AudioVisualizer.clearDelayQueue()

        runOnFxThread {
            try {
                fxPlayer?.seek(javafx.util.Duration.millis(targetMs.toDouble()))
            } catch (_: Exception) {}
        }
        
        if (fxPlayer == null) {
            val url = currentStreamUrl
            if (url != null) {
                val wasPaused = isPausedState
                isSeekingFlag = true
                try {
                    stop()
                    val sessionId = ++currentSessionId
                    currentStreamUrl = url
                    isPlayingState = !wasPaused
                    isPausedState = wasPaused
                    jlayerStartTimeNano = System.nanoTime()
                    jlayerPauseOffsetMs = targetMs
                    currentPositionMs = targetMs
                    playStreamWithFfmpeg(url, sessionId, targetMs)
                    if (wasPaused) {
                        pause()
                    }
                } finally {
                    isSeekingFlag = false
                }
                return
            }

            val wavFile = cachedWavFile?.takeIf { it.exists() && it.length() > 0 }
            val srcFile = currentFile?.takeIf { it.exists() }
            val fileToPlay = wavFile ?: srcFile
            if (fileToPlay != null) {
                val wasPaused = isPausedState
                isSeekingFlag = true
                try {
                    stop()
                    val sessionId = ++currentSessionId
                    currentFile = srcFile ?: fileToPlay
                    isPlayingState = !wasPaused
                    isPausedState = wasPaused
                    jlayerStartTimeNano = System.nanoTime()
                    jlayerPauseOffsetMs = targetMs
                    currentPositionMs = targetMs
                    
                    if (wavFile != null && wavFile.exists()) {
                        playWithJavaSound(wavFile, sessionId, targetMs)
                    } else {
                        val playFile = preparePlayableFile(fileToPlay)
                        playWithFallback(playFile, sessionId, targetMs)
                    }
                    
                    if (wasPaused) {
                        pause()
                    }
                } finally {
                    isSeekingFlag = false
                }
            }
        }
    }

    @Volatile private var currentVolume: Float = 1f

    actual fun setVolume(volume: Float) {
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

    actual fun setOnCompletionListener(listener: (() -> Unit)?) {
        onCompletionListener = listener
    }

    actual fun setOnInterruptionListener(listener: ((shouldPause: Boolean, canResume: Boolean) -> Unit)?) {
        onInterruptionListener = listener
    }

    actual fun setOnNearEndListener(listener: (() -> Unit)?) {
        onNearEndListener = listener
    }

    actual fun setOnErrorListener(listener: ((error: String) -> Unit)?) {
        onErrorListener = listener
    }

    actual fun setOnPlaybackStartedListener(listener: (() -> Unit)?) {
        onPlaybackStartedListener = listener
    }

    actual fun updateNearEndThreshold(thresholdMs: Long, expectedDurationMs: Long) {
        nearEndThresholdMs = thresholdMs
        nearEndExpectedDurationMs = expectedDurationMs
    }

    actual fun release() {
        stop()
        isPumpRunning = false
        try {
            javaSoundLine?.close()
        } catch (_: Exception) {}
        javaSoundLine = null
        playerScope.cancel()
    }
}
