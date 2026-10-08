@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.audiz

import io.github.audiz.models.FolderItem
import io.github.audiz.models.FolderListing
import io.github.audiz.models.FullAlbumInfo
import io.github.audiz.models.FullArtistInfo
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.PersonalPlaylistItemData
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.PlaylistInfo
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVMetadataCommonKeyArtwork
import platform.AVFoundation.AVMetadataItem
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.*
import platform.posix.memcpy

private const val STORAGE_PATH_KEY = "music_storage_path"

private val playlistJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

private fun sanitizeDirName(name: String): String {
    return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}

actual fun getDefaultMusicDir(): String {
    val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val docs = paths.firstOrNull() as? String ?: ""
    return if (docs.isNotEmpty()) "$docs/Music" else docs
}

actual fun saveMusicStoragePath(path: String) {
    NSUserDefaults.standardUserDefaults.setObject(path, forKey = STORAGE_PATH_KEY)
}

actual fun loadMusicStoragePath(): String? {
    val saved = NSUserDefaults.standardUserDefaults.stringForKey(STORAGE_PATH_KEY)
    if (saved.isNullOrBlank()) return null
    val fileManager = NSFileManager.defaultManager
    if (fileManager.fileExistsAtPath(saved)) return saved

    val currentDocs = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String) ?: ""
    if (currentDocs.isNotBlank() && saved.contains("/Documents/")) {
        val rel = saved.substringAfter("/Documents/")
        val remapped = "$currentDocs/$rel"
        if (fileManager.fileExistsAtPath(remapped)) {
            saveMusicStoragePath(remapped)
            return remapped
        }
    }
    return getDefaultMusicDir()
}

/**
 * 🔄 Разрешить локальный путь на iOS с поддержкой миграции UUID контейнера песочницы.
 * В iOS при каждом обновлении/переустановке приложения меняется <UUID> в
 * /var/mobile/Containers/Data/Application/<UUID>/Documents/...
 * Данная функция динамически сопоставляет сохраненный устаревший путь с актуальной папкой Documents.
 */
@OptIn(BetaInteropApi::class)
fun resolveIosLocalPath(path: String): String {
    val clean = path.trim().removePrefix("local:").removePrefix("file://")
    if (clean.isBlank()) return clean
    val fileManager = NSFileManager.defaultManager
    if (fileManager.fileExistsAtPath(clean)) return clean

    val currentDocs = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String) ?: ""
    val currentMusic = getDefaultMusicDir()

    // 1. Проверяем по маркеру /Documents/
    val docMarker = "/Documents/"
    if (clean.contains(docMarker) && currentDocs.isNotBlank()) {
        val relPath = clean.substringAfter(docMarker)
        val remapped = "$currentDocs/$relPath"
        if (fileManager.fileExistsAtPath(remapped)) {
            return remapped
        }
    }

    // 2. Проверяем по маркеру /Music/
    val musicMarker = "/Music/"
    if (clean.contains(musicMarker) && currentMusic.isNotBlank()) {
        val relPath = clean.substringAfter(musicMarker)
        val remapped = "$currentMusic/$relPath"
        if (fileManager.fileExistsAtPath(remapped)) {
            return remapped
        }
    }

    // 3. Поиск по имени файла и папке артиста в подпапках текущего хранилища (HQ, LQ, корень)
    val fileName = clean.substringAfterLast('/')
    val parentFolder = clean.substringBeforeLast('/').substringAfterLast('/')
    val searchRoots = listOf(
        "$currentMusic/HQ",
        "$currentMusic/LQ",
        currentMusic,
        "$currentDocs/HQ",
        "$currentDocs/LQ",
        currentDocs
    )

    val extensions = listOf(".m4a", ".mp3", ".flac", ".aac", ".wav")
    val baseNameWithoutExt = fileName.substringBeforeLast('.')

    for (root in searchRoots) {
        if (!fileManager.fileExistsAtPath(root)) continue

        // Сначала пробуем точное имя файла
        if (parentFolder.isNotBlank()) {
            val candidateArtist = "$root/$parentFolder/$fileName"
            if (fileManager.fileExistsAtPath(candidateArtist)) return candidateArtist
        }
        val candidateDirect = "$root/$fileName"
        if (fileManager.fileExistsAtPath(candidateDirect)) return candidateDirect

        // Затем пробуем другие поддерживаемые расширения (если качество/кодек отличались)
        for (ext in extensions) {
            val altFileName = "$baseNameWithoutExt$ext"
            if (parentFolder.isNotBlank()) {
                val candidateArtist = "$root/$parentFolder/$altFileName"
                if (fileManager.fileExistsAtPath(candidateArtist)) return candidateArtist
            }
            val candidateAlt = "$root/$altFileName"
            if (fileManager.fileExistsAtPath(candidateAlt)) return candidateAlt
        }

        // Поиск по подпапкам (папкам артистов)
        val subdirs = fileManager.contentsOfDirectoryAtPath(root, null) as? List<*>
        if (subdirs != null) {
            for (subObj in subdirs) {
                val sub = subObj as? String ?: continue
                val candidateSub = "$root/$sub/$fileName"
                if (fileManager.fileExistsAtPath(candidateSub)) return candidateSub
                for (ext in extensions) {
                    val candidateSubAlt = "$root/$sub/$baseNameWithoutExt$ext"
                    if (fileManager.fileExistsAtPath(candidateSubAlt)) return candidateSubAlt
                }
            }
        }
    }

    return clean
}

