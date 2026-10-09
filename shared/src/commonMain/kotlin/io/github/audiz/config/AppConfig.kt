package io.github.audiz

/**
 * ⚙️ Константы ключей конфигурации приложения.
 * Устраняют опечатки и централизуют доступ к настройкам приложения.
 */
object AppConfigKeys {
    const val DOWNLOAD_QUALITY = "download_quality"
    const val APP_THEME = "app_theme"
    const val ACCENT_COLOR = "accent_color"
    const val UI_MODE = "ui_mode"
    const val RECORD_TO_DISK = "record_to_disk"
    const val MUSIC_STORAGE_PATH = "music_storage_path"
    const val PLAYER_VOLUME = "player_volume"
    const val PLAYER_CROSSFADE_SECONDS = "player_crossfade_seconds"
    const val VISUALIZER_LATENCY_MS = "visualizer_latency_ms"
    const val EQ_ENABLED = "eq_enabled"
    const val EQ_HPF = "eq_hpf"
    const val EQ_LPF = "eq_lpf"
    const val EQ_PRESET = "eq_preset"
    const val SESSION_COOKIE = "session_cookie"
    const val RECENT_THEMATIC_WAVES = "recent_thematic_waves"
    const val CUSTOM_LOCAL_SOURCES = "custom_local_sources"
    const val LAST_PLAYBACK_SESSION = "last_playback_session"
    const val UI_WATCHDOG_ENABLED = "ui_watchdog_enabled"
    const val YAMSYNC_DEVICE_TOKEN = "yamsync_device_token"
    const val YAMSYNC_KNOWN_DEVICES = "yamsync_known_devices"
    const val PLAYER_SHUFFLE = "player_shuffle"
    const val MOBILE_PLAYER_EXPANDED = "mobile_player_expanded"
    const val TRACKS_LIST_VISIBLE = "tracks_list_visible"
    const val SHOW_PLAYLISTS_DIALOG = "show_playlists_dialog"
    const val EXPANDED_PLAYLIST_ID = "expanded_playlist_id"

    fun eqBand(index: Int): String = "eq_band_$index"
}

/**
 * 🔥 КРОСС-ПЛАТФОРМЕННЫЕ КОНТРАКТЫ ХРАНИЛИЩА КОНФИГУРАЦИИ И ФАЙЛОВ
 */
expect fun saveFilePlatformSpecific(bytes: ByteArray, fileName: String)
expect fun saveSessionToken(token: String)
expect fun loadSavedSessionToken(): String?
expect fun saveAppConfig(key: String, value: String)
expect fun loadAppConfig(key: String): String?
expect fun getFileSize(filePath: String): Long
