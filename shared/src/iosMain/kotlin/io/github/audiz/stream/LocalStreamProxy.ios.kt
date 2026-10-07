package io.github.audiz.stream

import io.github.audiz.api.MusicRepository
import io.github.audiz.api.decryptAesCtrChunk
import io.github.audiz.core.CoreLogger
import io.github.audiz.localFileExists
import io.github.audiz.models.TrackStreamMeta
import io.github.audiz.sanitizeKeepSpaces
import io.github.audiz.saveTrackFile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.*
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object LocalStreamProxy {
    private var isStarted: Boolean = false
    private var repository: MusicRepository? = null
    private var getMusicStoragePathFunc: (() -> String)? = null
    private var isRecordToDiskFunc: (() -> Boolean)? = null
    private var onTrackSavedCallback: ((trackId: String, filePath: String) -> Unit)? = null

    private val proxyScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeDownloadJobs = mutableMapOf<String, Job>()

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
        CoreLogger.i("LocalStreamProxy", "iOS LocalStreamProxy активирован (AVAssetResourceLoaderDelegate)")
    }

    actual fun stop() {
        isStarted = false
        repository = null
        activeDownloadJobs.values.forEach { it.cancel() }
        activeDownloadJobs.clear()
        CoreLogger.d("LocalStreamProxy", "iOS LocalStreamProxy остановлен")
    }

    actual fun isRunning(): Boolean = isStarted && repository != null

    actual fun getStreamUrl(trackId: String, quality: String, title: String, artist: String): String {
        val cleanTrackId = trackId.removePrefix("local:")
        val ext = if (quality == "2") "flac" else "m4a"
        val encTitle = encodeQueryParam(title)
        val encArtist = encodeQueryParam(artist)
        return "yamusic-stream://stream/$cleanTrackId.$ext?quality=$quality&title=$encTitle&artist=$encArtist"
    }

    fun createResourceLoaderDelegate(
        trackId: String,
        quality: String,
        title: String,
        artist: String
    ): AudioResourceLoaderDelegate? {
        val repo = repository ?: return null
        return AudioResourceLoaderDelegate(
            repository = repo,
            trackId = trackId,
            quality = quality,
            onMetaLoaded = { meta ->
                if (isRecordToDiskFunc?.invoke() == true) {
                    triggerBackgroundSave(meta, trackId, quality, title, artist)
                }
            }
        )
    }

    private fun encodeQueryParam(param: String): String {
        return (param as platform.Foundation.NSString).stringByAddingPercentEncodingWithAllowedCharacters(
            NSCharacterSet.URLQueryAllowedCharacterSet
        ) ?: param
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

        // Отменяем фоновые сохранения предыдущих треков для приоритета текущего
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

                val finalPath = "$basePath/$cleanArtist/$fullFileName"
                if (localFileExists(finalPath)) {
                    CoreLogger.d("LocalStreamProxy", "Трек уже сохранен на диске: $finalPath")
                    onTrackSavedCallback?.invoke(trackId, finalPath)
                    return@launch
                }

                val repo = repository ?: return@launch
                val totalSize = meta.totalSizeBytes
                if (totalSize <= 0L) return@launch

                CoreLogger.i("LocalStreamProxy", "Старт фоновой загрузки трека: $fullFileName")
                val chunkSize = 512 * 1024L
                var pos = 0L

                val mutableData = NSMutableData.create(length = totalSize.toULong()) ?: return@launch

                while (pos < totalSize && isActive) {
                    val end = minOf(pos + chunkSize - 1L, totalSize - 1L)
                    val enc = repo.fetchEncryptedRange(meta.directUrl, pos, end)
                    val dec = decryptAesCtrChunk(enc, meta.aesKey, pos)

                    dec.usePinned { pinned ->
                        val chunkData = NSData.create(bytes = pinned.addressOf(0), length = dec.size.toULong())
                        mutableData.appendData(chunkData)
                    }
                    pos = end + 1L
                }

                if (isActive && mutableData.length.toLong() == totalSize) {
                    val bytes = ByteArray(totalSize.toInt())
                    bytes.usePinned { pinned ->
                        mutableData.getBytes(pinned.addressOf(0), length = totalSize.toULong())
                    }
                    saveTrackFile(basePath, cleanArtist, fullFileName, bytes)
                    CoreLogger.i("LocalStreamProxy", "Трек успешно сохранен на диск в фоне: $finalPath")
                    onTrackSavedCallback?.invoke(trackId, finalPath)
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                CoreLogger.e("LocalStreamProxy", "Ошибка фонового сохранения трека: ${e.message}")
            } finally {
                activeDownloadJobs.remove(saveKey)
            }
        }
        activeDownloadJobs[saveKey] = job
    }
}
