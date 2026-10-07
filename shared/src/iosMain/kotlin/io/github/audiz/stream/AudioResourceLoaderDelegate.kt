package io.github.audiz.stream

import io.github.audiz.api.MusicRepository
import io.github.audiz.api.decryptAesCtrChunk
import io.github.audiz.core.CoreLogger
import io.github.audiz.models.TrackStreamMeta
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.*
import platform.AVFoundation.*
import platform.Foundation.*
import platform.darwin.NSObject

/**
 * 🎵 Делегат загрузки медиа-ресурсов AVFoundation для iOS.
 *
 * Перехватывает Range-запросы от AVPlayer для кастомной схемы yamusic-stream://
 * и передает аудиоданные в плеер.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AudioResourceLoaderDelegate(
    private val repository: MusicRepository,
    private val trackId: String,
    private val quality: String,
    private val onMetaLoaded: ((TrackStreamMeta) -> Unit)? = null
) : NSObject(), AVAssetResourceLoaderDelegateProtocol {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeJobs = mutableMapOf<AVAssetResourceLoadingRequest, Job>()
    private var cachedMeta: TrackStreamMeta? = null

    private suspend fun getOrFetchMeta(): TrackStreamMeta? {
        cachedMeta?.let { return it }
        return try {
            val meta = repository.getTrackStreamMeta(trackId, quality)
            cachedMeta = meta
            if (meta != null) {
                onMetaLoaded?.invoke(meta)
            }
            meta
        } catch (e: Throwable) {
            CoreLogger.e("ResourceLoader", "Ошибка получения метаданных трека $trackId: ${e.message}")
            null
        }
    }

    private fun getUTI(codec: String): String {
        val raw = codec.substringBefore("-").lowercase()
        return when (raw) {
            "aac", "m4a" -> "com.apple.m4a-audio"
            "flac" -> "org.xiph.flac"
            "mp3" -> "public.mp3"
            else -> "com.apple.m4a-audio"
        }
    }

    @kotlinx.cinterop.ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest
    ): Boolean {
        val contentInfo = shouldWaitForLoadingOfRequestedResource.contentInformationRequest
        val dataRequest = shouldWaitForLoadingOfRequestedResource.dataRequest

        CoreLogger.d("ResourceLoader", "Запрос ресурса: contentInfo=${contentInfo != null}, dataRequest=${dataRequest != null} (offset=${dataRequest?.requestedOffset}, len=${dataRequest?.requestedLength})")

        val job = scope.launch {
            try {
                val meta = getOrFetchMeta() ?: run {
                    shouldWaitForLoadingOfRequestedResource.finishLoadingWithError(
                        NSError.errorWithDomain("io.github.audiz.stream", -1, null)
                    )
                    return@launch
                }

                // 1. Заполняем информацию о контенте (MIME-тип, длина, поддержка Range)
                contentInfo?.let { info ->
                    info.contentType = getUTI(meta.codec)
                    info.contentLength = meta.totalSizeBytes
                    info.byteRangeAccessSupported = true
                    CoreLogger.d("ResourceLoader", "Отправлен contentInfo: totalSize=${meta.totalSizeBytes}, uti=${info.contentType}")
                }

                // 2. Обрабатываем запрос байтов
                if (dataRequest != null) {
                    val totalSize = meta.totalSizeBytes
                    val reqOffset = dataRequest.requestedOffset
                    val reqLength = dataRequest.requestedLength

                    if (dataRequest.currentOffset >= totalSize) {
                        shouldWaitForLoadingOfRequestedResource.finishLoading()
                        return@launch
                    }

                    val targetEnd = if (dataRequest.requestsAllDataToEndOfResource) {
                        totalSize - 1L
                    } else {
                        minOf(reqOffset + reqLength - 1L, totalSize - 1L)
                    }

                    var pos = dataRequest.currentOffset
                    val chunkSize = 256 * 1024L

                    while (pos <= targetEnd && isActive) {
                        val chunkEnd = minOf(pos + chunkSize - 1L, targetEnd)
                        val enc = repository.fetchEncryptedRange(meta.directUrl, pos, chunkEnd)
                        val dec = decryptAesCtrChunk(enc, meta.aesKey, pos)

                        dec.usePinned { pinned ->
                            val nsData = NSData.create(bytes = pinned.addressOf(0), length = dec.size.toULong())
                            dataRequest.respondWithData(nsData)
                        }

                        pos = chunkEnd + 1L
                    }

                    if (isActive) {
                        shouldWaitForLoadingOfRequestedResource.finishLoading()
                    }
                } else {
                    shouldWaitForLoadingOfRequestedResource.finishLoading()
                }
            } catch (_: CancellationException) {
                // Запрос отменен AVFoundation (например, при смене позиции или скипе)
            } catch (e: Throwable) {
                CoreLogger.e("ResourceLoader", "Ошибка обработки loadingRequest: ${e.message}")
                shouldWaitForLoadingOfRequestedResource.finishLoadingWithError(
                    NSError.errorWithDomain("io.github.audiz.stream", -2, null)
                )
            } finally {
                activeJobs.remove(shouldWaitForLoadingOfRequestedResource)
            }
        }

        activeJobs[shouldWaitForLoadingOfRequestedResource] = job
        return true
    }

    @kotlinx.cinterop.ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        didCancelLoadingRequest: AVAssetResourceLoadingRequest
    ) {
        CoreLogger.d("ResourceLoader", "Отмена запроса AVFoundation: offset=${didCancelLoadingRequest.dataRequest?.requestedOffset}")
        val job = activeJobs.remove(didCancelLoadingRequest)
        job?.cancel()
    }

    fun cancelAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        scope.cancel()
    }
}
