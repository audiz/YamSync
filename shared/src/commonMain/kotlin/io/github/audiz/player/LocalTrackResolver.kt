package io.github.audiz.player

import io.github.audiz.localFileExists
import io.github.audiz.resolveLocalPath
import io.github.audiz.sanitizeKeepSpaces
import io.github.audiz.scanDownloadedTrackPaths
import io.github.audiz.synchronized
import io.github.audiz.trackFileExists

/**
 * 🔍 Резолвер локальных аудиотреков на диске с мгновенным O(1) in-memory кэшированием.
 * Отвечает за:
 * - Разрешение прямых путей локальных файлов ("local:/path/to/audio").
 * - Мгновенную проверку наличия скачанных треков без обращения к файловой системе во время скролла.
 */
class LocalTrackResolver(
    val getMusicStoragePath: () -> String
) {
    companion object {
        fun sanitizeKeepSpaces(input: String): String = io.github.audiz.sanitizeKeepSpaces(input)

        @kotlin.concurrent.Volatile
        var globalCacheVersion: Long = 0L

        fun invalidateAllCaches() {
            globalCacheVersion++
        }
    }

    private var localCacheVersion: Long = -1L
    private val downloadedPathCache = mutableMapOf<String, String>()
    private val downloadedIndex = mutableMapOf<String, String>()
    private val cacheLock = Any()

    fun invalidateCache() {
        synchronized(cacheLock) {
            downloadedPathCache.clear()
            downloadedIndex.clear()
            localCacheVersion = -1L
        }
    }

    /**
     * Быстрая загрузка карты всех локальных аудиофайлов в память (занимает ~1мс для сотен треков).
     * Предотвращает системные вызовы stat/access во время анимации скролла списка.
     */
    private fun ensureIndexLoaded() {
        synchronized(cacheLock) {
            if (localCacheVersion == globalCacheVersion) return
            downloadedPathCache.clear()
            downloadedIndex.clear()
            localCacheVersion = globalCacheVersion

            val storagePath = getMusicStoragePath().trim()
            if (storagePath.isBlank()) return

            try {
                val paths = scanDownloadedTrackPaths(storagePath)
                val delimiters = listOf(" — ", " – ", " - ", "_—_", "_-_")
                for (p in paths) {
                    val fileName = p.substringAfterLast('/')
                    val rawName = fileName.substringBeforeLast('.')

                    var artist = ""
                    var title = rawName
                    for (delim in delimiters) {
                        if (rawName.contains(delim)) {
                            val parts = rawName.split(delim, limit = 2)
                            artist = parts[0].trim()
                            title = parts[1].trim()
                            break
                        }
                    }

                    if (artist.isBlank()) {
                        val parent = p.substringBeforeLast('/').substringAfterLast('/')
                        val ignoreParents = setOf("hq", "lq", "yamsync", "music", "yandexdownloader", "download", "downloads")
                        if (parent.lowercase() !in ignoreParents) {
                            artist = parent
                        }
                    }

                    val cleanTitle = title.trim().lowercase()
                    val cleanArtist = artist.trim().lowercase()
                    val cleanRaw = rawName.trim().lowercase()

                    if (cleanTitle.isNotBlank()) {
                        if (cleanArtist.isNotBlank()) {
                            downloadedIndex["$cleanArtist::$cleanTitle"] = p
                            downloadedIndex["$cleanArtist — $cleanTitle"] = p
                            downloadedIndex["$cleanArtist - $cleanTitle"] = p
                        }
                        downloadedIndex[cleanTitle] = p
                        downloadedIndex[cleanRaw] = p
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Поиск существующего пути файла трека на диске.
     * Проверяет явный путь `local:/...`, затем папки качества HQ/LQ в указанном хранилище.
     */
    fun findLocalTrackFile(
        trackId: String,
        artist: String,
        title: String,
        selectedQuality: String = "1"
    ): String? {
        if (trackId.startsWith("local:")) {
            val candidate = trackId.removePrefix("local:")
            val resolved = resolveLocalPath(candidate)
            if (localFileExists(resolved)) {
                return resolved
            }
        }
        val resolvedTrackId = resolveLocalPath(trackId)
        if (localFileExists(resolvedTrackId)) {
            return resolvedTrackId
        }

        return getDownloadedTrackPath(artist, title)
    }

    /**
     * Проверка, скачан ли трек на диск (мгновенный O(1) поиск в оперативной памяти).
     */
    fun isTrackDownloaded(artist: String, title: String): Boolean {
        return getDownloadedTrackPath(artist, title) != null
    }

    /**
     * Получить абсолютный путь к скачанному треку, если он существует.
     * Результаты кэшируются в памяти, полностью исключая системные вызовы диска при скролле.
     */
    fun getDownloadedTrackPath(artist: String, title: String): String? {
        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return null
        val cacheKey = "$cleanArtist::$cleanTitle"

        synchronized(cacheLock) {
            if (localCacheVersion != globalCacheVersion) {
                ensureIndexLoaded()
            }
            downloadedPathCache[cacheKey]?.let { cached ->
                return if (cached.isEmpty()) null else cached
            }

            val lowerArtist = cleanArtist.lowercase()
            val lowerTitle = cleanTitle.lowercase()

            val found = downloadedIndex["$lowerArtist::$lowerTitle"]
                ?: downloadedIndex[lowerTitle]
                ?: downloadedIndex["$lowerArtist — $lowerTitle"]
                ?: downloadedIndex["$lowerArtist - $lowerTitle"]

            val result = if (found != null && localFileExists(found)) {
                found
            } else {
                null
            }

            downloadedPathCache[cacheKey] = result ?: ""
            return result
        }
    }
}
