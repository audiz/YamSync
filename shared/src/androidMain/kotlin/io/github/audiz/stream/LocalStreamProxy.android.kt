package io.github.audiz.stream

import io.github.audiz.api.MusicRepository
import io.github.audiz.api.decryptAesCtrChunk
import io.github.audiz.localFileExists
import io.github.audiz.models.TrackStreamMeta
import io.github.audiz.sanitizeKeepSpaces
import io.github.audiz.saveTrackFile
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

actual object LocalStreamProxy {
    @Volatile private var isStarted: Boolean = false
    @Volatile private var repository: MusicRepository? = null
    @Volatile private var getMusicStoragePathFunc: (() -> String)? = null
    @Volatile private var isRecordToDiskFunc: (() -> Boolean)? = null
    @Volatile private var onTrackSavedCallback: ((trackId: String, filePath: String) -> Unit)? = null

    private val proxyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val activeDataSources = ConcurrentHashMap<String, AndroidStreamMediaDataSource>()

    actual fun start(
        repository: MusicRepository,
        getMusicStoragePath: () -> String,
        isRecordToDisk: () -> Boolean,
        onTrackSaved: ((trackId: String, filePath: String) -> Unit)?
    ) {
        this.repository = repository
        this.getMusicStoragePathFunc = getMusicStoragePath
        this.isRecordToDiskFunc = isRecordToDisk
        this.onTrackSavedCallback = onTrackSaved
        this.isStarted = true
        println("⚡ [StreamProxy] Android LocalStreamProxy активирован (MediaDataSource)")
    }

    actual fun stop() {
        isStarted = false
        repository = null
        activeDownloadJobs.values.forEach { it.cancel() }
        activeDownloadJobs.clear()
        activeDataSources.values.forEach { it.close() }
        activeDataSources.clear()
        println("⚡ [StreamProxy] Android LocalStreamProxy остановлен")
    }

    actual fun isRunning(): Boolean = isStarted && repository != null

    actual suspend fun preloadTrack(trackId: String, quality: String): String {
        val repo = repository ?: return ""
        return try {
            val meta = repo.getTrackStreamMeta(trackId, quality)
            val cleanTrackId = trackId.removePrefix("local:")
            val rawExt = meta.codec.substringBefore("-")
            val ext = if (rawExt.equals("aac", ignoreCase = true)) "m4a" else rawExt
            "yamusic-stream://stream/$cleanTrackId.$ext?quality=$quality"
        } catch (_: Exception) {
            ""
        }
    }

    actual fun onTrackCompleted(trackId: String, quality: String) {
        // Android использует потоковый MediaDataSource
    }

    actual fun getStreamUrl(trackId: String, quality: String, title: String, artist: String): String {
        val cleanTrackId = trackId.removePrefix("local:")
        val ext = if (quality == "2") "flac" else "m4a"
        val encTitle = encodeParam(title)
        val encArtist = encodeParam(artist)
        return "yamusic-stream://stream/$cleanTrackId.$ext?quality=$quality&title=$encTitle&artist=$encArtist"
    }

    fun createMediaDataSource(
        trackId: String,
        quality: String,
        title: String,
        artist: String
    ): AndroidStreamMediaDataSource? {
        val repo = repository ?: return null
        val dataSource = AndroidStreamMediaDataSource(
            repository = repo,
            trackId = trackId,
            quality = quality,
            onMetaLoaded = { meta ->
                if (isRecordToDiskFunc?.invoke() == true) {
                    triggerBackgroundSave(meta, trackId, quality, title, artist)
                }
            }
        )
        activeDataSources[trackId] = dataSource
        return dataSource
    }

    fun releaseMediaDataSource(trackId: String) {
        activeDataSources.remove(trackId)?.close()
    }

    private fun triggerBackgroundSave(
        meta: TrackStreamMeta,
        trackId: String,
        quality: String,
        title: String,
        artist: String
    ) {
        val saveKey = "save:$trackId:$quality"
        if (activeDownloadJobs.containsKey(saveKey)) return

        // Отменяем фоновые сохранения предыдущих треков для экономии сети
        activeDownloadJobs.forEach { (key, oldJob) ->
            if (key != saveKey) {
                oldJob.cancel()
                activeDownloadJobs.remove(key)
            }
        }

        val job = proxyScope.launch {
            try {
                val storagePath = getMusicStoragePathFunc?.invoke() ?: return@launch
                val qualityFolder = if (quality == "2") "HQ" else "LQ"
                val basePath = "$storagePath/$qualityFolder"
                val rawExt = meta.codec.substringBefore("-")
                val ext = if (rawExt.equals("aac", ignoreCase = true)) "m4a" else rawExt
                val cleanArtist = artist.trim()
                val cleanTitle = title.trim()
                val fullFileName = sanitizeKeepSpaces(if (cleanArtist.isNotEmpty()) {
                    "$cleanArtist — $cleanTitle.$ext"
                } else {
                    "$cleanTitle.$ext"
                })

                val finalPath = "$basePath/${sanitizeKeepSpaces(cleanArtist)}/$fullFileName"
                if (localFileExists(finalPath)) {
                    println("⚡ [StreamProxy Android] Трек уже сохранен на диске: $finalPath")
                    onTrackSavedCallback?.invoke(trackId, finalPath)
                    return@launch
                }

                val repo = repository ?: return@launch
                val totalSize = meta.totalSizeBytes
                if (totalSize <= 0L) return@launch

                println("⚡ [StreamProxy Android] Старт фонового сохранения трека: $fullFileName")
                val tempFile = File.createTempFile("ya_save_", ".$ext")
                tempFile.deleteOnExit()

                val chunkSize = 512 * 1024L
                var pos = 0L

                FileOutputStream(tempFile).use { fos ->
                    while (pos < totalSize && isActive) {
                        val end = minOf(pos + chunkSize - 1L, totalSize - 1L)
                        val enc = repo.fetchEncryptedRange(meta.directUrl, pos, end)
                        val dec = decryptAesCtrChunk(enc, meta.aesKey, pos)
                        fos.write(dec)
                        pos = end + 1L
                    }
                }

                if (isActive && tempFile.length() == totalSize) {
                    val bytes = tempFile.readBytes()
                    saveTrackFile(basePath, cleanArtist, fullFileName, bytes)
                    println("⚡ [StreamProxy Android] Трек успешно сохранен на диск в фоне: $finalPath")
                    onTrackSavedCallback?.invoke(trackId, finalPath)
                }
                tempFile.delete()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                println("⚡ [StreamProxy Android] Ошибка фонового сохранения трека: ${e.message}")
            } finally {
                activeDownloadJobs.remove(saveKey)
            }
        }
        activeDownloadJobs[saveKey] = job
    }

    private fun encodeParam(s: String): String {
        return try {
            URLEncoder.encode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }

    fun decodeParam(s: String): String {
        return try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }
}
