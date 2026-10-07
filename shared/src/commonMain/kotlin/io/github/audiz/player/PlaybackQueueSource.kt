package io.github.audiz.player

import io.github.audiz.models.FullTrackInfo

/**
 * 📜 Источник очереди треков (текущий плейлист, результаты поиска, пагинация).
 * Абстрагирует получение списков треков от UI-состояний ViewModel.
 */
interface PlaybackQueueSource {
    /** Список треков текущего активного контекста (плейлист, альбом, сохраненные треки) */
    fun getLoadedTracks(): List<FullTrackInfo>

    /** Список треков из активного поискового запроса */
    fun getSearchTracks(): List<FullTrackInfo> = emptyList()

    /** Асинхронная дозагрузка следующей страницы плейлиста. Возвращает true, если загружены новые треки */
    suspend fun loadMoreTracks(): Boolean = false
}
