package io.github.audiz.backend

import io.github.audiz.volumeToGain

/**
 * 🖥️ Запасной аудио-бэкенд для воспроизведения через внешние системные плееры (ffplay / mpv)
 */
class SystemProcessAudioBackend(
    private val callback: AudioBackendCallback
) : AudioBackend {

    override val name: String = "System Process (ffplay / mpv)"

    override fun isAvailable(): Boolean = FfmpegUtils.findSystemPlayer() != null

    @Volatile private var process: Process? = null
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
        val playerCmd = FfmpegUtils.findSystemPlayer()
        if (playerCmd == null) {
            callback.onError(sessionId, "Neither ffplay nor mpv found", canFallback = false)
            return
        }

        val sourceStr = when (source) {
            is PlaybackSource.Url -> source.url
            is PlaybackSource.LocalFile -> source.file.absolutePath
        }

        stop()
        currentSessionId = sessionId
        isPlayingState = true
        isPausedState = false
        currentPositionMs = seekPosMs
        pauseOffsetMs = seekPosMs
        startTimeNano = System.nanoTime()

        try {
            val args = mutableListOf<String>()
            val gain = volumeToGain(currentVolume)
            val normalizedCmd = playerCmd.substringAfterLast('/').substringAfterLast('\\')

            if (normalizedCmd.startsWith("ffplay", ignoreCase = true)) {
                val volPercent = (gain * 100).toInt().coerceIn(0, 100)
                args.addAll(listOf(playerCmd, "-nodisp", "-autoexit", "-loglevel", "quiet", "-volume", "$volPercent"))
                if (seekPosMs > 0) {
                    args.addAll(listOf("-ss", "${seekPosMs / 1000f}"))
                }
                args.add(sourceStr)
            } else if (normalizedCmd.startsWith("mpv", ignoreCase = true)) {
                val volPercent = (gain * 100).toInt().coerceIn(0, 100)
                args.addAll(listOf(playerCmd, "--no-video", "--really-quiet", "--volume=$volPercent"))
                if (seekPosMs > 0) {
                    args.addAll(listOf("--start=${seekPosMs / 1000f}"))
                }
                args.add(sourceStr)
            } else {
                args.addAll(listOf(playerCmd, sourceStr))
            }

            val proc = ProcessBuilder(args).start()
            process = proc
            callback.onPlaybackStarted(sessionId)

            Thread {
                val exitCode = proc.waitFor()
                if (sessionId == currentSessionId && process == proc) {
                    isPlayingState = false
                    isPausedState = false
                    process = null
                    if (exitCode == 0) {
                        callback.onCompletion(sessionId)
                    }
                }
            }.apply {
                isDaemon = true
                name = "AudioPlayer-Process-$sessionId"
                start()
            }
        } catch (e: Exception) {
            println("SystemProcessAudioBackend: Ошибка запуска: ${e.message}")
            if (sessionId == currentSessionId) {
                isPlayingState = false
                callback.onError(sessionId, e.message ?: "Process error", canFallback = false)
            }
        }
    }

    override fun pause() {
        isPausedState = true
        isPlayingState = false
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {}
        process = null
    }

    override fun resume(resumePosMs: Long) {
        pauseOffsetMs = resumePosMs
        startTimeNano = System.nanoTime()
        isPlayingState = true
        isPausedState = false
    }

    override fun stop() {
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {}
        process = null
        isPlayingState = false
        isPausedState = false
        currentPositionMs = 0L
    }

    override fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs
        pauseOffsetMs = positionMs
        startTimeNano = System.nanoTime()
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
