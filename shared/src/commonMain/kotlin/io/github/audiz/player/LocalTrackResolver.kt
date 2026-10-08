package io.github.audiz.player

import io.github.audiz.localFileExists
import io.github.audiz.resolveLocalPath
import io.github.audiz.sanitizeKeepSpaces
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

        val musicStoragePath = getMusicStoragePath().trim()
        if (musicStoragePath.isBlank()) return null

        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        val sanitizedArtist = sanitizeKeepSpaces(cleanArtist.ifBlank { "Unknown Artist" }).trim()

        val knownExtensions = if (selectedQuality == "2") KNOWN_HQ_EXTENSIONS else KNOWN_LQ_EXTENSIONS
        val candidateFolders = if (selectedQuality == "2") {
            listOf("$musicStoragePath/HQ", "$musicStoragePath/LQ", musicStoragePath)
        } else {
            listOf("$musicStoragePath/LQ", "$musicStoragePath/HQ", musicStoragePath)
        }

        for (folder in candidateFolders) {
            for (ext in knownExtensions) {
                val candidateNames = listOfNotNull(
                    if (cleanArtist.isNotEmpty()) sanitizeKeepSpaces("$cleanArtist — $cleanTitle.$ext") else null,
                    if (cleanArtist.isNotEmpty()) sanitizeKeepSpaces("$cleanArtist - $cleanTitle.$ext") else null,
                    sanitizeKeepSpaces("$cleanTitle.$ext")
                ).distinct()

                for (candidateName in candidateNames) {
                    val fullPath = "$folder/$sanitizedArtist/$candidateName"
                    val resolved = resolveLocalPath(fullPath)
                    if (localFileExists(resolved)) {
                        return resolved
                    }
                }
            }
        }

        return null
    }

    /**
     * Проверка, скачан ли трек на диск (ищет во всех папках HQ/LQ/root).
     */
    fun isTrackDownloaded(artist: String, title: String): Boolean {
        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        val musicStoragePath = getMusicStoragePath().trim()
        if (musicStoragePath.isBlank()) return false
        val qualityFolders = listOf("$musicStoragePath/HQ", "$musicStoragePath/LQ", musicStoragePath)

        for (basePath in qualityFolders) {
            for (ext in KNOWN_HQ_EXTENSIONS) {
                val candidateNames = listOfNotNull(
                    if (cleanArtist.isNotEmpty()) sanitizeKeepSpaces("$cleanArtist — $cleanTitle.$ext") else null,
                    if (cleanArtist.isNotEmpty()) sanitizeKeepSpaces("$cleanArtist - $cleanTitle.$ext") else null,
                    sanitizeKeepSpaces("$cleanTitle.$ext")
                ).distinct()

                for (candidateName in candidateNames) {
                    if (trackFileExists(basePath, cleanArtist, candidateName)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Получить абсолютный путь к скачанному треку, если он существует.
     */
    fun getDownloadedTrackPath(artist: String, title: String): String? {
        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        val sanitizedArtist = sanitizeKeepSpaces(cleanArtist.ifBlank { "Unknown Artist" }).trim()
        val musicStoragePath = getMusicStoragePath().trim()
        if (musicStoragePath.isBlank()) return null
        val qualityFolders = listOf("$musicStoragePath/HQ", "$musicStoragePath/LQ", musicStoragePath)

        for (basePath in qualityFolders) {
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
                        return resolved
                    }
                }
            }
        }
        return null
    }
}