actual fun resolveLocalPath(path: String): String = resolveIosLocalPath(path)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun saveTrackFile(basePath: String, artist: String, fileName: String, bytes: ByteArray) {
    val cleanArtist = sanitizeDirName(artist.ifBlank { "Unknown Artist" })
    val artistDir = "$basePath/$cleanArtist"
    val fileManager = NSFileManager.defaultManager

    if (!fileManager.fileExistsAtPath(artistDir)) {
        fileManager.createDirectoryAtPath(artistDir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    val filePath = "$artistDir/$fileName"
    bytes.usePinned { pinned ->
        val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        data.writeToFile(filePath, atomically = true)
    }

    // Исключаем из резервной копии iCloud, чтобы не заполнять облачный лимит пользователя
    try {
        val url = NSURL.fileURLWithPath(filePath)
        url.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    } catch (_: Throwable) {}

    println("iOS Storage: Сохранён файл: $filePath (${bytes.size} байт)")
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun saveTrackToFolder(targetDir: String, fileName: String, bytes: ByteArray): String? {
    return try {
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(targetDir)) {
            fileManager.createDirectoryAtPath(targetDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val safeName = sanitizeKeepSpaces(fileName)
        val filePath = "$targetDir/$safeName"
        bytes.usePinned { pinned ->
            val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            data.writeToFile(filePath, atomically = true)
        }
        filePath
    } catch (_: Throwable) {
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun copyFileToFolder(sourceFilePath: String, targetDir: String, destFileName: String?): String? {
    return try {
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(targetDir)) {
            fileManager.createDirectoryAtPath(targetDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val safeName = sanitizeKeepSpaces(destFileName ?: sourceFilePath.substringAfterLast('/'))
        val destPath = "$targetDir/$safeName"
        if (fileManager.fileExistsAtPath(destPath)) {
            fileManager.removeItemAtPath(destPath, error = null)
        }
        fileManager.copyItemAtPath(sourceFilePath, toPath = destPath, error = null)
        destPath
    } catch (_: Throwable) {
        null
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun saveFileToDirectPath(destFilePath: String, bytes: ByteArray): String? {
    return try {
        val fileManager = NSFileManager.defaultManager
        val parentDir = destFilePath.substringBeforeLast('/')
        if (!fileManager.fileExistsAtPath(parentDir)) {
            fileManager.createDirectoryAtPath(parentDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        bytes.usePinned { pinned ->
            val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            data.writeToFile(destFilePath, atomically = true)
        }
        destFilePath
    } catch (_: Throwable) {
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun copyFileToDirectPath(sourceFilePath: String, destFilePath: String): String? {
    return try {
        val fileManager = NSFileManager.defaultManager
        val parentDir = destFilePath.substringBeforeLast('/')
        if (!fileManager.fileExistsAtPath(parentDir)) {
            fileManager.createDirectoryAtPath(parentDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        if (fileManager.fileExistsAtPath(destFilePath)) {
            fileManager.removeItemAtPath(destFilePath, error = null)
        }
        fileManager.copyItemAtPath(sourceFilePath, toPath = destFilePath, error = null)
        destFilePath
    } catch (_: Throwable) {
        null
    }
}

actual fun trackFileExists(basePath: String, artist: String, fileName: String): Boolean {
    val cleanArtist = sanitizeDirName(artist.ifBlank { "Unknown Artist" })
    val filePath = "$basePath/$cleanArtist/$fileName"
    val resolved = resolveIosLocalPath(filePath)
    return NSFileManager.defaultManager.fileExistsAtPath(resolved)
}

actual fun localFileExists(filePath: String): Boolean {
    val clean = filePath.trim().removePrefix("local:").removePrefix("file://")
    val resolved = resolveIosLocalPath(clean)
    return NSFileManager.defaultManager.fileExistsAtPath(resolved)
}

actual fun isDirectory(path: String): Boolean {
    if (path.isBlank()) return false
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    return attrs?.get(NSFileType) == NSFileTypeDirectory
}

actual fun readTextFile(path: String): String? {
    if (path.isBlank()) return null
    return try {
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
    } catch (_: Throwable) {
        null
    }
}

actual fun scanDownloadedTracks(basePath: String): List<FullTrackInfo> {
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(basePath)) {
        return emptyList()
    }

    val supportedExtensions = setOf("m4a", "flac", "mp3", "aac", "opus", "wav", "ogg")
    val foundTracks = mutableListOf<FullTrackInfo>()
    val delimiters = listOf(" — ", " – ", " - ", "_—_", "_-_")

    try {
        val subpaths = fileManager.subpathsOfDirectoryAtPath(basePath, error = null) as? List<String> ?: emptyList()
        for (relPath in subpaths) {
            val ext = relPath.substringAfterLast('.', "").lowercase()
            if (ext !in supportedExtensions) continue

            val fullPath = "$basePath/$relPath"
            val fileName = relPath.substringAfterLast('/')
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
                val parts = relPath.split('/')
                if (parts.size >= 2) {
                    val parent = parts[parts.size - 2].trim()
                    val ignoreParents = setOf("hq", "lq", "music", "yandexdownloader", "download", "downloads")
                    if (parent.lowercase() !in ignoreParents) {
                        artist = parent
                    } else {
                        artist = "Unknown Artist"
                    }
                } else {
                    artist = "Unknown Artist"
                }
            }

            val isHQ = fullPath.contains("/HQ/", ignoreCase = true) || fullPath.endsWith("/HQ", ignoreCase = true)
            val isLQ = fullPath.contains("/LQ/", ignoreCase = true) || fullPath.endsWith("/LQ", ignoreCase = true)
            val albumQuality = when {
                isHQ -> "Скачано (HQ)"
                isLQ -> "Скачано (LQ)"
                else -> "На диске (${ext.uppercase()})"
            }

            // Длительность трека через AVURLAsset
            val durationMs = try {
                val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(fullPath), options = null)
                val sec = CMTimeGetSeconds(asset.duration)
                if (sec.isNaN() || sec.isInfinite()) 0L else (sec * 1000).toLong()
            } catch (_: Throwable) {
                0L
            }

            foundTracks.add(
                FullTrackInfo(
                    id = "local:$fullPath",
                    realId = fullPath,
                    title = title,
                    available = true,
                    durationMs = durationMs,
                    artists = listOf(FullArtistInfo(id = 0L, name = artist)),
                    albums = listOf(FullAlbumInfo(id = 0L, title = albumQuality)),
                    coverUri = "local:$fullPath"
                )
            )
        }
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка при сканировании $basePath: ${e.message}")
    }

    // Дедупликация: при наличии и в HQ, и в LQ выбираем версию HQ
    val deduplicated = foundTracks.groupBy { track ->
        val artistKey = track.artists.firstOrNull()?.name?.trim()?.lowercase() ?: ""
        val titleKey = track.title.trim().lowercase()
        artistKey to titleKey
    }.values.map { group ->
        if (group.size == 1) {
            group.first()
        } else {
            group.maxByOrNull { it.albums.firstOrNull()?.title?.contains("HQ") == true } ?: group.first()
        }
    }

    return deduplicated.sortedWith(
        compareBy(
            { it.artists.firstOrNull()?.name?.lowercase() ?: "" },
            { it.title.lowercase() }
        )
    )
}

@OptIn(BetaInteropApi::class)
actual fun savePlaylistTracksCache(basePath: String, playlistTitle: String, tracks: List<FullTrackInfo>) {
    if (basePath.isBlank() || tracks.isEmpty()) return
    try {
        val cacheDir = "$basePath/playlists_cache"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(cacheDir)) {
            fileManager.createDirectoryAtPath(cacheDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val cleanTitle = sanitizeDirName(playlistTitle)
        val filePath = "$cacheDir/$cleanTitle.json"
        val jsonStr = playlistJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(FullTrackInfo.serializer()),
            tracks
        )
        val nsStr = NSString.create(string = jsonStr)
        nsStr.writeToFile(filePath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка сохранения кеша плейлиста: ${e.message}")
    }
}

@OptIn(BetaInteropApi::class)
actual fun loadPlaylistTracksCache(basePath: String, playlistTitle: String): List<FullTrackInfo> {
    if (basePath.isBlank()) return emptyList()
    try {
        val cleanTitle = sanitizeDirName(playlistTitle)
        val filePath = "$basePath/playlists_cache/$cleanTitle.json"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(filePath)) return emptyList()
        val nsStr = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null) ?: return emptyList()
        return playlistJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(FullTrackInfo.serializer()),
            nsStr
        )
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка чтения кеша плейлиста: ${e.message}")
        return emptyList()
    }
}

@OptIn(BetaInteropApi::class)
actual fun savePersonalPlaylistsCache(basePath: String, items: List<PersonalPlaylistItemData>) {
    if (basePath.isBlank() || items.isEmpty()) return
    try {
        val cacheDir = "$basePath/playlists_cache"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(cacheDir)) {
            fileManager.createDirectoryAtPath(cacheDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val filePath = "$cacheDir/_personal_playlists.json"
        val jsonStr = playlistJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PersonalPlaylistItemData.serializer()),
            items
        )
        val nsStr = NSString.create(string = jsonStr)
        nsStr.writeToFile(filePath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка сохранения кеша персональных плейлистов: ${e.message}")
    }
}

@OptIn(BetaInteropApi::class)
actual fun loadPersonalPlaylistsCache(basePath: String): List<PersonalPlaylistItemData> {
    if (basePath.isBlank()) return emptyList()
    try {
        val filePath = "$basePath/playlists_cache/_personal_playlists.json"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(filePath)) return emptyList()
        val nsStr = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null) ?: return emptyList()
        return playlistJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PersonalPlaylistItemData.serializer()),
            nsStr
        )
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка чтения кеша персональных плейлистов: ${e.message}")
        return emptyList()
    }
}

@OptIn(BetaInteropApi::class)
actual fun saveUserPlaylistsCache(basePath: String, items: List<PlaylistInfo>) {
    if (basePath.isBlank() || items.isEmpty()) return
    try {
        val cacheDir = "$basePath/playlists_cache"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(cacheDir)) {
            fileManager.createDirectoryAtPath(cacheDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val jsonStr = playlistJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PlaylistInfo.serializer()),
            items
        )
        val nsStr = jsonStr as NSString
        nsStr.writeToFile("$cacheDir/_user_playlists.json", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка сохранения кеша пользовательских плейлистов: ${e.message}")
    }
}

@OptIn(BetaInteropApi::class)
actual fun loadUserPlaylistsCache(basePath: String): List<PlaylistInfo> {
    if (basePath.isBlank()) return emptyList()
    try {
        val filePath = "$basePath/playlists_cache/_user_playlists.json"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(filePath)) return emptyList()
        val nsStr = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null) ?: return emptyList()
        return playlistJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PlaylistInfo.serializer()),
            nsStr
        )
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка чтения кеша пользовательских плейлистов: ${e.message}")
        return emptyList()
    }
}

@OptIn(BetaInteropApi::class)
actual fun saveLocalPlaylists(basePath: String, playlists: List<LocalPlaylist>) {
    if (basePath.isBlank()) return
    try {
        val dir = "$basePath/playlists"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(dir)) {
            fileManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val jsonStr = playlistJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(LocalPlaylist.serializer()),
            playlists
        )
        val nsStr = jsonStr as NSString
        nsStr.writeToFile("$dir/local_playlists.json", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка сохранения локальных плейлистов: ${e.message}")
    }
}

@OptIn(BetaInteropApi::class)
actual fun loadLocalPlaylists(basePath: String): List<LocalPlaylist> {
    val fileManager = NSFileManager.defaultManager
    val resolvedBasePath = if (basePath.isBlank() || !fileManager.fileExistsAtPath(basePath)) {
        getDefaultMusicDir()
    } else {
        basePath
    }
    try {
        var filePath = "$resolvedBasePath/playlists/local_playlists.json"
        if (!fileManager.fileExistsAtPath(filePath)) {
            val fallbackPath = "${getDefaultMusicDir()}/playlists/local_playlists.json"
            if (fileManager.fileExistsAtPath(fallbackPath)) {
                filePath = fallbackPath
            } else {
                return emptyList()
            }
        }
        val nsStr = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null) ?: return emptyList()
        val loaded = playlistJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LocalPlaylist.serializer()),
            nsStr
        )

        // 🔥 Авто-миграция: обновляем устаревшие пути со старыми UUID контейнера
        var anyMigrated = false
        val migratedList = loaded.map { playlist ->
            var plChanged = false
            val updatedPaths = playlist.trackPaths.map { originalPath ->
                val resolved = resolveIosLocalPath(originalPath)
                if (resolved != originalPath && fileManager.fileExistsAtPath(resolved)) {
                    plChanged = true
                    resolved
                } else {
                    originalPath
                }
            }
            val distinctPaths = updatedPaths.distinctBy { path ->
                val resolved = resolveIosLocalPath(path)
                val fileName = resolved.substringAfterLast('/')
                fileName.lowercase()
            }
            if (distinctPaths.size != playlist.trackPaths.size) {
                plChanged = true
            }
            if (plChanged) {
                anyMigrated = true
                playlist.copy(trackPaths = distinctPaths)
            } else {
                playlist
            }
        }
        if (anyMigrated) {
            println("iOS Storage: 🔄 Авто-миграция путей плейлистов на новый UUID контейнера успешно выполнена")
            saveLocalPlaylists(resolvedBasePath, migratedList)
        }
        return migratedList
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка чтения локальных плейлистов: ${e.message}")
        return emptyList()
    }
}

