package io.github.audiz.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.AppConfigKeys
import io.github.audiz.YandexCookieService
import io.github.audiz.api.MusicRepository
import io.github.audiz.getDefaultMusicDir
import io.github.audiz.getPlatform
import io.github.audiz.loadAppConfig
import io.github.audiz.loadMusicStoragePath
import io.github.audiz.loadSavedSessionToken
import io.github.audiz.saveAppConfig
import io.github.audiz.saveMusicStoragePath
import io.github.audiz.saveSessionToken

/**
 * ⚙️ Менеджер настроек приложения и авторизации в Яндекс Музыке.
 * Инкапсулирует:
 * - Управление токеном доступа OAuth (сохранение в реестр, извлечение, валидация).
 * - Настройки звука (качество скачивания).
 * - Оформление (тема оформления, режим интерфейса Desktop/Mobile).
 * - Настройки пути к хранилищу музыки.
 */
class SettingsManager(
    private val repository: MusicRepository,
    private val onTokenUpdated: ((String) -> Unit)? = null,
    private val onStoragePathUpdated: ((String) -> Unit)? = null
) {
    // 🔧 Текущий OAuth access_token
    var currentAccessToken by mutableStateOf("")
        private set

    var currentSessionCookie: String
        get() = currentAccessToken
        set(value) { currentAccessToken = value }

    // 🔑 Сервис авторизации
    val cookieService = YandexCookieService()
    var isExchangingToken by mutableStateOf(false)
        private set
    var authStatusMessage by mutableStateOf<String?>(null)
    var storageStatusMessage by mutableStateOf<String?>(null)

    // 🎚️ Качество скачивания ("1" = low, "2" = high)
    var selectedQuality by mutableStateOf(loadAppConfig(AppConfigKeys.DOWNLOAD_QUALITY) ?: "1")
        private set

    // 🎨 Тема (Dark, Light, System)
    var appTheme by mutableStateOf(loadAppConfig(AppConfigKeys.APP_THEME) ?: "Dark")
        private set

    // 📱 Режим интерфейса (auto, mobile, desktop)
    var uiMode by mutableStateOf(loadAppConfig(AppConfigKeys.UI_MODE) ?: "auto")
        private set

    val isMobileUi: Boolean
        get() = when (uiMode) {
            "mobile" -> true
            "desktop" -> false
            else -> getPlatform().isMobile
        }

    // 📂 Путь хранения музыки (настраиваемый)
    var musicStoragePath by mutableStateOf(loadMusicStoragePath() ?: getDefaultMusicDir())
        private set

    init {
        val savedSession = loadSavedSessionToken()
        if (!savedSession.isNullOrBlank()) {
            val cleanToken = YandexCookieService.extractAccessToken(savedSession)
            repository.updateAccessToken(cleanToken)
            currentAccessToken = cleanToken
        } else {
            val defaultToken = repository.getAccessToken()
            if (defaultToken.isNotBlank()) {
                saveSessionToken(defaultToken)
                currentAccessToken = defaultToken
            }
        }
    }

    fun saveQuality(quality: String) {
        selectedQuality = quality
        saveAppConfig(AppConfigKeys.DOWNLOAD_QUALITY, quality)
    }

    fun saveTheme(theme: String) {
        appTheme = theme
        saveAppConfig(AppConfigKeys.APP_THEME, theme)
    }

    fun saveUiMode(mode: String) {
        uiMode = mode
        saveAppConfig(AppConfigKeys.UI_MODE, mode)
    }

    fun saveMusicPath(newPath: String) {
        if (newPath.isBlank()) return
        saveMusicStoragePath(newPath)
        musicStoragePath = newPath
        onStoragePathUpdated?.invoke(newPath)
    }

    fun saveNewToken(tokenOrUrl: String, onComplete: ((Boolean) -> Unit)? = null) {
        val cleanToken = YandexCookieService.extractAccessToken(tokenOrUrl)
        if (cleanToken.isBlank()) {
            authStatusMessage = "❌ Вставьте токен или ссылку"
            onComplete?.invoke(false)
            return
        }
        saveSessionToken(cleanToken)
        repository.updateAccessToken(cleanToken)
        currentAccessToken = cleanToken
        authStatusMessage = "✅ Токен успешно сохранен и применен!"
        onComplete?.invoke(true)
        onTokenUpdated?.invoke(cleanToken)
    }

    fun saveNewSession(tokenOrCookie: String) {
        saveNewToken(tokenOrCookie)
    }

    fun exchangeTokenForSession(tokenOrUrl: String, onComplete: ((Boolean) -> Unit)? = null) {
        saveNewToken(tokenOrUrl, onComplete)
    }
}
