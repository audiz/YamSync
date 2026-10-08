package io.github.audiz

import android.os.Environment
import java.io.File

actual fun getDefaultMusicDir(): String {
    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
    val dir = File(musicDir, "YandexDownloader")
    return try {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        if (dir.exists() && dir.canWrite()) {
            dir.absolutePath
        } else {
            val appExt = AppContextHolder.appContext?.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val appDir = if (appExt != null) File(appExt, "YandexDownloader") else null
            if (appDir != null && !appDir.exists()) appDir.mkdirs()
            appDir?.absolutePath ?: dir.absolutePath
        }
    } catch (_: Exception) {
        dir.absolutePath
    }
}

actual fun saveMusicStoragePath(path: String) {
    AppContextHolder.saveStoragePath(path)
}

actual fun loadMusicStoragePath(): String? {
    return AppContextHolder.loadStoragePath()
}
