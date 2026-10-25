package io.github.audiz.api

import io.github.audiz.models.*

/**
 * 🏛️ Фасад репозитория Яндекс Музыки (MusicRepository).
 * Делегирует выполнение специализированным API-клиентам:
 * - [ApiSessionContext]: управление Ktor-клиентом, токеном авторизации и заголовками
 * - [SearchClient]: поиск, получение треков артиста, детализация треков
 * - [PlaylistsClient]: плейлисты, альбомы, лайки/дизлайки, история
 * - [MediaStreamClient]: загрузка аудиопотоков, получение метаданных потоков, обложек
 * - [WaveClient]: сессии «Моей Волны», Rotor API, фидбеки и отчеты о прослушивании
 */
class MusicRepository(
    val context: ApiSessionContext = ApiSessionContext(),
    val searchClient: SearchClient = SearchClient(context),
    val playlistsClient: PlaylistsClient = PlaylistsClient(context, searchClient),
    val mediaStreamClient: MediaStreamClient = MediaStreamClient(context),
    val ynisonService: YnisonService = YnisonService(context.client),
    val waveClient: WaveClient = WaveClient(context, ynisonService)
) {
    // === Авторизация и сессия ===

    fun getAccessToken(): String = context.accessToken

    fun getSessionToken(): String = context.accessToken

    fun updateAccessToken(newToken: String) {
        context.updateAccessToken(newToken)
    }

    fun updateSessionCookie(newCookie: String) {
        updateAccessToken(newCookie)
    }

    suspend fun getUserId(): String? = context.getUserId()

    suspend fun getAccountUid(): String? = context.getAccountUid()

    suspend fun getAccountPlusInfo(): Boolean = context.getAccountPlusInfo()

    // === Отметки «Мне нравится» / «Не нравится» ===

    suspend fun likeTrack(trackIdWithAlbum: String): Boolean =
        playlistsClient.likeTrack(trackIdWithAlbum)

    suspend fun unlikeTrack(trackIdWithAlbum: String): Boolean =
        playlistsClient.unlikeTrack(trackIdWithAlbum)

    suspend fun dislikeTrack(trackIdWithAlbum: String): Boolean =
        playlistsClient.dislikeTrack(trackIdWithAlbum)

    suspend fun undislikeTrack(trackIdWithAlbum: String): Boolean =
        playlistsClient.undislikeTrack(trackIdWithAlbum)

    // === Поиск и информация о треках ===

    suspend fun searchInstant(query: String): YandexMusicResponse =
        searchClient.searchInstant(query)

    suspend fun getTrackIds(artistId: String): YandexArtistTrackIdsResponse =
        searchClient.getTrackIds(artistId)

    suspend fun getTracksDetails(trackIds: List<String>): YandexTracksDetailsResponse =
        searchClient.getTracksDetails(trackIds)

    // === Плейлисты, альбомы и история ===

    suspend fun getLikesPlaylistUuid(): String =
        playlistsClient.getLikesPlaylistUuid()

    suspend fun getPlaylistWithTracksByUuid(uuid: String): Pair<List<String>, List<FullTrackInfo>> =
        playlistsClient.getPlaylistWithTracksByUuid(uuid)

    suspend fun getPlaylistTrackIdsByUuid(uuid: String): List<String> =
        playlistsClient.getPlaylistTrackIdsByUuid(uuid)

    suspend fun getPlaylistWithTracksByUidKind(uid: Long, kind: Long): Pair<List<String>, List<FullTrackInfo>> =
        playlistsClient.getPlaylistWithTracksByUidKind(uid, kind)

    suspend fun getAlbumWithTracks(albumId: Long): Pair<List<String>, List<FullTrackInfo>> =
        playlistsClient.getAlbumWithTracks(albumId)

    suspend fun getPlaylistTrackIds(uid: Long, kind: Long): List<String> =
        playlistsClient.getPlaylistTrackIds(uid, kind)

    suspend fun getPersonalPlaylists(): List<PersonalPlaylistItemData> =
        playlistsClient.getPersonalPlaylists()

    suspend fun getUserPlaylists(): List<PlaylistInfo> =
        playlistsClient.getUserPlaylists()

    suspend fun getPlaylistDetails(uid: Long, kind: Long): YandexPlaylistDetails? =
        playlistsClient.getPlaylistDetails(uid, kind)

    suspend fun addTrackToPlaylist(kind: Long, trackId: String, albumId: Long? = null): Boolean =
        playlistsClient.addTrackToPlaylist(kind, trackId, albumId)

    suspend fun removeTrackFromPlaylist(kind: Long, trackId: String): Boolean =
        playlistsClient.removeTrackFromPlaylist(kind, trackId)

    suspend fun createPlaylist(title: String, visibility: String = "public"): PlaylistInfo? =
        playlistsClient.createPlaylist(title, visibility)

    suspend fun renamePlaylist(kind: Long, newTitle: String, uid: Long? = null): PlaylistInfo? =
        playlistsClient.renamePlaylist(kind, newTitle, uid)

    suspend fun getHistoryTrackIds(): List<String> =
        playlistsClient.getHistoryTrackIds()

    // === Потоки и медиа ===

    suspend fun downloadTrackAudio(
        trackId: String,
        quality: String = "1",
        customTimestamp: String? = null
    ): DownloadedTrackAudio =
        mediaStreamClient.downloadTrackAudio(trackId, quality, customTimestamp)

    suspend fun getBatchTrackDownloadInfo(
        trackIds: List<String>,
        quality: String = "1",
        customTimestamp: String? = null
    ): Map<String, BatchTrackDownloadInfo> =
        mediaStreamClient.getBatchTrackDownloadInfo(trackIds, quality, customTimestamp)

    suspend fun downloadTrackAudioFromInfo(info: BatchTrackDownloadInfo): DownloadedTrackAudio =
        mediaStreamClient.downloadTrackAudioFromInfo(info)

    suspend fun getTrackStreamMeta(
        trackId: String,
        quality: String = "1",
        customTimestamp: String? = null
    ): TrackStreamMeta =
        mediaStreamClient.getTrackStreamMeta(trackId, quality, customTimestamp)

    suspend fun fetchEncryptedRange(directUrl: String, startByte: Long, endByte: Long): ByteArray =
        mediaStreamClient.fetchEncryptedRange(directUrl, startByte, endByte)

    suspend fun fetchImageBytes(url: String): ByteArray? =
        mediaStreamClient.fetchImageBytes(url)

    // === Моя Волна (Rotor & Ynison) ===

    suspend fun fetchYnisonWaveSessionId(): String? =
        waveClient.fetchYnisonWaveSessionId()

    val lastYnisonHost: String? get() = waveClient.lastYnisonHost

    val lastYnisonError: String? get() = waveClient.lastYnisonError

    suspend fun cloneRotorSessionRaw(waveSessionId: String): String =
        waveClient.cloneRotorSessionRaw(waveSessionId)

    fun parseRotorResponse(jsonStr: String): RotorSessionResponse =
        waveClient.parseRotorResponse(jsonStr)

    suspend fun cloneRotorSession(waveSessionId: String): RotorSessionResponse =
        waveClient.cloneRotorSession(waveSessionId)

    suspend fun createRotorSessionRaw(seeds: List<String>): String =
        waveClient.createRotorSessionRaw(seeds)

    suspend fun createRotorSession(seeds: List<String>): RotorSessionResponse =
        waveClient.createRotorSession(seeds)

    suspend fun getRotorNextTracks(
        radioSessionId: String,
        feedbacks: List<RotorFeedbackItem>,
        remainingQueue: List<String> = emptyList()
    ): RotorSessionResponse =
        waveClient.getRotorNextTracks(radioSessionId, feedbacks, remainingQueue)

    suspend fun getRotorNextTracks(
        radioSessionId: String,
        batchId: String,
        currentTrackIdWithAlbum: String,
        playedSeconds: Double,
        remainingQueue: List<String> = emptyList(),
        eventType: String = "skip"
    ): RotorSessionResponse =
        waveClient.getRotorNextTracks(
            radioSessionId = radioSessionId,
            batchId = batchId,
            currentTrackIdWithAlbum = currentTrackIdWithAlbum,
            playedSeconds = playedSeconds,
            remainingQueue = remainingQueue,
            eventType = eventType
        )

    suspend fun getRotorNextTracksRaw(
        radioSessionId: String,
        feedbacks: List<RotorFeedbackItem>,
        remainingQueue: List<String> = emptyList()
    ): String =
        waveClient.getRotorNextTracksRaw(radioSessionId, feedbacks, remainingQueue)

    suspend fun sendRotorFeedbackTrackStarted(
        radioSessionId: String,
        batchId: String,
        trackIdWithAlbum: String,
        timestamp: String = currentIsoUtcTimestamp()
    ): Boolean =
        waveClient.sendRotorFeedbackTrackStarted(radioSessionId, batchId, trackIdWithAlbum, timestamp)

    suspend fun sendPlayReport(
        trackId: String,
        albumId: String,
        radioSessionId: String,
        batchId: String,
        totalPlayedSeconds: Double,
        endPositionSeconds: Double,
        trackLengthSeconds: Double,
        startTimestamp: String,
        endTimestamp: String = currentIsoUtcTimestamp(),
        changeReason: String = "finish",
        playId: String = generatePlayUuid()
    ): Boolean =
        waveClient.sendPlayReport(
            trackId = trackId,
            albumId = albumId,
            radioSessionId = radioSessionId,
            batchId = batchId,
            totalPlayedSeconds = totalPlayedSeconds,
            endPositionSeconds = endPositionSeconds,
            trackLengthSeconds = trackLengthSeconds,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            changeReason = changeReason,
            playId = playId
        )
}
