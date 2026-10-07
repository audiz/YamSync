package io.github.audiz.models

/**
 * Модель аудиоданных трека.
 */
data class DownloadedTrackAudio(
    val result: ByteArray,
    val type: String,
    val bitrate: Int? = null
) {
    val audioBytes: ByteArray get() = result
    val codec: String get() = type

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadedTrackAudio) return false
        if (!result.contentEquals(other.result)) return false
        if (type != other.type) return false
        if (bitrate != other.bitrate) return false
        return true
    }

    override fun hashCode(): Int {
        var res = result.contentHashCode()
        res = 31 * res + type.hashCode()
        res = 31 * res + (bitrate ?: 0)
        return res
    }
}
