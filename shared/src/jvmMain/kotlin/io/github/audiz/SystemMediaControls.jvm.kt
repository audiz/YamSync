package io.github.audiz

import io.github.audiz.player.NowPlayingArtworkLoader
import io.github.audiz.ui.CoverImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_CACHED_COVERS = 20

actual class SystemMediaControls {
    private var mprisServer: MprisServer? = null
    private var windowsControls: WindowsMediaControls? = null

    // Отключение интеграции с системой (используется в тестах, чтобы не трогать сессию пользователя)
    private val isDisabled = System.getProperty("yamsync.systemMediaControls.disabled") == "true"
    private val isLinux = !isDisabled && System.getProperty("os.name")?.lowercase()?.contains("linux") == true
    private val isWindows = !isDisabled && System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    private data class Metadata(
        val title: String, val artist: String, val album: String, val durationMs: Long, val trackId: String?
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentMetadata: Metadata? = null
    private val artworkLoader = NowPlayingArtworkLoader<String>(
        scope = scope,
        load = { uri -> withContext(DispatcherIO) { resolveLocalArtUrl(uri) } },
        publish = { artUrl -> currentMetadata?.let { publishMetadata(it, artUrl) } }
    )

    /**
     * MPRIS-обложка всегда отдаётся локальным file:// URL: апплеты рабочего стола (например, Cinnamon)
     * для http-ссылок сами запускают загрузку из своего главного процесса, а локальный файл читают асинхронно.
     */
    private suspend fun resolveLocalArtUrl(uri: String): String? {
        val url = CoverImageLoader.formatCoverUrl(uri, 400) ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        val dir = File(System.getProperty("java.io.tmpdir"), "yamsync-mpris-covers-${System.getProperty("user.name")}")
        val file = File(dir, "${url.hashCode().toUInt().toString(16)}.img")
        if (file.isFile && file.length() > 0L) return file.toURI().toString()
        val bytes = CoverImageLoader.repository?.fetchImageBytes(url)?.takeIf { it.isNotEmpty() } ?: return null
        dir.mkdirs()
        val tmp = File(dir, file.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return null
        }
        // Держим в папке только последние обложки
        dir.listFiles { f -> f.isFile && f.name.endsWith(".img") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_COVERS)
            ?.forEach { it.delete() }
        return file.toURI().toString()
    }

    private fun publishMetadata(metadata: Metadata, artUrl: String?) {
        mprisServer?.updateMetadata(metadata.title, metadata.artist, metadata.album,
            metadata.durationMs, metadata.trackId, artUrl)
    }

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

    actual fun setCustomActions(
        onToggleLike: (() -> Unit)?,
        onDislike: (() -> Unit)?
    ) {
        // На десктопе кастомные действия не требуются
    }

    actual fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        trackId: String?,
        coverUri: String?,
        isLiked: Boolean
    ) {
        if (isLinux) {
            scope.launch {
                val metadata = Metadata(title, artist, album, durationMs, trackId)
                currentMetadata = metadata
                val artUrl = artworkLoader.update(trackId ?: "$artist\u0000$title", listOfNotNull(coverUri))
                publishMetadata(metadata, artUrl)
            }
        } else if (isWindows) {
            windowsControls?.updateMetadata(title, artist, album)
        }
    }

    actual fun updateLikedState(isLiked: Boolean) {
        // На десктопе не требуется
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
            scope.launch {
                artworkLoader.clear()
                currentMetadata = null
                mprisServer?.updatePlaybackState(false, false, 0)
            }
        } else if (isWindows) {
            windowsControls?.clear()
        }
    }

    actual fun release() {
        scope.launch {
            artworkLoader.clear()
            currentMetadata = null
            if (isLinux) {
                mprisServer?.stop()
                mprisServer = null
            } else if (isWindows) {
                windowsControls?.stop()
                windowsControls = null
            }
            scope.cancel()
        }
    }
}
