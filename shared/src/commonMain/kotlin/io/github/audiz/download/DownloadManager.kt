package io.github.audiz.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.DispatcherIO
import io.github.audiz.api.MusicRepository
import io.github.audiz.clearAllDownloadedMusic
import io.github.audiz.clearPlaylistsCache
import io.github.audiz.copyFileToDirectPath
import io.github.audiz.copyFileToFolder
import io.github.audiz.deleteTrackFile
import io.github.audiz.loadAppConfig
import io.github.audiz.localFileExists
import io.github.audiz.player.LocalTrackResolver
import io.github.audiz.saveAppConfig
import io.github.audiz.saveFileToDirectPath
import io.github.audiz.saveTrackFile
import io.github.audiz.saveTrackToFolder
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.isLocalTrackId
import io.github.audiz.trackFileExists
import io.github.audiz.ui.CoverImageLoader
import io.github.audiz.util.AudioMetadataTagger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 💾 Менеджер скачивания и управления аудиофайлами на диске.
 * Инкапсулирует:
 * - Скачивание трека по клику (с выбором качества HQ/LQ).
 * - Удаление трека с диска и очистку хранилища.
 * - Режим «Record to disk» (автосохранение треков при воспроизведении).
 * - Проверку наличия трека на диске во всех подпапках качества.
 * - Версионирование локальной библиотеки (downloadVersion) для реактивного обновления карточек UI.
 */