@OptIn(BetaInteropApi::class)
actual fun exportPlaylistToM3u8(basePath: String, playlist: LocalPlaylist, tracks: List<FullTrackInfo>): String {
    if (basePath.isBlank()) return ""
    try {
        val dir = "$basePath/playlists"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(dir)) {
            fileManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val safeName = sanitizeDirName(playlist.title.ifBlank { "Playlist" })
        val filePath = "$dir/$safeName.m3u8"
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#PLAYLIST:${playlist.title}\n\n")
        for (track in tracks) {
            val durationSec = (track.durationMs / 1000L).coerceAtLeast(0L)
            val artist = track.artists.firstOrNull()?.name ?: "Unknown Artist"
            sb.append("#EXTINF:$durationSec,$artist - ${track.title}\n")
            val path = track.realId?.ifBlank { track.id.removePrefix("local:") } ?: track.id.removePrefix("local:")
            sb.append("$path\n")
        }
        val nsStr = sb.toString() as NSString
        nsStr.writeToFile(filePath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        return filePath
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка экспорта M3U8: ${e.message}")
        return ""
    }
}

/** Преобразовать список локальных путей к файлам в список объектов FullTrackInfo с проверкой наличия на диске */
actual fun getTracksFromLocalPaths(paths: List<String>): List<FullTrackInfo> {
    val fileManager = NSFileManager.defaultManager
    val foundTracks = mutableListOf<FullTrackInfo>()
    val delimiters = listOf(" — ", " – ", " - ", "_—_", "_-_")
    for (rawPath in paths) {
        val fullPath = resolveIosLocalPath(rawPath)
        if (!fileManager.fileExistsAtPath(fullPath)) continue
        val fileName = fullPath.substringAfterLast('/')
        val rawName = fileName.substringBeforeLast('.')
        var artist = "Unknown Artist"
        var title = rawName
        for (delim in delimiters) {
            if (rawName.contains(delim)) {
                val parts = rawName.split(delim, limit = 2)
                artist = parts[0].trim()
                title = parts[1].trim()
                break
            }
        }
        val isHQ = fullPath.contains("/HQ/", ignoreCase = true) || fullPath.endsWith("/HQ", ignoreCase = true)
        val albumQuality = if (isHQ) "Локальный (HQ)" else "Локальный"
        val durationMs = try {
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(fullPath), options = null)
            val sec = CMTimeGetSeconds(asset.duration)
            if (sec.isNaN() || sec.isInfinite()) 0L else (sec * 1000).toLong()
        } catch (_: Throwable) {
            0L
        }
        foundTracks.add(
            FullTrackInfo(
                id = "local:$fullPath",
                realId = fullPath,
                title = title,
                available = true,
                durationMs = durationMs,
                artists = listOf(FullArtistInfo(id = 0L, name = artist)),
                albums = listOf(FullAlbumInfo(id = 0L, title = albumQuality, year = null)),
                coverUri = "local:$fullPath"
            )
        )
    }
    return foundTracks
}

