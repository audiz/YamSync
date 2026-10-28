package io.github.audiz.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.AppConfigKeys
import io.github.audiz.DispatcherIO
import io.github.audiz.api.ApiSessionContext
import io.github.audiz.core.BuildInfo
import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class UpdateInfo(val version: String, val url: String)

/**
 * Проверяет наличие новой версии приложения в GitHub Releases.
 * Любая сетевая ошибка молча игнорируется: баннер просто не показывается.
 */
class UpdateManager {
    var availableUpdate by mutableStateOf<UpdateInfo?>(null)
        private set

    /**
     * Если ранее уже найдена более новая версия, повторно в сеть не ходим: баннер висит,
     * пока приложение не обновят. Иначе делаем один запрос и запоминаем результат.
     */
    suspend fun checkForUpdates() {
        val known = loadAppConfig(AppConfigKeys.KNOWN_LATEST_VERSION)?.takeIf { it.isNotBlank() }
        if (known != null) {
            if (isNewer(known, BuildInfo.APP_VERSION)) {
                availableUpdate = UpdateInfo(known, RELEASES_PAGE)
                return
            }
            // Приложение уже обновлено — забываем старую запись и проверяем заново
            saveAppConfig(AppConfigKeys.KNOWN_LATEST_VERSION, "")
        }
        val latest = fetchLatestRelease() ?: return
        if (!isNewer(latest.version, BuildInfo.APP_VERSION)) return
        saveAppConfig(AppConfigKeys.KNOWN_LATEST_VERSION, latest.version)
        availableUpdate = latest
    }

    private suspend fun fetchLatestRelease(): UpdateInfo? = withContext(DispatcherIO) {
        val client = ApiSessionContext.createDefaultHttpClient()
        try {
            val response = client.get(LATEST_RELEASE_API) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "YamSync")
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
            }
            if (!response.status.isSuccess()) return@withContext null
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val tag = json["tag_name"]?.jsonPrimitive?.content ?: return@withContext null
            val url = json["html_url"]?.jsonPrimitive?.content ?: RELEASES_PAGE
            UpdateInfo(version = tag.removePrefix("v"), url = url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }

    companion object {
        const val RELEASES_PAGE = "https://github.com/audiz/YamSync/releases/latest"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/audiz/YamSync/releases/latest"
        private const val REQUEST_TIMEOUT_MS = 8_000L

        /** Числовое сравнение по частям: 1.10.0 > 1.9.0. Нечисловые версии считаются не новее. */
        internal fun isNewer(candidate: String, current: String): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(current) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parse(version: String): List<Int>? =
            version.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
    }
}
