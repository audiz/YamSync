package io.github.audiz.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.audiz.AppConfigKeys
import io.github.audiz.DispatcherIO
import io.github.audiz.api.ApiSessionContext
import io.github.audiz.core.BuildInfo
import io.github.audiz.currentTimeMillis
import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class UpdateInfo(val version: String, val url: String)

/**
 * Проверяет наличие новой версии приложения в GitHub Releases.
 * При сетевой ошибке сохраняет ранее полученные данные.
 */
class UpdateManager(
    private val currentVersion: String = BuildInfo.APP_VERSION,
    private val now: () -> Long = ::currentTimeMillis,
    private val readConfig: (String) -> String? = ::loadAppConfig,
    private val writeConfig: (String, String) -> Unit = ::saveAppConfig,
    private val fetchRelease: (suspend () -> UpdateInfo?)? = null
) {
    private val checkMutex = Mutex()
    private var retryAfter = 0L
    private var dismissedVersion: String? = null

    var nextCheckAt by mutableStateOf<Long?>(null)
        private set

    var availableUpdate by mutableStateOf<UpdateInfo?>(null)
        private set

    /** Показывает кеш сразу и обновляет его не реже раза в сутки при доступной сети. */
    suspend fun checkForUpdates() = checkMutex.withLock {
        val cached = withContext(DispatcherIO) {
            val version = readConfig(AppConfigKeys.KNOWN_LATEST_VERSION)?.takeIf { it.isNotBlank() }
            val url = readConfig(AppConfigKeys.KNOWN_LATEST_RELEASE_URL)?.takeIf { it.isNotBlank() } ?: RELEASES_PAGE
            val checkedAt = readConfig(AppConfigKeys.LAST_UPDATE_CHECK_AT)?.toLongOrNull()
            Triple(version?.let { UpdateInfo(it, url) }, checkedAt, now())
        }
        val (known, checkedAt, timestamp) = cached
        publish(known)
        if (known != null && checkedAt != null && checkedAt <= timestamp && timestamp - checkedAt < CHECK_INTERVAL_MS) {
            nextCheckAt = checkedAt + CHECK_INTERVAL_MS
            return@withLock
        }
        if (retryAfter > timestamp) {
            nextCheckAt = retryAfter
            return@withLock
        }

        val latest = if (fetchRelease != null) fetchRelease.invoke() else fetchLatestRelease()
        if (latest == null || parse(latest.version) == null) {
            retryAfter = now() + RETRY_INTERVAL_MS
            nextCheckAt = retryAfter
            return@withLock
        }
        val completedAt = now()
        withContext(DispatcherIO) {
            writeConfig(AppConfigKeys.KNOWN_LATEST_VERSION, latest.version)
            writeConfig(AppConfigKeys.KNOWN_LATEST_RELEASE_URL, latest.url)
            writeConfig(AppConfigKeys.LAST_UPDATE_CHECK_AT, completedAt.toString())
        }
        retryAfter = 0L
        publish(latest)
        nextCheckAt = completedAt + CHECK_INTERVAL_MS
    }

    private fun publish(info: UpdateInfo?) {
        availableUpdate = info?.takeIf { isNewer(it.version, currentVersion) && it.version != dismissedVersion }
    }

    /** Скрывает эту версию до следующего запуска; новый релиз снова покажет баннер. */
    fun dismiss() {
        dismissedVersion = availableUpdate?.version
        availableUpdate = null
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
        const val RELEASES_PAGE = "https://github.com/yamsync/YamSync/releases/latest"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/yamsync/YamSync/releases/latest"
        internal const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val RETRY_INTERVAL_MS = 60 * 60 * 1000L
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
