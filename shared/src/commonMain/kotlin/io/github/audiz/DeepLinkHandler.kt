package io.github.audiz

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 🔗 Диспетчер диплинков (yamsync:// и https://music.yandex.ru/...)
 */
object DeepLinkHandler {
    private val _pendingTrackId = MutableStateFlow<String?>(null)
    val pendingTrackId: StateFlow<String?> = _pendingTrackId.asStateFlow()

    private val _pendingYamSyncUri = MutableStateFlow<String?>(null)
    val pendingYamSyncUri: StateFlow<String?> = _pendingYamSyncUri.asStateFlow()

    fun handleUrl(url: String?) {
        if (url.isNullOrBlank()) return
        val clean = url.trim()
        if (clean.startsWith("yamsync://pair", ignoreCase = true) ||
            (clean.contains("/pair") && (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)))) {
            _pendingYamSyncUri.value = clean
            return
        }
        val trackId = extractTrackId(clean)
        if (trackId != null) {
            _pendingTrackId.value = trackId
        }
    }

    /**
     * Извлекает ID трека из поддерживаемых форматов:
     * - yamsync://track/12345
     * - yamsync://12345
     * - yamsync://album/678/track/12345
     * - https://music.yandex.ru/track/12345
     * - https://music.yandex.ru/album/678/track/12345
     */
    fun extractTrackId(url: String): String? {
        val clean = url.trim()
        val trackMatch = Regex("""track[/=](\d+)""").find(clean)
        if (trackMatch != null) return trackMatch.groupValues[1]

        val schemeMatch = Regex("""yamsync://(?:track/)?(\d+)""").find(clean)
        if (schemeMatch != null) return schemeMatch.groupValues[1]

        if (clean.matches(Regex("""^\d{5,12}$"""))) {
            return clean
        }

        return null
    }

    fun consumeTrackId(): String? {
        val id = _pendingTrackId.value
        _pendingTrackId.value = null
        return id
    }

    fun consumeYamSyncUri(): String? {
        val uri = _pendingYamSyncUri.value
        _pendingYamSyncUri.value = null
        return uri
    }
}
