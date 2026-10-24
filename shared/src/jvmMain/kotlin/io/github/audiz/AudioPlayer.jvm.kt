package io.github.audiz

import io.github.audiz.backend.*
import io.github.audiz.dsp.AudioVisualizer
import io.github.audiz.dsp.EqualizerEngine
import io.github.audiz.util.AudioHeaderParser
import java.io.File
import java.util.Collections

/**
 * 🎵 Кросс-платформенная JVM-реализация аудиоплеера для Windows, Linux и macOS.
 *
 * Архитектура модульных бэкендов:
 * 1. JavaFxAudioBackend — основной движок для MP3, AAC/M4A, WAV на Win / Linux / Mac.
 * 2. FfmpegAudioPumpBackend — потоковое декодирование любого формата через FFmpeg в JavaSound с радио-кроссфейдом.
 * 3. JLayerAudioBackend — декодирование MP3 и прямое воспроизведение JavaSound без сторонних зависимостей.
 * 4. SystemProcessAudioBackend — воспроизведение через внешние плееры (ffplay / mpv) при отсутствии кодеков.
 */
actual class AudioPlayer actual constructor() {

    private val callback: AudioBackendCallback = object : AudioBackendCallback {
        override fun onPlaybackStarted(sessionId: Long) {
            if (sessionId == currentSessionId) {
                isPlayingState = true
                isPausedState = false
                onPlaybackStartedListener?.invoke()
            }
        }

        override fun onCompletion(sessionId: Long) {
            if (sessionId == currentSessionId) {
                isPlayingState = false
                isPausedState = false
                currentPositionMs = durationMs
                onCompletionListener?.invoke()
            }
        }

        override fun onError(sessionId: Long, error: String, canFallback: Boolean) {
            if (sessionId == currentSessionId) {
                if (canFallback) {
                    val url = currentStreamUrl
                    val file = currentFile
                    if (url != null) {
                        println("AudioPlayer: Ошибка в ${activeBackend?.name}, переключаемся на FFmpeg URL fallback...")
                        activeBackend = ffmpegPumpBackend
                        ffmpegPumpBackend.setDurationMs(durationMs)
                        ffmpegPumpBackend.play(PlaybackSource.Url(url), sessionId)
                        return
                    } else if (file != null) {
                        println("AudioPlayer: Ошибка в ${activeBackend?.name}, переключаемся на File fallback...")
                        isFallbackMode = true
                        playWithFallback(file, sessionId)
                        return
                    }
                }
                isPlayingState = false
                onErrorListener?.invoke(error)
            }
        }

        override fun onNearEnd(sessionId: Long) {
            if (sessionId == currentSessionId) {
                println("AudioPlayer JVM: 📻 Достигнут порог конца трека, вызываем onNearEndListener")
                try {
                    onNearEndListener?.invoke()
                } catch (_: Exception) {}
            }
        }
    }

    private val javaFxBackend: JavaFxAudioBackend = JavaFxAudioBackend(callback)
    private val ffmpegPumpBackend: FfmpegAudioPumpBackend = FfmpegAudioPumpBackend(callback)
    private val jlayerBackend: JLayerAudioBackend = JLayerAudioBackend(callback)
    private val systemProcessBackend: SystemProcessAudioBackend = SystemProcessAudioBackend(callback)

    @Volatile private var activeBackend: AudioBackend? = null

    @Volatile private var currentSessionId: Long = 0L
    @Volatile private var durationMs: Long = 0L
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var isPlayingState: Boolean = false
    @Volatile private var isPausedState: Boolean = false
    @Volatile private var isSeekingFlag: Boolean = false
    @Volatile private var isFallbackMode: Boolean = false

    @Volatile private var currentVolume: Float = 1f
    private var currentFile: File? = null
    @Volatile private var currentStreamUrl: String? = null
    @Volatile private var cachedWavFile: File? = null
    private val tempPlaybackFiles = Collections.synchronizedList(mutableListOf<File>())

    @Volatile private var onCompletionListener: (() -> Unit)? = null
    @Volatile private var onInterruptionListener: ((shouldPause: Boolean, canResume: Boolean) -> Unit)? = null
    @Volatile private var onNearEndListener: (() -> Unit)? = null
    @Volatile private var onErrorListener: ((String) -> Unit)? = null
    @Volatile private var onPlaybackStartedListener: (() -> Unit)? = null

    private fun createSecureTempFile(prefix: String, suffix: String): File {
        return try {
            java.nio.file.Files.createTempFile(prefix, suffix).toFile().apply {
                setReadable(true, true)
                setWritable(true, true)
            }
        } catch (_: Throwable) {
            File.createTempFile(prefix, suffix).apply {
                setReadable(true, true)
                setWritable(true, true)
            }
        }
    }

    private fun registerTempFile(file: File): File {
        tempPlaybackFiles.add(file)
        return file
    }

    private fun prepareForNewPlayback(crossfadeMs: Long) {
        if (crossfadeMs <= 0L) {
            stop()
        } else {
            durationMs = 0L
            currentPositionMs = 0L
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
            val tmp = createSecureTempFile("ya_stream_", ext)
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
        val cleanPath = filePath.trim().removePrefix("local:").removePrefix("file://").removePrefix("file:")
        val resolvedPath = resolveLocalPath(cleanPath)
        var file = File(resolvedPath)
        if (!file.exists()) {
            file = File(cleanPath)
        }
        if (!file.exists()) {
            file = File(filePath)
        }
        if (!file.exists()) {
            println("AudioPlayer: Файл не найден: $filePath (разрешенный: $resolvedPath)")
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

        if (FfmpegUtils.hasFfmpeg() || url.contains(".flac", ignoreCase = true) || EqualizerEngine.isEnabled) {
            activeBackend = ffmpegPumpBackend
            ffmpegPumpBackend.setDurationMs(durationMs)
            ffmpegPumpBackend.setVolume(currentVolume)
            ffmpegPumpBackend.play(PlaybackSource.Url(url), sessionId, crossfadeMs = crossfadeMs)
            return
        }

        if (!isFallbackMode && javaFxBackend.isAvailable()) {
            activeBackend = javaFxBackend
            javaFxBackend.setVolume(currentVolume)
            javaFxBackend.play(PlaybackSource.Url(url), sessionId, crossfadeMs = crossfadeMs)
        } else {
            activeBackend = ffmpegPumpBackend
            ffmpegPumpBackend.setDurationMs(durationMs)
            ffmpegPumpBackend.setVolume(currentVolume)
            ffmpegPumpBackend.play(PlaybackSource.Url(url), sessionId, crossfadeMs = crossfadeMs)
        }
    }

    private fun playFileInternal(file: File, crossfadeMs: Long = 0L) {
        val sessionId = ++currentSessionId
        isPlayingState = true
        isPausedState = false
        currentFile = file
        currentStreamUrl = null
        currentPositionMs = 0L
        durationMs = AudioHeaderParser.extractAudioDuration(file)

        val playFile = FfmpegUtils.preparePlayableFile(file, ::createSecureTempFile)
        if (playFile != file) {
            registerTempFile(playFile)
        }

        if (FfmpegUtils.hasFfmpeg() || FfmpegUtils.isFlacFile(playFile) || EqualizerEngine.isEnabled) {
            isFallbackMode = true
            playWithFallback(playFile, sessionId, crossfadeMs = crossfadeMs)
        } else if (!isFallbackMode && javaFxBackend.isAvailable()) {
            activeBackend = javaFxBackend
            javaFxBackend.setVolume(currentVolume)
            javaFxBackend.play(PlaybackSource.LocalFile(playFile), sessionId, crossfadeMs = crossfadeMs)
        } else {
            isFallbackMode = true
            playWithFallback(playFile, sessionId, crossfadeMs = crossfadeMs)
        }
    }

    private fun playWithFallback(file: File, sessionId: Long, seekPosMs: Long = 0L, crossfadeMs: Long = 0L) {
        if (sessionId != currentSessionId) return

        if (durationMs <= 0L) {
            durationMs = AudioHeaderParser.extractAudioDuration(file)
        }

        if (FfmpegUtils.hasFfmpeg()) {
            println("AudioPlayer Fallback: Воспроизведение через FFmpeg: ${file.name} (crossfade=${crossfadeMs}ms)")
            activeBackend = ffmpegPumpBackend
            ffmpegPumpBackend.setDurationMs(durationMs)
            ffmpegPumpBackend.setVolume(currentVolume)
            ffmpegPumpBackend.play(PlaybackSource.LocalFile(file), sessionId, seekPosMs, crossfadeMs)
            return
        }

        if (FfmpegUtils.isMp3File(file)) {
            println("AudioPlayer Fallback: Воспроизведение MP3 через JLayer: ${file.name}")
            activeBackend = jlayerBackend
            jlayerBackend.setVolume(currentVolume)
            jlayerBackend.play(PlaybackSource.LocalFile(file), sessionId, seekPosMs, crossfadeMs)
            return
        }

        if (FfmpegUtils.isFlacFile(file)) {
            println("AudioPlayer Fallback: Воспроизведение FLAC через JavaSound: ${file.name}")
            activeBackend = jlayerBackend
            jlayerBackend.setVolume(currentVolume)
            jlayerBackend.play(PlaybackSource.LocalFile(file), sessionId, seekPosMs, crossfadeMs)
            return
        }

        val systemPlayer = FfmpegUtils.findSystemPlayer()
        if (systemPlayer != null) {
            println("AudioPlayer Fallback: Воспроизведение через $systemPlayer: ${file.name}")
            activeBackend = systemProcessBackend
            systemProcessBackend.setDurationMs(durationMs)
            systemProcessBackend.setVolume(currentVolume)
            systemProcessBackend.play(PlaybackSource.LocalFile(file), sessionId, seekPosMs, crossfadeMs)
            return
        }

        println("AudioPlayer Fallback: Не удалось воспроизвести ${file.name}. Установите ffmpeg, ffplay или mpv.")
        if (sessionId == currentSessionId) {
            isPlayingState = false
            onErrorListener?.invoke("Не удалось воспроизвести файл: отсутствуют необходимые кодеки")
        }
    }

    actual fun pause() {
        val pausedPos = getCurrentPositionMs()
        currentPositionMs = pausedPos
        isPausedState = true
        isPlayingState = false
        AudioVisualizer.reset()
        activeBackend?.pause()
    }

    actual fun resume() {
        val resumePos = currentPositionMs
        isPlayingState = true
        isPausedState = false
        activeBackend?.resume(resumePos)
    }

    actual fun stop() {
        currentSessionId++
        AudioVisualizer.reset()

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

        activeBackend?.stop()
        javaFxBackend.stop()
        ffmpegPumpBackend.stop()
        jlayerBackend.stop()
        systemProcessBackend.stop()
        activeBackend = null

        for (f in filesToDelete) {
            try {
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }

        isPlayingState = false
        isPausedState = false
        currentPositionMs = 0L
        if (!isSeekingFlag) {
            durationMs = 0L
            currentStreamUrl = null
            currentFile = null
        }
    }

    actual fun isPlaying(): Boolean = activeBackend?.isPlaying() ?: (isPlayingState && !isPausedState)

    actual fun getDurationMs(): Long {
        val backendDur = activeBackend?.getDurationMs() ?: 0L
        if (backendDur > 0L) return backendDur
        return durationMs
    }

    actual fun getCurrentPositionMs(): Long {
        if (isPausedState) return currentPositionMs
        val backendPos = activeBackend?.getCurrentPositionMs() ?: 0L
        if (backendPos > 0L) {
            currentPositionMs = backendPos
            return backendPos
        }
        return currentPositionMs
    }

    actual fun seekTo(positionMs: Long) {
        val targetMs = positionMs.coerceAtLeast(0L)
        currentPositionMs = targetMs
        AudioVisualizer.clearDelayQueue()

        val backend = activeBackend
        if (backend == javaFxBackend && javaFxBackend.isPlaying()) {
            javaFxBackend.seekTo(targetMs)
            return
        }

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
                currentPositionMs = targetMs
                activeBackend = ffmpegPumpBackend
                ffmpegPumpBackend.setDurationMs(durationMs)
                ffmpegPumpBackend.setVolume(currentVolume)
                ffmpegPumpBackend.play(PlaybackSource.Url(url), sessionId, targetMs)
                if (wasPaused) {
                    pause()
                }
            } finally {
                isSeekingFlag = false
            }
            return
        }

        val file = currentFile
        if (file != null && file.exists()) {
            val wasPaused = isPausedState
            isSeekingFlag = true
            try {
                stop()
                val sessionId = ++currentSessionId
                currentFile = file
                isPlayingState = !wasPaused
                isPausedState = wasPaused
                currentPositionMs = targetMs

                val playFile = FfmpegUtils.preparePlayableFile(file, ::createSecureTempFile)
                playWithFallback(playFile, sessionId, targetMs)

                if (wasPaused) {
                    pause()
                }
            } finally {
                isSeekingFlag = false
            }
        }
    }

    actual fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        javaFxBackend.setVolume(currentVolume)
        ffmpegPumpBackend.setVolume(currentVolume)
        jlayerBackend.setVolume(currentVolume)
        systemProcessBackend.setVolume(currentVolume)
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
        ffmpegPumpBackend.updateNearEndThreshold(thresholdMs, expectedDurationMs)
        javaFxBackend.updateNearEndThreshold(thresholdMs, expectedDurationMs)
    }

    actual fun release() {
        stop()
        javaFxBackend.release()
        ffmpegPumpBackend.release()
        jlayerBackend.release()
        systemProcessBackend.release()
        activeBackend = null
    }
}
