package io.github.audiz.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.AudioPlayer
import io.github.audiz.SystemMediaControls
import io.github.audiz.TrackPlaySource
import io.github.audiz.acquirePlaybackWakeLock
import io.github.audiz.currentTimeMillis
import io.github.audiz.getFileSize
import io.github.audiz.loadAppConfig
import io.github.audiz.localFileExists
import io.github.audiz.releasePlaybackWakeLock
import io.github.audiz.saveAppConfig
import io.github.audiz.AppConfigKeys
import io.github.audiz.saveTrackFile
import io.github.audiz.trackFileExists
import io.github.audiz.stream.LocalStreamProxy
import io.github.audiz.api.MusicRepository
import io.github.audiz.models.DownloadedTrackAudio
import io.github.audiz.models.FullTrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * ⚡ Модель предзагруженного аудиотрека для мгновенного воспроизведения.
 */
data class PreloadedAudio(
    val trackId: String,
    val audioData: DownloadedTrackAudio?,
    val quality: String,
    val filePath: String? = null
)

/**
 * 🎵 Выделенный менеджер воспроизведения музыки и управления очередью треков.
 * Инкапсулирует:
 * - Стейт-машину аудиоплеера (play, pause, seek, volume, прогресс, битрейт).
 * - Аппаратную интеграцию (AudioPlayer, SystemMediaControls, PlatformWakeLock).
 * - Навигацию по истории и очереди треков (Next, Prev, Shuffle, loop).
 */
