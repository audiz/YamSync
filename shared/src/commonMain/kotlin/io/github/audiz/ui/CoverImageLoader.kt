package io.github.audiz.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.audiz.api.MusicRepository
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * 🖼️ Загрузчик и кэш обложек альбомов и треков
 */
object CoverImageLoader {
    private val memoryCache = mutableMapOf<String, ImageBitmap>()
    var repository: MusicRepository? = null

    /**
     * Форматирование шаблона URI от Яндекс Музыки (например avatars.yandex.net/.../%%) в прямой URL нужного разрешения
     */
    fun formatCoverUrl(uri: String?, size: Int = 400): String? {
        if (uri.isNullOrBlank()) return null
        var clean = uri.trim()
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
        val url = formatCoverUrl(coverUri, size) ?: return null
        memoryCache[url]?.let { return it }

        val repo = repository ?: return null
        val bytes = repo.fetchImageBytes(url) ?: return null
        return try {
            val bitmap = bytes.decodeToImageBitmap()
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
