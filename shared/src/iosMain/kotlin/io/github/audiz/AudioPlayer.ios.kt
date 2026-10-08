package io.github.audiz

import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.Foundation.NSURL
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNotification
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.darwin.dispatch_queue_create
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import io.github.audiz.stream.AudioResourceLoaderDelegate
import io.github.audiz.stream.LocalStreamProxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 🎵 iOS-реализация аудиоплеера на основе AVFoundation (AVPlayer)
 *
 * Поддерживает:
 * - AAC (M4A / ISO BMFF и ADTS)
 * - MP3
 * - ALAC, FLAC, WAV
 * - Каноничный стриминг чанками через AVAssetResourceLoaderDelegate
 * - Корректную обработку отключения Bluetooth / гарнитуры (Becoming Noisy)
 * - Системные прерывания (входящие вызовы, будильник, Siri)
 * - Нативное событие окончания трека (AVPlayerItemDidPlayToEndTimeNotification)
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual class AudioPlayer actual constructor() {

    private var avPlayer: AVPlayer? = null
    private var fadingAvPlayer: AVPlayer? = null
    private var crossfadeJob: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tempFilePath: String? = null
    private var currentResourceLoaderDelegate: AudioResourceLoaderDelegate? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onInterruptionListener: ((shouldPause: Boolean, canResume: Boolean) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var onPlaybackStartedListener: (() -> Unit)? = null

    private var itemEndObserver: NSObjectProtocol? = null
    private var interruptionObserver: NSObjectProtocol? = null
    private var routeChangeObserver: NSObjectProtocol? = null

    init {
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        } catch (e: Throwable) {
            println("AudioPlayer iOS: Ошибка настройки AVAudioSession: ${e.message}")
        }
        setupSessionObservers()
    }

    private fun setupSessionObservers() {
        // 1. Слушатель прерываний (звонки, будильники, Siri)
        interruptionObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { notification: NSNotification? ->
            try {
                val userInfo = notification?.userInfo ?: return@addObserverForName
                val typeValue = (userInfo[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue
                if (typeValue == AVAudioSessionInterruptionTypeBegan) {
                    println("AudioPlayer iOS: Прерывание аудио (звонок/будильник) началось -> пауза")
                    avPlayer?.pause()
                    onInterruptionListener?.invoke(true, true)
                } else if (typeValue == AVAudioSessionInterruptionTypeEnded) {
                    val optionsValue = (userInfo[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0uL
                    val shouldResume = (optionsValue and AVAudioSessionInterruptionOptionShouldResume) != 0uL
                    println("AudioPlayer iOS: Прерывание аудио закончилось. shouldResume=$shouldResume")
                    onInterruptionListener?.invoke(false, shouldResume)
                }
            } catch (t: Throwable) {
                println("AudioPlayer iOS: Ошибка обработки interruptionNotification: ${t.message}")
            }
        }

        // 2. Слушатель отключения внешних аудиоустройств (автомобильный Bluetooth, наушники)
        routeChangeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { notification: NSNotification? ->
            try {
                val userInfo = notification?.userInfo ?: return@addObserverForName
                val reasonValue = (userInfo[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.unsignedLongValue
                if (reasonValue == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) {
                    println("AudioPlayer iOS: Аудиоустройство отключено (BT/наушники) -> пауза (Becoming Noisy)")
                    avPlayer?.pause()
                    onInterruptionListener?.invoke(true, false)
                }
            } catch (t: Throwable) {
                println("AudioPlayer iOS: Ошибка обработки routeChangeNotification: ${t.message}")
            }
        }
    }

    private var timeObserverPlayer: AVPlayer? = null
    private var timeObserverToken: Any? = null
    private var onNearEndListener: (() -> Unit)? = null
    private var nearEndThresholdMs: Long = 8500L
    private var expectedDurationMs: Long = 0L
    private var hasTriggeredNearEnd: Boolean = false

    actual fun setOnNearEndListener(listener: (() -> Unit)?) {
        onNearEndListener = listener
    }

    actual fun updateNearEndThreshold(thresholdMs: Long, expectedDurationMs: Long) {
        this.nearEndThresholdMs = thresholdMs
        if (expectedDurationMs > 0L) {
            this.expectedDurationMs = expectedDurationMs
        }
    }

    private fun detachTimeObserver() {
        val token = timeObserverToken
        val player = timeObserverPlayer
        timeObserverToken = null
        timeObserverPlayer = null
        if (token != null && player != null) {
            try {
                player.removeTimeObserver(token)
            } catch (_: Throwable) {}
        }
    }

    private fun attachTimeObserver(player: AVPlayer) {
        detachTimeObserver()
        hasTriggeredNearEnd = false
        val interval = CMTimeMakeWithSeconds(0.25, 1000)
        timeObserverPlayer = player
        timeObserverToken = player.addPeriodicTimeObserverForInterval(
            interval = interval,
            queue = platform.darwin.dispatch_get_main_queue()
        ) { time ->
            try {
                val currentSec = CMTimeGetSeconds(time)
                if (currentSec.isNaN() || currentSec.isInfinite() || currentSec < 0.0) return@addPeriodicTimeObserverForInterval
                val currentMs = (currentSec * 1000.0).toLong()

                val currentItem = player.currentItem
                val durSec = if (currentItem != null) CMTimeGetSeconds(currentItem.duration) else Double.NaN
                val totalMs = if (!durSec.isNaN() && !durSec.isInfinite() && durSec > 0.0) {
                    (durSec * 1000.0).toLong()
                } else {
                    expectedDurationMs
                }

                if (totalMs > 6000L && currentMs >= (totalMs - nearEndThresholdMs)) {
                    if (!hasTriggeredNearEnd) {
                        hasTriggeredNearEnd = true
                        onNearEndListener?.invoke()
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun attachItemObserver(playerItem: AVPlayerItem) {
        detachItemObserver()
        itemEndObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = playerItem,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            println("AudioPlayer iOS: AVPlayerItem завершил воспроизведение трека")
            onCompletionListener?.invoke()
        }
    }

    private fun detachItemObserver() {
        itemEndObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
        }
        itemEndObserver = null
    }

    private fun startIosCrossfade(newPlayer: AVPlayer, crossfadeMs: Long) {
        crossfadeJob?.cancel()
        val outgoing = fadingAvPlayer ?: avPlayer
        val canCrossfade = outgoing != null && outgoing !== newPlayer && crossfadeMs > 0L

        if (canCrossfade && outgoing != null) {
            fadingAvPlayer = outgoing
            avPlayer = newPlayer
            attachTimeObserver(newPlayer)
            // Уходящий плеер продолжает звучать на полной громкости, пока новый плеер подготавливает и буферизует поток
            try { outgoing.volume = volumeToGain(currentVolume) } catch (_: Throwable) {}
            try { newPlayer.volume = 0f } catch (_: Throwable) {}

            val stepIntervalMs = 40L
            val steps = (crossfadeMs / stepIntervalMs).toInt().coerceAtLeast(1)

            crossfadeJob = playerScope.launch {
                // 1. Ожидаем, пока новый плеер реально начнет отдавать звук (буферизация чанков и старт ЦАП)
                var waitAttempts = 0
                while (waitAttempts < 60) {
                    val isNewPlaying = (newPlayer.timeControlStatus == AVPlayerTimeControlStatusPlaying) ||
                            ((newPlayer.rate) > 0f && CMTimeGetSeconds(newPlayer.currentTime()) > 0.02)
                    if (isNewPlaying) break
                    delay(50)
                    waitAttempts++
                }

                // 2. Только когда новый плеер физически зазвучал — плавно убавляем старый и наращиваем новый
                for (step in 1..steps) {
                    delay(stepIntervalMs)
                    val t = step.toFloat() / steps
                    val outFactor = kotlin.math.cos(t * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)
                    val inFactor = kotlin.math.sin(t * (kotlin.math.PI / 2)).toFloat().coerceIn(0f, 1f)

                    val outGain = volumeToGain(currentVolume * outFactor)
                    val inGain = volumeToGain(currentVolume * inFactor)

                    try { fadingAvPlayer?.volume = outGain } catch (_: Throwable) {}
                    try { newPlayer.volume = inGain } catch (_: Throwable) {}
                }

                try {
                    fadingAvPlayer?.pause()
                } catch (_: Throwable) {}
                fadingAvPlayer = null
                newPlayer.volume = volumeToGain(currentVolume)
            }
        } else {
            try {
                outgoing?.pause()
            } catch (_: Throwable) {}
            fadingAvPlayer = null
            avPlayer = newPlayer
            attachTimeObserver(newPlayer)
            newPlayer.volume = volumeToGain(currentVolume)
        }
    }

    actual fun playFromBytes(bytes: ByteArray) = playFromBytes(bytes, 0L)

    actual fun playFromBytes(bytes: ByteArray, crossfadeMs: Long) {
        val ext = when {
            bytes.size >= 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                    bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() -> ".m4a"
            bytes.size >= 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                    bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte() -> ".flac"
            else -> ".mp3"
        }
        val tmpPath = "${NSTemporaryDirectory()}ya_audio_${NSUUID.UUID().UUIDString}$ext"
        bytes.usePinned { pinned ->
            val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            data.writeToFile(tmpPath, atomically = true)
        }
        tempFilePath = tmpPath
        playFileInternal(tmpPath, crossfadeMs)
    }

    actual fun playFromFile(filePath: String) = playFromFile(filePath, 0L)

    actual fun playFromFile(filePath: String, crossfadeMs: Long) {
        playFileInternal(filePath, crossfadeMs)
    }

    actual fun playFromUrl(url: String) = playFromUrl(url, 0L)

    actual fun playFromUrl(url: String, crossfadeMs: Long) {
        try {
            val session = AVAudioSession.sharedInstance()
            session.setActive(true, error = null)

            val playerItem = if (url.startsWith("yamusic-stream://")) {
                val nsUrl = NSURL.URLWithString(url) ?: return
                val queryParams = parseQueryParams(url)
                val trackIdWithExt = url.substringAfter("yamusic-stream://stream/").substringBefore("?")
                val trackId = trackIdWithExt.substringBeforeLast(".")
                val quality = queryParams["quality"] ?: "1"
                val title = queryParams["title"] ?: ""
                val artist = queryParams["artist"] ?: ""

                val delegate = LocalStreamProxy.createResourceLoaderDelegate(trackId, quality, title, artist)
                if (delegate != null) {
                    currentResourceLoaderDelegate = delegate
                    val asset = AVURLAsset(uRL = nsUrl, options = null)
                    val queue = dispatch_queue_create("io.github.audiz.resourceloader", null)
                    asset.resourceLoader.setDelegate(delegate, queue)
                    println("AudioPlayer iOS: Запуск воспроизведения через каноничный AVAssetResourceLoaderDelegate для трека $trackId ($quality)")
                    AVPlayerItem(asset = asset)
                } else {
                    AVPlayerItem(uRL = nsUrl)
                }
            } else {
                val nsUrl = NSURL.URLWithString(url) ?: return
                AVPlayerItem(uRL = nsUrl)
            }

            attachItemObserver(playerItem)
            IosAudioTapRegistry.attacher?.attachTap(playerItem)
            val newPlayer = AVPlayer(playerItem = playerItem)
            if (crossfadeMs > 0L && (avPlayer?.rate ?: 0f) > 0f) {
                newPlayer.volume = 0f
            } else {
                newPlayer.volume = volumeToGain(currentVolume)
            }
            newPlayer.play()
            startIosCrossfade(newPlayer, crossfadeMs)
            onPlaybackStartedListener?.invoke()
        } catch (e: Throwable) {
            println("AudioPlayer iOS: Ошибка воспроизведения url: ${e.message}")
        }
    }

    private fun parseQueryParams(url: String): Map<String, String> {
        val query = url.substringAfter("?", "")
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx != -1) {
                val key = part.substring(0, idx)
                val value = part.substring(idx + 1)
                key to decodeQueryParam(value)
            } else null
        }.toMap()
    }

    private fun decodeQueryParam(s: String): String {
        if (!s.contains('%') && !s.contains('+')) return s
        return try {
            val bytes = mutableListOf<Byte>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '+' -> {
                        bytes.add(' '.code.toByte())
                        i++
                    }
                    c == '%' && i + 2 < s.length -> {
                        val hex = s.substring(i + 1, i + 3)
                        val byteVal = hex.toIntOrNull(16)
                        if (byteVal != null) {
                            bytes.add(byteVal.toByte())
                            i += 3
                        } else {
                            val charBytes = c.toString().encodeToByteArray()
                            for (b in charBytes) bytes.add(b)
                            i++
                        }
                    }
                    else -> {
                        val charBytes = c.toString().encodeToByteArray()
                        for (b in charBytes) bytes.add(b)
                        i++
                    }
                }
            }
            bytes.toByteArray().decodeToString()
        } catch (_: Throwable) {
            s
        }
    }

    private fun playFileInternal(filePath: String, crossfadeMs: Long = 0L) {
        try {
            val session = AVAudioSession.sharedInstance()
            session.setActive(true, error = null)
            val resolvedPath = resolveIosLocalPath(filePath)
            val url = NSURL.fileURLWithPath(resolvedPath)
            val playerItem = AVPlayerItem(uRL = url)
            attachItemObserver(playerItem)
            IosAudioTapRegistry.attacher?.attachTap(playerItem)
            val newPlayer = AVPlayer(playerItem = playerItem)
            if (crossfadeMs > 0L && (avPlayer?.rate ?: 0f) > 0f) {
                newPlayer.volume = 0f
            } else {
                newPlayer.volume = volumeToGain(currentVolume)
            }
            newPlayer.play()
            startIosCrossfade(newPlayer, crossfadeMs)
            onPlaybackStartedListener?.invoke()
        } catch (e: Throwable) {
            println("AudioPlayer iOS: Ошибка воспроизведения файла: ${e.message}")
        }
    }

    actual fun pause() {
        avPlayer?.pause()
        fadingAvPlayer?.pause()
    }

    actual fun resume() {
        avPlayer?.play()
        fadingAvPlayer?.play()
    }

    actual fun stop() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        try {
            fadingAvPlayer?.pause()
        } catch (_: Throwable) {}
        fadingAvPlayer = null

        detachItemObserver()
        detachTimeObserver()
        avPlayer?.pause()
        avPlayer = null
        currentResourceLoaderDelegate?.cancelAll()
        currentResourceLoaderDelegate = null
        io.github.audiz.dsp.AudioVisualizer.reset()
        tempFilePath?.let {
            platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(it, null)
            tempFilePath = null
        }
    }

    actual fun isPlaying(): Boolean {
        val isMainPlaying = (avPlayer?.timeControlStatus == AVPlayerTimeControlStatusPlaying) ||
                ((avPlayer?.rate ?: 0f) > 0f)
        val isFadingPlaying = (fadingAvPlayer?.rate ?: 0f) > 0f
        return isMainPlaying || isFadingPlaying
    }

    actual fun getDurationMs(): Long {
        val currentItem = avPlayer?.currentItem ?: return 0L
        val seconds = CMTimeGetSeconds(currentItem.duration)
        return if (seconds.isNaN() || seconds.isInfinite()) 0L else (seconds * 1000).toLong()
    }

    actual fun getCurrentPositionMs(): Long {
        val player = avPlayer ?: return 0L
        val seconds = CMTimeGetSeconds(player.currentTime())
        return if (seconds.isNaN() || seconds.isInfinite()) 0L else (seconds * 1000).toLong()
    }

    actual fun seekTo(positionMs: Long) {
        val seconds = positionMs / 1000.0
        val targetTime = CMTimeMakeWithSeconds(seconds, 1000)
        avPlayer?.seekToTime(targetTime)
    }

    private var currentVolume: Float = 1f

    actual fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (crossfadeJob?.isActive != true) {
            avPlayer?.volume = volumeToGain(currentVolume)
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

    actual fun release() {
        stop()
        playerScope.cancel()
        interruptionObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
        }
        interruptionObserver = null
        routeChangeObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
        }
        routeChangeObserver = null
    }
}