actual fun deleteTrackFile(basePath: String, artist: String, trackTitle: String, localFilePath: String?): Boolean {
    val fileManager = NSFileManager.defaultManager
    try {
        val cleanLocal = localFilePath?.trim()
            ?.removePrefix("local:")
            ?.removePrefix("file://")
            ?.removePrefix("file:")
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
        if (!cleanLocal.isNullOrBlank()) {
            val resolved = resolveIosLocalPath(cleanLocal)
            if (fileManager.fileExistsAtPath(resolved)) {
                return fileManager.removeItemAtPath(resolved, error = null)
            }
            if (basePath.isNotBlank()) {
                val relPath = "$basePath/$cleanLocal"
                val resolvedRel = resolveIosLocalPath(relPath)
                if (fileManager.fileExistsAtPath(resolvedRel)) {
                    return fileManager.removeItemAtPath(resolvedRel, error = null)
                }
            }
        }

        if (basePath.isBlank()) return false

        val artistDirNames = listOfNotNull(
            sanitizeDirName(artist.ifBlank { "Unknown Artist" }),
            if (artist.isNotBlank()) sanitizeKeepSpaces(artist) else null
        ).distinct()

        for (sub in listOf("HQ", "LQ", "")) {
            for (cleanArtist in artistDirNames) {
                val artistDir = if (sub.isNotEmpty()) "$basePath/$sub/$cleanArtist" else "$basePath/$cleanArtist"
                if (!fileManager.fileExistsAtPath(artistDir)) continue

                val items = fileManager.contentsOfDirectoryAtPath(artistDir, error = null) as? List<String> ?: continue
                for (item in items) {
                    if (item.contains(trackTitle, ignoreCase = true)) {
                        val target = "$artistDir/$item"
                        val deleted = fileManager.removeItemAtPath(target, error = null)
                        val remaining = fileManager.contentsOfDirectoryAtPath(artistDir, error = null) as? List<String>
                        if (remaining.isNullOrEmpty()) {
                            fileManager.removeItemAtPath(artistDir, error = null)
                        }
                        if (deleted) return true
                    }
                }
            }
        }
    } catch (e: Throwable) {
        println("iOS Storage: Ошибка при удалении файла: ${e.message}")
    }
    return false
}

