package io.github.audiz

import io.github.audiz.models.FolderItem
import io.github.audiz.models.FolderListing
import io.github.audiz.models.FullAlbumInfo
import io.github.audiz.models.FullArtistInfo
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.PersonalPlaylistItemData
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.PlaylistInfo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

private val playlistJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    isLenient = true
}


private fun sanitizePlaylistFileName(title: String): String {
    val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return clean.ifBlank { "Playlist" }
}

private fun getPlaylistCacheDir(basePath: String): File {
    val dir = File(basePath, "playlists_cache")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

private fun cleanUpEmptyDir(dir: File?, basePath: String) {
    if (dir == null || !dir.isDirectory) return
    val base = File(basePath).canonicalFile
    val hq = File(base, "HQ").canonicalFile
    val lq = File(base, "LQ").canonicalFile
    val yamsync = File(base, "YamSync").canonicalFile
    val playlists = File(base, "playlists").canonicalFile
    val cache = File(base, "playlists_cache").canonicalFile
    val current = dir.canonicalFile

    if (current == base || current == hq || current == lq || current == yamsync || current == playlists || current == cache) return

    val contents = dir.listFiles()
    if (contents != null && contents.isEmpty()) {
        dir.delete()
        println("MusicStorage: Удалена пустая папка: ${dir.absolutePath}")
    }
}

/** Сохранить трек в структурированную папку: {basePath}/{Artist}/{fileName} */
actual fun saveTrackFile(basePath: String, artist: String, fileName: String, bytes: ByteArray) {
    val cleanArtist = sanitizeDirName(artist.ifBlank { "Unknown Artist" })
    val artistDir = File(basePath, cleanArtist)

    if (!artistDir.exists()) {
        artistDir.mkdirs()
    }

    val outputFile = File(artistDir, sanitizeKeepSpaces(fileName))
    outputFile.writeBytes(bytes)
    io.github.audiz.player.LocalTrackResolver.invalidateAllCaches()
    println("MusicStorage: Сохранён файл: ${outputFile.absolutePath}")
}

/** Сохранить файл напрямую в указанную папку: {targetDir}/{fileName} */
actual fun saveTrackToFolder(targetDir: String, fileName: String, bytes: ByteArray): String? {
    return try {
        val dir = File(targetDir)
        if (!dir.exists()) dir.mkdirs()
        val destFile = File(dir, sanitizeKeepSpaces(fileName))
        destFile.writeBytes(bytes)
        io.github.audiz.player.LocalTrackResolver.invalidateAllCaches()
        println("MusicStorage: Файл сохранён напрямую в папку: ${destFile.absolutePath}")
        destFile.absolutePath
    } catch (e: Exception) {
        println("MusicStorage: ❌ Ошибка сохранения в папку: ${e.message}")
        null
    }
}

/** Скопировать существующий файл в указанную папку: {targetDir}/{destFileName} */
actual fun copyFileToFolder(sourceFilePath: String, targetDir: String, destFileName: String?): String? {
    return try {
        val src = File(sourceFilePath)
        if (!src.exists()) return null
        val dir = File(targetDir)
        if (!dir.exists()) dir.mkdirs()
        val finalName = sanitizeKeepSpaces(destFileName ?: src.name)
        val destFile = File(dir, finalName)
        src.copyTo(destFile, overwrite = true)
        println("MusicStorage: Файл скопирован в папку: ${destFile.absolutePath}")
        destFile.absolutePath
    } catch (e: Exception) {
        println("MusicStorage: ❌ Ошибка копирования в папку: ${e.message}")
        null
    }
}

/** Сохранить файл по прямому целевому пути: {destFilePath} */
actual fun saveFileToDirectPath(destFilePath: String, bytes: ByteArray): String? {
    return try {
        val destFile = File(destFilePath)
        destFile.parentFile?.mkdirs()
        destFile.writeBytes(bytes)
        println("MusicStorage: Файл сохранён по пути: ${destFile.absolutePath}")
        destFile.absolutePath
    } catch (e: Exception) {
        println("MusicStorage: ❌ Ошибка сохранения по прямому пути: ${e.message}")
        null
    }
}

/** Скопировать существующий файл по прямому целевому пути: {destFilePath} */
actual fun copyFileToDirectPath(sourceFilePath: String, destFilePath: String): String? {
    return try {
        val src = File(sourceFilePath)
        if (!src.exists()) return null
        val destFile = File(destFilePath)
        destFile.parentFile?.mkdirs()
        src.copyTo(destFile, overwrite = true)
        println("MusicStorage: Файл скопирован по прямому пути: ${destFile.absolutePath}")
        destFile.absolutePath
    } catch (e: Exception) {
        println("MusicStorage: ❌ Ошибка копирования по прямому пути: ${e.message}")
        null
    }
}

/** Разрешить локальный путь к файлу с быстрым O(1) поиском в папке музыки и её поддиректориях */
actual fun resolveLocalPath(path: String): String {
    val clean = path.trim().removePrefix("local:").removePrefix("file://").removePrefix("file:")
    if (clean.isBlank()) return ""

    return try {
        val rawFile = File(clean)
        if (rawFile.exists() && rawFile.isFile) return rawFile.absolutePath

        // Нормализуем путь для кроссплатформенности (Windows \ vs Unix /)
        val normalized = clean.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')
        val parentName = normalized.substringBeforeLast('/', "").substringAfterLast('/').takeIf { it.isNotBlank() }
        val baseName = fileName.substringBeforeLast('.')

        val baseMusic = loadMusicStoragePath()?.takeIf { runCatching { File(it).exists() }.getOrDefault(false) } ?: getDefaultMusicDir()
        val baseDir = File(baseMusic)
        if (!baseDir.exists()) return rawFile.absolutePath

        // Кандидаты прямого поиска (директории для O(1) проверок существования файлов)
        val candidateDirs = mutableListOf<File>()
        candidateDirs.add(baseDir)
        candidateDirs.add(File(baseDir, "YamSync"))
        candidateDirs.add(File(baseDir, "HQ"))
        candidateDirs.add(File(baseDir, "LQ"))

        if (!parentName.isNullOrBlank() && parentName != "HQ" && parentName != "LQ" && parentName != "YamSync") {
            candidateDirs.add(File(baseDir, parentName))
            candidateDirs.add(File(baseDir, "YamSync/$parentName"))
            candidateDirs.add(File(baseDir, "HQ/$parentName"))
            candidateDirs.add(File(baseDir, "LQ/$parentName"))
            val sanitizedParent = sanitizeDirName(parentName)
            if (sanitizedParent != parentName) {
                candidateDirs.add(File(baseDir, sanitizedParent))
                candidateDirs.add(File(baseDir, "YamSync/$sanitizedParent"))
                candidateDirs.add(File(baseDir, "HQ/$sanitizedParent"))
                candidateDirs.add(File(baseDir, "LQ/$sanitizedParent"))
            }
        }

        // Извлекаем возможного артиста из имени файла вида "Исполнитель — Название.mp3"
        val delimiters = listOf(" — ", " – ", " - ", "_—_", "_-_")
        for (delim in delimiters) {
            if (fileName.contains(delim)) {
                val potentialArtist = fileName.split(delim, limit = 2)[0].trim()
                if (potentialArtist.isNotBlank() && potentialArtist != parentName) {
                    candidateDirs.add(File(baseDir, potentialArtist))
                    candidateDirs.add(File(baseDir, "YamSync/$potentialArtist"))
                    candidateDirs.add(File(baseDir, "HQ/$potentialArtist"))
                    candidateDirs.add(File(baseDir, "LQ/$potentialArtist"))
                    val sanArtist = sanitizeDirName(potentialArtist)
                    if (sanArtist != potentialArtist) {
                        candidateDirs.add(File(baseDir, sanArtist))
                        candidateDirs.add(File(baseDir, "YamSync/$sanArtist"))
                        candidateDirs.add(File(baseDir, "HQ/$sanArtist"))
                        candidateDirs.add(File(baseDir, "LQ/$sanArtist"))
                    }
                }
                break
            }
        }

        val extList = listOf(".m4a", ".mp3", ".flac", ".aac", ".opus", ".wav", ".ogg")
        val candidateFileNames = mutableListOf<String>()
        candidateFileNames.add(fileName)
        for (ext in extList) {
            candidateFileNames.add("$baseName$ext")
        }

        // Проверяем каждого кандидата в подготовленных директориях без рекурсивного сканирования
        for (dir in candidateDirs.distinct()) {
            if (!dir.exists() || !dir.isDirectory) continue
            for (name in candidateFileNames.distinct()) {
                val f = File(dir, name)
                if (f.exists() && f.isFile) return f.absolutePath
            }
        }

        rawFile.absolutePath
    } catch (_: Throwable) {
        clean
    }
}

/** Проверить, существует ли трек на диске */
actual fun trackFileExists(basePath: String, artist: String, fileName: String): Boolean {
    return try {
        val cleanArtist = sanitizeDirName(artist.ifBlank { "Unknown Artist" })
        val file = File(File(basePath, cleanArtist), fileName)
        if (file.exists() && file.isFile) return true
        val direct = File(basePath, fileName)
        if (direct.exists() && direct.isFile) return true
        val resolved = resolveLocalPath(file.absolutePath)
        if (resolved.isNotBlank()) File(resolved).exists() else false
    } catch (_: Throwable) {
        false
    }
}

/** Проверить существование файла по прямому пути */
actual fun localFileExists(filePath: String): Boolean {
    if (filePath.isBlank()) return false
    return try {
        val file = File(filePath)
        if (file.exists() && file.isFile) return true
        val resolved = resolveLocalPath(filePath)
        if (resolved.isNotBlank()) File(resolved).exists() else false
    } catch (_: Throwable) {
        false
    }
}

/** Проверить, является ли путь существующей директорией */
actual fun isDirectory(path: String): Boolean {
    if (path.isBlank()) return false
    return try {
        val f = File(path)
        f.exists() && f.isDirectory
    } catch (_: Throwable) {
        false
    }
}

/** Прочитать содержимое текстового файла (например, плейлиста M3U) */
actual fun readTextFile(path: String): String? {
    if (path.isBlank()) return null
    return try {
        val f = File(path)
        if (f.exists() && f.isFile) f.readText() else null
    } catch (e: Exception) {
        println("MusicStorage: Ошибка чтения файла $path: ${e.message}")
        null
    }
}

/**
 * 📁 Сканирует локальную папку с сохранёнными аудиофайлами без обращения в интернет.
 * Распознает M4A, FLAC, MP3, AAC, OPUS, WAV, OGG.
 * Определяет исполнителя, название трека, качество (HQ/LQ) и длительность из структуры папок и заголовков файлов.
 */
actual fun scanDownloadedTrackPaths(basePath: String): List<String> {
    val rootDir = File(basePath)
    if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()
    val supportedExtensions = setOf("m4a", "flac", "mp3", "aac", "opus", "wav", "ogg")
    return try {
        rootDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
            .map { it.absolutePath.replace('\\', '/') }
            .toList()
    } catch (_: Throwable) {
        emptyList()
    }
}

actual fun scanDownloadedTracks(basePath: String): List<FullTrackInfo> {
    val rootDir = File(basePath)
    if (!rootDir.exists() || !rootDir.isDirectory) {
        return emptyList()
    }

    val supportedExtensions = setOf("m4a", "flac", "mp3", "aac", "opus", "wav", "ogg")
    val foundTracks = mutableListOf<FullTrackInfo>()
    val delimiters = listOf(" — ", " – ", " - ", "_—_", "_-_")

    try {
        val audioFiles = rootDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
            .toList()

        for (file in audioFiles) {
            val meta = io.github.audiz.util.AudioHeaderParser.extractAudioMetadata(file)
            val rawName = file.nameWithoutExtension
            var artist = meta.artist?.takeIf { it.isNotBlank() } ?: ""
            var title = meta.title?.takeIf { it.isNotBlank() } ?: rawName

            if (artist.isBlank() || title == rawName) {
                for (delim in delimiters) {
                    if (rawName.contains(delim)) {
                        val parts = rawName.split(delim, limit = 2)
                        if (artist.isBlank()) artist = parts[0].trim()
                        if (title == rawName) title = parts[1].trim()
                        break
                    }
                }
            }

            if (artist.isBlank()) {
                val parent = file.parentFile?.name?.trim() ?: ""
                val ignoreParents = setOf("hq", "lq", "yamsync", "music", "yandexdownloader", "download", "downloads", rootDir.name.lowercase())
                if (parent.isNotBlank() && parent.lowercase() !in ignoreParents) {
                    artist = parent
                } else {
                    artist = "Unknown Artist"
                }
            }

            val normalizedPath = file.absolutePath.replace('\\', '/')
            val isYamSync = normalizedPath.contains("/YamSync/", ignoreCase = true)
            val isHQ = normalizedPath.contains("/HQ/", ignoreCase = true) || normalizedPath.endsWith("/HQ", ignoreCase = true)
            val isLQ = normalizedPath.contains("/LQ/", ignoreCase = true) || normalizedPath.endsWith("/LQ", ignoreCase = true)
            val albumQuality = when {
                isYamSync -> "YamSync (P2P)"
                isHQ -> "Скачано (HQ)"
                isLQ -> "Скачано (LQ)"
                else -> "На диске (${file.extension.uppercase()})"
            }

            val durationMs = extractAudioDuration(file)

            foundTracks.add(
                FullTrackInfo(
                    id = "local:${file.absolutePath}",
                    realId = file.absolutePath,
                    title = title,
                    available = true,
                    durationMs = durationMs,
                    artists = listOf(FullArtistInfo(id = 0L, name = artist)),
                    albums = listOf(FullAlbumInfo(id = 0L, title = albumQuality)),
                    coverUri = "local:${file.absolutePath}"
                )
            )
        }
    } catch (e: Exception) {
        println("MusicStorage: Ошибка при сканировании $basePath: ${e.message}")
        e.printStackTrace()
    }

    // Дедупликация: если один и тот же трек есть и в HQ, и в LQ — выбираем версию HQ (или с большим размером)
    val deduplicated = foundTracks.groupBy { track ->
        val artistKey = track.artists.firstOrNull()?.name?.trim()?.lowercase() ?: ""
        val titleKey = track.title.trim().lowercase()
        artistKey to titleKey
    }.values.map { group ->
        if (group.size == 1) {
            group.first()
        } else {
            group.maxWithOrNull(
                compareBy<FullTrackInfo> { it.albums.firstOrNull()?.title?.contains("HQ") == true }
                    .thenBy { File(it.realId ?: "").length() }
            ) ?: group.first()
        }
    }

    // Сортировка по исполнителю, затем по названию трека
    return deduplicated.sortedWith(
        compareBy(
            { it.artists.firstOrNull()?.name?.lowercase() ?: "" },
            { it.title.lowercase() }
        )
    )
}

/**
 * Быстрое автономное извлечение длительности трека без тяжёлых библиотек и без интернета
 */
private fun extractAudioDuration(file: File): Long {
    return io.github.audiz.util.AudioHeaderParser.extractAudioDuration(file)
}

/** Сохранить список треков плейлиста в локальный кеш на диске: {basePath}/playlists_cache/{playlistTitle}.json */
actual fun savePlaylistTracksCache(basePath: String, playlistTitle: String, tracks: List<FullTrackInfo>) {
    if (basePath.isBlank() || tracks.isEmpty()) return
    try {
        val cacheDir = getPlaylistCacheDir(basePath)
        val file = File(cacheDir, "${sanitizePlaylistFileName(playlistTitle)}.json")
        val jsonStr = playlistJson.encodeToString(
            ListSerializer(FullTrackInfo.serializer()),
            tracks
        )
        file.writeText(jsonStr)
        println("MusicStorage: Кеш плейлиста сохранен (${tracks.size} треков): ${file.absolutePath}")
    } catch (e: Exception) {
        println("MusicStorage: Ошибка сохранения кеша плейлиста '$playlistTitle': ${e.message}")
    }
}

/** Загрузить список треков плейлиста из локального кеша на диске */
actual fun loadPlaylistTracksCache(basePath: String, playlistTitle: String): List<FullTrackInfo> {
    if (basePath.isBlank()) return emptyList()
    try {
        val cacheDir = File(basePath, "playlists_cache")
        val file = File(cacheDir, "${sanitizePlaylistFileName(playlistTitle)}.json")
        if (!file.exists() || file.length() == 0L) return emptyList()
        val jsonStr = file.readText()
        val list = playlistJson.decodeFromString(
            ListSerializer(FullTrackInfo.serializer()),
            jsonStr
        )
        println("MusicStorage: Загружен кеш плейлиста '$playlistTitle' (${list.size} треков)")
        return list
    } catch (e: Exception) {
        println("MusicStorage: Ошибка чтения кеша плейлиста '$playlistTitle': ${e.message}")
        return emptyList()
    }
}

/** Сохранить список персональных плейлистов в кеш для бокового меню */
actual fun savePersonalPlaylistsCache(basePath: String, items: List<PersonalPlaylistItemData>) {
    if (basePath.isBlank() || items.isEmpty()) return
    try {
        val cacheDir = getPlaylistCacheDir(basePath)
        val file = File(cacheDir, "_personal_playlists.json")
        val jsonStr = playlistJson.encodeToString(
            ListSerializer(PersonalPlaylistItemData.serializer()),
            items
        )
        file.writeText(jsonStr)
        println("MusicStorage: Сохранен кеш персональных плейлистов (${items.size} шт)")
    } catch (e: Exception) {
        println("MusicStorage: Ошибка сохранения кеша персональных плейлистов: ${e.message}")
    }
}

/** Загрузить список персональных плейлистов из кеша */
actual fun loadPersonalPlaylistsCache(basePath: String): List<PersonalPlaylistItemData> {
    if (basePath.isBlank()) return emptyList()
    try {
        val cacheDir = File(basePath, "playlists_cache")
        val file = File(cacheDir, "_personal_playlists.json")
        if (!file.exists() || file.length() == 0L) return emptyList()
        val jsonStr = file.readText()
        val list = playlistJson.decodeFromString(
            ListSerializer(PersonalPlaylistItemData.serializer()),
            jsonStr
        )
        println("MusicStorage: Загружен кеш персональных плейлистов (${list.size} шт)")
        return list
    } catch (e: Exception) {
        println("MusicStorage: Ошибка чтения кеша персональных плейлистов: ${e.message}")
        return emptyList()
    }
}

/** Сохранить список пользовательских плейлистов из Яндекса в кеш */
actual fun saveUserPlaylistsCache(basePath: String, items: List<PlaylistInfo>) {
    if (basePath.isBlank() || items.isEmpty()) return
    try {
        val cacheDir = getPlaylistCacheDir(basePath)
        val file = File(cacheDir, "_user_playlists.json")
        val jsonStr = playlistJson.encodeToString(
            ListSerializer(PlaylistInfo.serializer()),
            items
        )
        file.writeText(jsonStr)
        println("MusicStorage: Сохранен кеш пользовательских плейлистов Яндекса (${items.size} шт)")
    } catch (e: Exception) {
        println("MusicStorage: Ошибка сохранения кеша пользовательских плейлистов: ${e.message}")
    }
}

/** Загрузить список пользовательских плейлистов из Яндекса из кеша */
actual fun loadUserPlaylistsCache(basePath: String): List<PlaylistInfo> {
    if (basePath.isBlank()) return emptyList()
    try {
        val cacheDir = File(basePath, "playlists_cache")
        val file = File(cacheDir, "_user_playlists.json")
        if (!file.exists() || file.length() == 0L) return emptyList()
        val jsonStr = file.readText()
        val list = playlistJson.decodeFromString(
            ListSerializer(PlaylistInfo.serializer()),
            jsonStr
        )
        println("MusicStorage: Загружен кеш пользовательских плейлистов Яндекса (${list.size} шт)")
        return list
    } catch (e: Exception) {
        println("MusicStorage: Ошибка чтения кеша пользовательских плейлистов: ${e.message}")
        return emptyList()
    }
}

/** Сохранить список локальных оффлайн-плейлистов на диск */
actual fun saveLocalPlaylists(basePath: String, playlists: List<LocalPlaylist>) {
    if (basePath.isBlank()) return
    try {
        val dir = File(basePath, "playlists")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "local_playlists.json")
        val jsonStr = playlistJson.encodeToString(
            ListSerializer(LocalPlaylist.serializer()),
            playlists
        )
        file.writeText(jsonStr)
        println("MusicStorage: Сохранено ${playlists.size} локальных плейлистов в ${file.absolutePath}")
    } catch (e: Exception) {
        println("MusicStorage: Ошибка сохранения локальных плейлистов: ${e.message}")
    }
}

