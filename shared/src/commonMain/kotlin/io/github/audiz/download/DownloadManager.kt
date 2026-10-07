package io.github.audiz.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.DispatcherIO
import io.github.audiz.api.MusicRepository
import io.github.audiz.clearAllDownloadedMusic
import io.github.audiz.clearPlaylistsCache
import io.github.audiz.deleteTrackFile
import io.github.audiz.loadAppConfig
import io.github.audiz.localFileExists
import io.github.audiz.player.LocalTrackResolver
import io.github.audiz.saveAppConfig
import io.github.audiz.saveTrackFile
import io.github.audiz.trackFileExists
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
    private val onTrackDeleted: ((trackId: String) -> Unit)? = null,
    private val onAllTracksCleared: (() -> Unit)? = null,
    private val onError: (String) -> Unit = {},
    private val onStatusMessage: (String) -> Unit = {}
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
        onTrackDeleted: ((trackId: String) -> Unit)? = null,
        onAllTracksCleared: (() -> Unit)? = null,
        onError: (String) -> Unit = {},
        onStatusMessage: (String) -> Unit = {}
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
        onStatusMessage = onStatusMessage
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
            if (id.startsWith("local:")) return true
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
     * 🔥 Скачивание трека по клику
     */
    fun downloadTrack(trackId: String, trackTitle: String, artistName: String) {
        if (isTrackDownloading) return
        if (trackId.startsWith("local:")) {
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

                println("Saving to: $basePath / $cleanArtist / $fullFileName")
                saveTrackFile(basePath, cleanArtist, fullFileName, audioData.result)
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

                // 1. Если трек сейчас играет, останавливаем воспроизведение
                if (getPlayingTrackId() == trackId) {
                    onStopPlayback()
                }

                // 2. Локальный путь, если trackId начинается с "local:"
                val localPath = if (trackId.startsWith("local:")) trackId.removePrefix("local:") else null
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
                    onTrackDeleted?.invoke(trackId)
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
     * Гарантирует наличие файла трека на диске (находит существующий или скачивает его)
     */
    suspend fun ensureTrackFile(trackId: String, trackTitle: String, artistName: String): String? {
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

            saveTrackFile(basePath, cleanArtist, fullFileName, audioData.result)
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
}
