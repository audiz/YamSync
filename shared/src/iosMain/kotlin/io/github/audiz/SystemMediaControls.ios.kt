package io.github.audiz

import platform.MediaPlayer.*
import platform.Foundation.*
import platform.UIKit.UIImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import io.github.audiz.models.isLocalTrackId
import io.github.audiz.player.NowPlayingArtworkLoader
import io.github.audiz.ui.CoverImageLoader
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class SystemMediaControls {
    private var onPlayCallback: (() -> Unit)? = null
    private var onPauseCallback: (() -> Unit)? = null
    private var onTogglePlayPauseCallback: (() -> Unit)? = null
    private var onNextCallback: (() -> Unit)? = null
    private var onPrevCallback: (() -> Unit)? = null
    private var onSeekCallback: ((Long) -> Unit)? = null
    private var onToggleLikeCallback: (() -> Unit)? = null
    private var onDislikeCallback: (() -> Unit)? = null

    private var currentPositionMs: Long = 0L
    private var currentTrackKey: String? = null
    private var currentCoverUri: String? = null
    private var currentPlaybackRate = 0.0
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val artworkLoader = NowPlayingArtworkLoader<MPMediaItemArtwork>(
        scope = scope,
        load = ::loadIosArtwork,
        publish = { artwork ->
            val current = MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo
            val updated = current?.toMutableMap() ?: mutableMapOf<Any?, Any?>()
            updated[MPMediaItemPropertyArtwork] = artwork
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = updated
        }
    )

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

    actual fun setCustomActions(
        onToggleLike: (() -> Unit)?,
        onDislike: (() -> Unit)?
    ) {
        this.onToggleLikeCallback = onToggleLike
        this.onDislikeCallback = onDislike
        try {
            val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
            if (onToggleLike != null) {
                commandCenter.likeCommand.enabled = true
                commandCenter.likeCommand.addTargetWithHandler {
                    platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                        try { onToggleLikeCallback?.invoke() } catch (_: Throwable) {}
                    }
                    MPRemoteCommandHandlerStatusSuccess
                }
            }
            if (onDislike != null) {
                commandCenter.dislikeCommand.enabled = true
                commandCenter.dislikeCommand.addTargetWithHandler {
                    platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                        try { onDislikeCallback?.invoke() } catch (_: Throwable) {}
                    }
                    MPRemoteCommandHandlerStatusSuccess
                }
            }
        } catch (_: Throwable) {}
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
        scope.launch {
            val key = trackId ?: "$artist\u0000$title"
            val sameTrack = currentTrackKey == key
            currentCoverUri = coverUri?.trim()?.takeIf { it.isNotEmpty() }
                ?: currentCoverUri.takeIf { sameTrack }
            val sources = listOfNotNull(currentCoverUri,
                trackId?.takeIf { isLocalTrackId(it) }).distinct()
            val artwork = artworkLoader.update(key, sources)
            val previous = MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo
            val dict = if (sameTrack) previous?.toMutableMap() ?: mutableMapOf<Any?, Any?>()
                else mutableMapOf<Any?, Any?>()
            currentTrackKey = key
            if (!sameTrack) currentPositionMs = 0L
            dict[MPMediaItemPropertyTitle] = title
            dict[MPMediaItemPropertyArtist] = artist
            dict[MPMediaItemPropertyAlbumTitle] = album.ifBlank { "YamSync" }
            dict[MPMediaItemPropertyPlaybackDuration] = NSNumber(double = durationMs / 1000.0)
            dict[MPNowPlayingInfoPropertyElapsedPlaybackTime] = NSNumber(double = currentPositionMs / 1000.0)
            dict[MPNowPlayingInfoPropertyPlaybackRate] = NSNumber(double = currentPlaybackRate)
            if (artwork != null) dict[MPMediaItemPropertyArtwork] = artwork
            else dict.remove(MPMediaItemPropertyArtwork)
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = dict
        }
    }

    actual fun updateLikedState(isLiked: Boolean) {
        try {
            val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
            commandCenter.likeCommand.active = isLiked
        } catch (_: Throwable) {}
    }

    private suspend fun loadIosArtwork(source: String): MPMediaItemArtwork? = withContext(DispatcherIO) {
        try {
            val bytes = if (isLocalTrackId(source)) {
                loadLocalCoverBytes(source)
            } else {
                val url = CoverImageLoader.formatCoverUrl(source, 600) ?: return@withContext null
                CoverImageLoader.repository?.fetchImageBytes(url)
            }
            if (bytes == null || bytes.isEmpty()) return@withContext null
            val data = bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            val image = UIImage.imageWithData(data) ?: return@withContext null
            MPMediaItemArtwork(boundsSize = image.size, requestHandler = { image })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("SystemMediaControls iOS: Ошибка загрузки обложки: ${e.message}")
            null
        }
    }

    actual fun updatePlaybackState(
        isPlaying: Boolean,
        isPaused: Boolean,
        positionMs: Long
    ) {
        scope.launch {
            currentPositionMs = positionMs
            currentPlaybackRate = if (isPlaying && !isPaused) 1.0 else 0.0
            val current = MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo
            val dict = (current?.toMutableMap() ?: mutableMapOf<Any?, Any?>())
            dict[MPNowPlayingInfoPropertyElapsedPlaybackTime] = NSNumber(double = positionMs / 1000.0)
            dict[MPNowPlayingInfoPropertyPlaybackRate] = NSNumber(double = currentPlaybackRate)
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = dict
        }
    }

    actual fun clear() {
        scope.launch {
            artworkLoader.clear()
            currentTrackKey = null
            currentCoverUri = null
            currentPositionMs = 0L
            currentPlaybackRate = 0.0
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        }
    }

    actual fun release() {
        clear()
    }
}
