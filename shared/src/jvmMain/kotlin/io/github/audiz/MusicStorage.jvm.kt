package io.github.audiz

import java.io.File
import java.util.prefs.Preferences

private val prefs: Preferences = Preferences.userRoot().node("MusicDownloader")
private const val STORAGE_PATH_KEY = "music_storage_path"

actual fun getDefaultMusicDir(): String {
    val userHome = System.getProperty("user.home") ?: "."
    return File(userHome, "Music${File.separator}YandexDownloader").absolutePath
}

actual fun saveMusicStoragePath(path: String) {
    prefs.put(STORAGE_PATH_KEY, path)
    try {
        prefs.flush()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

actual fun loadMusicStoragePath(): String? {
    return prefs.get(STORAGE_PATH_KEY, null)
}
