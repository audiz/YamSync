package io.github.audiz

import java.io.File
import java.util.prefs.Preferences

private val prefs: Preferences = Preferences.userRoot().node("MusicDownloader")
private val sessionKey = "session_cookie"

actual fun saveFilePlatformSpecific(bytes: ByteArray, fileName: String) {
    // Если user.home не найден, на Windows лучше использовать текущую папку приложения ".", а не линуксовый путь
    val userHome = System.getProperty("user.home") ?: "."
    val downloadsDir = File(userHome, "Downloads")

    // Если папки Downloads почему-то нет и создать её нельзя (например, ограничения прав в Windows)
    val finalDir = if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
        File(userHome) // сохраняем просто в корень пользователя или в папку приложения
    } else {
        downloadsDir
    }

    val outputFile = File(finalDir, fileName)
    outputFile.writeBytes(bytes)
    println("Десктоп успешно сохранил файл: ${outputFile.absolutePath}")
}

actual fun saveSessionToken(token: String) {
    println("save sessionKey = $sessionKey")
    prefs.put(sessionKey, token)
    try {
        prefs.flush() // Принудительно сохраняет данные на диск ПРЯМО СЕЙЧАС
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

actual fun loadSavedSessionToken(): String? {
    return prefs.get(sessionKey, null)
}

actual fun saveAppConfig(key: String, value: String) {
    prefs.put(key, value)
    try {
        prefs.flush()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

actual fun loadAppConfig(key: String): String? {
    return prefs.get(key, null)
}

actual fun getFileSize(filePath: String): Long {
    val file = File(filePath)
    return if (file.exists()) file.length() else 0L
}

