package io.github.audiz

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

/** Очистка имени папки/файла от запрещённых символов */
private fun sanitizeDirName(name: String): String {
    return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
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
    val cache = File(base, "playlists_cache").canonicalFile
    val current = dir.canonicalFile

    if (current == base || current == hq || current == lq || current == cache) return

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

    val outputFile = File(artistDir, fileName)
    outputFile.writeBytes(bytes)
    println("MusicStorage: Сохранён файл: ${outputFile.absolutePath}")
}

/** Проверить, существует ли трек на диске */
actual fun trackFileExists(basePath: String, artist: String, fileName: String): Boolean {
    val cleanArtist = sanitizeDirName(artist.ifBlank { "Unknown Artist" })
    val file = File(File(basePath, cleanArtist), fileName)
    return file.exists()
}

/** Проверить существование файла по прямому пути */
actual fun localFileExists(filePath: String): Boolean {
    return File(filePath).exists()
}

/**
 * 📁 Сканирует локальную папку с сохранёнными аудиофайлами без обращения в интернет.
 * Распознает M4A, FLAC, MP3, AAC, OPUS, WAV, OGG.
 * Определяет исполнителя, название трека, качество (HQ/LQ) и длительность из структуры папок и заголовков файлов.
 */
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
            val rawName = file.nameWithoutExtension
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
                val parent = file.parentFile?.name?.trim() ?: ""
                val ignoreParents = setOf("hq", "lq", "music", "yandexdownloader", "download", "downloads", rootDir.name.lowercase())
                if (parent.isNotBlank() && parent.lowercase() !in ignoreParents) {
                    artist = parent
                } else {
                    artist = "Unknown Artist"
                }
            }

            val normalizedPath = file.absolutePath.replace('\\', '/')
            val isHQ = normalizedPath.contains("/HQ/", ignoreCase = true) || normalizedPath.endsWith("/HQ", ignoreCase = true)
            val isLQ = normalizedPath.contains("/LQ/", ignoreCase = true) || normalizedPath.endsWith("/LQ", ignoreCase = true)
            val albumQuality = when {
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
                    albums = listOf(FullAlbumInfo(id = 0L, title = albumQuality))
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
    // 1. Проверяем MP4/M4A контейнер (основной контейнер Яндекса для m4a, aac и flac)
    val mp4Dur = extractM4aDuration(file)
    if (mp4Dur > 0L) return mp4Dur

    // 2. Если не MP4 контейнер, пробуем парсер нативного FLAC
    val flacDur = extractFlacDuration(file)
    if (flacDur > 0L) return flacDur

    return 0L
}

/**
 * Парсер MP4/M4A атомов для получения точной длительности из заголовка mvhd
 */
private fun extractM4aDuration(file: File): Long {
    try {
        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            while (raf.filePointer + 8 <= fileLen) {
                val size = raf.readInt().toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)

                if (type == "moov") {
                    val moovEnd = raf.filePointer - 8 + size
                    while (raf.filePointer + 8 <= moovEnd && raf.filePointer + 8 <= fileLen) {
                        val subSize = raf.readInt().toLong() and 0xFFFFFFFFL
                        val subTypeBytes = ByteArray(4)
                        raf.readFully(subTypeBytes)
                        val subType = String(subTypeBytes, Charsets.US_ASCII)

                        if (subType == "mvhd") {
                            val version = raf.readByte().toInt()
                            raf.skipBytes(3) // flags
                            return if (version == 0) {
                                raf.skipBytes(8) // creation + modification time
                                val timescale = raf.readInt().toLong() and 0xFFFFFFFFL
                                val duration = raf.readInt().toLong() and 0xFFFFFFFFL
                                if (timescale > 0) (duration * 1000L) / timescale else 0L
                            } else {
                                raf.skipBytes(16) // 64-bit creation + modification time
                                val timescale = raf.readInt().toLong() and 0xFFFFFFFFL
                                val duration = raf.readLong()
                                if (timescale > 0) (duration * 1000L) / timescale else 0L
                            }
                        }
                        if (subSize <= 8) break
                        raf.seek(raf.filePointer - 8 + subSize)
                    }
                    break
                }
                if (size <= 8) break
                raf.seek(raf.filePointer - 8 + size)
            }
        }
    } catch (_: Exception) {
        // Игнорируем ошибки парсинга, плеер определит длительность при запуске
    }
    return 0L
}

/**
 * Парсер заголовка FLAC STREAMINFO для получения точной длительности
 */
