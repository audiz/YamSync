package io.github.audiz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import io.github.audiz.util.AppLogger

/**
 * 🎵 Bridge-объект для связи MediaPlaybackService с SystemMediaControls и ViewModel
 */
object MediaPlaybackBridge {
    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onTogglePlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var onToggleLike: (() -> Unit)? = null
    var onDislike: (() -> Unit)? = null
    var onCloseApp: (() -> Unit)? = null
    var closeAppHandler: (() -> Unit)? = null
    var sessionToken: MediaSession.Token? = null

    var currentTitle: String = ""
    var currentArtist: String = ""
    var currentCoverUri: String? = null
    @Volatile var currentArtworkBitmap: android.graphics.Bitmap? = null
    @Volatile var currentPositionMs: Long = 0L
    @Volatile var durationMs: Long = 0L
    var isPlaying: Boolean = false
    var isLiked: Boolean = false
}

/**
 * 🎵 Foreground Service для обеспечения непрерывного фонового воспроизведения
 * и переключения треков при выключенном экране телефона.
 */
class MediaPlaybackService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "media_playback_channel"

        const val ACTION_PLAY = "io.github.audiz.ACTION_PLAY"
        const val ACTION_PAUSE = "io.github.audiz.ACTION_PAUSE"
        const val ACTION_TOGGLE = "io.github.audiz.ACTION_TOGGLE"
        const val ACTION_NEXT = "io.github.audiz.ACTION_NEXT"
        const val ACTION_PREV = "io.github.audiz.ACTION_PREV"
        const val ACTION_REWIND = "io.github.audiz.ACTION_REWIND"
        const val ACTION_FAST_FORWARD = "io.github.audiz.ACTION_FAST_FORWARD"
        const val ACTION_TOGGLE_LIKE = "io.github.audiz.ACTION_TOGGLE_LIKE"
        const val ACTION_CLOSE_APP = "io.github.audiz.ACTION_CLOSE_APP"
        const val ACTION_DISLIKE = "io.github.audiz.ACTION_DISLIKE"
        const val ACTION_STOP = "io.github.audiz.ACTION_STOP"
        const val ACTION_UPDATE = "io.github.audiz.ACTION_UPDATE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> MediaPlaybackBridge.onPlay?.invoke()
            ACTION_PAUSE -> MediaPlaybackBridge.onPause?.invoke()
            ACTION_TOGGLE -> MediaPlaybackBridge.onTogglePlayPause?.invoke()
            ACTION_NEXT -> MediaPlaybackBridge.onNext?.invoke()
            ACTION_PREV -> MediaPlaybackBridge.onPrev?.invoke()
            ACTION_REWIND -> {
                val target = (MediaPlaybackBridge.currentPositionMs - 15000L).coerceAtLeast(0L)
                MediaPlaybackBridge.onSeek?.invoke(target)
            }
            ACTION_FAST_FORWARD -> {
                val target = MediaPlaybackBridge.currentPositionMs + 15000L
                MediaPlaybackBridge.onSeek?.invoke(target)
            }
            ACTION_TOGGLE_LIKE -> MediaPlaybackBridge.onToggleLike?.invoke()
            ACTION_CLOSE_APP -> {
                closeApplication()
                return START_NOT_STICKY
            }
            ACTION_DISLIKE -> MediaPlaybackBridge.onDislike?.invoke()
            ACTION_STOP -> {
                closeApplication()
                return START_NOT_STICKY
            }
        }

        startInForeground()
        return START_STICKY
    }

    private fun closeApplication() {
        try {
            MediaPlaybackBridge.onPause?.invoke()
        } catch (_: Throwable) {}
        try {
            MediaPlaybackBridge.closeAppHandler?.invoke()
        } catch (_: Throwable) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        } catch (_: Throwable) {}
        try {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(0)
        } catch (_: Throwable) {}
    }

    private fun getDrawableResId(name: String, fallback: Int): Int {
        val id = resources.getIdentifier(name, "drawable", packageName)
        return if (id != 0) id else fallback
    }

    private fun startInForeground() {
        createNotificationChannel()
        val notification = buildNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AppLogger.e("MediaPlaybackService", "Ошибка запуска startForeground: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Фоновое воспроизведение музыки",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Управление воспроизведением и медиа-уведомление"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                }
                nm?.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val title = MediaPlaybackBridge.currentTitle.ifBlank { "Яндекс Музыка" }
        val artist = MediaPlaybackBridge.currentArtist.ifBlank { "YamSync" }
        val isPlaying = MediaPlaybackBridge.isPlaying

        val playPauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val likeIntent = PendingIntent.getService(
            this, 6,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_TOGGLE_LIKE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val closeIntent = PendingIntent.getService(
            this, 7,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_CLOSE_APP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val mediaStyle = Notification.MediaStyle().apply {
            MediaPlaybackBridge.sessionToken?.let { setMediaSession(it) }
            // В компактной шторке отображаются 3 главные кнопки: Предыдущий (1), Play/Pause (2), Следующий (3)
            // В развернутом виде и на экране заставки доступны все 5 кнопок: Лайк (0), Назад (1), Play/Pause (2), Вперед (3), Закрыть (4)
            setShowActionsInCompactView(1, 2, 3)
        }

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Пауза" else "Играть"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val appIcon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_def_app_icon

        // 0: Сердечко (Лайк / В избранное)
        val likeResName = if (MediaPlaybackBridge.isLiked) "ic_notification_heart_filled" else "ic_notification_heart_outline"
        val likeFallback = if (MediaPlaybackBridge.isLiked) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        val likeResId = getDrawableResId(likeResName, likeFallback)
        val likeTitle = if (MediaPlaybackBridge.isLiked) "В избранном" else "В избранное"

        val likeAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val icon = android.graphics.drawable.Icon.createWithResource(this, likeResId)
            Notification.Action.Builder(icon, likeTitle, likeIntent).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(likeResId, likeTitle, likeIntent).build()
        }

        // 1: Предыдущий трек
        val prevAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val icon = android.graphics.drawable.Icon.createWithResource("android", android.R.drawable.ic_media_previous)
            Notification.Action.Builder(icon, "Назад", prevIntent).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(android.R.drawable.ic_media_previous, "Назад", prevIntent).build()
        }

        // 2: Воспроизведение / Пауза
        val playPauseAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val icon = android.graphics.drawable.Icon.createWithResource("android", playPauseIcon)
            Notification.Action.Builder(icon, playPauseTitle, playPauseIntent).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(playPauseIcon, playPauseTitle, playPauseIntent).build()
        }

        // 3: Следующий трек
        val nextAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val icon = android.graphics.drawable.Icon.createWithResource("android", android.R.drawable.ic_media_next)
            Notification.Action.Builder(icon, "Вперед", nextIntent).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(android.R.drawable.ic_media_next, "Вперед", nextIntent).build()
        }

        // 4: Крестик (Закрыть приложение)
        val closeResId = getDrawableResId("ic_notification_close", android.R.drawable.ic_menu_close_clear_cancel)
        val closeTitle = "Закрыть"

        val closeAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val icon = android.graphics.drawable.Icon.createWithResource(this, closeResId)
            Notification.Action.Builder(icon, closeTitle, closeIntent).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(closeResId, closeTitle, closeIntent).build()
        }

        val artwork = MediaPlaybackBridge.currentArtworkBitmap
        if (artwork != null && !artwork.isRecycled) {
            builder.setLargeIcon(artwork)
        }

        return builder
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("YamSync")
            .setColor(0xFFFFCC00.toInt()) // Фирменный жёлтый акцент YamSync
            .setColorized(true)
            .setSmallIcon(appIcon)
            .setContentIntent(launchIntent)
            .setStyle(mediaStyle)
            .setOngoing(isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(likeAction)       // 0: Сердечко (Лайк)
            .addAction(prevAction)       // 1: Предыдущий трек
            .addAction(playPauseAction)  // 2: Воспроизведение / Пауза
            .addAction(nextAction)       // 3: Следующий трек
            .addAction(closeAction)      // 4: Крестик (Закрыть приложение)
            .build()
    }
}

/**
 * Менеджер для управления запуском и остановкой службы
 */
object MediaPlaybackServiceManager {
    fun startOrUpdate(context: Context, title: String, artist: String, isPlaying: Boolean) {
        MediaPlaybackBridge.currentTitle = title
        MediaPlaybackBridge.currentArtist = artist
        MediaPlaybackBridge.isPlaying = isPlaying

        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_UPDATE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            println("MediaPlaybackServiceManager: Ошибка запуска foreground service: ${e.message}")
        }
    }

    fun updateNotification(context: Context) {
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_UPDATE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
    }

    fun updateLikedState(context: Context, isLiked: Boolean) {
        MediaPlaybackBridge.isLiked = isLiked
        updateNotification(context)
    }

    fun stop(context: Context) {
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            println("MediaPlaybackServiceManager: Ошибка остановки foreground service: ${e.message}")
        }
    }
}
