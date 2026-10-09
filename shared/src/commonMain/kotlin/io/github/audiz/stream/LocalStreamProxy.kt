package io.github.audiz.stream

import io.github.audiz.api.MusicRepository

/**
 * 🌐 Кроссплатформенный локальный HTTP стриминг-прокси (Zero Latency Streaming).
 * Запускает внутренний легковесный сервер на 127.0.0.1, позволяя плееру запрашивать
 * аудиопоток по кусочкам (HTTP Range Requests).
 */
expect object LocalStreamProxy {
    fun start(
        repository: MusicRepository,
        getMusicStoragePath: () -> String,
        isRecordToDisk: () -> Boolean,
        onTrackSaved: ((trackId: String, filePath: String) -> Unit)? = null
    )
    fun stop()
    fun getStreamUrl(trackId: String, quality: String, title: String, artist: String): String
    fun isRunning(): Boolean
    suspend fun preloadTrack(trackId: String, quality: String): String
    fun onTrackCompleted(trackId: String, quality: String)
}