actual fun clearPlaylistsCache(basePath: String): Int {
    if (basePath.isBlank()) return 0
    val cacheDir = "$basePath/playlists_cache"
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(cacheDir)) return 0
    val items = fileManager.contentsOfDirectoryAtPath(cacheDir, error = null) as? List<String> ?: return 0
    var count = 0
    for (item in items) {
        if (fileManager.removeItemAtPath("$cacheDir/$item", error = null)) {
            count++
        }
    }
    return count
}

actual fun clearAllDownloadedMusic(basePath: String): Int {
    if (basePath.isBlank()) return 0
    val fileManager = NSFileManager.defaultManager
    var count = 0
    val supportedExts = setOf("m4a", "flac", "mp3", "aac", "opus", "wav", "ogg")
    for (sub in listOf("HQ", "LQ")) {
        val dir = "$basePath/$sub"
        if (!fileManager.fileExistsAtPath(dir)) continue
        val subpaths = fileManager.subpathsOfDirectoryAtPath(dir, error = null) as? List<String> ?: continue
        for (p in subpaths) {
            val ext = p.substringAfterLast('.', "").lowercase()
            if (ext in supportedExts) {
                count++
            }
        }
        fileManager.removeItemAtPath(dir, error = null)
    }
    return count
}

actual fun getLogFilePath(): String {
    val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val docs = paths.firstOrNull() as? String ?: ""
    return "$docs/yamusic.log"
}

