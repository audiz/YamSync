package io.github.audiz

import android.os.Environment
import java.io.File

actual fun getDefaultMusicDir(): String {
    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
    return File(musicDir, "YandexDownloader").absolutePath
}

actual fun saveMusicStoragePath(path: String) {
    AppContextHolder.saveStoragePath(path)
}

actual fun loadMusicStoragePath(): String? {
    return AppContextHolder.loadStoragePath()
}
