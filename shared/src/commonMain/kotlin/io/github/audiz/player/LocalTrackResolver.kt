package io.github.audiz.player

import io.github.audiz.localFileExists
import io.github.audiz.resolveLocalPath
import io.github.audiz.sanitizeKeepSpaces
import io.github.audiz.synchronized
import io.github.audiz.trackFileExists

/**
 * 🔍 Резолвер локальных аудиотреков на диске.
 * Отвечает за:
 * - Разрешение прямых путей локальных файлов ("local:/path/to/audio").
 * - Поиск скачанных треков по структуре папок (HQ/LQ/base) и поддерживаемым аудиорасширениям.
 * - Проверку наличия скачанных треков без обращения к сети.
 */
class LocalTrackResolver(
    val getMusicStoragePath: () -> String
) {
    companion object {
        val KNOWN_HQ_EXTENSIONS = listOf("flac", "m4a", "aac", "mp3", "opus", "wav", "ogg")
        val KNOWN_LQ_EXTENSIONS = listOf("m4a", "aac", "mp3", "opus", "flac", "wav", "ogg")

        fun sanitizeKeepSpaces(input: String): String = io.github.audiz.sanitizeKeepSpaces(input)

        @kotlin.concurrent.Volatile
        var globalCacheVersion: Long = 0L

        fun invalidateAllCaches() {
            globalCacheVersion++
        }
    }

    private var localCacheVersion: Long = -1L
    private val downloadedPathCache = mutableMapOf<String, String>()
    private val cacheLock = Any()

    fun invalidateCache() {
        synchronized(cacheLock) {
            downloadedPathCache.clear()
            localCacheVersion = globalCacheVersion
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
     * Проверка, скачан ли трек на диск (ищет во всех папках HQ/LQ/root с кэшированием в памяти).
     */
    fun isTrackDownloaded(artist: String, title: String): Boolean {
        return getDownloadedTrackPath(artist, title) != null
    }

    /**
     * Получить абсолютный путь к скачанному треку, если он существует.
     * Результаты кэшируются в памяти, предотвращая блокировку UI-потока повторными системными вызовами диска.
     */
    fun getDownloadedTrackPath(artist: String, title: String): String? {
        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return null
        val cacheKey = "$cleanArtist::$cleanTitle"

        synchronized(cacheLock) {
            if (localCacheVersion != globalCacheVersion) {
                downloadedPathCache.clear()
                localCacheVersion = globalCacheVersion
            }
            downloadedPathCache[cacheKey]?.let { cached ->
                return if (cached.isEmpty()) null else cached
            }
        }

        val sanitizedArtist = sanitizeKeepSpaces(cleanArtist.ifBlank { "Unknown Artist" }).trim()
        val musicStoragePath = getMusicStoragePath().trim()
        if (musicStoragePath.isBlank()) {
            synchronized(cacheLock) { downloadedPathCache[cacheKey] = "" }
            return null
        }
        val qualityFolders = listOf("$musicStoragePath/YamSync", "$musicStoragePath/HQ", "$musicStoragePath/LQ", musicStoragePath)

        for (basePath in qualityFolders) {
            val artistFolder = "$basePath/$sanitizedArtist"
            val resolvedArtistFolder = resolveLocalPath(artistFolder)
            if (!localFileExists(resolvedArtistFolder)) {
                continue
            }

            for (ext in KNOWN_HQ_EXTENSIONS) {
                val candidateNames = listOfNotNull(
                    if (cleanArtist.isNotEmpty()) sanitizeKeepSpaces("$cleanArtist — $cleanTitle.$ext") else null,
                    if (cleanArtist.isNotEmpty()) sanitizeKeepSpaces("$cleanArtist - $cleanTitle.$ext") else null,
                    sanitizeKeepSpaces("$cleanTitle.$ext")
                ).distinct()

                for (candidateName in candidateNames) {
                    val fullPath = "$basePath/$sanitizedArtist/$candidateName"
                    val resolved = resolveLocalPath(fullPath)
                    if (localFileExists(resolved)) {
                        synchronized(cacheLock) { downloadedPathCache[cacheKey] = resolved }
                        return resolved
                    }
                }
            }
        }
        synchronized(cacheLock) { downloadedPathCache[cacheKey] = "" }
        return null
    }
}
