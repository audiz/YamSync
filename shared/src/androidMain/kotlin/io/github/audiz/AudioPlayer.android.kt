package io.github.audiz

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Equalizer as AndroidEqualizer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.PowerManager
import io.github.audiz.dsp.AudioVisualizer
import io.github.audiz.dsp.EqualizerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import io.github.audiz.stream.AndroidStreamMediaDataSource
import io.github.audiz.stream.LocalStreamProxy

/**
 * 🎵 Android-реализация аудиоплеера на основе MediaPlayer
 *
 * Поддерживает:
 * - Корректное управление фокусом воспроизведения (AudioFocus: звонки, уведомления)
 * - Отключение Bluetooth-устройств и проводных гарнитур (ACTION_AUDIO_BECOMING_NOISY)
 * - Аппаратный 19-полосный FFT-спектроанализатор Winamp Pro и 5-полосный визуализатор через Visualizer API
 * - Аппаратный эквалайзер и частотные срезы HPF/LPF через android.media.audiofx.Equalizer API
 */
actual class AudioPlayer actual constructor() {

    private var mediaPlayer: MediaPlayer? = null
    private var fadingMediaPlayer: MediaPlayer? = null
    @Volatile private var preparingMediaPlayer: MediaPlayer? = null
    @Volatile private var preparingMediaDataSource: AndroidStreamMediaDataSource? = null
    @Volatile private var currentMediaDataSource: AndroidStreamMediaDataSource? = null
    @Volatile private var fadingMediaDataSource: AndroidStreamMediaDataSource? = null
    @Volatile private var preparingSessionId: Long = 0L
    @Volatile private var currentSessionId: Long = 0L
    private var prepareTimeoutJob: Job? = null
    private var crossfadeJob: Job? = null
    private var visualizer: Visualizer? = null
    private var equalizer: AndroidEqualizer? = null
    private val playerScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        playerScope.launch {
            EqualizerEngine.state.collect { state ->
                applyEqualizerState(state)
            }
        }
    }

    private var tempFile: File? = null
    private var oldTempFile: File? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onInterruptionListener: ((shouldPause: Boolean, canResume: Boolean) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var onPlaybackStartedListener: (() -> Unit)? = null

    private var audioFocusRequest: AudioFocusRequest? = null
    private var isNoisyReceiverRegistered = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                println("AudioPlayer Android: ACTION_AUDIO_BECOMING_NOISY (BT/гарнитура отключена) -> пауза")
                pause()
                onInterruptionListener?.invoke(true, false)
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                println("AudioPlayer Android: AUDIOFOCUS_LOSS -> постоянная потеря фокуса -> пауза")
                pause()
                onInterruptionListener?.invoke(true, false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                println("AudioPlayer Android: AUDIOFOCUS_LOSS_TRANSIENT (звонок/навигатор) -> пауза")
                pause()
                onInterruptionListener?.invoke(true, true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                println("AudioPlayer Android: AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> пауза")
                pause()
                onInterruptionListener?.invoke(true, true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                println("AudioPlayer Android: AUDIOFOCUS_GAIN -> восстановление фокуса")
                onInterruptionListener?.invoke(false, true)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val context = AppContextHolder.appContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        try {
            val context = AppContextHolder.appContext
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) {
            println("AudioPlayer Android: Ошибка abandonAudioFocus: ${e.message}")
        }
    }

    private fun registerNoisyReceiver() {
        if (isNoisyReceiverRegistered) return
        try {
            val context = AppContextHolder.appContext
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            context.registerReceiver(becomingNoisyReceiver, filter)
            isNoisyReceiverRegistered = true
        } catch (e: Exception) {
            println("AudioPlayer Android: Ошибка регистрации becomingNoisyReceiver: ${e.message}")
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!isNoisyReceiverRegistered) return
        try {
            val context = AppContextHolder.appContext
            context.unregisterReceiver(becomingNoisyReceiver)
        } catch (e: Exception) {
            println("AudioPlayer Android: Ошибка отмены регистрации becomingNoisyReceiver: ${e.message}")
        } finally {
            isNoisyReceiverRegistered = false
        }
    }

    private fun attachCompletion(player: MediaPlayer) {
        player.setOnCompletionListener {
            if (mediaPlayer === player) {
                try {
                    fadingMediaPlayer?.stop()
                    fadingMediaPlayer?.release()
                } catch (_: Exception) {}
                fadingMediaPlayer = null
                oldTempFile?.delete()
                oldTempFile = null

                abandonAudioFocus()
                unregisterNoisyReceiver()
                releaseVisualizer()
                releaseEqualizer()
                onCompletionListener?.invoke()
            }
        }
    }

    private fun prepareForNewPlayback(crossfadeMs: Long): Long {
        val newSessionId = ++currentSessionId
        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = null

        val oldPreparing = preparingMediaPlayer
        preparingMediaPlayer = null
        val oldPreparingDs = preparingMediaDataSource
        preparingMediaDataSource = null
        preparingSessionId = 0L
        if (oldPreparing != null) {
            try {
                oldPreparing.reset()
                oldPreparing.release()
            } catch (_: Exception) {}
        }
        oldPreparingDs?.close()

        if (crossfadeMs <= 0L) {
            crossfadeJob?.cancel()
            crossfadeJob = null

            try {
                fadingMediaPlayer?.stop()
                fadingMediaPlayer?.release()
            } catch (_: Exception) {}
            fadingMediaPlayer = null
            fadingMediaDataSource?.close()
            fadingMediaDataSource = null

            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
            currentMediaDataSource?.close()
            currentMediaDataSource = null

            oldTempFile?.delete()
            oldTempFile = null
            tempFile?.delete()
            tempFile = null
        } else {
            crossfadeJob?.cancel()
            crossfadeJob = null

            try {
                fadingMediaPlayer?.stop()
                fadingMediaPlayer?.release()
            } catch (_: Exception) {}
            fadingMediaPlayer = null
            fadingMediaDataSource?.close()
            fadingMediaDataSource = currentMediaDataSource
            currentMediaDataSource = null

            oldTempFile?.delete()
            oldTempFile = null
        }
        return newSessionId
    }

    private fun startCrossfade(newPlayer: MediaPlayer, crossfadeMs: Long) {
        crossfadeJob?.cancel()
        val outgoing = fadingMediaPlayer ?: mediaPlayer
        val canCrossfade = outgoing != null && outgoing !== newPlayer && (try { outgoing.isPlaying } catch (_: Exception) { false }) && crossfadeMs > 0L

        if (canCrossfade && outgoing != null) {
            fadingMediaPlayer = outgoing
            mediaPlayer = newPlayer

            setupVisualizer(newPlayer.audioSessionId)
            setupEqualizer(newPlayer.audioSessionId)

            val stepIntervalMs = 40L
            val steps = (crossfadeMs / stepIntervalMs).toInt().coerceAtLeast(1)

            crossfadeJob = playerScope.launch {
                for (step in 1..steps) {
                    kotlinx.coroutines.delay(stepIntervalMs)
                    val t = step.toFloat() / steps
                    val outFactor = kotlin.math.cos(t * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)
                    val inFactor = kotlin.math.sin(t * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)

                    val outGain = volumeToGain(currentVolume * outFactor)
                    val inGain = volumeToGain(currentVolume * inFactor)

                    try { fadingMediaPlayer?.setVolume(outGain, outGain) } catch (_: Exception) {}
                    try { newPlayer.setVolume(inGain, inGain) } catch (_: Exception) {}
                }

                try {
                    fadingMediaPlayer?.stop()
                    fadingMediaPlayer?.release()
                } catch (_: Exception) {}
                fadingMediaPlayer = null
                fadingMediaDataSource?.close()
                fadingMediaDataSource = null
                oldTempFile?.delete()
                oldTempFile = null

                val finalGain = volumeToGain(currentVolume)
                try { newPlayer.setVolume(finalGain, finalGain) } catch (_: Exception) {}
            }
        } else {
            try {
                outgoing?.stop()
                outgoing?.release()
            } catch (_: Exception) {}
            fadingMediaPlayer = null
            fadingMediaDataSource?.close()
            fadingMediaDataSource = null
            oldTempFile?.delete()
            oldTempFile = null

            mediaPlayer = newPlayer
            val gain = volumeToGain(currentVolume)
            newPlayer.setVolume(gain, gain)
            setupVisualizer(newPlayer.audioSessionId)
            setupEqualizer(newPlayer.audioSessionId)
        }
    }

    actual fun playFromBytes(bytes: ByteArray) = playFromBytes(bytes, 0L)

    actual fun playFromBytes(bytes: ByteArray, crossfadeMs: Long) {
        val sessionId = prepareForNewPlayback(crossfadeMs)
        try {
            val ext = when {
                bytes.size >= 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                        bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() -> ".m4a"
                bytes.size >= 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                        bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte() -> ".flac"
                else -> ".mp3"
            }
            val tmp = File.createTempFile("yandex_audio_", ext)
            tmp.deleteOnExit()
            FileOutputStream(tmp).use { it.write(bytes) }
            oldTempFile?.delete()
            oldTempFile = tempFile
            tempFile = tmp

            requestAudioFocus()
            registerNoisyReceiver()

            val newPlayer = MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                try {
                    setWakeMode(AppContextHolder.appContext, PowerManager.PARTIAL_WAKE_LOCK)
                } catch (e: Exception) {
                    println("AudioPlayer Android: Не удалось установить WakeMode: ${e.message}")
                }
                attachCompletion(this)
                setOnErrorListener { mp, what, extra ->
                    println("AudioPlayer Android: Ошибка воспроизведения байтов ($what, $extra)")
                    if (sessionId == currentSessionId) {
                        try { mp.reset(); mp.release() } catch (_: Exception) {}
                        onErrorListener?.invoke("Ошибка воспроизведения аудио ($what, $extra)")
                    } else {
                        try { mp.reset(); mp.release() } catch (_: Exception) {}
                    }
                    true
                }
                prepare()
                val wasPlaying = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
                if (crossfadeMs > 0L && wasPlaying) {
                    setVolume(0f, 0f)
                } else {
                    val gain = volumeToGain(currentVolume)
                    setVolume(gain, gain)
                }
                start()
            }
            startCrossfade(newPlayer, crossfadeMs)
            onPlaybackStartedListener?.invoke()
        } catch (e: Exception) {
            println("AudioPlayer Android: Ошибка воспроизведения из bytes: ${e.message}")
            e.printStackTrace()
            if (sessionId == currentSessionId) {
                onErrorListener?.invoke("Ошибка воспроизведения аудио: ${e.message}")
            }
        }
    }

    actual fun playFromFile(filePath: String) = playFromFile(filePath, 0L)

    actual fun playFromFile(filePath: String, crossfadeMs: Long) {
        val sessionId = prepareForNewPlayback(crossfadeMs)
        try {
            requestAudioFocus()
            registerNoisyReceiver()

            val cleanPath = filePath.trim().removePrefix("local:").removePrefix("file://")
            val resolvedPath = resolveLocalPath(cleanPath)
            val file = File(resolvedPath)

            val newPlayer = MediaPlayer().apply {
                if (file.exists()) {
                    java.io.FileInputStream(file).use { fis ->
                        setDataSource(fis.fd, 0L, file.length())
                    }
                } else {
                    setDataSource(resolvedPath)
                }
                try {
                    setWakeMode(AppContextHolder.appContext, PowerManager.PARTIAL_WAKE_LOCK)
                } catch (e: Exception) {
                    println("AudioPlayer Android: Не удалось установить WakeMode: ${e.message}")
                }
                attachCompletion(this)
                setOnErrorListener { mp, what, extra ->
                    println("AudioPlayer Android: Ошибка воспроизведения файла ($what, $extra)")
                    if (sessionId == currentSessionId) {
                        try { mp.reset(); mp.release() } catch (_: Exception) {}
                        onErrorListener?.invoke("Ошибка воспроизведения файла ($what, $extra)")
                    } else {
                        try { mp.reset(); mp.release() } catch (_: Exception) {}
                    }
                    true
                }
                prepare()
                val wasPlaying = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
                if (crossfadeMs > 0L && wasPlaying) {
                    setVolume(0f, 0f)
                } else {
                    val gain = volumeToGain(currentVolume)
                    setVolume(gain, gain)
                }
                start()
            }
            startCrossfade(newPlayer, crossfadeMs)
            onPlaybackStartedListener?.invoke()
        } catch (e: Exception) {
            println("AudioPlayer Android: Ошибка воспроизведения файла: ${e.message}")
            e.printStackTrace()
            if (sessionId == currentSessionId) {
                val msg = e.message ?: ""
                val errorMsg = if (msg.contains("EACCES") || msg.contains("Permission denied", ignoreCase = true)) {
                    "Ошибка доступа к файлу: нет разрешения на чтение хранилища. Предоставьте доступ в настройках устройства."
                } else {
                    "Ошибка воспроизведения файла: ${e.message}"
                }
                onErrorListener?.invoke(errorMsg)
            }
        }
    }

    actual fun playFromUrl(url: String) = playFromUrl(url, 0L)

    actual fun playFromUrl(url: String, crossfadeMs: Long) {
        val sessionId = prepareForNewPlayback(crossfadeMs)
        try {
            val newPlayer = MediaPlayer()
            preparingMediaPlayer = newPlayer
            preparingSessionId = sessionId

            var mediaDataSource: AndroidStreamMediaDataSource? = null
            if (url.startsWith("yamusic-stream://")) {
                val queryParams = parseQueryParams(url)
                val trackIdWithExt = url.substringAfter("yamusic-stream://stream/").substringBefore("?")
                val trackId = if (trackIdWithExt.contains(".")) trackIdWithExt.substringBeforeLast(".") else trackIdWithExt
                val quality = queryParams["quality"] ?: "1"
                val title = queryParams["title"] ?: ""
                val artist = queryParams["artist"] ?: ""

                val ds = LocalStreamProxy.createMediaDataSource(trackId, quality, title, artist)
                if (ds != null) {
                    mediaDataSource = ds
                    preparingMediaDataSource = ds
                    newPlayer.setDataSource(ds)
                } else {
                    newPlayer.setDataSource(url)
                }
            } else {
                newPlayer.setDataSource(url)
            }

            try {
                newPlayer.setWakeMode(AppContextHolder.appContext, PowerManager.PARTIAL_WAKE_LOCK)
            } catch (e: Exception) {
                println("AudioPlayer Android: Не удалось установить WakeMode: ${e.message}")
            }
            attachCompletion(newPlayer)

            newPlayer.setOnErrorListener { mp, what, extra ->
                println("AudioPlayer Android: Ошибка MediaPlayer для сессии $sessionId (what=$what, extra=$extra)")
                prepareTimeoutJob?.cancel()
                prepareTimeoutJob = null
                mediaDataSource?.close()
                if (sessionId == currentSessionId) {
                    if (preparingMediaPlayer === mp) {
                        preparingMediaPlayer = null
                    }
                    if (preparingMediaDataSource === mediaDataSource) {
                        preparingMediaDataSource = null
                    }
                    try { mp.reset(); mp.release() } catch (_: Exception) {}
                    onErrorListener?.invoke("Ошибка декодирования или загрузки потока ($what, $extra)")
                } else {
                    try { mp.reset(); mp.release() } catch (_: Exception) {}
                }
                true
            }

            newPlayer.setOnPreparedListener { mp ->
                prepareTimeoutJob?.cancel()
                prepareTimeoutJob = null

                if (sessionId != currentSessionId) {
                    println("AudioPlayer Android: Сессия $sessionId устарела (текущая: $currentSessionId), освобождаем плеер")
                    mediaDataSource?.close()
                    try { mp.reset(); mp.release() } catch (_: Exception) {}
                    if (preparingMediaPlayer === mp) {
                        preparingMediaPlayer = null
                    }
                    if (preparingMediaDataSource === mediaDataSource) {
                        preparingMediaDataSource = null
                    }
                    return@setOnPreparedListener
                }

                if (preparingMediaPlayer === mp) {
                    preparingMediaPlayer = null
                }
                if (preparingMediaDataSource === mediaDataSource) {
                    preparingMediaDataSource = null
                }
                currentMediaDataSource = mediaDataSource

                requestAudioFocus()
                registerNoisyReceiver()

                val wasPlaying = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
                if (crossfadeMs > 0L && wasPlaying) {
                    mp.setVolume(0f, 0f)
                } else {
                    val gain = volumeToGain(currentVolume)
                    mp.setVolume(gain, gain)
                }

                try {
                    mp.start()
                    startCrossfade(mp, crossfadeMs)
                    onPlaybackStartedListener?.invoke()
                } catch (e: Exception) {
                    println("AudioPlayer Android: Ошибка старта плеера: ${e.message}")
                    mediaDataSource?.close()
                    try { mp.reset(); mp.release() } catch (_: Exception) {}
                    onErrorListener?.invoke("Ошибка запуска воспроизведения: ${e.message}")
                }
            }

            // Таймаут на асинхронную буферизацию (12 секунд)
            prepareTimeoutJob = playerScope.launch {
                kotlinx.coroutines.delay(12000L)
                if (sessionId == currentSessionId && preparingMediaPlayer === newPlayer) {
                    println("AudioPlayer Android: Таймаут буферизации для сессии $sessionId")
                    preparingMediaPlayer = null
                    preparingMediaDataSource = null
                    mediaDataSource?.close()
                    try { newPlayer.reset(); newPlayer.release() } catch (_: Exception) {}
                    onErrorListener?.invoke("Таймаут буферизации аудиопотока")
                }
            }

            newPlayer.prepareAsync()
        } catch (e: Exception) {
            println("AudioPlayer Android: Ошибка вызова playFromUrl: ${e.message}")
            e.printStackTrace()
            if (sessionId == currentSessionId) {
                preparingMediaPlayer = null
                preparingMediaDataSource = null
                onErrorListener?.invoke("Не удалось инициализировать аудиоплеер: ${e.message}")
            }
        }
    }

    private fun parseQueryParams(url: String): Map<String, String> {
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx != -1) {
                val k = pair.substring(0, idx)
                val v = pair.substring(idx + 1)
                result[k] = LocalStreamProxy.decodeParam(v)
            }
        }
        return result
    }

    actual fun pause() {
        try {
            mediaPlayer?.takeIf { it.isPlaying }?.pause()
        } catch (_: Exception) {}
        try {
            fadingMediaPlayer?.takeIf { it.isPlaying }?.pause()
        } catch (_: Exception) {}
        try {
            visualizer?.enabled = false
        } catch (_: Exception) {}
        unregisterNoisyReceiver()
    }

    actual fun resume() {
        requestAudioFocus()
        registerNoisyReceiver()
        try {
            mediaPlayer?.start()
            fadingMediaPlayer?.start()
            if (visualizer == null) {
                mediaPlayer?.audioSessionId?.let { setupVisualizer(it) }
            } else {
                visualizer?.enabled = true
            }
            if (equalizer == null) {
                mediaPlayer?.audioSessionId?.let { setupEqualizer(it) }
            } else {
                applyEqualizerState(EqualizerEngine.state.value)
            }
        } catch (_: Exception) {}
    }

    actual fun stop() {
        currentSessionId++
        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = null

        val oldPreparing = preparingMediaPlayer
        preparingMediaPlayer = null
        val oldPreparingDs = preparingMediaDataSource
        preparingMediaDataSource = null
        preparingSessionId = 0L
        if (oldPreparing != null) {
            try { oldPreparing.reset(); oldPreparing.release() } catch (_: Exception) {}
        }
        oldPreparingDs?.close()

        crossfadeJob?.cancel()
        crossfadeJob = null
        try {
            fadingMediaPlayer?.stop()
            fadingMediaPlayer?.release()
        } catch (_: Exception) {}
        fadingMediaPlayer = null
        fadingMediaDataSource?.close()
        fadingMediaDataSource = null
        oldTempFile?.delete()
        oldTempFile = null

        currentMediaDataSource?.close()
        currentMediaDataSource = null

        abandonAudioFocus()
        unregisterNoisyReceiver()
        releaseVisualizer()
        releaseEqualizer()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        tempFile?.delete()
        tempFile = null
    }

    actual fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying ?: (fadingMediaPlayer?.isPlaying ?: false)
        } catch (_: Exception) {
            false
        }
    }

    actual fun getDurationMs(): Long {
        return try {
            mediaPlayer?.duration?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    actual fun getCurrentPositionMs(): Long {
        return try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    actual fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.seekTo(positionMs.toInt())
        } catch (_: Exception) {}
    }

    private var currentVolume: Float = 1f

    actual fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (crossfadeJob?.isActive != true) {
            val gain = volumeToGain(currentVolume)
            try {
                mediaPlayer?.setVolume(gain, gain)
            } catch (_: Exception) {}
        }
    }

    actual fun setOnCompletionListener(listener: (() -> Unit)?) {
        onCompletionListener = listener
    }

    actual fun setOnInterruptionListener(listener: ((shouldPause: Boolean, canResume: Boolean) -> Unit)?) {
        onInterruptionListener = listener
    }

    actual fun setOnErrorListener(listener: ((error: String) -> Unit)?) {
        onErrorListener = listener
    }

    actual fun setOnPlaybackStartedListener(listener: (() -> Unit)?) {
        onPlaybackStartedListener = listener
    }

    private var onNearEndListener: (() -> Unit)? = null
    actual fun setOnNearEndListener(listener: (() -> Unit)?) {
        onNearEndListener = listener
    }

    actual fun updateNearEndThreshold(thresholdMs: Long, expectedDurationMs: Long) {
        // Android MediaPlayer handles near-end progress via PlaybackManager polling
    }

    actual fun release() {
        stop()
        releaseVisualizer()
        releaseEqualizer()
        playerScope.cancel()
        onCompletionListener = null
        onInterruptionListener = null
        onErrorListener = null
        onPlaybackStartedListener = null
    }

    private fun setupEqualizer(audioSessionId: Int) {
        releaseEqualizer()
        if (audioSessionId <= 0) return

        try {
            val eq = AndroidEqualizer(0, audioSessionId)
            equalizer = eq
            applyEqualizerState(EqualizerEngine.state.value)
            println("AudioPlayer Android: Equalizer успешно запущен (session=$audioSessionId, bands=${eq.numberOfBands})")
        } catch (e: Throwable) {
            println("AudioPlayer Android: Не удалось запустить Equalizer: ${e.message}")
            releaseEqualizer()
        }
    }

    private fun applyEqualizerState(state: EqualizerEngine.State) {
        val eq = equalizer ?: return
        try {
            if (!state.isEnabled) {
                eq.enabled = false
                return
            }

            val range = eq.bandLevelRange
            val minMb = if (range != null && range.size >= 2) range[0] else -1500.toShort()
            val maxMb = if (range != null && range.size >= 2) range[1] else 1500.toShort()

            val numBands = eq.numberOfBands
            for (b in 0 until numBands) {
                val centerFreqHz = eq.getCenterFreq(b.toShort()) / 1000f
                val targetGainDb = EqualizerEngine.calculateEffectiveGainDb(centerFreqHz)
                val targetMb = (targetGainDb * 100f).roundToInt().coerceIn(minMb.toInt(), maxMb.toInt()).toShort()
                eq.setBandLevel(b.toShort(), targetMb)
            }

            eq.enabled = true
        } catch (e: Throwable) {
            println("AudioPlayer Android: Ошибка применения настроек эквалайзера: ${e.message}")
        }
    }

    private fun releaseEqualizer() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (_: Throwable) {
        } finally {
            equalizer = null
        }
    }

    private fun setupVisualizer(audioSessionId: Int) {
        releaseVisualizer()
        if (audioSessionId <= 0) return

        val context = AppContextHolder.appContext
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            println("AudioPlayer Android: RECORD_AUDIO не предоставлено -> Visualizer не активен (fallback волна)")
            return
        }

        try {
            val v = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            val captureSize = if (range.size >= 2) range[1].coerceAtMost(1024) else 512
            v.captureSize = captureSize

            val maxRate = Visualizer.getMaxCaptureRate()
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || fft.isEmpty() || !isPlaying()) return
                    try {
                        processAndroidFft(fft, samplingRate, captureSize)
                    } catch (_: Throwable) {}
                }
            }, maxRate, false, true)

            v.enabled = true
            visualizer = v
            println("AudioPlayer Android: Visualizer успешно запущен (session=$audioSessionId, captureSize=$captureSize, rate=$maxRate)")
        } catch (e: Throwable) {
            println("AudioPlayer Android: Ошибка инициализации Visualizer: ${e.message}")
            releaseVisualizer()
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Throwable) {
        } finally {
            visualizer = null
        }
        AudioVisualizer.clearDelayQueue()
    }

    private fun processAndroidFft(fft: ByteArray, samplingRateMilliHz: Int, captureSize: Int) {
        val n = minOf(fft.size, captureSize)
        val numBins = n / 2
        if (numBins < 4) return

        val sampleRateHz = if (samplingRateMilliHz > 0) samplingRateMilliHz / 1000f else 44100f
        val binHz = sampleRateHz / n.toFloat()
        if (binHz <= 0f) return

        val magnitudesDb = FloatArray(numBins)
        magnitudesDb[0] = -100f

        for (k in 1 until numBins) {
            val r = fft[2 * k].toFloat()
            val i = fft[2 * k + 1].toFloat()
            val mag = hypot(r, i)
            magnitudesDb[k] = if (mag > 0.01f) {
                (20f * log10(mag / 128f)).coerceIn(-60f, 0f)
            } else {
                -60f
            }
        }

        val threshold = -60f
        fun calcBandEnergy(fStart: Float, fEnd: Float, tiltDb: Float, peakWeight: Float = 0.65f): Float {
            val startBin = (fStart / binHz).toInt().coerceIn(1, numBins - 1)
            val endBin = (fEnd / binHz).toInt().coerceIn(startBin, numBins - 1)
            var sum = 0f
            var maxDb = -120f
            for (k in startBin..endBin) {
                val v = magnitudesDb[k]
                sum += v
                if (v > maxDb) maxDb = v
            }
            val avgDb = sum / (endBin - startBin + 1)
            val combinedDb = (maxDb * peakWeight + avgDb * (1f - peakWeight)) + tiltDb
            return ((combinedDb - threshold) / (-threshold)).coerceIn(0f, 1f)
        }

        // 19 аппаратных полос Winamp Pro (обложка альбома)
        val spectrum19 = FloatArray(19)
        for (k in 0 until 19) {
            val progress = k.toFloat() / 18f
            val tilt = -7.0f + progress * 23.0f
            val (fStart, fEnd) = FREQ_RANGES_19[k]
            spectrum19[k] = calcBandEnergy(fStart, fEnd, tiltDb = tilt, peakWeight = 0.75f)
        }

        // 5 полос для кнопок плеера и микро-иконок
        // 1. Sub-bass / Kick (~30 - 170 Гц): -7.0 дБ
        val b1 = calcBandEnergy(30f, 170f, tiltDb = -7.0f)
        // 2. Bass & Mid-bass (~170 - 680 Гц): +4.5 дБ (85% пик)
        val b2 = calcBandEnergy(170f, 680f, tiltDb = 4.5f, peakWeight = 0.85f)
        // 3. Mids (~680 - 2200 Гц): +3.0 дБ
        val b3 = calcBandEnergy(680f, 2200f, tiltDb = 3.0f)
        // 4. High-Mids (~2200 - 6500 Гц): +9.0 дБ
        val b4 = calcBandEnergy(2200f, 6500f, tiltDb = 9.0f)
        // 5. Treble (~6500 - 15000 Гц): +15.5 дБ
        val b5 = calcBandEnergy(6500f, 15000f, tiltDb = 15.5f)

        AudioVisualizer.updateBands(b1, b2, b3, b4, b5)
        AudioVisualizer.updateSpectrum19(spectrum19)
    }

    companion object {
        // 🎛️ 19 аппаратных частотных полос Winamp Pro (20 Гц .. 20 кГц) для спектроанализатора на обложке:
        private val FREQ_RANGES_19 = arrayOf(
            20f to 55f,      // Полоса 0: ~31 Гц
            55f to 100f,     // Полоса 1: ~62 Гц
            100f to 155f,    // Полоса 2: ~93 Гц
            155f to 220f,    // Полоса 3: ~125 Гц
            220f to 310f,    // Полоса 4: ~170 Гц
            310f to 440f,    // Полоса 5: ~250 Гц
            440f to 620f,    // Полоса 6: ~350 Гц
            620f to 880f,    // Полоса 7: ~500 Гц
            880f to 1250f,   // Полоса 8: ~700 Гц
            1250f to 1750f,  // Полоса 9: ~1 кГц
            1750f to 2500f,  // Полоса 10: ~1.4 кГц
            2500f to 3500f,  // Полоса 11: ~2 кГц
            3500f to 5000f,  // Полоса 12: ~2.8 кГц
            5000f to 7000f,  // Полоса 13: ~4 кГц
            7000f to 9500f,  // Полоса 14: ~5.6 кГц
            9500f to 12500f, // Полоса 15: ~8 кГц
            12500f to 15000f,// Полоса 16: ~11 кГц
            15000f to 17500f,// Полоса 17: ~14 кГц
            17500f to 21000f // Полоса 18: ~16 кГц
        )
    }
}
