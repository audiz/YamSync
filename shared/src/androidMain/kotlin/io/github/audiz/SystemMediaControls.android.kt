package io.github.audiz

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.Rating
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import io.github.audiz.ui.CoverImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual class SystemMediaControls {
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentCoverJob: Job? = null

    actual fun setCustomActions(
        onToggleLike: (() -> Unit)?,
        onDislike: (() -> Unit)?
    ) {
        MediaPlaybackBridge.onToggleLike = onToggleLike
        MediaPlaybackBridge.onDislike = onDislike
    }

    actual fun initialize(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onTogglePlayPause: () -> Unit,
        onNext: () -> Unit,
        onPrev: () -> Unit,
        onSeek: (Long) -> Unit
    ) {
        try {
            val context = AppContextHolder.appContext
            val session = MediaSession(context, "YamSyncMediaSession")
            
            // 🛡️ Флаги для совместимости со старыми версиями Android (Android 8-10) и экраном блокировки
            @Suppress("DEPRECATION")
            session.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            session.setRatingType(Rating.RATING_HEART)

            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    onPlay()
                }
                override fun onPause() {
                    onPause()
                }
                override fun onSkipToNext() {
                    onNext()
                }
                override fun onSkipToPrevious() {
                    onPrev()
                }
                override fun onSeekTo(pos: Long) {
                    onSeek(pos)
                }
                override fun onFastForward() {
                    val target = MediaPlaybackBridge.currentPositionMs + 15000L
                    onSeek(target)
                }
                override fun onRewind() {
                    val target = (MediaPlaybackBridge.currentPositionMs - 15000L).coerceAtLeast(0L)
                    onSeek(target)
                }
                override fun onSetRating(rating: Rating) {
                    MediaPlaybackBridge.onToggleLike?.invoke()
                }
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        MediaPlaybackService.ACTION_TOGGLE_LIKE -> MediaPlaybackBridge.onToggleLike?.invoke()
                        MediaPlaybackService.ACTION_CLOSE_APP -> MediaPlaybackBridge.onCloseApp?.invoke()
                        MediaPlaybackService.ACTION_DISLIKE -> MediaPlaybackBridge.onDislike?.invoke()
                    }
                }
                override fun onStop() {
                    onPause()
                }
            })
            session.isActive = true
            mediaSession = session

            // 🔗 Связываем с Foreground Service
            MediaPlaybackBridge.onPlay = onPlay
            MediaPlaybackBridge.onPause = onPause
            MediaPlaybackBridge.onTogglePlayPause = onTogglePlayPause
            MediaPlaybackBridge.onNext = onNext
            MediaPlaybackBridge.onPrev = onPrev
            MediaPlaybackBridge.onSeek = onSeek
            MediaPlaybackBridge.onCloseApp = {
                try {
                    val intent = Intent(context, MediaPlaybackService::class.java).apply {
                        action = MediaPlaybackService.ACTION_CLOSE_APP
                    }
                    context.startService(intent)
                } catch (e: Throwable) {
                    println("SystemMediaControls: Ошибка вызова ACTION_CLOSE_APP: ${e.message}")
                }
            }
            MediaPlaybackBridge.sessionToken = session.sessionToken
        } catch (e: Exception) {
            println("Android MediaSession init error: ${e.message}")
        }
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
        try {
            MediaPlaybackBridge.currentTitle = title
            MediaPlaybackBridge.currentArtist = artist
            MediaPlaybackBridge.currentCoverUri = coverUri
            MediaPlaybackBridge.durationMs = durationMs
            MediaPlaybackBridge.isLiked = isLiked

            val metaBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album.ifBlank { "YamSync" })
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                .putRating(MediaMetadata.METADATA_KEY_USER_RATING, Rating.newHeartRating(isLiked))
                .putRating(MediaMetadata.METADATA_KEY_RATING, Rating.newHeartRating(isLiked))

            // Если уже есть закэшированная обложка, сразу устанавливаем
            val cachedArtwork = MediaPlaybackBridge.currentArtworkBitmap
            if (cachedArtwork != null && !cachedArtwork.isRecycled) {
                metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cachedArtwork)
                metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, cachedArtwork)
            }

            mediaSession?.setMetadata(metaBuilder.build())

            val context = AppContextHolder.appContext
            if (MediaPlaybackBridge.isPlaying) {
                MediaPlaybackServiceManager.startOrUpdate(
                    context,
                    title,
                    artist,
                    isPlaying = true
                )
            }

            // Асинхронно подгружаем изображение обложки для MediaSession и Notification
            currentCoverJob?.cancel()
            currentCoverJob = scope.launch {
                val bitmap: Bitmap? = loadArtworkBitmap(coverUri, trackId)
                if (bitmap != null && MediaPlaybackBridge.currentCoverUri == coverUri) {
                    MediaPlaybackBridge.currentArtworkBitmap = bitmap
                    try {
                        val updatedMeta = metaBuilder
                            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                            .putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
                            .build()
                        mediaSession?.setMetadata(updatedMeta)
                        MediaPlaybackServiceManager.updateNotification(context)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun loadArtworkBitmap(coverUri: String?, trackId: String?): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 1. Проверяем локальный файл
            if (trackId?.startsWith("local:") == true) {
                val filePath = trackId.removePrefix("local:")
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(filePath)
                    val art = retriever.embeddedPicture
                    if (art != null) {
                        return@withContext BitmapFactory.decodeByteArray(art, 0, art.size)
                    }
                } catch (_: Exception) {
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }

            // 2. Проверяем URL обложки (Яндекс Музыка)
            val formattedUrl = CoverImageLoader.formatCoverUrl(coverUri, 600)
            if (!formattedUrl.isNullOrBlank()) {
                val url = URL(formattedUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { input ->
                    return@withContext BitmapFactory.decodeStream(input)
                }
            }
        } catch (e: Exception) {
            println("SystemMediaControls Android: Ошибка загрузки обложки: ${e.message}")
        }
        null
    }

    actual fun updateLikedState(isLiked: Boolean) {
        try {
            MediaPlaybackBridge.isLiked = isLiked
            val context = AppContextHolder.appContext
            MediaPlaybackServiceManager.updateLikedState(context, isLiked)

            val metaBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, MediaPlaybackBridge.currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, MediaPlaybackBridge.currentArtist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "YamSync")
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaPlaybackBridge.currentArtist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, MediaPlaybackBridge.durationMs)
                .putRating(MediaMetadata.METADATA_KEY_USER_RATING, Rating.newHeartRating(isLiked))
                .putRating(MediaMetadata.METADATA_KEY_RATING, Rating.newHeartRating(isLiked))

            val cachedArtwork = MediaPlaybackBridge.currentArtworkBitmap
            if (cachedArtwork != null && !cachedArtwork.isRecycled) {
                metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cachedArtwork)
                metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, cachedArtwork)
            }
            mediaSession?.setMetadata(metaBuilder.build())

            updatePlaybackState(
                isPlaying = MediaPlaybackBridge.isPlaying,
                isPaused = !MediaPlaybackBridge.isPlaying && MediaPlaybackBridge.currentPositionMs > 0,
                positionMs = MediaPlaybackBridge.currentPositionMs
            )
        } catch (_: Exception) {}
    }

    actual fun updatePlaybackState(
        isPlaying: Boolean,
        isPaused: Boolean,
        positionMs: Long
    ) {
        try {
            val state = when {
                isPlaying && !isPaused -> PlaybackState.STATE_PLAYING
                isPaused -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_STOPPED
            }
            val context = AppContextHolder.appContext
            val likeResName = if (MediaPlaybackBridge.isLiked) "ic_notification_heart_filled" else "ic_notification_heart_outline"
            val likeFallback = if (MediaPlaybackBridge.isLiked) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            val likeResId = context.resources.getIdentifier(likeResName, "drawable", context.packageName).takeIf { it != 0 } ?: likeFallback

            val closeResId = context.resources.getIdentifier("ic_notification_close", "drawable", context.packageName).takeIf { it != 0 } ?: android.R.drawable.ic_menu_close_clear_cancel

            val likeCustomAction = PlaybackState.CustomAction.Builder(
                MediaPlaybackService.ACTION_TOGGLE_LIKE,
                if (MediaPlaybackBridge.isLiked) "В избранном" else "В избранное",
                likeResId
            ).build()

            val closeCustomAction = PlaybackState.CustomAction.Builder(
                MediaPlaybackService.ACTION_CLOSE_APP,
                "Закрыть приложение",
                closeResId
            ).build()

            val playbackState = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_SET_RATING
                )
                .addCustomAction(likeCustomAction)
                .addCustomAction(closeCustomAction)
                .setState(state, positionMs, if (isPlaying && !isPaused) 1.0f else 0.0f)
                .build()
            mediaSession?.setPlaybackState(playbackState)

            val active = isPlaying && !isPaused
            val wasActive = MediaPlaybackBridge.isPlaying
            MediaPlaybackBridge.isPlaying = active
            MediaPlaybackBridge.currentPositionMs = positionMs

            if (isPlaying || isPaused) {
                // Обновляем Foreground Service только при смене состояния воспроизведения (play/pause),
                // чтобы не перезапускать уведомление при каждом тике таймера (250мс),
                // что сбивало бы жест перемотки (drag seekbar) на экране заставки!
                if (wasActive != active) {
                    MediaPlaybackServiceManager.startOrUpdate(
                        context,
                        MediaPlaybackBridge.currentTitle,
                        MediaPlaybackBridge.currentArtist,
                        isPlaying = active
                    )
                }
            } else {
                MediaPlaybackServiceManager.stop(context)
            }
        } catch (_: Exception) {}
    }

    actual fun clear() {
        try {
            val playbackState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, 0, 0f)
                .build()
            mediaSession?.setPlaybackState(playbackState)

            MediaPlaybackBridge.isPlaying = false
            MediaPlaybackBridge.currentArtworkBitmap = null
            MediaPlaybackBridge.currentPositionMs = 0L
            val context = AppContextHolder.appContext
            MediaPlaybackServiceManager.stop(context)
        } catch (_: Exception) {}
    }

    actual fun release() {
        try {
            currentCoverJob?.cancel()
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null

            MediaPlaybackBridge.isPlaying = false
            MediaPlaybackBridge.currentArtworkBitmap = null
            MediaPlaybackBridge.currentPositionMs = 0L
            val context = AppContextHolder.appContext
            MediaPlaybackServiceManager.stop(context)
        } catch (_: Exception) {}
    }
}
