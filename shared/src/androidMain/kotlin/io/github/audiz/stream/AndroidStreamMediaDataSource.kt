package io.github.audiz.stream

import android.media.MediaDataSource
import io.github.audiz.api.MusicRepository
import io.github.audiz.api.decryptAesCtrChunk
import io.github.audiz.models.TrackStreamMeta
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 🎵 Нативный Android MediaDataSource для потокового воспроизведения аудио чанками.
 *
 * Преимущества:
 * - Прямое взаимодействие с MediaPlayer без локального HTTP-сервера
 * - Мгновенный запуск воспроизведения по мере готовности первого чанка (256 КБ)
 * - Экономия мобильного трафика: при быстром перелистывании треков загружаются
 *   только фактически запрошенные плером байты, а не весь файл целиком
 * - Поддержка произвольной перемотки (seek) без ожидания выкачивания всего трека
 */
class AndroidStreamMediaDataSource(
    private val repository: MusicRepository,
    private val trackId: String,
    private val quality: String,
    private val onMetaLoaded: ((TrackStreamMeta) -> Unit)? = null
) : MediaDataSource() {

    private val isClosed = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var meta: TrackStreamMeta? = null
    private val metaLock = Any()

    // Размер чанка: 256 КБ (быстрый старт и низкая нагрузка на сеть)
    private val chunkSize = 256 * 1024L
    private val chunkCache = ConcurrentHashMap<Long, ByteArray>()
    private val prefetchJobs = ConcurrentHashMap<Long, Job>()

    private fun getOrFetchMeta(): TrackStreamMeta? {
        meta?.let { return it }
        if (isClosed.get()) return null

        synchronized(metaLock) {
            meta?.let { return it }
            if (isClosed.get()) return null

            return try {
                runBlocking(Dispatchers.IO) {
                    val fetched = repository.getTrackStreamMeta(trackId, quality)
                    meta = fetched
                    onMetaLoaded?.invoke(fetched)
                    fetched
                }
            } catch (e: Exception) {
                println("AndroidStreamMediaDataSource: Ошибка получения метаданных для $trackId: ${e.message}")
                null
            }
        }
    }

    override fun getSize(): Long {
        if (isClosed.get()) return -1L
        val m = getOrFetchMeta() ?: return -1L
        return m.totalSizeBytes
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (isClosed.get()) return -1
        if (size <= 0) return 0

        val m = getOrFetchMeta() ?: return -1
        val totalSize = m.totalSizeBytes
        if (totalSize <= 0L || position >= totalSize) return -1

        val bytesToRead = minOf(size.toLong(), totalSize - position).toInt()
        if (bytesToRead <= 0) return 0

        var totalCopied = 0
        var currentPos = position
        var currentOffset = offset
        var remaining = bytesToRead

        while (remaining > 0 && !isClosed.get()) {
            val chunkIndex = currentPos / chunkSize
            val offsetInChunk = (currentPos % chunkSize).toInt()
            val chunkData = getOrFetchChunk(m, chunkIndex) ?: break

            val available = chunkData.size - offsetInChunk
            if (available <= 0) break

            val toCopy = minOf(remaining, available)
            System.arraycopy(chunkData, offsetInChunk, buffer, currentOffset, toCopy)

            totalCopied += toCopy
            currentPos += toCopy
            currentOffset += toCopy
            remaining -= toCopy

            // Запускаем упреждающую загрузку следующего чанка в фоне
            val nextChunkIndex = chunkIndex + 1L
            val nextStart = nextChunkIndex * chunkSize
            if (nextStart < totalSize && !chunkCache.containsKey(nextChunkIndex) && !prefetchJobs.containsKey(nextChunkIndex)) {
                prefetchNextChunk(m, nextChunkIndex)
            }
        }

        return if (totalCopied > 0) totalCopied else -1
    }

    private fun getOrFetchChunk(m: TrackStreamMeta, chunkIndex: Long): ByteArray? {
        chunkCache[chunkIndex]?.let { return it }
        if (isClosed.get()) return null

        val activeJob = prefetchJobs[chunkIndex]
        if (activeJob != null && activeJob.isActive) {
            try {
                runBlocking(Dispatchers.IO) {
                    activeJob.join()
                }
            } catch (_: Exception) {}
            chunkCache[chunkIndex]?.let { return it }
        }

        if (isClosed.get()) return null

        return synchronized(this) {
            chunkCache[chunkIndex]?.let { return it }
            if (isClosed.get()) return null

            try {
                runBlocking(Dispatchers.IO) {
                    val startByte = chunkIndex * chunkSize
                    val endByte = minOf(startByte + chunkSize - 1L, m.totalSizeBytes - 1L)
                    val enc = repository.fetchEncryptedRange(m.directUrl, startByte, endByte)
                    val dec = decryptAesCtrChunk(enc, m.aesKey, startByte)
                    chunkCache[chunkIndex] = dec
                    dec
                }
            } catch (e: Exception) {
                if (!isClosed.get()) {
                    println("AndroidStreamMediaDataSource: Ошибка загрузки чанка $chunkIndex: ${e.message}")
                }
                null
            }
        }
    }

    private fun prefetchNextChunk(m: TrackStreamMeta, chunkIndex: Long) {
        if (isClosed.get()) return
        val job = scope.launch {
            try {
                val startByte = chunkIndex * chunkSize
                val endByte = minOf(startByte + chunkSize - 1L, m.totalSizeBytes - 1L)
                val enc = repository.fetchEncryptedRange(m.directUrl, startByte, endByte)
                if (isActive && !isClosed.get()) {
                    val dec = decryptAesCtrChunk(enc, m.aesKey, startByte)
                    chunkCache[chunkIndex] = dec
                }
            } catch (_: Exception) {
            } finally {
                prefetchJobs.remove(chunkIndex)
            }
        }
        prefetchJobs[chunkIndex] = job
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            scope.cancel()
            prefetchJobs.values.forEach { it.cancel() }
            prefetchJobs.clear()
            chunkCache.clear()
        }
    }
}
