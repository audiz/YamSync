package io.github.audiz.player

/**
 * 🎵 Единая модель состояния пользовательского интерфейса аудиоплеера (UDF).
 * Консолидирует все свойства воспроизведения, метаданных и статусов трека.
 */
data class PlayerUiState(
    val trackId: String? = null,
    val albumId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val coverUri: String? = null,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val volume: Float = 1f,
    val crossfadeSeconds: Int = 0,
    val bitrate: Int? = null,
    val isHQ: Boolean = false,
    val isShuffle: Boolean = false,
    val isNextLoading: Boolean = false,
    val isPrevLoading: Boolean = false,
    val isPlayLoading: Boolean = false,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val isSavedToDisk: Boolean = false,
    val isSavingToDisk: Boolean = false,
    val isWaveMode: Boolean = false,
    val currentWaveTitle: String? = null
)