/** Загрузить список локальных оффлайн-плейлистов из {basePath}/playlists/local_playlists.json */
actual fun loadLocalPlaylists(basePath: String): List<LocalPlaylist> {
    if (basePath.isBlank()) return emptyList()
    try {
        val dir = File(basePath, "playlists")
        val file = File(dir, "local_playlists.json")
        if (!file.exists() || file.length() == 0L) return emptyList()
        val jsonStr = file.readText()
        val list = playlistJson.decodeFromString(
            ListSerializer(LocalPlaylist.serializer()),
            jsonStr
        )
        var anyMigrated = false
        val migratedList = list.map { playlist ->
            var changed = false
            // 1. Однократное разрешение путей с сохранением только реально существующих новых путей
            val updatedTracks = playlist.trackPaths.map { originalPath ->
                val resolved = resolveLocalPath(originalPath)
                if (resolved != originalPath && File(resolved).exists()) {
                    changed = true
                    resolved
                } else {
                    originalPath
                }
            }
            // 2. Строгая дедупликация по нормализованному имени файла / пути
            val seenNames = mutableSetOf<String>()
            val distinctTracks = mutableListOf<String>()
            for (p in updatedTracks) {
                val normName = p.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
                if (normName.isNotBlank() && seenNames.add(normName)) {
                    distinctTracks.add(p)
                } else if (normName.isBlank() && !distinctTracks.contains(p)) {
                    distinctTracks.add(p)
                }
            }
            if (distinctTracks.size != playlist.trackPaths.size) {
                changed = true
            }
            if (changed) {
                anyMigrated = true
                playlist.copy(trackPaths = distinctTracks)
            } else {
                playlist
            }
        }
        if (anyMigrated) {
            saveLocalPlaylists(basePath, migratedList)
            println("MusicStorage: Локальные плейлисты успешно обновлены с новыми путями и дедупликацией")
        }
        println("MusicStorage: Загружено ${migratedList.size} локальных плейлистов")
        return migratedList
    } catch (e: Exception) {
        println("MusicStorage: Ошибка чтения локальных плейлистов: ${e.message}")
        return emptyList()
    }
}

