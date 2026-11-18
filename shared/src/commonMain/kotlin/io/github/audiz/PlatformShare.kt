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

internal fun buildTrackShareCaption(
    title: String,
    artist: String,
    trackId: String,
    includeLocalLink: Boolean = false
): String = buildString {
    append("🎵 ").append(if (artist.isNotBlank()) "$artist — $title" else title).append("\n")
    if (includeLocalLink) {
        append("▶️ Открыть в YamSync: http://127.0.0.1:48293/track/$trackId\n")
    }
    append("🔗 Открыть в YamSync: yamsync://track/$trackId\n")
    append("🌐 Яндекс Музыка: https://music.yandex.ru/track/$trackId\n\n")
    append("Слушайте вместе с YamSync 🎧\n")
    append("Скачать приложение: https://github.com/yamsync/YamSync/releases/latest")
}
