package io.github.audiz.models

import kotlinx.serialization.Serializable

/**
 * 🎵 Тип последней активной сессии воспроизведения.
 */
@Serializable
enum class LastPlaybackType {
    WAVE,                  // «Моя Волна» (персональная или тематическая)
    DOWNLOADED,            // Сохраненная на диске музыка («Загруженная музыка»)
    LOCAL_PLAYLIST,        // Персональный оффлайн-плейлист из вкладки «💾 Локальные»
    CUSTOM_SOURCE,         // Добавленный пользователем источник (папка/файл/M3U)
    CUSTOM_FOLDER,         // Подпапка, запущенная через встроенный проводник папок
    YANDEX_USER_PLAYLIST,  // Пользовательский плейлист из Яндекс Музыки
    YANDEX_UUID,           // Персональный или кураторский плейлист Яндекс по UUID
    YANDEX_LIKES,          // Плейлист «Мне нравится»
    YANDEX_HISTORY,        // Плейлист «История прослушиваний»
    ARTIST                 // Популярные треки артиста
}

/**
 * 💾 Модель сохраненной последней сессии воспроизведения.
 * Позволяет восстановить экран плейлиста или Мою волну при перезапуске приложения.
 */
@Serializable
data class LastPlaybackSession(
    val type: LastPlaybackType,
    val title: String? = null,
    val path: String? = null,
    val sourceId: String? = null,
    val isRecursive: Boolean = true,
    val id: String? = null,
    val uuid: String? = null,
    val uid: Long? = null,
    val kind: Long? = null,
    val artistId: String? = null,
    val artistName: String? = null,
    val waveTitle: String? = null,
    val waveSeeds: List<String> = emptyList(),
    val lastTrackId: String? = null,
    val lastTrackTitle: String? = null,
    val lastArtistName: String? = null,
    val lastCoverUri: String? = null,
    val lastDurationMs: Long = 0L,
    val timestamp: Long = 0L
)