@OptIn(BetaInteropApi::class)
actual fun appendLogToFile(line: String) {
    try {
        val path = getLogFilePath()
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createFileAtPath(path, contents = null, attributes = null)
        }
        val fileHandle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return
        fileHandle.seekToEndOfFile()
        val nsStr = "$line\n" as NSString
        val data = nsStr.dataUsingEncoding(NSUTF8StringEncoding)
        if (data != null) {
            fileHandle.writeData(data)
        }
        fileHandle.closeFile()
    } catch (_: Throwable) {}
}

/** Получить листинг содержимого директории (подпапки и аудиофайлы) для встроенного проводника */
actual fun listFolderContents(folderPath: String, rootPath: String?): FolderListing {
    val fileManager = NSFileManager.defaultManager
    var isDir = false
    val exists = fileManager.fileExistsAtPath(folderPath)
    if (!exists) {
        return FolderListing(folderPath, null, rootPath, emptyList(), emptyList())
    }

    val supportedExtensions = setOf("mp3", "flac", "m4a", "aac", "opus", "wav", "ogg")
    val subfolders = mutableListOf<FolderItem>()
    val audioFilePaths = mutableListOf<String>()

    val items = fileManager.contentsOfDirectoryAtPath(folderPath, null) as? List<*> ?: emptyList<Any>()
    for (item in items) {
        val itemName = item as? String ?: continue
        if (itemName.startsWith(".")) continue
        val fullPath = "$folderPath/$itemName"

        val isDirectoryItem = isDirectory(fullPath)
        if (isDirectoryItem) {
            val audioCount = try {
                scanDownloadedTracks(fullPath).size
            } catch (_: Exception) { 0 }

            subfolders.add(
                FolderItem(
                    name = itemName,
                    path = fullPath,
                    isDirectory = true,
                    trackCount = audioCount
                )
            )
        } else {
            val ext = itemName.substringAfterLast('.', "").lowercase()
            if (ext in supportedExtensions) {
                audioFilePaths.add(fullPath)
            }
        }
    }

    subfolders.sortBy { it.name.lowercase() }
    val tracks = getTracksFromLocalPaths(audioFilePaths).sortedBy { it.title.lowercase() }

    val parent = if (rootPath != null && folderPath.equals(rootPath, ignoreCase = true)) {
        null
    } else {
        folderPath.substringBeforeLast('/', "").ifBlank { null }
    }

    return FolderListing(
        currentPath = folderPath,
        parentPath = parent,
        rootPath = rootPath ?: folderPath,
        subfolders = subfolders,
        tracks = tracks
    )
}

