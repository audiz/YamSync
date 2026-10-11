package io.github.audiz.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import io.github.audiz.AndroidFilePickerBridge
import io.github.audiz.App // Импортируем точку входа из вашего модуля :shared
import io.github.audiz.AppContextHolder
import io.github.audiz.DeepLinkHandler

class MainActivity : ComponentActivity() {

    private var pendingDirectoryCallback: ((String?) -> Unit)? = null
    private var pendingFileCallback: ((String?) -> Unit)? = null

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val path = uri?.let { AndroidFilePickerBridge.resolveTreeUriToPath(applicationContext, it) }
        pendingDirectoryCallback?.invoke(path)
        pendingDirectoryCallback = null
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val path = uri?.let { AndroidFilePickerBridge.resolveDocUriToPath(applicationContext, it) }
        pendingFileCallback?.invoke(path)
        pendingFileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🔥 Инициализируем контекст для SharedPreferences (сохранение cookie)
        AppContextHolder.appContext = applicationContext

        // 📁 Связываем ActivityResult контракты с PlatformFilePicker
        AndroidFilePickerBridge.directoryPickerLauncher = { callback ->
            pendingDirectoryCallback = callback
            try {
                openDocumentTreeLauncher.launch(null)
            } catch (t: Throwable) {
                println("MainActivity: Ошибка launch OpenDocumentTree: ${t.message}")
                pendingDirectoryCallback = null
                callback(null)
            }
        }

        AndroidFilePickerBridge.filePickerLauncher = { callback ->
            pendingFileCallback = callback
            try {
                openDocumentLauncher.launch(arrayOf("audio/*", "application/ogg", "application/x-mpegurl", "audio/x-scpls"))
            } catch (t: Throwable) {
                println("MainActivity: Ошибка launch OpenDocument: ${t.message}")
                pendingFileCallback = null
                callback(null)
            }
        }

        // 🛡️ Глобальный перехватчик необработанных исключений для записи краш-логов на диск
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = java.io.File(applicationContext.filesDir, "crash_log.txt")
        if (crashFile.exists()) {
            try {
                val prevCrash = crashFile.readText()
                if (prevCrash.isNotBlank()) {
                    io.github.audiz.util.AppLogger.e("CrashHandler", "Предыдущее аварийное завершение:\n$prevCrash")
                }
            } catch (_: Throwable) {}
        }

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackTrace = throwable.stackTraceToString()
                System.err.println("CRITICAL UNCAUGHT EXCEPTION on thread ${thread.name}: $stackTrace")
                io.github.audiz.util.AppLogger.e("CrashHandler", "Критическая ошибка на потоке ${thread.name}", throwable)
                crashFile.writeText("Time: ${java.util.Date()}\nThread: ${thread.name}\n$stackTrace\n")
            } catch (_: Throwable) {}
            originalHandler?.uncaughtException(thread, throwable)
        }

        // 🛡️ Безопасная обработка кнопки "Назад" на корневом экране (предотвращает падение Activity на некоторых Android)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    // Безопасно сворачиваем плеер в фон (как в Spotify / Яндекс Музыке),
                    // сохраняя непрерывное воспроизведение и избегая крэша жизненного цикла Activity
                    if (!moveTaskToBack(false)) {
                        finish()
                    }
                } catch (e: Throwable) {
                    println("MainActivity: Ошибка при moveTaskToBack: ${e.message}")
                    try { finish() } catch (_: Throwable) {}
                }
            }
        })

        intent?.dataString?.let { url ->
            DeepLinkHandler.handleUrl(url)
        }

        // 🔔 Запрашиваем необходимые разрешения: медиафайлы, уведомления и микрофон для визуализатора
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 101)
        }

        setContent {
            App() // Запускаем ваш плеер
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { url ->
            DeepLinkHandler.handleUrl(url)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AndroidFilePickerBridge.directoryPickerLauncher = null
        AndroidFilePickerBridge.filePickerLauncher = null
    }
}
