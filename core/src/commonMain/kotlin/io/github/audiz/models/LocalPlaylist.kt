package io.github.audiz.models

import kotlinx.serialization.Serializable

/**
 * 💾 Модель персонального локального плейлиста, содержащего скачанные на диск аудиофайлы.
 */
@Serializable
data class LocalPlaylist(
    val id: String,                         // Уникальный идентификатор (UUID)
    val title: String,                      // Название плейлиста ("В дорогу", "Спорт" и т.д.)
    val description: String = "",            // Пользовательское описание
    val createdAt: Long = 0L,               // Время создания (timestamp ms)
    val updatedAt: Long = 0L,               // Время последнего изменения
    val trackPaths: List<String> = emptyList() // Список локальных путей к файлам на диске
) {
    val trackCount: Int get() = trackPaths.size
}