private fun NSData.toByteArray(): ByteArray {
    val len = this.length.toInt()
    if (len <= 0) return ByteArray(0)
    val bytes = ByteArray(len)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return bytes
}

actual fun loadLocalCoverBytes(pathOrUri: String): ByteArray? {
    val cleanPath = pathOrUri
        .removePrefix("local:")
        .removePrefix("file://")
        .removePrefix("file:")
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(cleanPath)) return null

    val ext = cleanPath.substringAfterLast('.', "").lowercase()
    if (ext in listOf("jpg", "jpeg", "png", "webp", "gif")) {
        val data = NSData.dataWithContentsOfFile(cleanPath) ?: return null
        return data.toByteArray()
    }

    // 1. Извлечение обложки через AVURLAsset
    try {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(cleanPath), options = null)
        val metadata = asset.commonMetadata
        for (item in metadata) {
            val metaItem = item as? AVMetadataItem ?: continue
            if (metaItem.commonKey == AVMetadataCommonKeyArtwork) {
                val data = metaItem.dataValue ?: (metaItem.value as? NSData)
                if (data != null) {
                    return data.toByteArray()
                }
            }
        }
    } catch (_: Throwable) {}

    // 2. Резервный поиск обложки в папке альбома
    val parentDir = cleanPath.substringBeforeLast('/', "")
    if (parentDir.isNotBlank()) {
        val candidates = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "album.jpg")
        for (cand in candidates) {
            val candPath = "$parentDir/$cand"
            if (fileManager.fileExistsAtPath(candPath)) {
                val data = NSData.dataWithContentsOfFile(candPath)
                if (data != null) return data.toByteArray()
            }
        }
    }

    return null
}

actual fun getLocalCoverArtUrl(pathOrUri: String): String? {
    val cleanPath = pathOrUri
        .removePrefix("local:")
        .removePrefix("file://")
        .removePrefix("file:")
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(cleanPath)) return null

    val ext = cleanPath.substringAfterLast('.', "").lowercase()
    if (ext in listOf("jpg", "jpeg", "png", "webp", "gif")) {
        return "file://$cleanPath"
    }

    val parentDir = cleanPath.substringBeforeLast('/', "")
    if (parentDir.isNotBlank()) {
        val candidates = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "album.jpg")
        for (cand in candidates) {
            val candPath = "$parentDir/$cand"
            if (fileManager.fileExistsAtPath(candPath)) {
                return "file://$candPath"
            }
        }
    }
    return "file://$cleanPath"
}




