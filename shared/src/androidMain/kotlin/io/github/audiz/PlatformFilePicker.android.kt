package io.github.audiz

actual val isPlatformPickerSupported: Boolean = false

actual fun pickDirectory(): String? = null

actual fun pickAudioOrPlaylistFile(): String? = null
 
actual fun pickSaveFile(defaultFileName: String, title: String): String? = null
