package io.github.audiz.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Intent
import io.github.audiz.App // Импортируем точку входа из вашего модуля :shared
import io.github.audiz.AppContextHolder
import io.github.audiz.DeepLinkHandler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🔥 Инициализируем контекст для SharedPreferences (сохранение cookie)
        AppContextHolder.appContext = applicationContext

        intent?.dataString?.let { url ->
            DeepLinkHandler.handleUrl(url)
        }

        // 🔔 Запрашиваем разрешения: медиа-уведомления (Android 13+) и RECORD_AUDIO (для визуализатора)
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
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
}
