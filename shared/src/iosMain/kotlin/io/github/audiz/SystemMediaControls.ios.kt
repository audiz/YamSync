package io.github.audiz

import platform.MediaPlayer.*
import platform.Foundation.*
import platform.UIKit.UIImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual class SystemMediaControls {
    private var onPlayCallback: (() -> Unit)? = null
    private var onPauseCallback: (() -> Unit)? = null
    private var onTogglePlayPauseCallback: (() -> Unit)? = null
    private var onNextCallback: (() -> Unit)? = null
    private var onPrevCallback: (() -> Unit)? = null
    private var onSeekCallback: ((Long) -> Unit)? = null

    private var currentPositionMs: Long = 0L
    private var currentCoverUri: String? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var coverJob: Job? = null

    actual fun initialize(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onTogglePlayPause: () -> Unit,
        onNext: () -> Unit,
        onPrev: () -> Unit,
        onSeek: (Long) -> Unit
    ) {
        this.onPlayCallback = onPlay
        this.onPauseCallback = onPause
        this.onTogglePlayPauseCallback = onTogglePlayPause
        this.onNextCallback = onNext
        this.onPrevCallback = onPrev
        this.onSeekCallback = onSeek

        try {
            val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
            commandCenter.playCommand.enabled = true
            commandCenter.playCommand.addTargetWithHandler {
                platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                    try { onPlayCallback?.invoke() } catch (_: Throwable) {}
                }
                MPRemoteCommandHandlerStatusSuccess
            }
            commandCenter.pauseCommand.enabled = true
            commandCenter.pauseCommand.addTargetWithHandler {
                platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                    try { onPauseCallback?.invoke() } catch (_: Throwable) {}
                }
                MPRemoteCommandHandlerStatusSuccess
            }
            commandCenter.togglePlayPauseCommand.enabled = true
            commandCenter.togglePlayPauseCommand.addTargetWithHandler {
                platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                    try { onTogglePlayPauseCallback?.invoke() } catch (_: Throwable) {}
                }
                MPRemoteCommandHandlerStatusSuccess
            }
            commandCenter.nextTrackCommand.enabled = true
            commandCenter.nextTrackCommand.addTargetWithHandler {
                platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                    try { onNextCallback?.invoke() } catch (_: Throwable) {}
                }
                MPRemoteCommandHandlerStatusSuccess
            }
            commandCenter.previousTrackCommand.enabled = true
            commandCenter.previousTrackCommand.addTargetWithHandler {
                platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                    try { onPrevCallback?.invoke() } catch (_: Throwable) {}
                }
                MPRemoteCommandHandlerStatusSuccess
            }

            // ⏩ Перемотка ползунком на экране блокировки iOS (Scrubber) остается активной
            commandCenter.changePlaybackPositionCommand.enabled = true
            commandCenter.changePlaybackPositionCommand.addTargetWithHandler { event ->
                try {
                    val posEvent = event as? MPChangePlaybackPositionCommandEvent
                    if (posEvent != null) {
                        val posMs = (posEvent.positionTime * 1000.0).toLong()
                        platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                            try { onSeekCallback?.invoke(posMs) } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
                MPRemoteCommandHandlerStatusSuccess
            }

            // Кнопки быстрой перемотки ±15 сек и ускоренной перемотки отключаем, чтобы на экране блокировки
            // и в Пункте управления iOS отображались кнопки «Следующий трек» (⏭) и «Предыдущий трек» (⏮),
            // а позиция плавно перематывалась ползунком (Scrubber)
            commandCenter.skipForwardCommand.enabled = false
            commandCenter.skipBackwardCommand.enabled = false
            commandCenter.seekForwardCommand.enabled = false
            commandCenter.seekBackwardCommand.enabled = false
        } catch (_: Throwable) {}
    }

    actual fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        trackId: String?,
        coverUri: String?
    ) {
        try {
            currentCoverUri = coverUri
            val dict = mutableMapOf<Any?, Any?>(
                MPMediaItemPropertyTitle to title,
                MPMediaItemPropertyArtist to artist,
                MPMediaItemPropertyAlbumTitle to album.ifBlank { "YamSync" },
                MPMediaItemPropertyPlaybackDuration to NSNumber(double = durationMs / 1000.0)
            )
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = dict

            // Асинхронно загружаем обложку для красивого полноэкранного плеера на экране заставки iOS
            coverJob?.cancel()
            if (!coverUri.isNullOrBlank()) {
                coverJob = scope.launch {
                    val artwork = loadIosArtwork(coverUri, trackId)
                    if (artwork != null && currentCoverUri == coverUri) {
                        withContext(Dispatchers.Main) {
                            try {
                                val current = MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo
                                val updated = (current?.toMutableMap() ?: mutableMapOf<Any?, Any?>())
                                updated[MPMediaItemPropertyArtwork] = artwork
                                MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = updated
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private suspend fun loadIosArtwork(coverUri: String?, trackId: String?): MPMediaItemArtwork? = withContext(Dispatchers.Default) {
        try {
            // 1. Проверяем локальный файл
            if (trackId?.startsWith("local:") == true) {
                val filePath = trackId.removePrefix("local:")
                val data = NSData.dataWithContentsOfFile(filePath)
                if (data != null) {
                    val image = UIImage.imageWithData(data)
                    if (image != null) {
                        @Suppress("DEPRECATION")
                        return@withContext MPMediaItemArtwork(image)
                    }
                }
            }

            // 2. Сетевая обложка Яндекс Музыки
            val formattedUrl = io.github.audiz.ui.CoverImageLoader.formatCoverUrl(coverUri, 600)
            if (!formattedUrl.isNullOrBlank()) {
                val nsUrl = NSURL.URLWithString(formattedUrl)
                if (nsUrl != null) {
                    val data = NSData.dataWithContentsOfURL(nsUrl)
                    if (data != null) {
                        val image = UIImage.imageWithData(data)
                        if (image != null) {
                            @Suppress("DEPRECATION")
                            return@withContext MPMediaItemArtwork(image)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            println("SystemMediaControls iOS: Ошибка загрузки обложки: ${e.message}")
        }
        null
    }

    actual fun updatePlaybackState(
        isPlaying: Boolean,
        isPaused: Boolean,
        positionMs: Long
    ) {
        try {
            currentPositionMs = positionMs
            val current = MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo
            val dict = (current?.toMutableMap() ?: mutableMapOf<Any?, Any?>())
            dict[MPNowPlayingInfoPropertyElapsedPlaybackTime] = NSNumber(double = positionMs / 1000.0)
            dict[MPNowPlayingInfoPropertyPlaybackRate] = NSNumber(double = if (isPlaying && !isPaused) 1.0 else 0.0)
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = dict
        } catch (_: Throwable) {}
    }

    actual fun clear() {
        try {
            coverJob?.cancel()
            currentCoverUri = null
            currentPositionMs = 0L
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        } catch (_: Throwable) {}
    }

    actual fun release() {
        clear()
    }
}
