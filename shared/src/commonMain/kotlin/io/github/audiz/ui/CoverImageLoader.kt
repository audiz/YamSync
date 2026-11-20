package io.github.audiz.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import io.github.audiz.DispatcherIO
import io.github.audiz.api.MusicRepository
import io.github.audiz.getLocalCoverArtUrl
import io.github.audiz.loadLocalCoverBytes
import io.github.audiz.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * 🖼️ Загрузчик и кэш обложек альбомов и треков.
 *
 * Ключ кэша — итоговый URL нужного размера (или путь локального файла), поэтому
 * обложки разных размеров не подменяют друг друга. Одинаковые параллельные запросы
 * объединяются в одну загрузку, а обложку следующего трека можно загрузить заранее.
 */
object CoverImageLoader {
    private const val MEMORY_CACHE_LIMIT = 64

    private val cacheLock = Any()
    private val memoryCache = LinkedHashMap<String, ImageBitmap>()
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    private val requestedSizes = LinkedHashSet<Int>()
    private val loaderScope = CoroutineScope(SupervisorJob() + DispatcherIO)

    var repository: MusicRepository? = null

    /**
     * Форматирование шаблона URI от Яндекс Музыки (например avatars.yandex.net/.../%%) в прямой URL нужного разрешения
     * Для локальных файлов возвращает локальный URL к обложке (file://...).
     */
    fun formatCoverUrl(uri: String?, size: Int = 400): String? {
        if (uri.isNullOrBlank()) return null
        var clean = uri.trim()
        if (isLocal(clean)) {
            return getLocalCoverArtUrl(clean) ?: clean
        }
        if (clean.contains("%%")) {
            clean = clean.replace("%%", "${size}x${size}")
        }
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        return clean
    }

    private fun isLocal(uri: String): Boolean =
        uri.startsWith("local:") || uri.startsWith("file:") || uri.startsWith("/")

    /** Ключ кэша без обращения к диску: локальная обложка — по исходному пути, сетевая — по URL размера. */
    private fun cacheKey(coverUri: String?, size: Int): String? {
        val trimmed = coverUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isLocal(trimmed)) return trimmed
        return formatCoverUrl(trimmed, size)
    }

    /** Обложка из памяти без загрузки (null, если её ещё нет в кэше). */
    fun peek(coverUri: String?, size: Int = 400): ImageBitmap? {
        val key = cacheKey(coverUri, size) ?: return null
        return synchronized(cacheLock) {
            val bitmap = memoryCache.remove(key)
            if (bitmap != null) {
                memoryCache[key] = bitmap
            }
            bitmap
        }
    }

    /**
     * Асинхронная загрузка обложки трека с кэшированием в оперативной памяти
     */
    suspend fun loadCover(coverUri: String?, size: Int = 400): ImageBitmap? {
        val key = cacheKey(coverUri, size) ?: return null
        val trimmed = coverUri!!.trim()
        val request = synchronized(cacheLock) {
            requestedSizes.add(size)
            val cached = memoryCache.remove(key)
            if (cached != null) {
                memoryCache[key] = cached
                return cached
            }
            inFlight[key] ?: loaderScope.async { fetchAndDecode(key, trimmed) }.also { deferred ->
                inFlight[key] = deferred
                deferred.invokeOnCompletion { synchronized(cacheLock) { if (inFlight[key] === deferred) inFlight.remove(key) } }
            }
        }
        return request.await()
    }

    /** Заранее загрузить обложку (например, следующего трека) во всех размерах, которые сейчас показывает UI. */
    fun prefetch(coverUri: String?) {
        if (coverUri.isNullOrBlank()) return
        val sizes = synchronized(cacheLock) { requestedSizes.toList() }
        for (size in sizes) {
            val key = cacheKey(coverUri, size) ?: continue
            val trimmed = coverUri.trim()
            synchronized(cacheLock) {
                if (memoryCache.containsKey(key) || inFlight.containsKey(key)) return@synchronized
                val deferred = loaderScope.async { fetchAndDecode(key, trimmed) }
                inFlight[key] = deferred
                deferred.invokeOnCompletion { synchronized(cacheLock) { if (inFlight[key] === deferred) inFlight.remove(key) } }
            }
        }
    }

    private suspend fun fetchAndDecode(key: String, trimmed: String): ImageBitmap? {
        val bytes = try {
            if (isLocal(trimmed)) loadLocalCoverBytes(trimmed)
            else repository?.fetchImageBytes(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("CoverImageLoader: Ошибка загрузки обложки $key: ${e.message}")
            null
        }
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val bitmap = bytes.decodeToImageBitmap()
            synchronized(cacheLock) {
                memoryCache.remove(key)
                memoryCache[key] = bitmap
                while (memoryCache.size > MEMORY_CACHE_LIMIT) {
                    memoryCache.remove(memoryCache.keys.first())
                }
            }
            bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("CoverImageLoader: Ошибка декодирования обложки $key: ${e.message}")
            null
        }
    }
}

/**
 * 🎨 Composable-хэлпер для автоматической загрузки и реактивного обновления ImageBitmap.
 *
 * При смене обложки сразу показывает закэшированную картинку (без кадра со старой),
 * а если её ещё нет — заглушку до окончания загрузки.
 */
@Composable
fun rememberCoverBitmap(coverUri: String?, size: Int = 400): ImageBitmap? {
    var bitmap by remember(coverUri, size) { mutableStateOf(CoverImageLoader.peek(coverUri, size)) }
    LaunchedEffect(coverUri, size) {
        if (bitmap == null) bitmap = CoverImageLoader.loadCover(coverUri, size)
    }
    return bitmap
}
