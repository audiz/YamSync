package io.github.audiz.tracklist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.models.LastPlaybackSession

/** Хранит просмотр артиста отдельно от списка, из которого продолжает играть музыка. */
internal class TrackListNavigation<T>(initial: T) {
    var active by mutableStateOf(initial)
        private set
    private var preview by mutableStateOf<Preview<T>?>(null)

    val displayed: T get() = preview?.list ?: active
    val isPreview: Boolean get() = preview != null

    fun browse(list: T, session: LastPlaybackSession) {
        preview = Preview(list, session)
    }

    fun selectTrack(): LastPlaybackSession? {
        val selected = preview ?: return null
        active = selected.list
        preview = null
        return selected.session
    }

    fun closePreview(): T? {
        val previous = preview?.list
        preview = null
        return previous
    }

    private data class Preview<T>(val list: T, val session: LastPlaybackSession)
}

/** Однократная прокрутка к треку при открытии очереди из плеера. */
data class TrackListScrollRequest(val requestId: Long, val trackId: String)
