package io.github.audiz

/**
 * 🎵 Кросс-платформенная интеграция с системным медиаплеером / виджетом звука ОС
 * (MPRIS на Linux, SMTC на Windows, MediaSession на Android, NowPlaying на iOS)
 */
expect class SystemMediaControls() {
    fun initialize(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onTogglePlayPause: () -> Unit,
        onNext: () -> Unit,
        onPrev: () -> Unit,
        onSeek: (Long) -> Unit
    )

    fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        trackId: String? = null,
        coverUri: String? = null
    )

    fun updatePlaybackState(
        isPlaying: Boolean,
        isPaused: Boolean,
        positionMs: Long
    )

    fun clear()

    fun release()
}
