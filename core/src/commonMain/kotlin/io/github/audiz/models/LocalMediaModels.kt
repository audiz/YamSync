package io.github.audiz.models

import kotlinx.serialization.Serializable

/**
 * 📂 Тип локального пользовательского источника аудио.
 */
@Serializable
enum class LocalSourceType {
    FOLDER,        // Вся директория (папка со своей структурой подпапок)
    SINGLE_FILE,   // Отдельный музыкальный файл
    PLAYLIST_FILE  // Внешний файл плейлиста (.m3u / .m3u8)
}

/**
 * 💾 Модель пользовательского источника аудио на устройстве.
 * Сохраняется в конфигурации и доступна при перезапусках приложения.
 */
@Serializable
data class CustomMediaSource(
    val id: String,                 // Уникальный идентификатор (UUID)
    val type: LocalSourceType,      // Тип источника
    val name: String,               // Отображаемое имя
    val path: String,               // Абсолютный путь к файлу или папке на устройстве
    val trackCount: Int = 1,        // Количество обнаруженных аудиотреков
    val addedAt: Long = 0L          // Timestamp добавления (ms)
)

/**
 * 📁 Элемент файловой структуры (папка).
 */
@Serializable
data class FolderItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean = true,
    val trackCount: Int = 0
)

/**
 * 📂 Листинг содержимого директории для встроенного проводника.
 */
@Serializable
data class FolderListing(
    val currentPath: String,
    val parentPath: String? = null,
    val rootPath: String? = null,
    val subfolders: List<FolderItem> = emptyList(),
    val tracks: List<FullTrackInfo> = emptyList()
)
