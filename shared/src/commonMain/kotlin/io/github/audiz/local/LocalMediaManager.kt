package io.github.audiz.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.AppConfigKeys
import io.github.audiz.api.generatePlayUuid
import io.github.audiz.currentTimeMillis
import io.github.audiz.getTracksFromLocalPaths
import io.github.audiz.isDirectory
import io.github.audiz.loadAppConfig
import io.github.audiz.localFileExists
import io.github.audiz.listFolderContents
import io.github.audiz.models.CustomMediaSource
import io.github.audiz.models.FolderListing
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.LocalSourceType
import io.github.audiz.readTextFile
import io.github.audiz.saveAppConfig
import io.github.audiz.scanDownloadedTracks
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 📂 Менеджер пользовательских источников медиа (папки, отдельные треки, M3U-плейлисты).
 * Инкапсулирует:
 * - Хранение и персистентность добавленных путей к директориям и файлам.
 * - Сканирование и разрешение источников в список аудиотреков FullTrackInfo.
 * - Парсинг внешних плейлистов .m3u / .m3u8.
 */
class LocalMediaManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val customSources = mutableStateListOf<CustomMediaSource>()

    var statusMessage by mutableStateOf<String?>(null)

    companion object {
        val SUPPORTED_AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "opus", "wav", "ogg")
    }

    init {
        loadCustomSources()
    }

    /**
     * 📥 Загрузить сохраненные источники из постоянной конфигурации
     */
    fun loadCustomSources() {
        try {
            val jsonStr = loadAppConfig(AppConfigKeys.CUSTOM_LOCAL_SOURCES)
            if (!jsonStr.isNullOrBlank()) {
                val list = json.decodeFromString(
                    ListSerializer(CustomMediaSource.serializer()),
                    jsonStr
                )
                customSources.clear()
                customSources.addAll(list)
            }
        } catch (e: Exception) {
            println("LocalMediaManager: ❌ Ошибка загрузки источников: ${e.message}")
        }
    }

    /**
     * 💾 Сохранить список источников в постоянную конфигурацию
     */
    private fun saveCustomSources() {
        try {
            val jsonStr = json.encodeToString(
                ListSerializer(CustomMediaSource.serializer()),
                customSources.toList()
            )
            saveAppConfig(AppConfigKeys.CUSTOM_LOCAL_SOURCES, jsonStr)
        } catch (e: Exception) {
            println("LocalMediaManager: ❌ Ошибка сохранения источников: ${e.message}")
        }
    }

    /**
     * ➕ Добавить путь к папке, файлу или M3U плейлисту.
     * Автоматически определяет тип источника.
     */
    fun addSource(rawPath: String, customName: String? = null): CustomMediaSource? {
        val cleanPath = normalizePath(rawPath)
        if (cleanPath.isBlank()) {
            statusMessage = "❌ Укажите путь к файлу или папке"
            return null
        }

        // Проверяем, не добавлен ли уже этот путь
        if (customSources.any { it.path.equals(cleanPath, ignoreCase = true) }) {
            statusMessage = "⚠️ Этот источник уже добавлен в список"
            return null
        }

        if (!localFileExists(cleanPath)) {
            statusMessage = "❌ Файл или папка не найдены на устройстве"
            return null
        }

        val source: CustomMediaSource = when {
            // 1. Папка со структурой файлов
            isDirectory(cleanPath) -> {
                val tracks = scanDownloadedTracks(cleanPath)
                val folderName = customName?.trim()?.ifBlank { null }
                    ?: cleanPath.trimEnd('/').substringAfterLast('/')
                    .ifBlank { "Папка с музыкой" }

                CustomMediaSource(
                    id = generatePlayUuid(),
                    type = LocalSourceType.FOLDER,
                    name = folderName,
                    path = cleanPath,
                    trackCount = tracks.size,
                    addedAt = currentTimeMillis()
                )
            }

            // 2. Файл плейлиста M3U / M3U8
            isM3uPlaylist(cleanPath) -> {
                val paths = parseM3uFile(cleanPath)
                val playlistName = customName?.trim()?.ifBlank { null }
                    ?: cleanPath.substringAfterLast('/').substringBeforeLast('.')
                    .ifBlank { "Плейлист M3U" }

                CustomMediaSource(
                    id = generatePlayUuid(),
                    type = LocalSourceType.PLAYLIST_FILE,
                    name = playlistName,
                    path = cleanPath,
                    trackCount = paths.size,
                    addedAt = currentTimeMillis()
                )
            }

            // 3. Отдельный аудиофайл
            isAudioFile(cleanPath) -> {
                val fileName = customName?.trim()?.ifBlank { null }
                    ?: cleanPath.substringAfterLast('/')
                    .ifBlank { "Аудиофайл" }

                CustomMediaSource(
                    id = generatePlayUuid(),
                    type = LocalSourceType.SINGLE_FILE,
                    name = fileName,
                    path = cleanPath,
                    trackCount = 1,
                    addedAt = currentTimeMillis()
                )
            }

            else -> {
                statusMessage = "❌ Неподдерживаемый формат (поддерживаются папки, аудиофайлы и M3U)"
                return null
            }
        }

        customSources.add(0, source)
        saveCustomSources()
        statusMessage = "✅ Источник '${source.name}' успешно добавлен!"
        return source
    }

    /**
     * 🗑️ Удалить добавленный источник
     */
    fun removeSource(id: String) {
        val removed = customSources.removeAll { it.id == id }
        if (removed) {
            saveCustomSources()
            statusMessage = "🗑️ Источник удален"
        }
    }

    /**
     * 🎵 Разрешить источник в список треков FullTrackInfo для воспроизведения
     */
    fun getTracksForSource(source: CustomMediaSource): List<FullTrackInfo> {
        return when (source.type) {
            LocalSourceType.SINGLE_FILE -> {
                getTracksFromLocalPaths(listOf(source.path))
            }

            LocalSourceType.FOLDER -> {
                scanDownloadedTracks(source.path)
            }

            LocalSourceType.PLAYLIST_FILE -> {
                val paths = parseM3uFile(source.path)
                getTracksFromLocalPaths(paths)
            }
        }
    }

    /**
     * 📜 Парсинг файла плейлиста M3U / M3U8
     */
    fun parseM3uFile(m3uPath: String): List<String> {
        val content = readTextFile(m3uPath) ?: return emptyList()
        val dir = m3uPath.substringBeforeLast('/', "")
        val result = mutableListOf<String>()

        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                // Если путь относительный, дополняем папкой плейлиста
                val fullPath = if (!trimmed.startsWith("/") && !trimmed.contains(":\\")) {
                    if (dir.isNotEmpty()) "$dir/$trimmed" else trimmed
                } else {
                    trimmed
                }
                if (localFileExists(fullPath)) {
                    result.add(fullPath)
                }
            }
        }
        return result
    }

    private fun isAudioFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_AUDIO_EXTENSIONS
    }

    private fun isM3uPlaylist(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext == "m3u" || ext == "m3u8"
    }

    /**
     * 🔍 Получить листинг содержимого папки для встроенного проводника
     */
    fun browseFolder(folderPath: String, rootPath: String? = null): FolderListing {
        val clean = normalizePath(folderPath)
        val cleanRoot = rootPath?.let { normalizePath(it) }
        return listFolderContents(clean, cleanRoot)
    }

    private fun normalizePath(path: String): String {
        return path.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace('\\', '/')
    }
}