class PlaybackManager(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    val localTrackResolver: LocalTrackResolver,
    val queueSource: PlaybackQueueSource,
    val queueManager: PlaybackQueueManager = PlaybackQueueManager(),
    val waveBridge: WavePlaybackBridge? = null,
    private val getSelectedQuality: () -> String,
    private val isRecordToDisk: () -> Boolean,
    private val onTrackSavedToDisk: (() -> Unit)? = null,
    private val onTrackStarted: ((trackId: String, title: String, artist: String, coverUri: String?, isWave: Boolean) -> Unit)? = null,
    private val onError: (String) -> Unit = {}
) {
    /**
     * Вторичный конструктор для 100% обратной совместимости с тестами и существующим кодом.
     */
    constructor(
        scope: CoroutineScope,
        repository: MusicRepository,
        getMusicStoragePath: () -> String,
        getSelectedQuality: () -> String,
        isRecordToDisk: () -> Boolean,
        onTrackSavedToDisk: (() -> Unit)? = null,
        isTrackDownloaded: (artist: String, title: String) -> Boolean = { _, _ -> false },
        getLoadedTracks: () -> List<FullTrackInfo>,
        getSearchTracks: () -> List<FullTrackInfo> = { emptyList() },
        onLoadMoreTracks: (suspend () -> Boolean)? = null,
        isWaveMode: () -> Boolean = { false },
        onPlayWaveByIndex: ((Int, TrackPlaySource) -> Unit)? = null,
        onPlayNextWave: (() -> Unit)? = null,
        onPlayPrevWave: (() -> Unit)? = null,
        onWaveTrackFinished: ((crossfadeMs: Long) -> Unit)? = null,
        onInitialWave: ((autoPlay: Boolean, source: TrackPlaySource) -> Unit)? = null,
        getWaveTracks: () -> List<FullTrackInfo> = { emptyList() },
        getWaveCurrentIndex: () -> Int = { 0 },
        getNextWaveTrack: (() -> FullTrackInfo?)? = null,
        onError: (String) -> Unit = {}
    ) : this(
        scope = scope,
        repository = repository,
        localTrackResolver = LocalTrackResolver(getMusicStoragePath),
        queueSource = object : PlaybackQueueSource {
            override fun getLoadedTracks(): List<FullTrackInfo> = getLoadedTracks()
            override fun getSearchTracks(): List<FullTrackInfo> = getSearchTracks()
            override suspend fun loadMoreTracks(): Boolean = onLoadMoreTracks?.invoke() ?: false
        },
        queueManager = PlaybackQueueManager(),
        waveBridge = object : WavePlaybackBridge {
            override val isWaveMode: Boolean get() = isWaveMode()
            override val waveTracks: List<FullTrackInfo> get() = getWaveTracks()
            override val waveCurrentIndex: Int get() = getWaveCurrentIndex()
            override fun playWaveTrack(index: Int, source: TrackPlaySource, crossfadeMs: Long) {
                onPlayWaveByIndex?.invoke(index, source)
            }
            override fun playNextWaveTrack(crossfadeMs: Long) { onPlayNextWave?.invoke() }
            override fun playPrevWaveTrack() { onPlayPrevWave?.invoke() }
            override fun onWaveTrackFinished(crossfadeMs: Long) { onWaveTrackFinished?.invoke(crossfadeMs) }
            override fun loadInitialWave(autoPlay: Boolean, source: TrackPlaySource) { onInitialWave?.invoke(autoPlay, source) }
            override fun getNextWaveTrackCandidate(): FullTrackInfo? = getNextWaveTrack?.invoke()
        },
        getSelectedQuality = getSelectedQuality,
        isRecordToDisk = isRecordToDisk,
        onTrackSavedToDisk = onTrackSavedToDisk,
        onError = onError
    )

    companion object {
        const val PREV_RESTART_THRESHOLD_MS = 5000L // 5 секунд: если трек играет дольше, ⏮ начинает его сначала
        const val PREV_DOUBLE_CLICK_WINDOW_MS = 2000L // 2 секунды: окно для повторного нажатия ⏮ для перехода на предыдущий
    }

    val audioPlayer = AudioPlayer()
    val systemMediaControls = SystemMediaControls()

    var isPlaying by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var trackId by mutableStateOf<String?>(null)
        private set
    var albumId by mutableStateOf<Long?>(null)
        private set
    var trackTitle by mutableStateOf("")
        private set
    var artistName by mutableStateOf("")
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var bitrate by mutableStateOf<Int?>(null)
        private set
    var coverUri by mutableStateOf<String?>(null)
        private set
    var volume by mutableStateOf(loadAppConfig(AppConfigKeys.PLAYER_VOLUME)?.toFloatOrNull() ?: 1f)
        private set

    // 📻 Плавный переход между треками (Кроссфейд в секундах, 0 = Выкл / Умный стык)
    var crossfadeSeconds by mutableStateOf(loadAppConfig(AppConfigKeys.PLAYER_CROSSFADE_SECONDS)?.toIntOrNull() ?: 3)

    val isShuffleEnabled: Boolean get() = queueManager.isShuffleEnabled

    var isNextLoading by mutableStateOf(false)
        private set
    var isPrevLoading by mutableStateOf(false)
        private set
    var isPlayLoading by mutableStateOf(false)
        private set

    var currentPlayingFilePath: String? = null
        private set
    var currentPlayingSizeBytes: Long? = null
        private set
    var isCurrentTrackHQ: Boolean = false
        private set

    // ⚡ Предзагрузка следующего трека
    var plannedNextTrack: FullTrackInfo?
        get() = queueManager.plannedNextTrack
        set(value) { queueManager.plannedNextTrack = value }
    var preloadedAudio: PreloadedAudio? = null
        private set
    private var preloadJob: Job? = null

    private var progressJob: Job? = null
    private var playTrackJob: Job? = null
    @Volatile private var isSeeking: Boolean = false
    @Volatile private var hasTriggeredAutoAdvance: Boolean = false
    @Volatile private var lastSeekTimestamp: Long = 0L
    @Volatile private var lastPrevClickTimestamp: Long = 0L
    private var trackStartSystemTime: Long = 0L

    // 📜 История воспроизведения для точного возврата назад (⏮) и перехода вперед (⏭)
    val playbackHistory: MutableList<String> get() = queueManager.playbackHistory
    val forwardHistory: MutableList<String> get() = queueManager.forwardHistory

    var wasInterruptedBySystem: Boolean = false
        private set

    init {
        audioPlayer.setVolume(volume)

        // 🐧 Кросс-платформенная интеграция с системным медиаплеером / MPRIS / SMTC
        systemMediaControls.initialize(
            onPlay = { if (isPaused) togglePlayPause() },
            onPause = { if (isPlaying && !isPaused) togglePlayPause() },
            onTogglePlayPause = { togglePlayPause() },
            onNext = { playNextTrack() },
            onPrev = { playPrevTrack() },
            onSeek = { seekTo(it) }
        )

        // 🎵 Аппаратный коллбэк завершения трека
        audioPlayer.setOnCompletionListener {
            if (!hasTriggeredAutoAdvance) {
                handleTrackFinished(isNaturalCompletion = true)
            }
        }

        // 📻 Нативный аппаратный тайм-обсервер (надежно срабатывает в фоне / при выключенном экране на iOS)
        audioPlayer.setOnNearEndListener {
            val totalDur = if (durationMs > 0) durationMs else audioPlayer.getDurationMs()
            val crossfadeMs = crossfadeSeconds * 1000L
            val maxAllowedCrossfade = (totalDur * 0.35f).toLong()
            val effectiveCrossfadeMs = if (crossfadeMs > 0L) minOf(crossfadeMs, maxAllowedCrossfade) else 0L

            if (!hasTriggeredAutoAdvance && !isSeeking && isPlaying && !isPaused && totalDur > 6000L) {
                hasTriggeredAutoAdvance = true
                println("PlaybackManager: 📻 Аппаратный TimeObserver: автопереход к следующему треку (кроссфейд: ${effectiveCrossfadeMs}мс)")
                if (waveBridge?.isWaveMode == true) {
                    waveBridge.onWaveTrackFinished(effectiveCrossfadeMs)
                } else {
                    playNextTrack(source = TrackPlaySource.PLAY, crossfadeMs = effectiveCrossfadeMs)
                }
            }
        }

        audioPlayer.setOnPlaybackStartedListener {
            scope.launch {
                isNextLoading = false
                isPrevLoading = false
                isPlayLoading = false
                isPlaying = true
                isPaused = false
                releasePlaybackWakeLock()
                systemMediaControls.updatePlaybackState(true, false, positionMs)
                startProgressPolling()
            }
        }

        audioPlayer.setOnErrorListener { errorMsg ->
            scope.launch {
                handlePlaybackError(errorMsg)
            }
        }

        // 🎧 Обработка системных прерываний (звонки, будильники) и отключений (Bluetooth/наушники)
        audioPlayer.setOnInterruptionListener { shouldPause, canResume ->
            scope.launch {
                if (shouldPause) {
                    if (isPlaying && !isPaused) {
                        positionMs = audioPlayer.getCurrentPositionMs()
                        audioPlayer.pause()
                        isPaused = true
                        wasInterruptedBySystem = canResume
                        systemMediaControls.updatePlaybackState(isPlaying = false, isPaused = true, positionMs)
                    }
                } else {
                    // Прерывание завершилось (звонок окончен)
                    if (wasInterruptedBySystem && isPaused && canResume) {
                        wasInterruptedBySystem = false
                        audioPlayer.resume()
                        isPaused = false
                        systemMediaControls.updatePlaybackState(isPlaying = true, isPaused = false, positionMs)
                        startProgressPolling()
                    }
                }
            }
        }

        // 🌐 Запуск локального HTTP стриминг-прокси (Zero-Latency Audio Streaming)
        try {
            LocalStreamProxy.start(
                repository = repository,
                getMusicStoragePath = localTrackResolver.getMusicStoragePath,
                isRecordToDisk = isRecordToDisk,
                onTrackSaved = { savedTrackId, filePath ->
                    if (trackId == savedTrackId) {
                        currentPlayingFilePath = filePath
                        currentPlayingSizeBytes = getFileSize(filePath).takeIf { it > 0 }
                    }
                    onTrackSavedToDisk?.invoke()
                }
            )
        } catch (e: Throwable) {
            println("PlaybackManager: Ошибка запуска LocalStreamProxy: ${e.message}")
        }
    }

    fun changeVolume(newVolume: Float) {
        volume = newVolume
        audioPlayer.setVolume(newVolume)
        saveAppConfig(AppConfigKeys.PLAYER_VOLUME, newVolume.toString())
    }

    fun changeCrossfade(seconds: Int) {
        val clamped = seconds.coerceIn(0, 12)
        crossfadeSeconds = clamped
        saveAppConfig(AppConfigKeys.PLAYER_CROSSFADE_SECONDS, clamped.toString())
    }

    fun toggleShuffle() {
        queueManager.toggleShuffle()
        preloadJob?.cancel()
        preloadedAudio = null
        queueManager.plannedNextTrack = null
        queueManager.plannedNextTrack = calculateNextTrackCandidate()
        schedulePreload()
    }

    fun setLoadingFlags(next: Boolean, prev: Boolean, play: Boolean) {
        isNextLoading = next
        isPrevLoading = prev
        isPlayLoading = play
    }

    fun resetLoadingFlags() {
        isNextLoading = false
        isPrevLoading = false
        isPlayLoading = false
    }

    fun updateCurrentPlayingFilePath(path: String?) {
        currentPlayingFilePath = path
    }

    fun stopPlayback() {
        wasInterruptedBySystem = false
        hasTriggeredAutoAdvance = false
        lastPrevClickTimestamp = 0L
        playTrackJob?.cancel()
        playTrackJob = null
        isNextLoading = false
        isPrevLoading = false
        isPlayLoading = false
        progressJob?.cancel()
        audioPlayer.stop()
        releasePlaybackWakeLock()
        isPlaying = false
        isPaused = false
        trackId = null
        albumId = null
        positionMs = 0L
        durationMs = 0L
        trackTitle = ""
        artistName = ""
        bitrate = null
        currentPlayingFilePath = null
        currentPlayingSizeBytes = null
        isCurrentTrackHQ = false
        preloadJob?.cancel()
        preloadJob = null
        preloadedAudio = null
        queueManager.clearAll()
        systemMediaControls.clear()
    }

    fun seekTo(position: Long) {
        lastSeekTimestamp = currentTimeMillis()
        isSeeking = true
        hasTriggeredAutoAdvance = false
        io.github.audiz.dsp.AudioVisualizer.resetTrackAudibility()
        try {
            audioPlayer.seekTo(position)
            positionMs = position
            systemMediaControls.updatePlaybackState(isPlaying, isPaused, position)
        } finally {
            scope.launch {
                delay(700)
                if (currentTimeMillis() - lastSeekTimestamp >= 600) {
                    isSeeking = false
                }
            }
        }
    }

    fun togglePlayPause() {
        wasInterruptedBySystem = false
        if (isPlaying && !isPaused) {
            positionMs = audioPlayer.getCurrentPositionMs()
            audioPlayer.pause()
            isPaused = true
            systemMediaControls.updatePlaybackState(false, true, positionMs)
        } else if (isPaused) {
            audioPlayer.resume()
            isPaused = false
            systemMediaControls.updatePlaybackState(true, false, positionMs)
            startProgressPolling()
        } else if (trackId == null) {
            isPlayLoading = true
            val waveTracks = waveBridge?.waveTracks ?: emptyList()
            if (waveTracks.isNotEmpty()) {
                waveBridge?.playWaveTrack(waveBridge.waveCurrentIndex, TrackPlaySource.PLAY)
            } else {
                waveBridge?.loadInitialWave(true, TrackPlaySource.PLAY)
            }
        } else {
            isPlayLoading = true
            playTrack(
                targetTrackId = trackId!!,
                targetTrackTitle = trackTitle,
                targetArtistName = artistName,
                targetAlbumId = albumId,
                isManualSelection = false,
                source = TrackPlaySource.PLAY
            )
        }
    }

    /**
     * 🎵 Установить начальный трек (для восстановления сессии при рестарте).
     * Выставляет метаданные трека в плеере в состоянии готовности к запуску.
     */
    fun setInitialTrack(track: FullTrackInfo) {
        trackId = track.id
        trackTitle = track.title
        artistName = track.artists.joinToString { it.name }.ifBlank { "Unknown Artist" }
        albumId = track.albums.firstOrNull()?.id
        durationMs = track.durationMs
        positionMs = 0L
        coverUri = track.coverUri
        isPlaying = false
        isPaused = false
    }

    fun sanitizeKeepSpaces(input: String): String = LocalTrackResolver.sanitizeKeepSpaces(input)

    fun findTrackInfo(targetTrackId: String): FullTrackInfo? {
        val cleanTarget = targetTrackId.removePrefix("local:")
        fun matches(it: FullTrackInfo): Boolean {
            return it.id == targetTrackId || it.realId == targetTrackId ||
                   it.id.removePrefix("local:") == cleanTarget ||
                   it.realId?.removePrefix("local:") == cleanTarget
        }
        return queueSource.getLoadedTracks().firstOrNull(::matches)
            ?: waveBridge?.waveTracks?.firstOrNull(::matches)
            ?: queueSource.getSearchTracks().firstOrNull(::matches)
    }

    /**
     * Воспроизвести трек: если файл есть на диске — играть с диска; иначе скачать по сети.
     */
    fun playTrack(
        targetTrackId: String,
        targetTrackTitle: String,
        targetArtistName: String,
        targetAlbumId: Long? = null,
        targetCoverUri: String? = null,
        isManualSelection: Boolean = true,
        isWave: Boolean = false,
        source: TrackPlaySource = TrackPlaySource.PLAY,
        crossfadeMs: Long = 0L
    ) {
        if (crossfadeMs <= 0L) {
            hasTriggeredAutoAdvance = false
        }
        isFallbackDownloading = false
        wasInterruptedBySystem = false
        progressJob?.cancel()
        val effectiveCrossfade = if (crossfadeMs > 0L) {
            crossfadeMs
        } else {
            0L
        }
        lastPrevClickTimestamp = 0L
        coverUri = targetCoverUri ?: findTrackInfo(targetTrackId)?.coverUri
        if (isManualSelection) {
            val currentId = trackId
            if (currentId != null && currentId != targetTrackId) {
                queueManager.recordPlayed(currentId)
                queueManager.clearForwardHistory()
            }
        }

        playTrackJob?.cancel()
        preloadJob?.cancel()
        io.github.audiz.dsp.AudioVisualizer.resetTrackAudibility()
        if (preloadedAudio?.trackId != targetTrackId) {
            preloadedAudio = null
        }
        when (source) {
            TrackPlaySource.NEXT -> {
                isNextLoading = true
                isPrevLoading = false
                isPlayLoading = false
            }
            TrackPlaySource.PREV -> {
                isPrevLoading = true
                isNextLoading = false
                isPlayLoading = false
            }
            TrackPlaySource.PLAY -> {
                isPlayLoading = true
                isNextLoading = false
                isPrevLoading = false
            }
        }

        var thisJob: Job? = null
        thisJob = scope.launch {
            val cleanArtist = targetArtistName.trim()
            val cleanTitle = targetTrackTitle.trim()
            val musicStoragePath = localTrackResolver.getMusicStoragePath()
            val selectedQuality = getSelectedQuality()
            var isStreamingStarted = false
            try {
                val resolvedAlbumId = targetAlbumId ?: findTrackInfo(targetTrackId)?.albums?.firstOrNull()?.id
                val sanitizedArtist = sanitizeKeepSpaces(cleanArtist.ifBlank { "Unknown Artist" })

                // 🔍 1. Проверяем, есть ли файл на диске
                var existingFilePath: String? = null
                val preloaded = preloadedAudio

                if (preloaded?.trackId == targetTrackId && preloaded.quality == selectedQuality && preloaded.filePath != null && localFileExists(preloaded.filePath)) {
                    existingFilePath = preloaded.filePath
                }

                if (existingFilePath == null) {
                    existingFilePath = localTrackResolver.findLocalTrackFile(
                        trackId = targetTrackId,
                        artist = cleanArtist,
                        title = cleanTitle,
                        selectedQuality = selectedQuality
                    )
                }

                // Если файл найден — играем с диска без скачивания
                if (existingFilePath != null) {
                    println("▶️ Играем с диска: $existingFilePath")
                    isNextLoading = false
                    isPrevLoading = false
                    isPlayLoading = false
                    trackId = targetTrackId
                    albumId = resolvedAlbumId
                    trackTitle = cleanTitle
                    artistName = cleanArtist
                    currentPlayingFilePath = existingFilePath
                    currentPlayingSizeBytes = getFileSize(existingFilePath).takeIf { it > 0 }
                    isCurrentTrackHQ = (selectedQuality == "2") && existingFilePath.contains("HQ", ignoreCase = true)
                    val knownDuration = findTrackInfo(targetTrackId)?.durationMs?.takeIf { it > 0 } ?: 0L
                    positionMs = 0L
                    durationMs = knownDuration
                    val calculatedBitrate = if (durationMs > 0 && currentPlayingSizeBytes != null && currentPlayingSizeBytes!! > 0) {
                        ((currentPlayingSizeBytes!! * 8) / durationMs).toInt().takeIf { it > 0 }
                    } else null
                    val defaultBitrate = if (isCurrentTrackHQ) 320 else 192
                    audioPlayer.playFromFile(existingFilePath, effectiveCrossfade)
                    if (durationMs <= 0L) {
                        val playerDur = audioPlayer.getDurationMs()
                        if (playerDur > 0L) {
                            durationMs = playerDur
                        } else {
                            try {
                                val localInfo = io.github.audiz.getTracksFromLocalPaths(listOf(existingFilePath)).firstOrNull()
                                if (localInfo != null && localInfo.durationMs > 0L) {
                                    durationMs = localInfo.durationMs
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    isPlaying = true
                    isPaused = false
                    releasePlaybackWakeLock()
                    systemMediaControls.updateMetadata(cleanTitle, cleanArtist, "", durationMs, targetTrackId, coverUri)
                    systemMediaControls.updatePlaybackState(true, false, 0L)
                    onTrackStarted?.invoke(targetTrackId, cleanTitle, cleanArtist, coverUri, isWave)
                    preloadedAudio = null
                    startProgressPolling()
                    queueManager.plannedNextTrack = calculateNextTrackCandidate()
                    schedulePreload()
                    return@launch
                }

                // 2. Если трек локальный, но файла нет — не запрашиваем сеть
                if (targetTrackId.startsWith("local:")) {
                    onError("Локальный файл не найден на диске: ${targetTrackId.removePrefix("local:")}")
                    releasePlaybackWakeLock()
                    isNextLoading = false
                    isPrevLoading = false
                    isPlayLoading = false
                    return@launch
                }

                // 3. 📥 Файла нет на диске и трек сетевой
                val isPreloadedInMemory = preloaded?.trackId == targetTrackId && preloaded.quality == selectedQuality && preloaded.audioData != null

                // 🌐 Если нет готового аудио в памяти и работает локальный стриминг-прокси — запускаем воспроизведение мгновенно!
                if (!isPreloadedInMemory && LocalStreamProxy.isRunning()) {
                    trackId = targetTrackId
                    albumId = resolvedAlbumId
                    trackTitle = cleanTitle
                    artistName = cleanArtist
                    val knownDuration = findTrackInfo(targetTrackId)?.durationMs?.takeIf { it > 0 } ?: 0L
                    positionMs = 0L
                    durationMs = knownDuration
                    val defaultBitrate = if (selectedQuality == "2") 320 else 192
                    bitrate = defaultBitrate
                    isCurrentTrackHQ = (selectedQuality == "2")
                    currentPlayingFilePath = null
                    currentPlayingSizeBytes = null

                    val streamUrl = LocalStreamProxy.getStreamUrl(
                        trackId = targetTrackId,
                        quality = selectedQuality,
                        title = cleanTitle,
                        artist = cleanArtist
                    )
                    println("⚡ [Стриминг] Мгновенный запуск потока через LocalStreamProxy: $streamUrl")
                    isStreamingStarted = true
                    audioPlayer.playFromUrl(streamUrl, effectiveCrossfade)

                    isPlaying = true
                    isPaused = false
                    isNextLoading = false
                    isPrevLoading = false
                    isPlayLoading = false
                    releasePlaybackWakeLock()
                    systemMediaControls.updateMetadata(cleanTitle, cleanArtist, "", durationMs, targetTrackId, coverUri)
                    systemMediaControls.updatePlaybackState(true, false, 0L)
                    onTrackStarted?.invoke(targetTrackId, cleanTitle, cleanArtist, coverUri, isWave)
                    startProgressPolling()
                    preloadedAudio = null
                    queueManager.plannedNextTrack = calculateNextTrackCandidate()
                    schedulePreload()
                    return@launch
                }

                val qualityFolder = if (selectedQuality == "2") "HQ" else "LQ"
                val basePath = "$musicStoragePath/$qualityFolder"

                val audioData = if (isPreloadedInMemory) {
                    println("⚡ [Предзагрузка] Используем готовое аудио из памяти для: $cleanTitle")
                    preloaded.audioData
                } else {
                    repository.downloadTrackAudio(targetTrackId, quality = selectedQuality)
                }
                preloadedAudio = null

                val rawExtension = audioData.type.substringBefore("-")
                val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension
                val fullFileName = sanitizeKeepSpaces(if (cleanArtist.isNotEmpty()) {
                    "$cleanArtist — $cleanTitle.$extension"
                } else {
                    "$cleanTitle.$extension"
                })

                trackId = targetTrackId
                albumId = resolvedAlbumId
                trackTitle = cleanTitle
                artistName = cleanArtist
                val knownDuration = findTrackInfo(targetTrackId)?.durationMs?.takeIf { it > 0 } ?: 0L
                val bitrateFromType = audioData.type.substringAfter("-").substringBefore("-").toIntOrNull()
                val parsedBitrate = audioData.bitrate ?: (if (bitrateFromType != null && bitrateFromType in 32..1000) bitrateFromType else null)
                positionMs = 0L
                durationMs = knownDuration
                currentPlayingSizeBytes = audioData.result.size.toLong()
                val calculatedBitrate = parsedBitrate ?: if (durationMs > 0 && currentPlayingSizeBytes != null) {
                    ((currentPlayingSizeBytes!! * 8) / durationMs).toInt().takeIf { it > 0 }
                } else null
                val defaultBitrate = if (selectedQuality == "2") 320 else 192
                bitrate = calculatedBitrate ?: defaultBitrate
                isCurrentTrackHQ = (selectedQuality == "2")

                if (isRecordToDisk() && musicStoragePath.isNotBlank()) {
                    saveTrackFile(basePath, cleanArtist, fullFileName, audioData.result)
                    localTrackResolver.invalidateCache()
                    onTrackSavedToDisk?.invoke()
                    val resolvedFile = localTrackResolver.findLocalTrackFile(
                        trackId = targetTrackId,
                        artist = cleanArtist,
                        title = cleanTitle,
                        selectedQuality = selectedQuality
                    ) ?: "$basePath/$sanitizedArtist/$fullFileName"
                    currentPlayingFilePath = resolvedFile
                    audioPlayer.playFromFile(resolvedFile, effectiveCrossfade)
                } else {
                    println("💾 Record to disk выключен: воспроизводим трек без сохранения в библиотеку")
                    currentPlayingFilePath = null
                    audioPlayer.playFromBytes(audioData.result, effectiveCrossfade)
                }

                isPlaying = true
                isPaused = false
                isNextLoading = false
                isPrevLoading = false
                isPlayLoading = false
                releasePlaybackWakeLock()
                systemMediaControls.updateMetadata(cleanTitle, cleanArtist, "", durationMs, targetTrackId, coverUri)
                systemMediaControls.updatePlaybackState(true, false, 0L)
                onTrackStarted?.invoke(targetTrackId, cleanTitle, cleanArtist, coverUri, isWave)
                startProgressPolling()
                queueManager.plannedNextTrack = calculateNextTrackCandidate()
                schedulePreload()

            } catch (e: Exception) {
                releasePlaybackWakeLock()
                isNextLoading = false
                isPrevLoading = false
                isPlayLoading = false
                val errorMsg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Неизвестная ошибка"
                io.github.audiz.util.AppLogger.e("PlaybackManager", "Ошибка воспроизведения '$cleanTitle': $errorMsg", e)
                onError("Ошибка воспроизведения: $errorMsg")
                e.printStackTrace()
            } finally {
                if (!isStreamingStarted) {
                    val currentJob = coroutineContext[Job]
                    if (currentJob == null || !currentJob.isCancelled || playTrackJob === currentJob || playTrackJob === thisJob) {
                        isNextLoading = false
                        isPrevLoading = false
                        isPlayLoading = false
                    }
                }
            }
        }
        playTrackJob = thisJob
    }

    /**
     * Fallback: принудительно скачать и воспроизвести в LQ
     */
    fun playTrackForceLQ(
        targetTrackId: String,
        targetTrackTitle: String,
        targetArtistName: String,
        source: TrackPlaySource = TrackPlaySource.PLAY
    ) {
        wasInterruptedBySystem = false
        playTrackJob?.cancel()
        when (source) {
            TrackPlaySource.NEXT -> {
                isNextLoading = true
                isPrevLoading = false
                isPlayLoading = false
            }
            TrackPlaySource.PREV -> {
                isPrevLoading = true
                isNextLoading = false
                isPlayLoading = false
            }
            TrackPlaySource.PLAY -> {
                isPlayLoading = true
                isNextLoading = false
                isPrevLoading = false
            }
        }
        var thisJob: Job? = null
        thisJob = scope.launch {
            val cleanArtist = targetArtistName.trim()
            val cleanTitle = targetTrackTitle.trim()
            val musicStoragePath = localTrackResolver.getMusicStoragePath()
            try {
                val sanitizedArtist = sanitizeKeepSpaces(cleanArtist.ifBlank { "Unknown Artist" })
                val qualityFolder = "LQ"
                val basePath = "$musicStoragePath/$qualityFolder"

                val existingFilePath = localTrackResolver.findLocalTrackFile(
                    trackId = targetTrackId,
                    artist = cleanArtist,
                    title = cleanTitle,
                    selectedQuality = "1"
                )
                val knownDuration = findTrackInfo(targetTrackId)?.durationMs?.takeIf { it > 0 } ?: 0L

                if (existingFilePath != null) {
                    println("▶️ Играем LQ с диска: $existingFilePath")
                    isNextLoading = false
                    isPrevLoading = false
                    isPlayLoading = false
                    trackId = targetTrackId
                    trackTitle = cleanTitle
                    artistName = cleanArtist
                    currentPlayingFilePath = existingFilePath
                    positionMs = 0L
                    durationMs = knownDuration
                    currentPlayingSizeBytes = getFileSize(existingFilePath).takeIf { it > 0 }
                    val calculatedBitrate = if (durationMs > 0 && currentPlayingSizeBytes != null) {
                        ((currentPlayingSizeBytes!! * 8) / durationMs).toInt().takeIf { it > 0 }
                    } else null
                    bitrate = calculatedBitrate ?: 192
                    audioPlayer.playFromFile(existingFilePath)
                    isPlaying = true
                    isPaused = false
                    releasePlaybackWakeLock()
                    startProgressPolling()
                    queueManager.plannedNextTrack = calculateNextTrackCandidate()
                    schedulePreload()
                    return@launch
                }

                if (LocalStreamProxy.isRunning()) {
                    trackId = targetTrackId
                    trackTitle = cleanTitle
                    artistName = cleanArtist
                    positionMs = 0L
                    durationMs = knownDuration
                    bitrate = 192
                    isCurrentTrackHQ = false
                    currentPlayingFilePath = null
                    currentPlayingSizeBytes = null

                    val streamUrl = LocalStreamProxy.getStreamUrl(
                        trackId = targetTrackId,
                        quality = "1",
                        title = cleanTitle,
                        artist = cleanArtist
                    )
                    println("⚡ [Стриминг LQ] Запуск потока через LocalStreamProxy: $streamUrl")
                    audioPlayer.playFromUrl(streamUrl)

                    val resolvedCoverUri = findTrackInfo(targetTrackId)?.coverUri
                    coverUri = resolvedCoverUri
                    val isWave = waveBridge?.isWaveMode == true

                    isPlaying = true
                    isPaused = false
                    isNextLoading = false
                    isPrevLoading = false
                    isPlayLoading = false
                    releasePlaybackWakeLock()
                    systemMediaControls.updateMetadata(cleanTitle, cleanArtist, "", durationMs, targetTrackId, resolvedCoverUri)
                    systemMediaControls.updatePlaybackState(true, false, 0L)
                    onTrackStarted?.invoke(targetTrackId, cleanTitle, cleanArtist, resolvedCoverUri, isWave)
                    startProgressPolling()
                    preloadedAudio = null
                    queueManager.plannedNextTrack = calculateNextTrackCandidate()
                    schedulePreload()
                    return@launch
                }

                println("📥 Скачиваем LQ трек...")
                val audioData = repository.downloadTrackAudio(targetTrackId, quality = "1")
                val rawExtension = audioData.type.substringBefore("-")
                val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension
                val fullFileName = sanitizeKeepSpaces(if (cleanArtist.isNotEmpty()) {
                    "$cleanArtist — $cleanTitle.$extension"
                } else {
                    "$cleanTitle.$extension"
                })

                trackId = targetTrackId
                trackTitle = cleanTitle
                artistName = cleanArtist
                val bitrateFromType = audioData.type.substringAfter("-").substringBefore("-").toIntOrNull()
                val parsedBitrate = audioData.bitrate ?: (if (bitrateFromType != null && bitrateFromType in 32..1000) bitrateFromType else null)
                positionMs = 0L
                durationMs = knownDuration
                currentPlayingSizeBytes = audioData.result.size.toLong()
                val calculatedBitrate = parsedBitrate ?: if (durationMs > 0 && currentPlayingSizeBytes != null) {
                    ((currentPlayingSizeBytes!! * 8) / durationMs).toInt().takeIf { it > 0 }
                } else null
                bitrate = calculatedBitrate ?: 192
                isCurrentTrackHQ = false

                if (isRecordToDisk() && musicStoragePath.isNotBlank()) {
                    saveTrackFile(basePath, cleanArtist, fullFileName, audioData.result)
                    onTrackSavedToDisk?.invoke()
                    val resolvedFile = localTrackResolver.findLocalTrackFile(
                        trackId = targetTrackId,
                        artist = cleanArtist,
                        title = cleanTitle,
                        selectedQuality = "1"
                    ) ?: "$basePath/$sanitizedArtist/$fullFileName"
                    currentPlayingFilePath = resolvedFile
                    audioPlayer.playFromFile(resolvedFile)
                } else {
                    currentPlayingFilePath = null
                    audioPlayer.playFromBytes(audioData.result)
                }

                isPlaying = true
                isPaused = false
                isNextLoading = false
                isPrevLoading = false
                isPlayLoading = false
                releasePlaybackWakeLock()
                startProgressPolling()
                queueManager.plannedNextTrack = calculateNextTrackCandidate()
                schedulePreload()

            } catch (e: Exception) {
                releasePlaybackWakeLock()
                isNextLoading = false
                isPrevLoading = false
                isPlayLoading = false
                onError("Ошибка LQ fallback: ${e.message}")
                e.printStackTrace()
            } finally {
                val currentJob = coroutineContext[Job]
                if (currentJob == null || !currentJob.isCancelled || playTrackJob === currentJob || playTrackJob === thisJob) {
                    isNextLoading = false
                    isPrevLoading = false
                    isPlayLoading = false
                }
            }
        }
        playTrackJob = thisJob
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        trackStartSystemTime = currentTimeMillis()
        progressJob = scope.launch {
            var hasStartedPlaying = false
            var checksWithoutPlaying = 0

            while (true) {
                delay(250)
                val isAudioPlaying = audioPlayer.isPlaying()

                if (isAudioPlaying) {
                    hasStartedPlaying = true
                    checksWithoutPlaying = 0
                    positionMs = audioPlayer.getCurrentPositionMs()
                    val audioDur = audioPlayer.getDurationMs()
                    if (audioDur > 0 && durationMs != audioDur) {
                        durationMs = audioDur
                        systemMediaControls.updateMetadata(
                            title = trackTitle,
                            artist = artistName,
                            album = "",
                            durationMs = durationMs,
                            trackId = trackId,
                            coverUri = coverUri
                        )
                    }

                    systemMediaControls.updatePlaybackState(true, false, positionMs)

                    val totalDur = if (durationMs > 0) durationMs else audioDur
                    val playedSinceStartMs = currentTimeMillis() - trackStartSystemTime

                    // Сбрасываем флаг автоперехода только когда новый трек уверенно заиграл (> 2.5 сек)
                    if (hasTriggeredAutoAdvance && playedSinceStartMs > 1500L && (positionMs > 2500L || (totalDur in 1..5000L && positionMs > totalDur / 2))) {
                        hasTriggeredAutoAdvance = false
                    }

                    if ((bitrate == null || bitrate == 0) && durationMs > 0) {
                        val sizeBytes = currentPlayingSizeBytes
                            ?: currentPlayingFilePath?.let { getFileSize(it) }?.takeIf { it > 0 }
                        if (sizeBytes != null && sizeBytes > 0) {
                            val calculated = ((sizeBytes * 8) / durationMs).toInt()
                            if (calculated > 0) {
                                bitrate = calculated
                            }
                        }
                        if (bitrate == null || bitrate == 0) {
                            bitrate = if (isCurrentTrackHQ) 320 else 192
                        }
                    }

                    // 📻 Радио-стык / Кроссфейд: автопереход к следующему треку заранее
                    val crossfadeMs = crossfadeSeconds * 1000L
                    val maxAllowedCrossfade = (totalDur * 0.35f).toLong()
                    val effectiveCrossfadeMs = if (crossfadeMs > 0L) minOf(crossfadeMs, maxAllowedCrossfade) else 0L
                    val isNextLocal = preloadedAudio?.filePath != null ||
                            queueManager.plannedNextTrack?.id?.startsWith("local:") == true ||
                            (currentPlayingFilePath != null && queueManager.plannedNextTrack?.let {
                                localTrackResolver.findLocalTrackFile(it.id, it.artists.firstOrNull()?.name ?: "", it.title, getSelectedQuality())
                            } != null)

                    val leadHeadroomMs = if (crossfadeMs > 0L) {
                        if (isNextLocal) 500L else 6500L
                    } else 800L
                    val triggerThresholdMs = if (crossfadeMs > 0L) {
                        (effectiveCrossfadeMs + leadHeadroomMs).coerceAtMost(maxAllowedCrossfade + leadHeadroomMs).coerceAtMost((totalDur * 0.45f).toLong())
                    } else {
                        leadHeadroomMs
                    }
                    audioPlayer.updateNearEndThreshold(triggerThresholdMs, totalDur)

                    // Обнаружение тишины в хвосте трека (активно как при радио-кроссфейде, так и без него)
                    val isSilenceAtTail = io.github.audiz.dsp.AudioVisualizer.isSilenceDetectedInTail(positionMs, totalDur)
                    val isNearTrackEnd = if (crossfadeMs > 0L) {
                        positionMs >= (totalDur - triggerThresholdMs)
                    } else {
                        false
                    }

                    if (!hasTriggeredAutoAdvance && !isSeeking && totalDur > 6000L && playedSinceStartMs > 3000L && (isNearTrackEnd || isSilenceAtTail)) {
                        hasTriggeredAutoAdvance = true
                        println("PlaybackManager: 📻 Автопереключение на следующий трек (кроссфейд: ${effectiveCrossfadeMs}мс, позиция: $positionMs / $totalDur мс, тишина: $isSilenceAtTail)")
                        if (waveBridge?.isWaveMode == true) {
                            waveBridge.onWaveTrackFinished(effectiveCrossfadeMs)
                        } else {
                            playNextTrack(source = TrackPlaySource.PLAY, crossfadeMs = effectiveCrossfadeMs)
                        }
                    }
                } else if (!isPaused && isPlaying) {
                    if (isSeeking) {
                        continue
                    }
                    if (!hasStartedPlaying) {
                        checksWithoutPlaying++
                        if (checksWithoutPlaying < 48) { // 12 секунд на сетевой запуск и буферизацию
                            continue
                        }
                        println("PlaybackManager: Трек не запустился за 12 секунд -> ошибка запуска")
                        handleTrackFinished(isNaturalCompletion = false)
                        break
                    }

                    // Трек уже играл, но временно не играет (буферизация, системная пауза или естественный конец)
                    val currentPos = audioPlayer.getCurrentPositionMs()
                    val totalDur = if (durationMs > 0) durationMs else audioPlayer.getDurationMs()
                    val nearEndThreshold = if (crossfadeSeconds > 0) ((crossfadeSeconds * 1000L) + 3000L).coerceAtMost((totalDur * 0.45f).toLong()) else 3500L
                    val isNearEnd = totalDur > 0 && (currentPos >= (totalDur - nearEndThreshold) || (totalDur > 10000L && currentPos >= totalDur * 0.95f))

                    val timeSinceStart = currentTimeMillis() - trackStartSystemTime
                    if (isNearEnd && timeSinceStart > 3000L) {
                        println("PlaybackManager: Трек завершился около конца ($currentPos / $totalDur мс)")
                        if (!hasTriggeredAutoAdvance) {
                            handleTrackFinished(isNaturalCompletion = true)
                        }
                        break
                    }

                    checksWithoutPlaying++
                    if (checksWithoutPlaying < 12) { // даем 3 секунды на буферизацию
                        continue
                    }

                    // Если через 3 секунды плеер все еще не играет и пауза не была нажата пользователем:
                    // Синхронизируем стейт с реальным состоянием аудиоустройства (звонок, отключение BT)
                    if (!audioPlayer.isPlaying()) {
                        println("PlaybackManager: Воспроизведение остановлено в середине трека -> синхронизируем паузу")
                        positionMs = currentPos
                        isPaused = true
                        systemMediaControls.updatePlaybackState(isPlaying = false, isPaused = true, positionMs)
                        break
                    }
                }
            }
        }
    }

    private var isFallbackDownloading = false

    private suspend fun handlePlaybackError(errorMsg: String) {
        println("PlaybackManager: ⚠️ Ошибка воспроизведения: $errorMsg (trackId=$trackId, currentPlayingFilePath=$currentPlayingFilePath)")
        val currentId = trackId
        val currentTitle = trackTitle
        val currentArtist = artistName

        if (currentId != null && !currentId.startsWith("local:") && currentPlayingFilePath == null && !isFallbackDownloading) {
            println("PlaybackManager: 🔄 Потоковое воспроизведение не удалось. Переключаемся на прямое скачивание для трека $currentTitle ($currentId)...")
            isFallbackDownloading = true
            try {
                val selectedQuality = getSelectedQuality()
                val audioData = repository.downloadTrackAudio(currentId, quality = selectedQuality)

                if (trackId != currentId) {
                    println("PlaybackManager: Трек сменился во время фоллбэк-скачивания ($currentId -> $trackId), отменяем воспроизведение")
                    return
                }

                val cleanTitle = currentTitle.trim()
                val cleanArtist = currentArtist.trim()
                val rawExtension = audioData.type.substringBefore("-")
                val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension
                val fullFileName = sanitizeKeepSpaces(if (cleanArtist.isNotEmpty()) {
                    "$cleanArtist — $cleanTitle.$extension"
                } else {
                    "$cleanTitle.$extension"
                })
                val qualityFolder = if (selectedQuality == "2") "HQ" else "LQ"
                val basePath = "${localTrackResolver.getMusicStoragePath()}/$qualityFolder"
                val sanitizedArtist = sanitizeKeepSpaces(cleanArtist.ifBlank { "Unknown Artist" })

                val knownDuration = findTrackInfo(currentId)?.durationMs?.takeIf { it > 0 } ?: 0L
                val bitrateFromType = audioData.type.substringAfter("-").substringBefore("-").toIntOrNull()
                val parsedBitrate = audioData.bitrate ?: (if (bitrateFromType != null && bitrateFromType in 32..1000) bitrateFromType else null)
                positionMs = 0L
                durationMs = knownDuration
                currentPlayingSizeBytes = audioData.result.size.toLong()
                val calculatedBitrate = parsedBitrate ?: if (durationMs > 0 && currentPlayingSizeBytes != null) {
                    ((currentPlayingSizeBytes!! * 8) / durationMs).toInt().takeIf { it > 0 }
                } else null
                val defaultBitrate = if (selectedQuality == "2") 320 else 192
                bitrate = calculatedBitrate ?: defaultBitrate
                isCurrentTrackHQ = (selectedQuality == "2")

                if (isRecordToDisk()) {
                    saveTrackFile(basePath, cleanArtist, fullFileName, audioData.result)
                    onTrackSavedToDisk?.invoke()
                    val filePath = "$basePath/$sanitizedArtist/$fullFileName"
                    currentPlayingFilePath = filePath
                    audioPlayer.playFromFile(filePath, 0L)
                } else {
                    currentPlayingFilePath = null
                    audioPlayer.playFromBytes(audioData.result, 0L)
                }

                isPlaying = true
                isPaused = false
                isNextLoading = false
                isPrevLoading = false
                isPlayLoading = false
                releasePlaybackWakeLock()
                systemMediaControls.updateMetadata(cleanTitle, cleanArtist, "", durationMs, currentId, coverUri)
                systemMediaControls.updatePlaybackState(true, false, 0L)
                startProgressPolling()
                queueManager.plannedNextTrack = calculateNextTrackCandidate()
                schedulePreload()
                return
            } catch (e: Exception) {
                println("PlaybackManager: ❌ Прямое скачивание после ошибки стриминга завершилось ошибкой: ${e.message}")
            } finally {
                isFallbackDownloading = false
            }
        }

        isNextLoading = false
        isPrevLoading = false
        isPlayLoading = false
        isPlaying = false
        isPaused = false
        releasePlaybackWakeLock()
        val fullError = "Ошибка воспроизведения: $errorMsg"
        io.github.audiz.util.AppLogger.e("PlaybackManager", fullError)
        onError(fullError)
    }

    fun handleTrackFinished(isNaturalCompletion: Boolean = false) {
        scope.launch {
            if (!isPlaying && !isNaturalCompletion) return@launch
            if (isSeeking) return@launch

            val wasAutoTriggered = hasTriggeredAutoAdvance
            hasTriggeredAutoAdvance = true
            acquirePlaybackWakeLock()

            progressJob?.cancel()
            val prevId = trackId
            val prevTitle = trackTitle
            val prevArtist = artistName
            isPlaying = false
            trackId = null
            positionMs = durationMs

            val playedTimeMs = currentTimeMillis() - trackStartSystemTime
            if (prevId != null) {
                if (isNaturalCompletion) {
                    if (LocalStreamProxy.isRunning()) {
                        LocalStreamProxy.onTrackCompleted(prevId, if (isCurrentTrackHQ) "2" else "1")
                    }
                    val naturalCrossfadeMs = if (wasAutoTriggered) {
                        (crossfadeSeconds * 1000L).takeIf { it > 0L } ?: 0L
                    } else {
                        0L // Если трек физически уже завершился (EOF) до автоперехода — играем следующий сразу на 100% громкости
                    }
                    if (waveBridge?.isWaveMode == true) {
                        waveBridge.onWaveTrackFinished(naturalCrossfadeMs)
                    } else {
                        playNextTrack(prevId, source = TrackPlaySource.PLAY, crossfadeMs = naturalCrossfadeMs)
                    }
                } else {
                    println("⚠️ Трек упал или не смог запуститься.")
                    if (prevId.startsWith("local:")) {
                        onError("Ошибка: не удалось воспроизвести локальный файл.")
                        playNextTrack(prevId, source = TrackPlaySource.PLAY, crossfadeMs = 0L)
                    } else if (isCurrentTrackHQ) {
                        println("♻️ Fallback: Пытаемся скачать и воспроизвести LQ версию...")
                        isCurrentTrackHQ = false
                        playTrackForceLQ(prevId, prevTitle, prevArtist, source = TrackPlaySource.PLAY)
                    } else {
                        onError("Ошибка: не удалось воспроизвести трек даже в LQ.")
                        if (waveBridge?.isWaveMode == true) {
                            waveBridge.onWaveTrackFinished(0L)
                        } else {
                            playNextTrack(prevId, source = TrackPlaySource.PLAY, crossfadeMs = 0L)
                        }
                    }
                }
            }
        }
    }

    /**
     * Переход к следующему треку в очереди
     */
    fun playNextTrack(currentId: String? = trackId, source: TrackPlaySource = TrackPlaySource.NEXT, crossfadeMs: Long = 0L) {
        lastPrevClickTimestamp = 0L
        if (source == TrackPlaySource.NEXT) {
            isNextLoading = true
            isPrevLoading = false
            isPlayLoading = false
        }
        if (waveBridge?.isWaveMode == true) {
            waveBridge.playNextWaveTrack(crossfadeMs)
            return
        }

        val activeId = currentId ?: trackId
        val loadedTracks = queueSource.getLoadedTracks()
        val searchTracks = queueSource.getSearchTracks()

        // 1. История перемотки вперед
        val fromForward = queueManager.popNextFromForwardHistory(activeId, loadedTracks, searchTracks)
        if (fromForward != null) {
            playTrack(
                targetTrackId = fromForward.id,
                targetTrackTitle = fromForward.title,
                targetArtistName = fromForward.artists.joinToString { it.name },
                targetAlbumId = fromForward.albums.firstOrNull()?.id,
                isManualSelection = false,
                source = source,
                crossfadeMs = crossfadeMs
            )
            return
        }

        if (activeId != null) {
            queueManager.recordPlayed(activeId)
        }

        // 2. Текущий плейлист (loadedTracks)
        if (loadedTracks.isNotEmpty()) {
            if (isShuffleEnabled) {
                val nextTrack = queueManager.getNextFromList(activeId, loadedTracks)
                if (nextTrack != null) {
                    playTrack(
                        targetTrackId = nextTrack.id,
                        targetTrackTitle = nextTrack.title,
                        targetArtistName = nextTrack.artists.joinToString { it.name },
                        targetAlbumId = nextTrack.albums.firstOrNull()?.id,
                        isManualSelection = false,
                        source = source,
                        crossfadeMs = crossfadeMs
                    )
                    return
                }
            } else {
                val currentIndex = if (activeId != null) loadedTracks.indexOfFirst { PlaybackQueueManager.isSameTrack(it, activeId) } else -1
                if (currentIndex != -1 && currentIndex < loadedTracks.size - 1) {
                    val nextTrack = loadedTracks[currentIndex + 1]
                    playTrack(
                        targetTrackId = nextTrack.id,
                        targetTrackTitle = nextTrack.title,
                        targetArtistName = nextTrack.artists.joinToString { it.name },
                        targetAlbumId = nextTrack.albums.firstOrNull()?.id,
                        isManualSelection = false,
                        source = source,
                        crossfadeMs = crossfadeMs
                    )
                    return
                } else if (currentIndex != -1 && currentIndex == loadedTracks.size - 1) {
                    if (source == TrackPlaySource.NEXT) isNextLoading = true
                    scope.launch {
                        try {
                            val loaded = queueSource.loadMoreTracks()
                            val updatedTracks = queueSource.getLoadedTracks()
                            if (loaded && currentIndex + 1 < updatedTracks.size) {
                                val nextTrack = updatedTracks[currentIndex + 1]
                                playTrack(
                                    targetTrackId = nextTrack.id,
                                    targetTrackTitle = nextTrack.title,
                                    targetArtistName = nextTrack.artists.joinToString { it.name },
                                    targetAlbumId = nextTrack.albums.firstOrNull()?.id,
                                    isManualSelection = false,
                                    source = source,
                                    crossfadeMs = crossfadeMs
                                )
                                return@launch
                            }
                        } catch (e: Exception) {
                            println("PlaybackManager: Ошибка догрузки следующей страницы: ${e.message}")
                            if (source == TrackPlaySource.NEXT) isNextLoading = false
                        }
                        val finalTracks = queueSource.getLoadedTracks()
                        if (finalTracks.isNotEmpty()) {
                            val firstTrack = finalTracks.first()
                            playTrack(
                                targetTrackId = firstTrack.id,
                                targetTrackTitle = firstTrack.title,
                                targetArtistName = firstTrack.artists.joinToString { it.name },
                                targetAlbumId = firstTrack.albums.firstOrNull()?.id,
                                isManualSelection = false,
                                source = source,
                                crossfadeMs = crossfadeMs
                            )
                        } else {
                            if (source == TrackPlaySource.NEXT) isNextLoading = false
                        }
                    }
                    return
                } else if (currentIndex == loadedTracks.size - 1 || currentIndex == -1) {
                    val firstTrack = loadedTracks.first()
                    playTrack(
                        targetTrackId = firstTrack.id,
                        targetTrackTitle = firstTrack.title,
                        targetArtistName = firstTrack.artists.joinToString { it.name },
                        targetAlbumId = firstTrack.albums.firstOrNull()?.id,
                        isManualSelection = false,
                        source = source,
                        crossfadeMs = crossfadeMs
                    )
                    return
                }
            }
        }

        // 3. Результаты поиска
        if (searchTracks.isNotEmpty()) {
            val nextTrack = queueManager.getNextFromList(activeId, searchTracks)
            if (nextTrack != null) {
                playTrack(
                    targetTrackId = nextTrack.id,
                    targetTrackTitle = nextTrack.title,
                    targetArtistName = nextTrack.artists.joinToString { it.name },
                    targetAlbumId = null,
                    isManualSelection = false,
                    source = source,
                    crossfadeMs = crossfadeMs
                )
                return
            }
        }

        // 4. Волна
        val waveTracks = waveBridge?.waveTracks ?: emptyList()
        if (activeId == null && waveTracks.isNotEmpty()) {
            waveBridge?.playNextWaveTrack()
        } else {
            isNextLoading = false
        }
    }

    /**
     * Переход к предыдущему треку в очереди (или перезапуск текущего, если прошло > 5 сек)
     */
    fun playPrevTrack(currentId: String? = trackId, source: TrackPlaySource = TrackPlaySource.PREV) {
        val now = currentTimeMillis()
        val currentPos = if (positionMs > 0L) positionMs else audioPlayer.getCurrentPositionMs()
        val timeSinceLastPrev = now - lastPrevClickTimestamp

        // 🔄 Если трек уже играет больше 5 секунд и это НЕ быстрый повторный клик (прошло > 2 сек):
        // перематываем текущий трек на начало и запускаем воспроизведение
        if (trackId != null && currentPos > PREV_RESTART_THRESHOLD_MS && timeSinceLastPrev > PREV_DOUBLE_CLICK_WINDOW_MS) {
            println("PlaybackManager: ⏮ Трек играет $currentPos мс (> 5 сек) -> перезапуск с начала")
            lastPrevClickTimestamp = now
            seekTo(0L)
            if (isPaused) {
                audioPlayer.resume()
                isPaused = false
                systemMediaControls.updatePlaybackState(isPlaying = true, isPaused = false, 0L)
                startProgressPolling()
            } else if (!isPlaying) {
                togglePlayPause()
            }
            return
        }

        // Если сработал переход к предыдущему треку — сбрасываем таймер быстрого клика
        lastPrevClickTimestamp = 0L

        if (source == TrackPlaySource.PREV) {
            isPrevLoading = true
            isNextLoading = false
            isPlayLoading = false
        }
        if (waveBridge?.isWaveMode == true) {
            waveBridge.playPrevWaveTrack()
            return
        }

        val activeId = currentId ?: trackId
        val loadedTracks = queueSource.getLoadedTracks()
        val searchTracks = queueSource.getSearchTracks()

        // 1. Реальная история прослушивания
        val prevFromHistory = queueManager.popPrevFromPlaybackHistory(activeId, loadedTracks, searchTracks)
        if (prevFromHistory != null) {
            playTrack(
                targetTrackId = prevFromHistory.id,
                targetTrackTitle = prevFromHistory.title,
                targetArtistName = prevFromHistory.artists.joinToString { it.name },
                targetAlbumId = prevFromHistory.albums.firstOrNull()?.id,
                isManualSelection = false,
                source = source
            )
            return
        }

        // 2. Текущий плейлист (loadedTracks)
        if (loadedTracks.isNotEmpty()) {
            if (activeId != null) queueManager.recordForward(activeId)
            val prevTrack = queueManager.getPrevFromList(activeId, loadedTracks)
            if (prevTrack != null) {
                playTrack(
                    targetTrackId = prevTrack.id,
                    targetTrackTitle = prevTrack.title,
                    targetArtistName = prevTrack.artists.joinToString { it.name },
                    targetAlbumId = prevTrack.albums.firstOrNull()?.id,
                    isManualSelection = false,
                    source = source
                )
                return
            }
        }

        // 3. Результаты поиска
        if (searchTracks.isNotEmpty()) {
            if (activeId != null) queueManager.recordForward(activeId)
            val prevTrack = queueManager.getPrevFromList(activeId, searchTracks)
            if (prevTrack != null) {
                playTrack(
                    targetTrackId = prevTrack.id,
                    targetTrackTitle = prevTrack.title,
                    targetArtistName = prevTrack.artists.joinToString { it.name },
                    targetAlbumId = null,
                    isManualSelection = false,
                    source = source
                )
                return
            }
        }

        val waveTracks = waveBridge?.waveTracks ?: emptyList()
        if (activeId == null && waveTracks.isNotEmpty()) {
            waveBridge?.playPrevWaveTrack()
        } else {
            isPrevLoading = false
        }
    }

    /**
     * ⏭ Вычислить кандидата на следующий трек в очереди с учетом приоритетов:
     * 1. Моя Волна (getNextWaveTrack)
     * 2. История перемотки вперед (forwardHistory)
     * 3. Текущий список треков (loadedTracks) со случайным (Shuffle) или последовательным порядком
     * 4. Результаты поиска (searchTracks)
     */
    fun calculateNextTrackCandidate(): FullTrackInfo? {
        return queueManager.calculateNextCandidate(
            activeId = trackId,
            isWaveMode = waveBridge?.isWaveMode == true,
            nextWaveTrack = waveBridge?.getNextWaveTrackCandidate(),
            loadedTracks = queueSource.getLoadedTracks(),
            searchTracks = queueSource.getSearchTracks()
        )
    }

    /**
     * ⚡ Запуск фоновой предзагрузки запланированного следующего трека
     */
    fun schedulePreload() {
        preloadJob?.cancel()
        val nextTrack = plannedNextTrack ?: return
        val targetTrackId = nextTrack.id
        val cleanTitle = nextTrack.title.trim()
        val cleanArtist = nextTrack.artists.joinToString { it.name }.trim()
        val selectedQuality = getSelectedQuality()

        if (preloadedAudio?.trackId == targetTrackId && preloadedAudio?.quality == selectedQuality) {
            return
        }

        preloadJob = scope.launch {
            // Задержка 3 секунды для предотвращения лишних скачиваний при быстром перелистывании
            delay(3000L)

            if (trackId == targetTrackId || plannedNextTrack?.id != targetTrackId) {
                return@launch
            }

            try {
                val musicStoragePath = localTrackResolver.getMusicStoragePath()
                val sanitizedArtist = LocalTrackResolver.sanitizeKeepSpaces(cleanArtist.ifBlank { "Unknown Artist" })

                // 1. Проверяем, есть ли уже файл на диске
                val existingFilePath = localTrackResolver.findLocalTrackFile(
                    trackId = targetTrackId,
                    artist = cleanArtist,
                    title = cleanTitle,
                    selectedQuality = selectedQuality
                )

                if (existingFilePath != null) {
                    println("⚡ [Предзагрузка] Трек '$cleanTitle' уже есть на диске: $existingFilePath")
                    preloadedAudio = PreloadedAudio(
                        trackId = targetTrackId,
                        audioData = null,
                        quality = selectedQuality,
                        filePath = existingFilePath
                    )
                    return@launch
                }

                if (targetTrackId.startsWith("local:")) {
                    return@launch
                }

                // 2. ⚡ Если запущен LocalStreamProxy — предзагружаем только Chunk 0 (256 КБ), экономя трафик!
                if (LocalStreamProxy.isRunning()) {
                    println("⚡ [Предзагрузка] Предзагрузка первого чанка стрима через LocalStreamProxy: $cleanTitle")
                    try {
                        LocalStreamProxy.preloadTrack(targetTrackId, selectedQuality)
                        if (plannedNextTrack?.id == targetTrackId) {
                            preloadedAudio = PreloadedAudio(
                                trackId = targetTrackId,
                                audioData = null,
                                quality = selectedQuality,
                                filePath = null
                            )
                        }
                    } catch (e: Exception) {
                        println("⚡ [Предзагрузка] Ошибка предзагрузки стрима: ${e.message}")
                    }
                    return@launch
                }

                // 3. Скачиваем трек в фоне (fallback для сред без прокси)
                println("⚡ [Предзагрузка] Фоновое скачивание трека: $cleanTitle ($cleanArtist) [Качество: $selectedQuality]")
                val audioData = repository.downloadTrackAudio(targetTrackId, quality = selectedQuality)

                if (plannedNextTrack?.id != targetTrackId) {
                    return@launch
                }

                if (isRecordToDisk()) {
                    val qualityFolder = if (selectedQuality == "2") "HQ" else "LQ"
                    val basePath = "$musicStoragePath/$qualityFolder"
                    val rawExtension = audioData.type.substringBefore("-")
                    val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension
                    val fullFileName = sanitizeKeepSpaces(if (cleanArtist.isNotEmpty()) {
                        "$cleanArtist — $cleanTitle.$extension"
                    } else {
                        "$cleanTitle.$extension"
                    })
                    saveTrackFile(basePath, cleanArtist, fullFileName, audioData.result)
                    localTrackResolver.invalidateCache()
                    onTrackSavedToDisk?.invoke()
                    val filePath = "$basePath/$sanitizedArtist/$fullFileName"
                    println("⚡ [Предзагрузка] Трек '$cleanTitle' сохранен на диск: $filePath")
                    preloadedAudio = PreloadedAudio(
                        trackId = targetTrackId,
                        audioData = null,
                        quality = selectedQuality,
                        filePath = filePath
                    )
                } else {
                    println("⚡ [Предзагрузка] Трек '$cleanTitle' сохранен в памяти (${audioData.result.size} байт)")
                    preloadedAudio = PreloadedAudio(
                        trackId = targetTrackId,
                        audioData = audioData,
                        quality = selectedQuality,
                        filePath = null
                    )
                }
            } catch (e: Exception) {
                println("⚡ [Предзагрузка] Не удалось предзагрузить трек: ${e.message}")
            }
        }
    }

    /**
     * 🌊 Уведомление об изменении очереди (например, дозагрузка треков в Моей Волне)
     */
    fun onQueueUpdated() {
        val candidate = calculateNextTrackCandidate()
        if (candidate?.id != plannedNextTrack?.id) {
            plannedNextTrack = candidate
            schedulePreload()
        }
    }

    fun release() {
        preloadJob?.cancel()
        preloadJob = null
        preloadedAudio = null
        plannedNextTrack = null
        playTrackJob?.cancel()
        progressJob?.cancel()
        audioPlayer.release()
        systemMediaControls.release()
        LocalStreamProxy.stop()
    }
}
