@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.audiz

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
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.*

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
    return NSUserDefaults.standardUserDefaults.stringForKey(STORAGE_PATH_KEY)
}

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

actual fun trackFileExists(basePath: String, artist: String, fileName: String): Boolean {
    val cleanArtist = sanitizeDirName(artist.ifBlank { "Unknown Artist" })
    val filePath = "$basePath/$cleanArtist/$fileName"
    return NSFileManager.defaultManager.fileExistsAtPath(filePath)
}

actual fun localFileExists(filePath: String): Boolean {
    return NSFileManager.defaultManager.fileExistsAtPath(filePath)
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
                    albums = listOf(FullAlbumInfo(id = 0L, title = albumQuality))
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
    if (basePath.isBlank()) return emptyList()
    try {
        val filePath = "$basePath/playlists/local_playlists.json"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(filePath)) return emptyList()
        val nsStr = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null) ?: return emptyList()
        return playlistJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LocalPlaylist.serializer()),
            nsStr
        )
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
    for (fullPath in paths) {
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
                albums = listOf(FullAlbumInfo(id = 0L, title = albumQuality, year = null))
            )
        )
    }
    return foundTracks
}



actual fun deleteTrackFile(basePath: String, artist: String, trackTitle: String, localFilePath: String?): Boolean {
    if (basePath.isBlank()) return false
    val fileManager = NSFileManager.defaultManager
    try {
        if (!localFilePath.isNullOrBlank() && fileManager.fileExistsAtPath(localFilePath)) {
            return fileManager.removeItemAtPath(localFilePath, error = null)
        }

        val cleanArtist = sanitizeDirName(artist.ifBlank { "Unknown Artist" })
        for (sub in listOf("HQ", "LQ", "")) {
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


