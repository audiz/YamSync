package io.github.audiz

actual val isPlatformPickerSupported: Boolean = false

actual fun pickDirectory(): String? = null

actual fun pickAudioOrPlaylistFile(): String? = null
 
actual fun pickSaveFile(defaultFileName: String, title: String): String? = null

actual fun launchDirectoryPicker(onResult: (String?) -> Unit) {
    onResult(null)
}

actual fun launchAudioOrPlaylistFilePicker(onResult: (String?) -> Unit) {
    onResult(null)
}

actual fun getPlatformPresetDirectories(): List<Pair<String, String>> {
    val list = mutableListOf<Pair<String, String>>()
    val musicDir = java.io.File("/storage/emulated/0/Music")
    if (musicDir.exists() && musicDir.isDirectory) {
        list.add("📁 Папка Музыка" to musicDir.absolutePath)
    }
    val downloadsDir = java.io.File("/storage/emulated/0/Download")
    if (downloadsDir.exists() && downloadsDir.isDirectory) {
        list.add("📥 Папка Загрузки" to downloadsDir.absolutePath)
    }
    return list
}
