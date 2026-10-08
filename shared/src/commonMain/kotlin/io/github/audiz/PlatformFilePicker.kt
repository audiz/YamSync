package io.github.audiz

/**
 * 🖥️ Кроссплатформенный системный диалог выбора папки и файла
 */
expect val isPlatformPickerSupported: Boolean

expect fun pickDirectory(): String?

expect fun pickAudioOrPlaylistFile(): String?

expect fun pickSaveFile(defaultFileName: String, title: String = "Сохранить трек"): String?
