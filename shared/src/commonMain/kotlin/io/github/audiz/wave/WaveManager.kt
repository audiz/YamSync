package io.github.audiz.wave

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.audiz.TrackPlaySource
import io.github.audiz.api.MusicRepository
import io.github.audiz.api.RotorFeedbackItem
import io.github.audiz.api.currentIsoUtcTimestamp
import io.github.audiz.api.generatePlayUuid
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.player.PlaybackManager
import io.github.audiz.player.WavePlaybackBridge
import io.github.audiz.AppConfigKeys
import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import io.github.audiz.models.ThematicWavePreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 🌊 Менеджер режима «Моя Волна» (Ynison WebSocket + Rotor Radio REST API).
 * Инкапсулирует:
 * - Управление сессиями волны (Ynison session ID, Rotor radio session ID, batch ID).
 * - Очередь треков текущей пачки и историю переходов (до 5 треков назад/вперед).
 * - Буфер накопления фидбеков (skip, trackFinished) и запрос следующих пачек.
 * - Взаимодействие с PlaybackManager для запуска воспроизведения треков волны.
 */
class WaveManager(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val getAccessToken: () -> String,
    private val onTrackLiked: ((String) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) : WavePlaybackBridge {
    private var playbackManager: PlaybackManager? = null

    fun attachPlayback(playback: PlaybackManager) {
        this.playbackManager = playback
    }

    // 🌊 Ynison Моя Волна session_id:
    var ynisonWaveSessionId by mutableStateOf<String?>(null)
        private set
    var isFetchingYnison by mutableStateOf(false)
        private set

    // 🌊 Состояния «Моя Волна» (Rotor Radio):
    var waveRadioSessionId by mutableStateOf<String?>(null)
        private set
    var waveBatchId by mutableStateOf<String?>(null)
        private set
    override val waveTracks: SnapshotStateList<FullTrackInfo> = mutableStateListOf()
    override var waveCurrentIndex by mutableStateOf(0)
        private set
    override var isWaveMode by mutableStateOf(false)
        private set
    var isWaveLoading by mutableStateOf(false)
        private set

    // 🌊 Тематическая Волна (по жанру / настроению):
    var currentWaveTitle by mutableStateOf<String?>(null)
        private set
    var currentWaveSeeds by mutableStateOf<List<String>>(emptyList())
        private set

    companion object {
        val ALL_STYLE_PRESETS: List<ThematicWavePreset> = listOf(
            ThematicWavePreset("Рок", listOf("genre:rock")),
            ThematicWavePreset("Поп", listOf("genre:pop")),
            ThematicWavePreset("Хип-хоп", listOf("genre:rap")),
            ThematicWavePreset("Электроника", listOf("genre:electronic")),
            ThematicWavePreset("Метал", listOf("genre:metal")),
            ThematicWavePreset("Джаз", listOf("genre:jazz")),
            ThematicWavePreset("Инди", listOf("genre:indie")),
            ThematicWavePreset("Панк", listOf("genre:punk")),
            ThematicWavePreset("Lo-Fi", listOf("genre:lofi")),
            ThematicWavePreset("Классика", listOf("genre:classical")),
            ThematicWavePreset("R&B", listOf("genre:rnb")),
            ThematicWavePreset("Фолк", listOf("genre:folk")),
            ThematicWavePreset("Саундтреки", listOf("genre:soundtrack")),
            ThematicWavePreset("Диско", listOf("genre:disco")),
            ThematicWavePreset("Блюз", listOf("genre:blues")),
            ThematicWavePreset("Альтернатива", listOf("genre:alternative")),
            ThematicWavePreset("Эмбиент", listOf("genre:ambient")),
            ThematicWavePreset("Регги", listOf("genre:reggae")),
            ThematicWavePreset("Синти-поп", listOf("genre:synthpop")),
            ThematicWavePreset("Фанк", listOf("genre:funk"))
        )
    }

    // 🌊 Постоянные пресеты тематических волн (динамически разные музыкальные стили):
    val defaultThematicWaves: SnapshotStateList<ThematicWavePreset> = mutableStateListOf()

    fun refreshDefaultThematicWaves() {
        val available = ALL_STYLE_PRESETS.filterNot { preset ->
            recentThematicWaves.any { it.title.equals(preset.title, ignoreCase = true) || it.seeds == preset.seeds }
        }
        val selected = available.shuffled().take(3)
        defaultThematicWaves.clear()
        defaultThematicWaves.addAll(selected)
    }

    // 🌊 До 3 недавних тематических волн:
    val recentThematicWaves: SnapshotStateList<ThematicWavePreset> = mutableStateListOf()
    private val waveJson = Json { ignoreUnknownKeys = true }

    init {
        loadRecentThematicWaves()
        refreshDefaultThematicWaves()
    }

    private fun loadRecentThematicWaves() {
        try {
            val jsonStr = loadAppConfig(AppConfigKeys.RECENT_THEMATIC_WAVES)
            if (!jsonStr.isNullOrBlank()) {
                val list = waveJson.decodeFromString<List<ThematicWavePreset>>(jsonStr)
                recentThematicWaves.clear()
                recentThematicWaves.addAll(list.take(3))
            }
        } catch (e: Throwable) {
            println("WaveManager: Ошибка загрузки недавних волн: ${e.message}")
        }
    }

    private fun recordThematicWave(title: String?, seeds: List<String>) {
        if (seeds.isEmpty()) return
        val displayTitle = title?.takeIf { it.isNotBlank() } ?: "Моя Волна"
        val entry = ThematicWavePreset(displayTitle, seeds)

        // Исключаем предыдущее появление такой же волны (по seeds или по названию)
        val filtered = recentThematicWaves.filterNot { it.seeds == seeds || it.title == displayTitle }
        val updated = (listOf(entry) + filtered).take(3)

        recentThematicWaves.clear()
        recentThematicWaves.addAll(updated)

        try {
            val jsonStr = waveJson.encodeToString(updated)
            saveAppConfig(AppConfigKeys.RECENT_THEMATIC_WAVES, jsonStr)
        } catch (e: Throwable) {
            println("WaveManager: Ошибка сохранения недавних волн: ${e.message}")
        }

        // Если запущенная волна была среди пресетов, заменяем её новым стилем
        if (defaultThematicWaves.any { it.title.equals(displayTitle, ignoreCase = true) || it.seeds == seeds }) {
            refreshDefaultThematicWaves()
        }
    }

    var activeWaveTrack by mutableStateOf<FullTrackInfo?>(null)
        private set

    val currentWaveTrack: FullTrackInfo?
        get() = activeWaveTrack ?: waveTracks.getOrNull(waveCurrentIndex)

    /**
     * ⏭ Получить кандидата на следующий трек в Моей Волне (для предзагрузки)
     */
    override fun getNextWaveTrackCandidate(): FullTrackInfo? {
        val currentId = currentWaveTrack?.id
        val futureCandidate = waveFutureTracks.firstOrNull { it.id != currentId }
        if (futureCandidate != null) return futureCandidate

        for (i in (waveCurrentIndex + 1) until waveTracks.size) {
            val candidate = waveTracks.getOrNull(i)
            if (candidate != null && candidate.id != currentId) {
                return candidate
            }
        }
        return null
    }

    // 🌊 История навигации Моей Волны (до 5 треков назад / вперед):
    val wavePastTracks: SnapshotStateList<FullTrackInfo> = mutableStateListOf()
    val waveFutureTracks: SnapshotStateList<FullTrackInfo> = mutableStateListOf()

    // 🌊 Буфер накопленных фидбеков Моей Волны (строго изолирован от плейлистов):
    private val pendingWaveFeedbacks = mutableListOf<RotorFeedbackItem>()
    private val waveTrackBatchMap = mutableMapOf<String, String>()
    private val trackStartTimestamps = mutableMapOf<String, String>()
    private val trackPlayIds = mutableMapOf<String, String>()
    private var replenishmentJob: Job? = null

    /**
     * 🌊 Инициализировать Мою Волну (получить Ynison session, клонировать сессию и загрузить первые 5 треков)
     */
    override fun loadInitialWave(autoPlay: Boolean, source: TrackPlaySource) {
        currentWaveTitle = null
        currentWaveSeeds = emptyList()
        val token = getAccessToken()
        if (token.isBlank()) return
        if (autoPlay) {
            when (source) {
                TrackPlaySource.NEXT -> playbackManager?.setLoadingFlags(next = true, prev = false, play = false)
                TrackPlaySource.PREV -> playbackManager?.setLoadingFlags(next = false, prev = true, play = false)
                TrackPlaySource.PLAY -> playbackManager?.setLoadingFlags(next = false, prev = false, play = true)
            }
        }
        scope.launch {
            isWaveLoading = true
            isFetchingYnison = true
            try {
                val sid = repository.fetchYnisonWaveSessionId()
                ynisonWaveSessionId = sid
                println("WaveManager: Получен Ynison Wave Session ID = $sid")

                if (!sid.isNullOrBlank()) {
                    println("WaveManager: Клонируем сессию Волны: $sid")
                    val response = repository.cloneRotorSession(sid)
                    waveRadioSessionId = response.radioSessionId ?: sid
                    waveBatchId = response.batchId
                    replenishmentJob?.cancel()
                    trackStartTimestamps.clear()
                    trackPlayIds.clear()
                    waveTracks.clear()
                    wavePastTracks.clear()
                    waveFutureTracks.clear()
                    activeWaveTrack = null
                    pendingWaveFeedbacks.clear()
                    waveTrackBatchMap.clear()
                    val parsedTracks = response.sequence.mapNotNull { it.track }.distinctBy { it.id }
                    waveTracks.addAll(parsedTracks)
                    parsedTracks.forEach { t ->
                        response.batchId?.let { bId -> waveTrackBatchMap[t.id] = bId }
                    }
                    response.sequence.forEach { item ->
                        val t = item.track
                        if (item.liked && t != null) {
                            onTrackLiked?.invoke(t.id)
                        }
                    }
                    waveCurrentIndex = 0
                    if (waveTracks.isNotEmpty()) {
                        isWaveMode = true
                        activeWaveTrack = parsedTracks.firstOrNull()
                    }
                    println("WaveManager: Волна инициализирована! Загружено треков: ${waveTracks.size}, batchId: $waveBatchId")

                    if (autoPlay && waveTracks.isNotEmpty()) {
                        playWaveTrack(0, source = source)
                    } else if (autoPlay) {
                        playbackManager?.resetLoadingFlags()
                    }
                } else if (autoPlay) {
                    playbackManager?.resetLoadingFlags()
                }
            } catch (e: Throwable) {
                println("WaveManager: Ошибка инициализации Волны: ${e.message}")
                e.printStackTrace()
                onError?.invoke("Ошибка инициализации Волны: ${e.message}")
                if (autoPlay) {
                    playbackManager?.resetLoadingFlags()
                }
            } finally {
                isFetchingYnison = false
                isWaveLoading = false
            }
        }
    }

    /**
     * 🌊 Запустить тематическую Мою Волну (по жанру, артисту, стилю через seeds)
     */
    fun startThematicWave(
        title: String?,
        seeds: List<String>,
        autoPlay: Boolean = true,
        source: TrackPlaySource = TrackPlaySource.PLAY
    ) {
        if (seeds.isEmpty()) {
            loadInitialWave(autoPlay = autoPlay, source = source)
            return
        }

        currentWaveTitle = title
        currentWaveSeeds = seeds
        recordThematicWave(title, seeds)

        val token = getAccessToken()
        if (token.isBlank()) return

        if (autoPlay) {
            when (source) {
                TrackPlaySource.NEXT -> playbackManager?.setLoadingFlags(next = true, prev = false, play = false)
                TrackPlaySource.PREV -> playbackManager?.setLoadingFlags(next = false, prev = true, play = false)
                TrackPlaySource.PLAY -> playbackManager?.setLoadingFlags(next = false, prev = false, play = true)
            }
        }

        scope.launch {
            isWaveLoading = true
            try {
                println("WaveManager: Запуск тематической Волны '$title', seeds=$seeds")
                val response = repository.createRotorSession(seeds)
                val sid = response.radioSessionId
                waveRadioSessionId = sid
                waveBatchId = response.batchId
                replenishmentJob?.cancel()
                trackStartTimestamps.clear()
                trackPlayIds.clear()
                waveTracks.clear()
                wavePastTracks.clear()
                waveFutureTracks.clear()
                activeWaveTrack = null
                pendingWaveFeedbacks.clear()
                waveTrackBatchMap.clear()

                val parsedTracks = response.sequence.mapNotNull { it.track }
                waveTracks.addAll(parsedTracks)
                parsedTracks.forEach { t ->
                    response.batchId?.let { bId -> waveTrackBatchMap[t.id] = bId }
                }
                response.sequence.forEach { item ->
                    val t = item.track
                    if (item.liked && t != null) {
                        onTrackLiked?.invoke(t.id)
                    }
                }
                waveCurrentIndex = 0
                println("WaveManager: Тематическая Волна запущена! Загружено треков: ${waveTracks.size}, radioSessionId: $waveRadioSessionId, batchId: $waveBatchId")

                if (autoPlay && waveTracks.isNotEmpty()) {
                    playWaveTrack(0, source = source)
                } else if (autoPlay) {
                    playbackManager?.resetLoadingFlags()
                }
            } catch (e: Throwable) {
                println("WaveManager: Ошибка запуска тематической Волны: ${e.message}")
                e.printStackTrace()
                onError?.invoke("Ошибка запуска Волны: ${e.message}")
                if (autoPlay) {
                    playbackManager?.resetLoadingFlags()
                }
            } finally {
                isWaveLoading = false
            }
        }
    }

    /**
     * 🔄 Сбросить Мою Волну к дефолтной персональной волне пользователя
     */
    fun resetToDefaultWave(autoPlay: Boolean = true) {
        currentWaveTitle = null
        currentWaveSeeds = emptyList()
        loadInitialWave(autoPlay = autoPlay, source = TrackPlaySource.PLAY)
    }

    /**
     * 🌊 Воспроизвести трек Волны по индексу (0..4)
     */
    fun playWaveTrack() {
        playWaveTrack(waveCurrentIndex, TrackPlaySource.PLAY, 0L)
    }

    override fun playWaveTrack(index: Int, source: TrackPlaySource, crossfadeMs: Long) {
        if (index < 0 || index >= waveTracks.size) return
        val track = waveTracks[index]
        waveCurrentIndex = index
        isWaveMode = true
        activeWaveTrack = track
        onTrackPlaybackStarted(track)
        println("🌊 Волна: Воспроизведение трека [$index/${waveTracks.size}]: ${track.title}")

        // 🌊 Превентивная дозагрузка: если впереди осталось <= 4 треков, сразу пополняем очередь в фоне
        val remainingAhead = waveTracks.size - (index + 1)
        if (remainingAhead <= 4 && replenishmentJob?.isActive != true) {
            replenishWaveAhead(null, track)
        }

        playbackManager?.playTrack(
            targetTrackId = track.id,
            targetTrackTitle = track.title,
            targetArtistName = track.artists.joinToString { it.name },
            targetAlbumId = track.albums.firstOrNull()?.id,
            targetCoverUri = track.coverUri,
            isManualSelection = false,
            isWave = true,
            source = source,
            crossfadeMs = crossfadeMs
        )
    }

    fun playWaveTrackDirect(track: FullTrackInfo, source: TrackPlaySource = TrackPlaySource.PLAY, crossfadeMs: Long = 0L) {
        isWaveMode = true
        activeWaveTrack = track
        val idx = waveTracks.indexOfFirst { it.id == track.id }
        if (idx != -1) {
            waveCurrentIndex = idx
        } else {
            // Если трек из истории отсутствовал в текущей пачке:
            // вставляем его в текущую пачку на текущую позицию, чтобы индекс и очередь были согласованы
            val insertIdx = waveCurrentIndex.coerceIn(0, waveTracks.size)
            waveTracks.add(insertIdx, track)
            waveCurrentIndex = insertIdx
        }
        onTrackPlaybackStarted(track)
        println("🌊 Волна: Воспроизведение трека из истории: ${track.title}")
        playbackManager?.playTrack(
            targetTrackId = track.id,
            targetTrackTitle = track.title,
            targetArtistName = track.artists.joinToString { it.name },
            targetAlbumId = track.albums.firstOrNull()?.id,
            targetCoverUri = track.coverUri,
            isManualSelection = false,
            isWave = true,
            source = source,
            crossfadeMs = crossfadeMs
        )
    }

    private fun onTrackPlaybackStarted(track: FullTrackInfo) {
        val startTs = currentIsoUtcTimestamp()
        val playId = generatePlayUuid()
        trackStartTimestamps[track.id] = startTs
        trackPlayIds[track.id] = playId

        val radioSessionId = waveRadioSessionId ?: ynisonWaveSessionId
        val bId = waveTrackBatchMap[track.id] ?: waveBatchId
        if (!radioSessionId.isNullOrBlank() && !bId.isNullOrBlank()) {
            val albumId = track.albums.firstOrNull()?.id ?: 0
            val trackIdWithAlbum = "${track.id}:$albumId"
            scope.launch {
                repository.sendRotorFeedbackTrackStarted(
                    radioSessionId = radioSessionId,
                    batchId = bId,
                    trackIdWithAlbum = trackIdWithAlbum,
                    timestamp = startTs
                )
            }
        }
    }

    /**
     * ⏮ Переход к предыдущему треку в Моей волне (до 5 треков назад)
     */
    override fun playPrevWaveTrack() {
        if (!isWaveMode) {
            if (waveTracks.isNotEmpty()) {
                playWaveTrack(waveCurrentIndex, source = TrackPlaySource.PREV)
            }
            return
        }

        if (wavePastTracks.isEmpty()) {
            println("🌊 Волна: История назад пуста (достигнут лимит 5 треков)")
            val currentPos = playbackManager?.positionMs ?: 0L
            if (currentPos > 2000L) {
                playbackManager?.seekTo(0L)
            }
            return
        }

        playbackManager?.setLoadingFlags(next = false, prev = true, play = false)

        val current = currentWaveTrack
        if (current != null) {
            waveFutureTracks.removeAll { it.id == current.id }
            waveFutureTracks.add(0, current)
            if (waveFutureTracks.size > 5) {
                waveFutureTracks.removeLast()
            }
        }

        val prevTrack = wavePastTracks.removeLast()
        waveFutureTracks.removeAll { it.id == prevTrack.id }
        println("🌊 Волна: Назад к треку [в истории назад: ${wavePastTracks.size}, вперед: ${waveFutureTracks.size}]: ${prevTrack.title}")
        playWaveTrackDirect(prevTrack, source = TrackPlaySource.PREV)
    }

    /**
     * ⏭ Переход к следующему треку в Моей волне:
     * Если пользователь ранее нажимал «Назад», возвращаемся вперед по истории.
     * Если история вперед исчерпана — пропускаем трек (skip) с запросом новой пачки.
     */
    override fun playNextWaveTrack(crossfadeMs: Long) {
        playbackManager?.setLoadingFlags(next = true, prev = false, play = false)

        val current = currentWaveTrack
        while (waveFutureTracks.isNotEmpty() && waveFutureTracks.first().id == current?.id) {
            waveFutureTracks.removeAt(0)
        }

        if (waveFutureTracks.isNotEmpty()) {
            if (current != null && wavePastTracks.lastOrNull()?.id != current.id) {
                wavePastTracks.add(current)
                if (wavePastTracks.size > 5) {
                    wavePastTracks.removeAt(0)
                }
            }
            val nextTrack = waveFutureTracks.removeAt(0)
            println("🌊 Волна: Вперед по истории к треку [в истории вперед: ${waveFutureTracks.size}]: ${nextTrack.title}")
            playWaveTrackDirect(nextTrack, source = TrackPlaySource.NEXT, crossfadeMs = crossfadeMs)
        } else {
            skipWaveTrack(crossfadeMs = crossfadeMs)
        }
    }

    /**
     * 🌊 Переключить play/pause для Моей волны
     */
    fun togglePlayPauseWave() {
        val pm = playbackManager
        if (isWaveMode && pm?.trackId != null) {
            pm.togglePlayPause()
        } else {
            if (waveTracks.isEmpty()) {
                loadInitialWave(autoPlay = true, source = TrackPlaySource.PLAY)
            } else {
                playWaveTrack(waveCurrentIndex, source = TrackPlaySource.PLAY)
            }
        }
    }

    /**
     * ⏭ Пропустить трек в Моей волне (skip):
     * Если в очереди уже есть следующий трек — переключаем мгновенно (Zero Latency)
     * и в фоне отправляем фидбек + обновляем хвост очереди.
     */
    fun skipWaveTrack(crossfadeMs: Long = 0L) {
        if (!isWaveMode) {
            if (waveTracks.isNotEmpty()) {
                val nextIndex = if (waveCurrentIndex + 1 < waveTracks.size) waveCurrentIndex + 1 else 0
                playWaveTrack(nextIndex, source = TrackPlaySource.NEXT, crossfadeMs = crossfadeMs)
            }
            return
        }

        // Если в текущей очереди есть следующий трек — мгновенно переключаем на него (Zero Latency)
        if (waveCurrentIndex + 1 < waveTracks.size) {
            val oldTrack = currentWaveTrack
            val nextIndex = waveCurrentIndex + 1
            val nextTrack = waveTracks[nextIndex]

            if (oldTrack != null && wavePastTracks.lastOrNull()?.id != oldTrack.id) {
                wavePastTracks.add(oldTrack)
                if (wavePastTracks.size > 5) {
                    wavePastTracks.removeAt(0)
                }
            }
            waveFutureTracks.clear()

            // 1. Мгновенно запускаем воспроизведение следующего трека
            playWaveTrack(nextIndex, source = TrackPlaySource.NEXT, crossfadeMs = crossfadeMs)

            val radioSessionId = waveRadioSessionId ?: ynisonWaveSessionId
            val batchId = waveBatchId
            if (radioSessionId == null || batchId == null) {
                return
            }

            // 2. В фоне отправляем skip фидбек, отчет /plays и обновляем хвост очереди
            replenishmentJob?.cancel()
            replenishmentJob = scope.launch {
                try {
                    val oldAlbumId = oldTrack?.albums?.firstOrNull()?.id ?: 0
                    val oldTrackIdWithAlbum = "${oldTrack?.id}:$oldAlbumId"
                    val bId = (oldTrack?.let { waveTrackBatchMap[it.id] }) ?: waveBatchId ?: batchId
                    val playedSeconds = ((playbackManager?.positionMs ?: 0L).toDouble() / 1000.0).coerceAtLeast(0.0)
                    val durSeconds = if ((oldTrack?.durationMs ?: 0L) > 0) (oldTrack!!.durationMs.toDouble() / 1000.0) else playedSeconds
                    val startTs = (oldTrack?.let { trackStartTimestamps[it.id] }) ?: currentIsoUtcTimestamp()
                    val playId = (oldTrack?.let { trackPlayIds[it.id] }) ?: generatePlayUuid()

                    // Отправляем /plays для пропущенного трека
                    if (oldTrack != null) {
                        repository.sendPlayReport(
                            trackId = oldTrack.id,
                            albumId = oldAlbumId.toString(),
                            radioSessionId = radioSessionId,
                            batchId = bId,
                            totalPlayedSeconds = playedSeconds,
                            endPositionSeconds = playedSeconds,
                            trackLengthSeconds = durSeconds,
                            startTimestamp = startTs,
                            changeReason = "skip",
                            playId = playId
                        )

                        // Добавляем событие skip в буфер фидбеков (без trackLengthSeconds)
                        pendingWaveFeedbacks.add(
                            RotorFeedbackItem(
                                batchId = bId,
                                trackId = oldTrackIdWithAlbum,
                                eventType = "skip",
                                totalPlayedSeconds = playedSeconds,
                                trackLengthSeconds = null
                            )
                        )
                    }

                    val feedbacksToSend = pendingWaveFeedbacks.toList()
                    pendingWaveFeedbacks.clear()

                    val nextAlbumId = nextTrack.albums.firstOrNull()?.id ?: 0
                    val nextTrackIdWithAlbum = "${nextTrack.id}:$nextAlbumId"
                    val transitionQueue = listOf(nextTrackIdWithAlbum)

                    println("🌊 Волна: отправка ${feedbacksToSend.size} фидбеков (skip: ${oldTrack?.title}), обновляем очередь после ${nextTrack.title}...")
                    val nextResponse = repository.getRotorNextTracks(
                        radioSessionId = radioSessionId,
                        feedbacks = feedbacksToSend,
                        remainingQueue = transitionQueue
                    )

                    waveBatchId = nextResponse.batchId
                    val newTracks = nextResponse.sequence.mapNotNull { it.track }

                    // Заменяем треки в очереди ПОСЛЕ текущего играющего (waveCurrentIndex)
                    while (waveTracks.size > waveCurrentIndex + 1) {
                        waveTracks.removeLast()
                    }
                    val existingIds = waveTracks.map { it.id }.toSet()
                    val pastIds = wavePastTracks.map { it.id }.toSet()
                    val forbiddenIds = buildSet {
                        addAll(existingIds)
                        addAll(pastIds)
                        oldTrack?.let { add(it.id) }
                        add(nextTrack.id)
                    }
                    val filteredNew = newTracks.filter { it.id !in forbiddenIds }.distinctBy { it.id }
                    val finalNew = if (filteredNew.isNotEmpty()) {
                        filteredNew
                    } else {
                        newTracks.filter { it.id !in existingIds && it.id != oldTrack?.id && it.id != nextTrack.id }.distinctBy { it.id }
                    }
                    waveTracks.addAll(finalNew)
                    newTracks.forEach { t ->
                        nextResponse.batchId?.let { b -> waveTrackBatchMap[t.id] = b }
                    }
                    nextResponse.sequence.forEach { item ->
                        val t = item.track
                        if (item.liked && t != null) {
                            onTrackLiked?.invoke(t.id)
                        }
                    }
                    println("🌊 Волна: Очередь обновлена новыми рекомендациями (${newTracks.size} шт). Всего в очереди: ${waveTracks.size}")
                    playbackManager?.onQueueUpdated()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    println("WaveManager: Ошибка обновления очереди после skip: ${e.message}")
                }
            }
            return
        }

        // Если в очереди больше нет следующих треков — проверяем сессию
        val radioSessionId = waveRadioSessionId ?: ynisonWaveSessionId
        val batchId = waveBatchId
        if (radioSessionId == null) {
            println("WaveManager: skipWaveTrack - сессия не готова, перезапускаем")
            loadInitialWave(autoPlay = true, source = TrackPlaySource.NEXT)
            return
        }

        // Показываем загрузку и ждем ответа сервера
        playbackManager?.setLoadingFlags(next = true, prev = false, play = false)
        val current = currentWaveTrack
        if (current != null && wavePastTracks.lastOrNull()?.id != current.id) {
            wavePastTracks.add(current)
            if (wavePastTracks.size > 5) {
                wavePastTracks.removeAt(0)
            }
        }
        waveFutureTracks.clear()

        replenishmentJob?.cancel()
        replenishmentJob = scope.launch {
            isWaveLoading = true
            try {
                val albumId = current?.albums?.firstOrNull()?.id ?: 0
                val trackIdWithAlbum = "${current?.id}:$albumId"
                val bId = (current?.let { waveTrackBatchMap[it.id] }) ?: waveBatchId ?: batchId ?: ""
                val playedSeconds = ((playbackManager?.positionMs ?: 0L).toDouble() / 1000.0).coerceAtLeast(0.0)
                val durSeconds = if ((current?.durationMs ?: 0L) > 0) (current!!.durationMs.toDouble() / 1000.0) else playedSeconds
                val startTs = (current?.let { trackStartTimestamps[it.id] }) ?: currentIsoUtcTimestamp()
                val playId = (current?.let { trackPlayIds[it.id] }) ?: generatePlayUuid()

                if (current != null) {
                    repository.sendPlayReport(
                        trackId = current.id,
                        albumId = albumId.toString(),
                        radioSessionId = radioSessionId,
                        batchId = bId,
                        totalPlayedSeconds = playedSeconds,
                        endPositionSeconds = playedSeconds,
                        trackLengthSeconds = durSeconds,
                        startTimestamp = startTs,
                        changeReason = "skip",
                        playId = playId
                    )
                    pendingWaveFeedbacks.add(
                        RotorFeedbackItem(
                            batchId = bId,
                            trackId = trackIdWithAlbum,
                            eventType = "skip",
                            totalPlayedSeconds = playedSeconds,
                            trackLengthSeconds = null
                        )
                    )
                }

                val feedbacksToSend = pendingWaveFeedbacks.toList()
                pendingWaveFeedbacks.clear()

                val transitionQueue = emptyList<String>()

                println("🌊 Волна: очередь пуста, отправка ${feedbacksToSend.size} фидбеков, запрашиваем новую пачку...")
                val nextResponse = repository.getRotorNextTracks(
                    radioSessionId = radioSessionId,
                    feedbacks = feedbacksToSend,
                    remainingQueue = transitionQueue
                )

                waveBatchId = nextResponse.batchId
                waveTracks.clear()
                val newTracks = nextResponse.sequence.mapNotNull { it.track }
                val pastIds = wavePastTracks.map { it.id }.toSet()
                val forbiddenIds = buildSet {
                    addAll(pastIds)
                    current?.let { add(it.id) }
                }
                val filteredNew = newTracks.filter { it.id !in forbiddenIds }.distinctBy { it.id }
                val finalNew = if (filteredNew.isNotEmpty()) {
                    filteredNew
                } else {
                    newTracks.filter { it.id != current?.id }.distinctBy { it.id }
                }
                waveTracks.addAll(if (finalNew.isNotEmpty()) finalNew else newTracks)
                newTracks.forEach { t ->
                    nextResponse.batchId?.let { b -> waveTrackBatchMap[t.id] = b }
                }
                nextResponse.sequence.forEach { item ->
                    val t = item.track
                    if (item.liked && t != null) {
                        onTrackLiked?.invoke(t.id)
                    }
                }
                waveCurrentIndex = 0
                isWaveMode = true
                println("🌊 Волна: Новая пачка из ${waveTracks.size} треков загружена после skip! Запуск первого трека.")

                if (waveTracks.isNotEmpty()) {
                    playWaveTrack(0, source = TrackPlaySource.NEXT)
                } else {
                    playbackManager?.resetLoadingFlags()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("WaveManager: Ошибка при skip в Волне: ${e.message}")
                e.printStackTrace()
                onError?.invoke("Ошибка при skip в Волне: ${e.message}")
                playbackManager?.resetLoadingFlags()
            } finally {
                isWaveLoading = false
            }
        }
    }

    /**
     * 🌊 Обработка естественного окончания трека (Next не нажималась):
     * Если в истории вперед есть треки (пользователь нажимал назад) -> играем следующий из истории.
     * Иначе: если в текущей пачке еще есть треки (1..4) -> играем следующий.
     * Если доиграл 5-й трек -> загружаем новую пачку из 5 треков.
     */
    override fun onWaveTrackFinished(crossfadeMs: Long) {
        if (!isWaveMode) return

        val current = currentWaveTrack
        if (current != null) {
            if (wavePastTracks.lastOrNull()?.id != current.id) {
                wavePastTracks.add(current)
                if (wavePastTracks.size > 5) {
                    wavePastTracks.removeAt(0)
                }
            }

            // Накапливаем фидбек trackFinished для завершившегося трека Волны
            val albumId = current.albums.firstOrNull()?.id ?: 0
            val trackIdWithAlbum = "${current.id}:$albumId"
            val bId = waveTrackBatchMap[current.id] ?: waveBatchId ?: ""
            val playedSec = ((playbackManager?.durationMs ?: 0L).toDouble() / 1000.0).coerceAtLeast(0.0)
            val lengthSec = if (current.durationMs > 0) current.durationMs.toDouble() / 1000.0 else playedSec
            val startTs = trackStartTimestamps[current.id] ?: currentIsoUtcTimestamp()
            val playId = trackPlayIds[current.id] ?: generatePlayUuid()
            val radioSessionId = waveRadioSessionId ?: ynisonWaveSessionId

            // Отправляем /plays со статусом finish
            if (!radioSessionId.isNullOrBlank() && bId.isNotBlank()) {
                scope.launch {
                    repository.sendPlayReport(
                        trackId = current.id,
                        albumId = albumId.toString(),
                        radioSessionId = radioSessionId,
                        batchId = bId,
                        totalPlayedSeconds = playedSec,
                        endPositionSeconds = playedSec,
                        trackLengthSeconds = lengthSec,
                        startTimestamp = startTs,
                        changeReason = "finish",
                        playId = playId
                    )
                }
            }

            pendingWaveFeedbacks.add(
                RotorFeedbackItem(
                    batchId = bId,
                    trackId = trackIdWithAlbum,
                    eventType = "trackFinished",
                    totalPlayedSeconds = playedSec,
                    trackLengthSeconds = lengthSec
                )
            )
        }

        // Очищаем из waveFutureTracks любые вхождения текущего доигравшего трека
        while (waveFutureTracks.isNotEmpty() && waveFutureTracks.first().id == current?.id) {
            waveFutureTracks.removeAt(0)
        }

        if (waveFutureTracks.isNotEmpty()) {
            val nextTrack = waveFutureTracks.removeAt(0)
            println("🌊 Волна: трек доиграл. Переход к следующему из истории: ${nextTrack.title}")
            playWaveTrackDirect(nextTrack, source = TrackPlaySource.PLAY, crossfadeMs = crossfadeMs)
            return
        }

        if (waveCurrentIndex + 1 < waveTracks.size) {
            var nextIndex = waveCurrentIndex + 1
            while (nextIndex < waveTracks.size && waveTracks[nextIndex].id == current?.id) {
                nextIndex++
            }
            if (nextIndex < waveTracks.size) {
                println("🌊 Волна: трек доиграл. Переходим к следующему треку пачки ($nextIndex/${waveTracks.size})")
                playWaveTrack(nextIndex, source = TrackPlaySource.PLAY, crossfadeMs = crossfadeMs)

                // Проверяем остаток очереди: если осталось <= 4 треков впереди, в фоне дозагружаем следующую пачку
                val remainingAhead = waveTracks.size - (nextIndex + 1)
                if (remainingAhead <= 4) {
                    replenishWaveAhead(current, waveTracks[nextIndex])
                }
                return
            }
        }

        println("🌊 Волна: вся пачка из ${waveTracks.size} треков доиграла. Запрашиваем следующую пачку...")
        loadNextWaveBatchAfterNaturalCompletion(current, crossfadeMs = crossfadeMs)
    }

    private fun replenishWaveAhead(finishedTrack: FullTrackInfo?, currentPlayingTrack: FullTrackInfo) {
        val radioSessionId = waveRadioSessionId ?: ynisonWaveSessionId ?: return
        if (replenishmentJob?.isActive == true) return
        replenishmentJob = scope.launch {
            try {
                val feedbacksToSend = pendingWaveFeedbacks.toList()
                pendingWaveFeedbacks.clear()

                val currentAlbumId = currentPlayingTrack.albums.firstOrNull()?.id ?: 0
                val queueTransition = listOf("${currentPlayingTrack.id}:$currentAlbumId")

                println("🌊 Волна: фоновое пополнение очереди (${feedbacksToSend.size} фидбеков, queue: $queueTransition)...")
                val nextResponse = repository.getRotorNextTracks(
                    radioSessionId = radioSessionId,
                    feedbacks = feedbacksToSend,
                    remainingQueue = queueTransition
                )

                waveBatchId = nextResponse.batchId
                val newTracks = nextResponse.sequence.mapNotNull { it.track }
                val existingIds = waveTracks.map { it.id }.toSet()
                val pastIds = wavePastTracks.map { it.id }.toSet()
                val forbiddenIds = buildSet {
                    addAll(existingIds)
                    addAll(pastIds)
                    finishedTrack?.let { add(it.id) }
                    add(currentPlayingTrack.id)
                }
                val filteredNew = newTracks.filter { it.id !in forbiddenIds }.distinctBy { it.id }
                val finalNew = if (filteredNew.isNotEmpty()) {
                    filteredNew
                } else {
                    newTracks.filter { it.id !in existingIds && it.id != finishedTrack?.id && it.id != currentPlayingTrack.id }.distinctBy { it.id }
                }
                waveTracks.addAll(finalNew)
                newTracks.forEach { t ->
                    nextResponse.batchId?.let { b -> waveTrackBatchMap[t.id] = b }
                }
                nextResponse.sequence.forEach { item ->
                    val t = item.track
                    if (item.liked && t != null) {
                        onTrackLiked?.invoke(t.id)
                    }
                }
                println("🌊 Волна: Очередь успешно пополнена! Всего треков: ${waveTracks.size}")
                playbackManager?.onQueueUpdated()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("WaveManager: Ошибка при фоновом пополнении Волны: ${e.message}")
            }
        }
    }

    fun loadNextWaveBatchAfterNaturalCompletion(lastFinishedTrack: FullTrackInfo? = null, crossfadeMs: Long = 0L) {
        if (!isWaveMode) return
        val radioSessionId = waveRadioSessionId ?: ynisonWaveSessionId
        if (radioSessionId == null) {
            loadInitialWave(autoPlay = true, source = TrackPlaySource.PLAY)
            return
        }

        playbackManager?.setLoadingFlags(next = false, prev = false, play = true)
        replenishmentJob?.cancel()
        replenishmentJob = scope.launch {
            isWaveLoading = true
            try {
                val feedbacksToSend = pendingWaveFeedbacks.toList()
                pendingWaveFeedbacks.clear()

                val queueList = emptyList<String>()

                println("🌊 Волна: пачка завершена, отправка ${feedbacksToSend.size} накопленных фидбеков...")
                val nextResponse = repository.getRotorNextTracks(
                    radioSessionId = radioSessionId,
                    feedbacks = feedbacksToSend,
                    remainingQueue = queueList
                )

                waveBatchId = nextResponse.batchId
                waveTracks.clear()
                val newTracks = nextResponse.sequence.mapNotNull { it.track }
                val pastIds = wavePastTracks.map { it.id }.toSet()
                val forbiddenIds = buildSet {
                    addAll(pastIds)
                    lastFinishedTrack?.let { add(it.id) }
                }
                val filteredNew = newTracks.filter { it.id !in forbiddenIds }.distinctBy { it.id }
                val finalNew = if (filteredNew.isNotEmpty()) {
                    filteredNew
                } else {
                    newTracks.filter { it.id != lastFinishedTrack?.id }.distinctBy { it.id }
                }
                waveTracks.addAll(if (finalNew.isNotEmpty()) finalNew else newTracks)
                newTracks.forEach { t ->
                    nextResponse.batchId?.let { bId -> waveTrackBatchMap[t.id] = bId }
                }
                nextResponse.sequence.forEach { item ->
                    val t = item.track
                    if (item.liked && t != null) {
                        onTrackLiked?.invoke(t.id)
                    }
                }
                waveCurrentIndex = 0
                isWaveMode = true
                println("🌊 Волна: Новая пачка из ${waveTracks.size} треков получена после завершения предыдущей! Запуск.")

                if (waveTracks.isNotEmpty()) {
                    playWaveTrack(0, source = TrackPlaySource.PLAY, crossfadeMs = crossfadeMs)
                } else {
                    playbackManager?.resetLoadingFlags()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("WaveManager: Ошибка при загрузке следующей пачки Волны: ${e.message}")
                e.printStackTrace()
                onError?.invoke("Ошибка при загрузке следующей пачки Волны: ${e.message}")
                playbackManager?.resetLoadingFlags()
            } finally {
                isWaveLoading = false
            }
        }
    }

    /**
     * Сбросить режим Волны при запуске трека не из Волны (из поиска или плейлиста).
     */
    fun resetWaveMode() {
        isWaveMode = false
        replenishmentJob?.cancel()
        pendingWaveFeedbacks.clear()
        trackStartTimestamps.clear()
        trackPlayIds.clear()
    }
}
