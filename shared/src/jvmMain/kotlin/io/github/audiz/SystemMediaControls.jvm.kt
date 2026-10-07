package io.github.audiz

actual class SystemMediaControls {
    private var mprisServer: MprisServer? = null
    private var windowsControls: WindowsMediaControls? = null

    private val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true
    private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    actual fun initialize(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onTogglePlayPause: () -> Unit,
        onNext: () -> Unit,
        onPrev: () -> Unit,
        onSeek: (Long) -> Unit
    ) {
        if (isLinux) {
            try {
                mprisServer = MprisServer(
                    onPlay = onPlay,
                    onPause = onPause,
                    onTogglePlayPause = onTogglePlayPause,
                    onNext = onNext,
                    onPrev = onPrev,
                    onSeek = onSeek
                ).apply {
                    start()
                }
            } catch (e: Exception) {
                println("SystemMediaControls JVM: Не удалось запустить MPRIS: ${e.message}")
            }
        } else if (isWindows) {
            try {
                windowsControls = WindowsMediaControls(
                    onPlay = onPlay,
                    onPause = onPause,
                    onTogglePlayPause = onTogglePlayPause,
                    onNext = onNext,
                    onPrev = onPrev,
                    onSeek = onSeek
                ).apply {
                    start()
                }
            } catch (e: Exception) {
                println("SystemMediaControls JVM: Не удалось запустить Windows SMTC: ${e.message}")
            }
        }
    }

    actual fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        trackId: String?,
        coverUri: String?
    ) {
        if (isLinux) {
            val artUrl = coverUri?.let { io.github.audiz.ui.CoverImageLoader.formatCoverUrl(it, 400) }
            mprisServer?.updateMetadata(title, artist, album, durationMs, trackId, artUrl)
        } else if (isWindows) {
            windowsControls?.updateMetadata(title, artist, album)
        }
    }

    actual fun updatePlaybackState(
        isPlaying: Boolean,
        isPaused: Boolean,
        positionMs: Long
    ) {
        if (isLinux) {
            mprisServer?.updatePlaybackState(isPlaying, isPaused, positionMs)
        } else if (isWindows) {
            windowsControls?.updatePlaybackState(isPlaying, isPaused)
        }
    }

    actual fun clear() {
        if (isLinux) {
            mprisServer?.updatePlaybackState(false, false, 0)
        } else if (isWindows) {
            windowsControls?.clear()
        }
    }

    actual fun release() {
        if (isLinux) {
            mprisServer?.stop()
            mprisServer = null
        } else if (isWindows) {
            windowsControls?.stop()
            windowsControls = null
        }
    }
}