/** Экспортировать локальный плейлист в формат M3U8 */
actual fun exportPlaylistToM3u8(basePath: String, playlist: LocalPlaylist, tracks: List<FullTrackInfo>): String {
    if (basePath.isBlank()) return ""
    try {
        val dir = File(basePath, "playlists")
        if (!dir.exists()) dir.mkdirs()
        val safeName = sanitizePlaylistFileName(playlist.title.ifBlank { "Playlist" })
        val file = File(dir, "$safeName.m3u8")
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
        file.writeText(sb.toString())
        println("MusicStorage: Экспортирован плейлист M3U8: ${file.absolutePath}")
        return file.absolutePath
    } catch (e: Exception) {
        println("MusicStorage: Ошибка экспорта M3U8: ${e.message}")
        return ""
    }
}

/** Преобразовать список локальных путей к файлам в список объектов FullTrackInfo с проверкой наличия на диске */
actual fun getTracksFromLocalPaths(paths: List<String>): List<FullTrackInfo> {
    val results = mutableListOf<FullTrackInfo>()
    val delimiters = listOf(" — ", " – ", " - ", "_—_", "_-_")
    var cachedScanned: List<FullTrackInfo>? = null

    for (p in paths) {
        val resolved = resolveLocalPath(p)
        var file = File(resolved)
        if (!file.exists() || !file.isFile) {
            val fileName = p.substringAfterLast('/').substringAfterLast('\\')
            if (fileName.isNotBlank()) {
                val fallbackResolved = resolveLocalPath(fileName)
                val fallbackFile = File(fallbackResolved)
                if (fallbackFile.exists() && fallbackFile.isFile) {
                    file = fallbackFile
                } else {
                    if (cachedScanned == null) {
                        val baseMusic = loadMusicStoragePath()?.takeIf { File(it).exists() } ?: getDefaultMusicDir()
                        cachedScanned = scanDownloadedTracks(baseMusic)
                    }
                    val matched = cachedScanned.firstOrNull {
                        val real = it.realId ?: it.id.removePrefix("local:")
                        real.substringAfterLast('/').substringAfterLast('\\').equals(fileName, ignoreCase = true)
                    }
                    if (matched != null) {
                        val matchedPath = matched.realId?.ifBlank { matched.id.removePrefix("local:") } ?: matched.id.removePrefix("local:")
                        val mf = File(matchedPath)
                        if (mf.exists() && mf.isFile) {
                            file = mf
                        }
                    }
                }
            }
        }

        if (!file.exists() || !file.isFile) {
            println("MusicStorage: ⚠️ Трек плейлиста не найден на диске: '$p'")
            appendLogToFile("MusicStorage: ⚠️ Трек плейлиста не найден на диске: '$p'")
            continue
        }
        val meta = io.github.audiz.util.AudioHeaderParser.extractAudioMetadata(file)
        val rawName = file.nameWithoutExtension
        var artist = meta.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        var title = meta.title?.takeIf { it.isNotBlank() } ?: rawName
        if (meta.title.isNullOrBlank() || meta.artist.isNullOrBlank()) {
            for (delim in delimiters) {
                if (rawName.contains(delim)) {
                    val parts = rawName.split(delim, limit = 2)
                    if (meta.artist.isNullOrBlank()) artist = parts[0].trim()
                    if (meta.title.isNullOrBlank()) title = parts[1].trim()
                    break
                }
            }
        }
        val durationMs = if (meta.durationMs > 0L) meta.durationMs else extractAudioDuration(file)
        val normalizedPath = file.absolutePath.replace('\\', '/')
        val isHQ = normalizedPath.contains("/HQ/", ignoreCase = true) || normalizedPath.endsWith("/HQ", ignoreCase = true)
        val albumQuality = if (isHQ) "Локальный (HQ)" else meta.album?.takeIf { it.isNotBlank() } ?: "Локальный"
        results.add(
            FullTrackInfo(
                id = "local:${file.absolutePath}",
                realId = file.absolutePath,
                title = title,
                available = true,
                durationMs = durationMs,
                artists = listOf(FullArtistInfo(id = 0L, name = artist)),
                albums = listOf(FullAlbumInfo(id = 0L, title = albumQuality, year = null)),
                coverUri = "local:${file.absolutePath}"
            )
        )
    }
    return results
}