class DownloadManager(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    val localTrackResolver: LocalTrackResolver,
    private val getSelectedQuality: () -> String,
    private val getPlayingTrackId: () -> String?,
    private val getPlayingTrackTitle: () -> String,
    private val getPlayingArtistName: () -> String,
    private val getPlayingFilePath: () -> String?,
    private val onUpdatePlayingFilePath: (String) -> Unit = {},
    private val onStopPlayback: () -> Unit = {},
    private val onTrackDeleted: ((trackId: String, wasPlaying: Boolean) -> Unit)? = null,
    private val onAllTracksCleared: (() -> Unit)? = null,
    private val onError: (String) -> Unit = {},
    private val onStatusMessage: (String) -> Unit = {},
    private val findTrackInfo: ((String) -> FullTrackInfo?)? = null,
    private val getPlayingTrackCoverUrl: (() -> String?)? = null
) {
    /**
     * Вторичный конструктор для 100% обратной совместимости.
     */
    constructor(
        scope: CoroutineScope,
        repository: MusicRepository,
        getMusicStoragePath: () -> String,
        getSelectedQuality: () -> String,
        getPlayingTrackId: () -> String?,
        getPlayingTrackTitle: () -> String,
        getPlayingArtistName: () -> String,
        getPlayingFilePath: () -> String?,
        onUpdatePlayingFilePath: (String) -> Unit = {},
        onStopPlayback: () -> Unit = {},
        onTrackDeleted: ((trackId: String, wasPlaying: Boolean) -> Unit)? = null,
        onAllTracksCleared: (() -> Unit)? = null,
        onError: (String) -> Unit = {},
        onStatusMessage: (String) -> Unit = {},
        findTrackInfo: ((String) -> FullTrackInfo?)? = null,
        getPlayingTrackCoverUrl: (() -> String?)? = null
    ) : this(
        scope = scope,
        repository = repository,
        localTrackResolver = LocalTrackResolver(getMusicStoragePath),
        getSelectedQuality = getSelectedQuality,
        getPlayingTrackId = getPlayingTrackId,
        getPlayingTrackTitle = getPlayingTrackTitle,
        getPlayingArtistName = getPlayingArtistName,
        getPlayingFilePath = getPlayingFilePath,
        onUpdatePlayingFilePath = onUpdatePlayingFilePath,
        onStopPlayback = onStopPlayback,
        onTrackDeleted = onTrackDeleted,
        onAllTracksCleared = onAllTracksCleared,
        onError = onError,
        onStatusMessage = onStatusMessage,
        findTrackInfo = findTrackInfo,
        getPlayingTrackCoverUrl = getPlayingTrackCoverUrl
    )

    private val getMusicStoragePath: () -> String get() = localTrackResolver.getMusicStoragePath
    var downloadingTrackId by mutableStateOf<String?>(null)
        private set

    var isTrackDownloading by mutableStateOf(false)
        private set

    var downloadVersion by mutableStateOf(0)
        private set

    var isRecordToDiskActive by mutableStateOf(loadAppConfig(io.github.audiz.AppConfigKeys.RECORD_TO_DISK)?.toBooleanStrictOrNull() ?: false)
        private set

    fun incrementDownloadVersion() {
        localTrackResolver.invalidateCache()
        downloadVersion++
    }

    /**
     * Переключить режим автосохранения на диск при воспроизведении
     */
    fun toggleRecordToDisk(active: Boolean = !isRecordToDiskActive) {
        isRecordToDiskActive = active
        saveAppConfig(io.github.audiz.AppConfigKeys.RECORD_TO_DISK, active.toString())
        println("DownloadManager: Record to disk установлен в: $active")

        // Если включили сохранение во время воспроизведения несохраненного трека — сохраняем его на диск
        val playingId = getPlayingTrackId()
        if (active && getPlayingFilePath() == null && playingId != null) {
            downloadTrack(playingId, getPlayingTrackTitle(), getPlayingArtistName())
        }
    }

    /**
     * Проверяет, сохранен ли текущий воспроизводимый трек в библиотеке на диске
     */
    val isCurrentTrackSavedToDisk: Boolean
        get() {
            val id = getPlayingTrackId() ?: return false
            if (isLocalTrackId(id)) return true
            downloadVersion // чтение состояния для реактивной рекомпозиции
            return isTrackDownloaded(getPlayingArtistName(), getPlayingTrackTitle())
        }

    /**
     * Проверяет, идет ли скачивание текущего трека в библиотеку прямо сейчас
     */
    val isCurrentTrackSavingToDisk: Boolean
        get() = isTrackDownloading && downloadingTrackId == getPlayingTrackId()

    /**
     * Скачать текущий трек на диск или удалить его из библиотеки, если уже скачан
     */
    fun toggleCurrentTrackSaveOrDelete() {
        val trackId = getPlayingTrackId() ?: return
        val title = getPlayingTrackTitle()
        val artist = getPlayingArtistName()

        if (isCurrentTrackSavedToDisk) {
            println("DownloadManager: Кнопка сохранения: удаление трека из библиотеки: $artist — $title")
            deleteTrack(trackId, title, artist)
        } else {
            println("DownloadManager: Кнопка сохранения: принудительное скачивание трека: $artist — $title")
            downloadTrack(trackId, title, artist)
        }
    }

    /**
     * 🔥 Скачивание трека по клику (с автоматическим встраиванием обложки и метаданных)
     */
    fun downloadTrack(
        trackId: String,
        trackTitle: String,
        artistName: String,
        albumTitle: String? = null,
        year: String? = null,
        coverUri: String? = null
    ) {
        if (isTrackDownloading) return
        if (isLocalTrackId(trackId)) {
            onError("✅ Файл уже находится на диске: $trackTitle")
            return
        }

        scope.launch {
            isTrackDownloading = true
            downloadingTrackId = trackId
            try {
                val quality = getSelectedQuality()
                val audioData = repository.downloadTrackAudio(trackId, quality = quality)

                val rawExtension = audioData.type.substringBefore("-")
                val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension
                val cleanArtist = artistName.trim()
                val cleanTitle = trackTitle.trim()
                val fullFileName = sanitizeKeepSpaces(if (cleanArtist.isNotEmpty()) {
                    "$cleanArtist — $cleanTitle.$extension"
                } else {
                    "$cleanTitle.$extension"
                })

                val qualityFolder = if (quality == "2") "HQ" else "LQ"
                val storagePath = getMusicStoragePath()
                val basePath = "$storagePath/$qualityFolder"

                if (trackFileExists(basePath, cleanArtist, fullFileName)) {
                    println("⏭️ Трек уже существует: $fullFileName")
                    onError("✅ Уже скачан: $fullFileName")
                    return@launch
                }

                val taggedBytes = prepareAndTagAudio(
                    trackId = trackId,
                    cleanTitle = cleanTitle,
                    cleanArtist = cleanArtist,
                    extension = extension,
                    rawAudioBytes = audioData.result,
                    albumTitle = albumTitle,
                    year = year,
                    coverUri = coverUri
                )

                println("Saving to: $basePath / $cleanArtist / $fullFileName (size: ${taggedBytes.size} bytes)")
                saveTrackFile(basePath, cleanArtist, fullFileName, taggedBytes)
                downloadVersion++

                if (getPlayingTrackId() == trackId) {
                    onUpdatePlayingFilePath("$basePath/${sanitizeKeepSpaces(cleanArtist)}/$fullFileName")
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                val userMsg = if (msg.contains("connect", ignoreCase = true) || msg.contains("host", ignoreCase = true) || msg.contains("socket", ignoreCase = true)) {
                    "Не удалось скачать трек: нет подключения к интернету"
                } else {
                    "Ошибка скачивания трека: $msg"
                }
                onError(userMsg)
            } finally {
                isTrackDownloading = false
                downloadingTrackId = null
            }
        }
    }

    /**
     * 🗑️ Удалить трек с диска
     */
    fun deleteTrack(trackId: String, trackTitle: String, artistName: String) {
        scope.launch {
            try {
                val cleanArtist = artistName.trim()
                val cleanTitle = trackTitle.trim()

                // 1. Локальный путь, если trackId указывает на файл или начинается с "local:"
                val resolvedPath = when {
                    trackId.startsWith("local:") -> trackId.removePrefix("local:")
                    isLocalTrackId(trackId) -> trackId
                    localFileExists(trackId) -> trackId
                    else -> {
                        localTrackResolver.findLocalTrackFile(
                            trackId = trackId,
                            artist = cleanArtist,
                            title = cleanTitle,
                            selectedQuality = getSelectedQuality()
                        ) ?: localTrackResolver.getDownloadedTrackPath(cleanArtist, cleanTitle)
                    }
                }

                val playingFilePath = getPlayingFilePath()?.removePrefix("local:")
                val localPath = resolvedPath ?: if (
                    (getPlayingTrackId() == trackId || getPlayingTrackId()?.removePrefix("local:") == trackId.removePrefix("local:")) &&
                    playingFilePath != null && localFileExists(playingFilePath)
                ) {
                    playingFilePath
                } else null

                // 2. Проверяем, играет ли этот трек сейчас (с нормализацией префикса local: и проверкой localPath / playingFilePath)
                val playingId = getPlayingTrackId()
                val isPlayingCurrent = (playingId != null && (
                    playingId == trackId ||
                    playingId.removePrefix("local:") == trackId.removePrefix("local:") ||
                    (localPath != null && (playingId == localPath || playingId.removePrefix("local:") == localPath))
                )) || (playingFilePath != null && (
                    playingFilePath == trackId ||
                    playingFilePath == trackId.removePrefix("local:") ||
                    (localPath != null && playingFilePath == localPath)
                ))

                // Если трек сейчас играет, останавливаем его воспроизведение перед удалением файла с диска
                if (isPlayingCurrent) {
                    onStopPlayback()
                }
                val storagePath = getMusicStoragePath()

                // 3. Вызываем удаление файла на диске
                val success = withContext(DispatcherIO) {
                    deleteTrackFile(
                        basePath = storagePath,
                        artist = cleanArtist,
                        trackTitle = cleanTitle,
                        localFilePath = localPath
                    )
                }

                if (success) {
                    downloadVersion++
                    onTrackDeleted?.invoke(trackId, isPlayingCurrent)
                    onError("🗑️ Трек удален с диска: $cleanTitle")
                } else {
                    onError("Не удалось найти или удалить файл на диске: $cleanTitle")
                }
            } catch (e: Exception) {
                onError("Ошибка при удалении трека: ${e.message}")
            }
        }
    }

    /**
     * 🧹 Очистить кеш плейлистов
     */
    fun clearPlaylistsCache() {
        scope.launch {
            try {
                val storagePath = getMusicStoragePath()
                val count = withContext(DispatcherIO) {
                    io.github.audiz.clearPlaylistsCache(storagePath)
                }
                onStatusMessage("✅ Кеш плейлистов очищен ($count файлов удалено)")
            } catch (e: Exception) {
                onStatusMessage("❌ Ошибка очистки кеша: ${e.message}")
            }
        }
    }

    /**
     * 💣 Удалить все скачанные треки с диска
     */
    fun clearAllDownloadedMusic() {
        scope.launch {
            try {
                onStopPlayback()
                val storagePath = getMusicStoragePath()
                val count = withContext(DispatcherIO) {
                    io.github.audiz.clearAllDownloadedMusic(storagePath)
                }
                localTrackResolver.invalidateCache()
                downloadVersion++
                onAllTracksCleared?.invoke()
                onStatusMessage("✅ Удалено скачанных треков: $count шт.")
            } catch (e: Exception) {
                onStatusMessage("❌ Ошибка удаления музыки: ${e.message}")
            }
        }
    }

    fun sanitizeKeepSpaces(input: String): String = LocalTrackResolver.sanitizeKeepSpaces(input)

    /**
     * Проверка, скачан ли трек на диск (в HQ или LQ или в корневую папку)
     */
    fun isTrackDownloaded(artistName: String, trackTitle: String): Boolean {
        @Suppress("UNUSED_VARIABLE")
        val version = downloadVersion
        return localTrackResolver.isTrackDownloaded(artistName, trackTitle)
    }

    /**
     * Возвращает абсолютный путь к скачанному треку, если он существует на диске
     */
    fun getDownloadedTrackPath(artistName: String, trackTitle: String): String? {
        return localTrackResolver.getDownloadedTrackPath(artistName, trackTitle)
    }

    /**
     * Гарантирует наличие файла трека на диске (находит существующий или скачивает его с встраиванием тегов)
     */
    suspend fun ensureTrackFile(
        trackId: String,
        trackTitle: String,
        artistName: String,
        albumTitle: String? = null,
        year: String? = null,
        coverUri: String? = null
    ): String? {
        if (trackId.startsWith("local:")) {
            val localPath = trackId.removePrefix("local:")
            if (localFileExists(localPath)) return localPath
        }

        val existing = getDownloadedTrackPath(artistName, trackTitle)
        if (existing != null) return existing

        return try {
            val quality = getSelectedQuality()
            val audioData = repository.downloadTrackAudio(trackId, quality = quality)

            val rawExtension = audioData.type.substringBefore("-")
            val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension
            val cleanArtist = artistName.trim()
            val cleanTitle = trackTitle.trim()
            val fullFileName = sanitizeKeepSpaces(if (cleanArtist.isNotEmpty()) {
                "$cleanArtist — $cleanTitle.$extension"
            } else {
                "$cleanTitle.$extension"
            })

            val qualityFolder = if (quality == "2") "HQ" else "LQ"
            val storagePath = getMusicStoragePath()
            val basePath = "$storagePath/$qualityFolder"
            val sanitizedArtist = sanitizeKeepSpaces(cleanArtist.ifBlank { "Unknown Artist" }).trim()
            val targetPath = "$basePath/$sanitizedArtist/$fullFileName"

            val taggedBytes = prepareAndTagAudio(
                trackId = trackId,
                cleanTitle = cleanTitle,
                cleanArtist = cleanArtist,
                extension = extension,
                rawAudioBytes = audioData.result,
                albumTitle = albumTitle,
                year = year,
                coverUri = coverUri
            )

            saveTrackFile(basePath, cleanArtist, fullFileName, taggedBytes)
            downloadVersion++

            if (getPlayingTrackId() == trackId) {
                onUpdatePlayingFilePath(targetPath)
            }
            targetPath
        } catch (e: Exception) {
            println("DownloadManager: Ошибка при подготовке файла трека: ${e.message}")
            null
        }
    }

    /**
     * 💾 Сохранить трек в указанную папку
     */
    fun saveTrackToCustomFolder(
        track: FullTrackInfo,
        targetDirectory: String,
        customFileName: String? = null,
        onComplete: ((String?) -> Unit)? = null
    ) {
        scope.launch {
            try {
                val cleanArtist = track.artists.joinToString(", ") { it.name }.trim()
                val cleanTitle = track.title.trim()
                val defaultBaseTitle = if (cleanArtist.isNotEmpty()) "$cleanArtist — $cleanTitle" else cleanTitle
                val chosenBaseTitle = customFileName?.trim()?.ifBlank { null } ?: defaultBaseTitle

                onStatusMessage("⏳ Сохранение '$chosenBaseTitle' в папку...")

                // 1. Проверяем, есть ли трек уже локально на диске
                var localSrcPath: String? = null
                if (track.id.startsWith("local:")) {
                    val p = track.id.removePrefix("local:")
                    if (localFileExists(p)) localSrcPath = p
                }
                if (localSrcPath == null) {
                    val downloaded = getDownloadedTrackPath(cleanArtist, cleanTitle)
                    if (downloaded != null && localFileExists(downloaded)) {
                        localSrcPath = downloaded
                    }
                }
                if (localSrcPath == null) {
                    val currentPlayPath = getPlayingFilePath()?.removePrefix("local:")
                    if (currentPlayPath != null && (getPlayingTrackId() == track.id || getPlayingTrackId() == track.realId) && localFileExists(currentPlayPath)) {
                        localSrcPath = currentPlayPath
                    }
                }

                if (localSrcPath != null) {
                    val ext = localSrcPath.substringAfterLast('.', "mp3")
                    val rawName = if (chosenBaseTitle.endsWith(".$ext", ignoreCase = true)) {
                        chosenBaseTitle
                    } else {
                        "$chosenBaseTitle.$ext"
                    }
                    val fileName = sanitizeKeepSpaces(rawName)
                    val saved = withContext(DispatcherIO) {
                        copyFileToFolder(localSrcPath, targetDirectory, fileName)
                    }
                    if (saved != null) {
                        onStatusMessage("✅ Файл сохранён: $fileName")
                        onComplete?.invoke(saved)
                    } else {
                        onStatusMessage("❌ Ошибка копирования файла")
                        onComplete?.invoke(null)
                    }
                    return@launch
                }

                // 2. Если трека нет на диске — скачиваем аудиопоток из Яндекс Музыки
                val quality = getSelectedQuality()
                val audioData = repository.downloadTrackAudio(track.id, quality = quality)
                val rawExtension = audioData.type.substringBefore("-")
                val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension
                val rawName = if (chosenBaseTitle.endsWith(".$extension", ignoreCase = true)) {
                    chosenBaseTitle
                } else {
                    "$chosenBaseTitle.$extension"
                }
                val fileName = sanitizeKeepSpaces(rawName)

                val taggedBytes = prepareAndTagAudio(
                    trackId = track.id,
                    cleanTitle = cleanTitle,
                    cleanArtist = cleanArtist,
                    extension = extension,
                    rawAudioBytes = audioData.result,
                    albumTitle = track.albums.firstOrNull()?.title,
                    year = track.albums.firstOrNull()?.year?.toString(),
                    coverUri = track.coverUri
                )

                val saved = withContext(DispatcherIO) {
                    saveTrackToFolder(targetDirectory, fileName, taggedBytes)
                }

                if (saved != null) {
                    onStatusMessage("✅ Трек скачан и сохранён: $fileName")
                    onComplete?.invoke(saved)
                } else {
                    onStatusMessage("❌ Ошибка сохранения трека на диск")
                    onComplete?.invoke(null)
                }
            } catch (e: Exception) {
                onStatusMessage("❌ Ошибка: ${e.message ?: e.toString()}")
                onComplete?.invoke(null)
            }
        }
    }

    /**
     * 💾 Сохранить трек по конкретному выбранному пути файла (из системного Save As диалога)
     */
    fun saveTrackToFilePath(
        track: FullTrackInfo,
        targetFilePath: String,
        onComplete: ((String?) -> Unit)? = null
    ) {
        scope.launch {
            try {
                val cleanArtist = track.artists.joinToString(", ") { it.name }.trim()
                val cleanTitle = track.title.trim()
                val baseTitle = if (cleanArtist.isNotEmpty()) "$cleanArtist — $cleanTitle" else cleanTitle

                onStatusMessage("⏳ Сохранение '$baseTitle'...")

                // 1. Проверяем, есть ли трек уже локально на диске
                var localSrcPath: String? = null
                if (track.id.startsWith("local:")) {
                    val p = track.id.removePrefix("local:")
                    if (localFileExists(p)) localSrcPath = p
                }
                if (localSrcPath == null) {
                    val downloaded = getDownloadedTrackPath(cleanArtist, cleanTitle)
                    if (downloaded != null && localFileExists(downloaded)) {
                        localSrcPath = downloaded
                    }
                }
                if (localSrcPath == null) {
                    val currentPlayPath = getPlayingFilePath()?.removePrefix("local:")
                    if (currentPlayPath != null && (getPlayingTrackId() == track.id || getPlayingTrackId() == track.realId) && localFileExists(currentPlayPath)) {
                        localSrcPath = currentPlayPath
                    }
                }

                if (localSrcPath != null) {
                    val ext = localSrcPath.substringAfterLast('.', "mp3")
                    val normalizedPath = if (targetFilePath.substringAfterLast('/', "").contains('.')) {
                        targetFilePath
                    } else {
                        "$targetFilePath.$ext"
                    }
                    val saved = withContext(DispatcherIO) {
                        copyFileToDirectPath(localSrcPath, normalizedPath)
                    }
                    if (saved != null) {
                        val fileName = saved.substringAfterLast('/', saved.substringAfterLast('\\', saved))
                        onStatusMessage("✅ Файл сохранён: $fileName")
                        onComplete?.invoke(saved)
                    } else {
                        onStatusMessage("❌ Ошибка копирования файла")
                        onComplete?.invoke(null)
                    }
                    return@launch
                }

                // 2. Если трека нет на диске — скачиваем аудиопоток из Яндекс Музыки
                val quality = getSelectedQuality()
                val audioData = repository.downloadTrackAudio(track.id, quality = quality)
                val rawExtension = audioData.type.substringBefore("-")
                val extension = if (rawExtension.equals("aac", ignoreCase = true)) "m4a" else rawExtension

                val normalizedPath = if (targetFilePath.substringAfterLast('/', "").contains('.')) {
                    targetFilePath
                } else {
                    "$targetFilePath.$extension"
                }

                val taggedBytes = prepareAndTagAudio(
                    trackId = track.id,
                    cleanTitle = cleanTitle,
                    cleanArtist = cleanArtist,
                    extension = extension,
                    rawAudioBytes = audioData.result,
                    albumTitle = track.albums.firstOrNull()?.title,
                    year = track.albums.firstOrNull()?.year?.toString(),
                    coverUri = track.coverUri
                )

                val saved = withContext(DispatcherIO) {
                    saveFileToDirectPath(normalizedPath, taggedBytes)
                }

                if (saved != null) {
                    val fileName = saved.substringAfterLast('/', saved.substringAfterLast('\\', saved))
                    onStatusMessage("✅ Трек скачан и сохранён: $fileName")
                    onComplete?.invoke(saved)
                } else {
                    onStatusMessage("❌ Ошибка сохранения трека на диск")
                    onComplete?.invoke(null)
                }
            } catch (e: Exception) {
                onStatusMessage("❌ Ошибка: ${e.message ?: e.toString()}")
                onComplete?.invoke(null)
            }
        }
    }

    private suspend fun prepareAndTagAudio(
        trackId: String,
        cleanTitle: String,
        cleanArtist: String,
        extension: String,
        rawAudioBytes: ByteArray,
        albumTitle: String? = null,
        year: String? = null,
        coverUri: String? = null
    ): ByteArray {
        val info = findTrackInfo?.invoke(trackId)
        var effectiveCoverUri = coverUri ?: info?.coverUri
        var effectiveAlbum = albumTitle ?: info?.albums?.firstOrNull()?.title
        var effectiveYear = year ?: info?.albums?.firstOrNull()?.year?.toString()

        if (effectiveCoverUri == null && getPlayingTrackId() == trackId) {
            effectiveCoverUri = getPlayingTrackCoverUrl?.invoke()
        }

        if (effectiveCoverUri == null && !isLocalTrackId(trackId)) {
            try {
                val details = repository.getTracksDetails(listOf(trackId))
                val fetched = details.result.firstOrNull()
                if (fetched != null) {
                    effectiveCoverUri = fetched.coverUri
                    if (effectiveAlbum == null) effectiveAlbum = fetched.albums.firstOrNull()?.title
                    if (effectiveYear == null) effectiveYear = fetched.albums.firstOrNull()?.year?.toString()
                }
            } catch (_: Exception) {}
        }

        val coverBytes = effectiveCoverUri?.let { uri ->
            val url = CoverImageLoader.formatCoverUrl(uri, 600)
            if (url != null) repository.fetchImageBytes(url) else null
        }

        return AudioMetadataTagger.embedMetadata(
            audioBytes = rawAudioBytes,
            extension = extension,
            title = cleanTitle,
            artist = cleanArtist,
            album = effectiveAlbum,
            year = effectiveYear,
            coverBytes = coverBytes
        )
    }
}
