package io.github.audiz.api

import io.github.audiz.YandexCookieService
import io.github.audiz.core.NativeTrackSigner
import io.github.audiz.core.constantTimeEquals
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

/**
 * 🌐 Сетевой контекст и сессия для API клиентов:
 * - Общий Ktor HttpClient с таймаутами и WebSockets
 * - Управление токеном авторизации и кэширование ID пользователя
 * - Формирование заголовков устройств и клиента
 */
class ApiSessionContext(
    val client: HttpClient = createDefaultHttpClient()
) {
    val trackSigner = NativeTrackSigner()

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    val clientHeader: String = "YandexMusicAndroid/24023621"
    private val deviceModels = listOf(
        "Pixel 7", "Pixel 8", "Pixel 8 Pro", "SM-S918B", "SM-S928B", "SM-A546B", "Xiaomi 2210132G"
    )

    var accessToken: String = ""
        private set

    var cachedUserId: String? = null

    val baseUserAgent: String
        get() {
            val key = cachedUserId ?: accessToken.ifBlank { "default" }
            val idx = (key.hashCode() and 0x7FFFFFFF) % deviceModels.size
            return "YandexMusic/24023621 (Linux; U; Android 14; ru_RU; ${deviceModels[idx]})"
        }

    fun getAuthHeader(): String? = if (accessToken.isNotBlank()) "OAuth $accessToken" else null

    fun updateAccessToken(newToken: String) {
        val extracted = YandexCookieService.extractAccessToken(newToken)
        if (!constantTimeEquals(extracted, this.accessToken)) {
            cachedUserId = null
        }
        this.accessToken = extracted
    }

    fun HttpRequestBuilder.applyAuthHeaders() {
        val auth = getAuthHeader()
        if (auth != null) {
            header(HttpHeaders.Authorization, auth)
        }
        header(HttpHeaders.UserAgent, baseUserAgent)
        header("X-Yandex-Music-Client", clientHeader)
    }

    /**
     * 👤 Получить ID пользователя (кэшируется)
     */
    suspend fun getUserId(): String? {
        cachedUserId?.let { return it }
        val uid = getAccountUid()
        if (uid != null) {
            cachedUserId = uid
        }
        return uid
    }

    /**
     * 👤 Получить UID аккаунта из эндпоинта /account/status
     */
    suspend fun getAccountUid(): String? {
        val auth = getAuthHeader() ?: return null
        return try {
            val response: HttpResponse = client.get("https://api.music.yandex.net/account/status") {
                applyAuthHeaders()
            }
            if (response.status == HttpStatusCode.OK) {
                val bodyStr = response.bodyAsText()
                val jsonObj = json.parseToJsonElement(bodyStr).jsonObject
                val resultObj = jsonObj["result"]?.jsonObject
                val accountObj = resultObj?.get("account")?.jsonObject
                val uid = accountObj?.get("uid")?.jsonPrimitive?.content
                uid
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 👑 Проверить наличие подписки Плюс
     */
    suspend fun getAccountPlusInfo(): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            val response: HttpResponse = client.get("https://api.music.yandex.net/account/status") {
                applyAuthHeaders()
            }
            if (response.status == HttpStatusCode.OK) {
                val bodyStr = response.bodyAsText()
                val jsonObj = json.parseToJsonElement(bodyStr).jsonObject
                val resultObj = jsonObj["result"]?.jsonObject
                val plusObj = resultObj?.get("plus")?.jsonObject
                val hasPlus = plusObj?.get("hasPlus")?.jsonPrimitive?.booleanOrNull ?: false
                hasPlus
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun createDefaultHttpClient(): HttpClient = HttpClient {
            followRedirects = false
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; coerceInputValues = true })
            }
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 20_000
            }
        }
    }
}
