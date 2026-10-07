package io.github.audiz

/**
 * 📤 Мультиплатформенный сервис отправки аудиофайлов и ссылок в мессенджеры
 */
expect fun shareTrackFile(
    filePath: String,
    title: String,
    artist: String,
    trackId: String,
    albumId: Long? = null
)
