package io.github.audiz.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.audiz.DispatcherIO
import io.github.audiz.api.MusicRepository
import io.github.audiz.getLocalCoverArtUrl
import io.github.audiz.loadLocalCoverBytes
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * 🖼️ Загрузчик и кэш обложек альбомов и треков
 */
object CoverImageLoader {
    private val memoryCache = mutableMapOf<String, ImageBitmap>()
    var repository: MusicRepository? = null

    /**
     * Форматирование шаблона URI от Яндекс Музыки (например avatars.yandex.net/.../%%) в прямой URL нужного разрешения
     * Для локальных файлов возвращает локальный URL к обложке (file://...).
     */
    fun formatCoverUrl(uri: String?, size: Int = 400): String? {
        if (uri.isNullOrBlank()) return null
        var clean = uri.trim()
        if (clean.startsWith("local:") || clean.startsWith("file:") || clean.startsWith("/")) {
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

    /**
     * Асинхронная загрузка обложки трека с кэшированием в оперативной памяти
     */
    suspend fun loadCover(coverUri: String?, size: Int = 400): ImageBitmap? {
        if (coverUri.isNullOrBlank()) return null
        val trimmed = coverUri.trim()
        memoryCache[trimmed]?.let { return it }

        // 1. Проверяем локальные файлы
        if (trimmed.startsWith("local:") || trimmed.startsWith("file:") || trimmed.startsWith("/")) {
            val bytes = withContext(DispatcherIO) {
                loadLocalCoverBytes(trimmed)
            }
            if (bytes != null && bytes.isNotEmpty()) {
                return try {
                    val bitmap = bytes.decodeToImageBitmap()
                    memoryCache[trimmed] = bitmap
                    bitmap
                } catch (e: Exception) {
                    println("CoverImageLoader: Ошибка декодирования локальной обложки $trimmed: ${e.message}")
                    null
                }
            }
            return null
        }

        // 2. Сетевая обложка Яндекс Музыки
        val url = formatCoverUrl(trimmed, size) ?: return null
        memoryCache[url]?.let { return it }

        val repo = repository ?: return null
        val bytes = repo.fetchImageBytes(url) ?: return null
        return try {
            val bitmap = bytes.decodeToImageBitmap()
            memoryCache[trimmed] = bitmap
            memoryCache[url] = bitmap
            bitmap
        } catch (e: Exception) {
            println("CoverImageLoader: Ошибка декодирования изображения $url: ${e.message}")
            null
        }
    }
}

/**
 * 🎨 Composable-хэлпер для автоматической загрузки и реактивного обновления ImageBitmap
 */
@androidx.compose.runtime.Composable
fun rememberCoverBitmap(coverUri: String?, size: Int = 400): ImageBitmap? {
    val bitmapState = androidx.compose.runtime.produceState<ImageBitmap?>(initialValue = null, key1 = coverUri, key2 = size) {
        value = CoverImageLoader.loadCover(coverUri, size)
    }
    return bitmapState.value
}