/** Удалить аудиофайл трека с диска (и пустую папку артиста, если файлов больше нет) */
actual fun deleteTrackFile(basePath: String, artist: String, trackTitle: String, localFilePath: String?): Boolean {
    try {
        // 1. Если передан прямой локальный путь
        val rawLocal = localFilePath?.trim()
            ?.removePrefix("local:")
            ?.removePrefix("file://")
            ?.removePrefix("file:")
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")

        val cleanLocal = if (rawLocal != null && rawLocal.startsWith("~/")) {
            System.getProperty("user.home") + rawLocal.substring(1)
        } else if (rawLocal == "~") {
            System.getProperty("user.home")
        } else {
            rawLocal
        }

        if (!cleanLocal.isNullOrBlank()) {
            val resolved = resolveLocalPath(cleanLocal)
            val file = File(resolved)
            if (file.exists() && file.isFile) {
                val parent = file.parentFile
                val deleted = try {
                    java.nio.file.Files.deleteIfExists(file.toPath())
                } catch (e: Exception) {
                    file.delete()
                }
                if (deleted) {
                    println("MusicStorage: Удален локальный файл: ${file.absolutePath}")
                    if (basePath.isNotBlank()) {
                        cleanUpEmptyDir(parent, basePath)
                    }
                    return true
                }
            }

            if (basePath.isNotBlank()) {
                val relFile = File(basePath, cleanLocal)
                if (relFile.exists() && relFile.isFile) {
                    val parent = relFile.parentFile
                    val deleted = try {
                        java.nio.file.Files.deleteIfExists(relFile.toPath())
                    } catch (e: Exception) {
                        relFile.delete()
                    }
                    if (deleted) {
                        println("MusicStorage: Удален относительный файл: ${relFile.absolutePath}")
                        cleanUpEmptyDir(parent, basePath)
                        return true
                    }
                }
            }
        }

        if (basePath.isBlank()) return false

        val cleanArtist = artist.trim()
        val cleanTitle = trackTitle.trim()
        val sanitizedArtist = sanitizeDirName(cleanArtist.ifBlank { "Unknown Artist" })
        val knownExtensions = listOf("flac", "m4a", "aac", "mp3", "opus", "wav", "ogg")
        val candidateFolders = listOf(File(basePath, "HQ"), File(basePath, "LQ"), File(basePath))

        var wasDeleted = false
        for (folder in candidateFolders) {
            if (!folder.exists()) continue
            val artistDirs = listOfNotNull(
                File(folder, sanitizedArtist),
                if (cleanArtist.isNotBlank()) File(folder, sanitizeKeepSpaces(cleanArtist)) else null,
                if (cleanArtist.isNotBlank()) File(folder, cleanArtist) else null
            ).distinct()

            for (artistDir in artistDirs) {
                if (artistDir.exists() && artistDir.isDirectory) {
                    for (ext in knownExtensions) {
                        val candidateNames = mutableListOf<String>()
                        if (cleanArtist.isNotEmpty()) {
                            candidateNames.add(sanitizeDirName("$cleanArtist — $cleanTitle.$ext"))
                            candidateNames.add(sanitizeDirName("$cleanArtist - $cleanTitle.$ext"))
                            candidateNames.add(sanitizeKeepSpaces("$cleanArtist — $cleanTitle.$ext"))
                            candidateNames.add(sanitizeKeepSpaces("$cleanArtist - $cleanTitle.$ext"))
                            candidateNames.add("$cleanArtist — $cleanTitle.$ext")
                            candidateNames.add("$cleanArtist - $cleanTitle.$ext")
                        }
                        candidateNames.add(sanitizeDirName("$cleanTitle.$ext"))
                        candidateNames.add(sanitizeKeepSpaces("$cleanTitle.$ext"))
                        candidateNames.add("$cleanTitle.$ext")

                        for (name in candidateNames.distinct()) {
                            val fInArtist = File(artistDir, name)
                            if (fInArtist.exists() && fInArtist.isFile) {
                                val deleted = try {
                                    java.nio.file.Files.deleteIfExists(fInArtist.toPath())
                                } catch (e: Exception) {
                                    fInArtist.delete()
                                }
                                if (deleted) {
                                    println("MusicStorage: Удален файл: ${fInArtist.absolutePath}")
                                    wasDeleted = true
                                    cleanUpEmptyDir(artistDir, basePath)
                                }
                            }
                        }
                    }

                    // Дополнительный поиск в папке артиста по частичному имени, если точное совпадение не сработало
                    if (!wasDeleted && cleanTitle.isNotBlank()) {
                        val files = artistDir.listFiles()
                        if (files != null) {
                            for (f in files) {
                                if (!f.isFile) continue
                                val ext = f.extension.lowercase()
                                if (ext !in knownExtensions) continue
                                val nameWithoutExt = f.nameWithoutExtension
                                val matches = nameWithoutExt.equals(cleanTitle, ignoreCase = true) ||
                                    (cleanArtist.isNotBlank() && (
                                        nameWithoutExt.equals("$cleanArtist — $cleanTitle", ignoreCase = true) ||
                                        nameWithoutExt.equals("$cleanArtist - $cleanTitle", ignoreCase = true)
                                    ))
                                if (matches) {
                                    val deleted = try {
                                        java.nio.file.Files.deleteIfExists(f.toPath())
                                    } catch (e: Exception) {
                                        f.delete()
                                    }
                                    if (deleted) {
                                        println("MusicStorage: Удален файл по сопоставлению: ${f.absolutePath}")
                                        wasDeleted = true
                                        cleanUpEmptyDir(artistDir, basePath)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Проверяем также файлы напрямую в папке quality/base
            for (ext in knownExtensions) {
                val candidateNames = mutableListOf<String>()
                if (cleanArtist.isNotEmpty()) {
                    candidateNames.add(sanitizeDirName("$cleanArtist — $cleanTitle.$ext"))
                    candidateNames.add(sanitizeDirName("$cleanArtist - $cleanTitle.$ext"))
                    candidateNames.add(sanitizeKeepSpaces("$cleanArtist — $cleanTitle.$ext"))
                    candidateNames.add(sanitizeKeepSpaces("$cleanArtist - $cleanTitle.$ext"))
                    candidateNames.add("$cleanArtist — $cleanTitle.$ext")
                    candidateNames.add("$cleanArtist - $cleanTitle.$ext")
                }
                candidateNames.add(sanitizeDirName("$cleanTitle.$ext"))
                candidateNames.add(sanitizeKeepSpaces("$cleanTitle.$ext"))
                candidateNames.add("$cleanTitle.$ext")

                for (name in candidateNames.distinct()) {
                    val fDirect = File(folder, name)
                    if (fDirect.exists() && fDirect.isFile) {
                        val deleted = try {
                            java.nio.file.Files.deleteIfExists(fDirect.toPath())
                        } catch (e: Exception) {
                            fDirect.delete()
                        }
                        if (deleted) {
                            println("MusicStorage: Удален файл: ${fDirect.absolutePath}")
                            wasDeleted = true
                        }
                    }
                }
            }
        }
        return wasDeleted
    } catch (e: Exception) {
        println("MusicStorage: Ошибка при удалении трека '$artist - $trackTitle': ${e.message}")
        return false
    }
}

/** Очистить весь кеш плейлистов: удаляет все файлы из {basePath}/playlists_cache/ */
actual fun clearPlaylistsCache(basePath: String): Int {
    if (basePath.isBlank()) return 0
    var count = 0
    try {
        val cacheDir = File(basePath, "playlists_cache")
        if (cacheDir.exists() && cacheDir.isDirectory) {
            val files = cacheDir.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isFile && file.delete()) {
                        count++
                    }
                }
            }
            println("MusicStorage: Очищен кеш плейлистов ($count файлов удалено)")
        }
    } catch (e: Exception) {
        println("MusicStorage: Ошибка при очистке кеша плейлистов: ${e.message}")
    }
    return count
}

/** Удалить все скачанные треки из папок HQ, LQ и корневой директории */
actual fun clearAllDownloadedMusic(basePath: String): Int {
    if (basePath.isBlank()) return 0
    var count = 0
    val audioExtensions = setOf("flac", "m4a", "aac", "mp3", "opus", "wav", "ogg")
    try {
        val folders = listOf(File(basePath, "YamSync"), File(basePath, "HQ"), File(basePath, "LQ"), File(basePath))
        for (folder in folders) {
            if (!folder.exists() || !folder.isDirectory) continue
            // Удаляем аудиофайлы рекурсивно (но не заходя в playlists_cache и playlists!)
            folder.walkBottomUp().forEach { file ->
                if (file.isFile && audioExtensions.contains(file.extension.lowercase())) {
                    if (file.delete()) {
                        count++
                    }
                } else if (file.isDirectory && file != folder && file.name != "playlists_cache" && file.name != "playlists") {
                    // Удаляем пустые папки
                    if (file.listFiles()?.isEmpty() == true) {
                        file.delete()
                    }
                }
            }
        }
        println("MusicStorage: Удалены все скачанные треки ($count шт.)")
    } catch (e: Exception) {
        println("MusicStorage: Ошибка при удалении скачанной музыки: ${e.message}")
    }
    return count
}

actual fun getLogFilePath(): String {
    val dir = getDefaultMusicDir()
    val file = if (dir.isNotBlank()) File(dir, "yamusic.log") else File("yamusic.log")
    return file.absolutePath
}

actual fun appendLogToFile(line: String) {
    try {
        val file = File(getLogFilePath())
        file.parentFile?.mkdirs()
        file.appendText("$line\n")
    } catch (_: Throwable) {}
}

/** Получить листинг содержимого директории (подпапки и аудиофайлы) для встроенного проводника */
actual fun listFolderContents(folderPath: String, rootPath: String?): FolderListing {
    val dir = File(folderPath)
    if (!dir.exists() || !dir.isDirectory) {
        return FolderListing(folderPath, null, rootPath, emptyList(), emptyList())
    }

    val supportedExtensions = setOf("mp3", "flac", "m4a", "aac", "opus", "wav", "ogg")
    val subfolders = mutableListOf<FolderItem>()
    val audioFilePaths = mutableListOf<String>()

    val files = dir.listFiles() ?: emptyArray()
    for (f in files) {
        if (f.name.startsWith(".")) continue
        if (f.isDirectory) {
            val audioCount = try {
                f.walkTopDown().maxDepth(10).count { it.isFile && it.extension.lowercase() in supportedExtensions }
            } catch (_: Exception) { 0 }

            subfolders.add(
                FolderItem(
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = true,
                    trackCount = audioCount
                )
            )
        } else if (f.isFile && f.extension.lowercase() in supportedExtensions) {
            audioFilePaths.add(f.absolutePath)
        }
    }

    subfolders.sortBy { it.name.lowercase() }
    val tracks = getTracksFromLocalPaths(audioFilePaths).sortedBy { it.title.lowercase() }

    val parent = if (rootPath != null && dir.absolutePath.equals(rootPath, ignoreCase = true)) {
        null
    } else {
        dir.parentFile?.absolutePath
    }

    return FolderListing(
        currentPath = dir.absolutePath,
        parentPath = parent,
        rootPath = rootPath ?: dir.absolutePath,
        subfolders = subfolders,
        tracks = tracks
    )
}

/**
 * Загрузить байты обложки локального аудиофайла (встроенный тег APIC/PICTURE/covr или файл обложки в папке)
 */
actual fun loadLocalCoverBytes(pathOrUri: String): ByteArray? {
    val cleanPath = pathOrUri
        .removePrefix("local:")
        .removePrefix("file://")
        .removePrefix("file:")
    val file = File(cleanPath)
    if (!file.exists()) return null

    val ext = file.extension.lowercase()
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
    if (ext in imageExtensions) {
        return try {
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    return io.github.audiz.util.AudioHeaderParser.extractCoverArtBytes(file)
}

/**
 * Получить локальный URL/URI к обложке трека (для MPRIS/системных уведомлений)
 */
actual fun getLocalCoverArtUrl(pathOrUri: String): String? {
    val cleanPath = pathOrUri
        .removePrefix("local:")
        .removePrefix("file://")
        .removePrefix("file:")
    val file = File(cleanPath)
    if (!file.exists()) return null

    val ext = file.extension.lowercase()
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
    if (ext in imageExtensions) {
        return "file://${file.absolutePath}"
    }

    // 1. Проверяем наличие файла обложки в папке альбома
    val folderCover = io.github.audiz.util.AudioHeaderParser.findFolderCoverFile(file)
    if (folderCover != null && folderCover.exists() && folderCover.length() > 0L) {
        return "file://${folderCover.absolutePath}"
    }

    // 2. Если в папке файла нет, извлекаем встроенную обложку и кэшируем на диск
    val bytes = io.github.audiz.util.AudioHeaderParser.extractCoverArtBytes(file) ?: return null
    return try {
        val baseDir = getDefaultMusicDir().ifBlank { System.getProperty("java.io.tmpdir") ?: "." }
        val cacheDir = File(baseDir, ".covers_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val hash = (file.absolutePath + file.length() + file.lastModified()).hashCode().toUInt().toString(16)
        val cachedFile = File(cacheDir, "cover_$hash.jpg")
        if (!cachedFile.exists() || cachedFile.length() == 0L) {
            cachedFile.writeBytes(bytes)
        }
        "file://${cachedFile.absolutePath}"
    } catch (_: Exception) {
        null
    }
}


