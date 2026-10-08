package io.github.audiz

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

// 🔥 Контекст для доступа к SharedPreferences (инициализируется при старте приложения)
object AppContextHolder {
    lateinit var appContext: Context

    private const val PREFS_NAME = "MusicDownloaderPrefs"
    private const val SESSION_KEY = "session_cookie"
    private const val STORAGE_PATH_KEY = "music_storage_path"

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveToken(token: String) {
        prefs.edit().putString(SESSION_KEY, token).apply()
    }

    fun loadToken(): String? {
        return prefs.getString(SESSION_KEY, null)
    }

    fun saveStoragePath(path: String) {
        prefs.edit().putString(STORAGE_PATH_KEY, path).apply()
    }

    fun loadStoragePath(): String? {
        return prefs.getString(STORAGE_PATH_KEY, null)
    }

    fun saveConfig(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun loadConfig(key: String): String? {
        return prefs.getString(key, null)
    }
}

// 1. Создаем правильную реализацию, возвращающую модель Platform
class AndroidPlatform : Platform {
    override val name: String = "Android ${android.os.Build.VERSION.SDK_INT}"
    override val isMobile: Boolean = true
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual val DispatcherIO: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

actual inline fun <R> synchronized(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)

actual fun getLocalIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: ""
                    if (host.isNotBlank()) return host
                }
            }
        }
    } catch (_: Exception) {}
    return "127.0.0.1"
}

actual fun getDeviceName(): String {
    val model = android.os.Build.MODEL
    val manufacturer = android.os.Build.MANUFACTURER
    return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
}

actual fun generateRandomSessionToken(): String =
    java.util.UUID.randomUUID().toString().replace("-", "").take(10).lowercase()




// 2. 🔥 ИСПРАВЛЕНО: Убрали параметр fileType, теперь функция строго соответствует expect из commonMain!
actual fun saveFilePlatformSpecific(bytes: ByteArray, fileName: String) {
    try {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outputFile = File(downloadDir, fileName)

        outputFile.writeBytes(bytes)
        println("Android IO: Файл успешно сохранен в загрузки: ${outputFile.absolutePath}")
    } catch (e: Exception) {
        println("Android IO: Ошибка сохранения файла: ${e.message}")
        e.printStackTrace()
    }
}

// 3. 🔥 Реализация сохранения токена сессии через SharedPreferences (переживает перезапуск)
actual fun saveSessionToken(token: String) {
    AppContextHolder.saveToken(token)
}

actual fun loadSavedSessionToken(): String? {
    return AppContextHolder.loadToken()
}

actual fun saveAppConfig(key: String, value: String) {
    AppContextHolder.saveConfig(key, value)
}

actual fun loadAppConfig(key: String): String? {
    return AppContextHolder.loadConfig(key)
}

actual fun getFileSize(filePath: String): Long {
    val file = File(filePath)
    return if (file.exists()) file.length() else 0L
}