private fun extractFlacDuration(file: File): Long {
    try {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "fLaC") return 0L

            val blockHeader = raf.readInt()
            val blockType = (blockHeader ushr 24) and 0x7F
            val blockLength = blockHeader and 0x00FFFFFF

            if (blockType == 0 && blockLength >= 34) {
                raf.skipBytes(10) // min/max block size (4) + min/max frame size (6)
                val b0 = raf.read().toLong() and 0xFF
                val b1 = raf.read().toLong() and 0xFF
                val b2 = raf.read().toLong() and 0xFF
                val b3 = raf.read().toLong() and 0xFF
                val b4 = raf.read().toLong() and 0xFF
                val b5 = raf.read().toLong() and 0xFF
                val b6 = raf.read().toLong() and 0xFF
                val b7 = raf.read().toLong() and 0xFF

                val sampleRate = (b0 shl 12) or (b1 shl 4) or (b2 ushr 4)
                val totalSamples = ((b3 and 0x0FL) shl 32) or (b4 shl 24) or (b5 shl 16) or (b6 shl 8) or b7

                if (sampleRate > 0) {
                    return (totalSamples * 1000L) / sampleRate
                }
            }
        }
    } catch (_: Exception) {
        // Игнорируем ошибки парсинга
    }
    return 0L
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
        println("MusicStorage: Загружено ${list.size} локальных плейлистов")
        return list
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
    for (p in paths) {
        val file = File(p)
        if (!file.exists() || !file.isFile) continue
        val rawName = file.nameWithoutExtension
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
        val durationMs = extractAudioDuration(file)
        val normalizedPath = file.absolutePath.replace('\\', '/')
        val isHQ = normalizedPath.contains("/HQ/", ignoreCase = true) || normalizedPath.endsWith("/HQ", ignoreCase = true)
        val albumQuality = if (isHQ) "Локальный (HQ)" else "Локальный"
        results.add(
            FullTrackInfo(
                id = "local:${file.absolutePath}",
                realId = file.absolutePath,
                title = title,
                available = true,
                durationMs = durationMs,
                artists = listOf(FullArtistInfo(id = 0L, name = artist)),
                albums = listOf(FullAlbumInfo(id = 0L, title = albumQuality, year = null))
            )
        )
    }
    return results
}


/** Удалить аудиофайл трека с диска (и пустую папку артиста, если файлов больше нет) */
actual fun deleteTrackFile(basePath: String, artist: String, trackTitle: String, localFilePath: String?): Boolean {
    if (basePath.isBlank()) return false
    try {
        // 1. Если передан прямой локальный путь
        if (!localFilePath.isNullOrBlank()) {
            val file = File(localFilePath)
            if (file.exists() && file.isFile) {
                val parent = file.parentFile
                val deleted = file.delete()
                if (deleted) {
                    println("MusicStorage: Удален локальный файл: ${file.absolutePath}")
                    cleanUpEmptyDir(parent, basePath)
                    return true
                }
            }
        }

        val cleanArtist = artist.trim()
        val cleanTitle = trackTitle.trim()
        val sanitizedArtist = sanitizeDirName(cleanArtist.ifBlank { "Unknown Artist" })
        val knownExtensions = listOf("flac", "m4a", "aac", "mp3", "opus", "wav", "ogg")
        val candidateFolders = listOf(File(basePath, "HQ"), File(basePath, "LQ"), File(basePath))

        var wasDeleted = false
        for (folder in candidateFolders) {
            if (!folder.exists()) continue
            val artistDir = File(folder, sanitizedArtist)
            for (ext in knownExtensions) {
                val candidateNames = mutableListOf<String>()
                if (cleanArtist.isNotEmpty()) {
                    candidateNames.add(sanitizeDirName("$cleanArtist — $cleanTitle.$ext"))
                }
                candidateNames.add(sanitizeDirName("$cleanTitle.$ext"))

                for (name in candidateNames) {
                    // Проверяем внутри папки артиста
                    val fInArtist = File(artistDir, name)
                    if (fInArtist.exists() && fInArtist.isFile) {
                        if (fInArtist.delete()) {
                            println("MusicStorage: Удален файл: ${fInArtist.absolutePath}")
                            wasDeleted = true
                            cleanUpEmptyDir(artistDir, basePath)
                        }
                    }
                    // Проверяем напрямую в папке (HQ/LQ/base)
                    val fDirect = File(folder, name)
                    if (fDirect.exists() && fDirect.isFile) {
                        if (fDirect.delete()) {
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
        val folders = listOf(File(basePath, "HQ"), File(basePath, "LQ"), File(basePath))
        for (folder in folders) {
            if (!folder.exists() || !folder.isDirectory) continue
            // Удаляем аудиофайлы рекурсивно (но не заходя в playlists_cache!)
            folder.walkBottomUp().forEach { file ->
                if (file.isFile && audioExtensions.contains(file.extension.lowercase())) {
                    if (file.delete()) {
                        count++
                    }
                } else if (file.isDirectory && file != folder && file.name != "playlists_cache") {
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
