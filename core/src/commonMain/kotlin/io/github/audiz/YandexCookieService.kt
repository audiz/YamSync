package io.github.audiz

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * 🔑 Сервис авторизации и извлечения OAuth access_token для Яндекс Музыки
 */
class YandexCookieService {

    companion object {
        const val OAUTH_AUTH_URL = "https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d"
        const val MUSIC_CLIENT_HEADER = "YandexMusicAndroid/24023621"
        const val AUTH_FINISH_URL = "https://passport.yandex.ru/auth/finish/"

        /**
         * Извлечь access_token из сырой строки (токена, "OAuth ...", или полного URL редиректа)
         * e.g. https://music.yandex.ru/#access_token=AQAAAA...&token_type=bearer&expires_in=...
         */
        fun extractAccessToken(input: String): String {
            val trimmed = input.trim()
            if (trimmed.contains("access_token=")) {
                return trimmed.substringAfter("access_token=").substringBefore("&").substringBefore("#").trim()
            }
            if (trimmed.startsWith("OAuth ", ignoreCase = true)) {
                return trimmed.substring(6).trim()
            }
            if (trimmed.startsWith("Session_id=", ignoreCase = true)) {
                return trimmed.substring(11).trim()
            }
            return trimmed
        }
    }

    fun extractAccessToken(input: String): String = Companion.extractAccessToken(input)

    /**
     * Обменять access_token на Session_id cookie через паспорт Яндекса (fallback)
     */
    suspend fun getSessionCookieFromToken(rawTokenOrUrl: String): String? {
        val token = extractAccessToken(rawTokenOrUrl)
        if (token.isBlank()) return null

        val client = HttpClient {
            followRedirects = false
            expectSuccess = false
        }

        try {
            val response: HttpResponse = client.get(AUTH_FINISH_URL) {
                parameter("token", token)
                parameter("retpath", "https://music.yandex.ru")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            }

            val setCookieHeaders = response.headers.getAll(HttpHeaders.SetCookie) ?: emptyList()

            for (header in setCookieHeaders) {
                try {
                    val parsed = parseServerSetCookieHeader(header)
                    if (parsed.name == "Session_id" && parsed.value.isNotBlank()) {
                        return "Session_id=${parsed.value}"
                    }
                } catch (_: Exception) {}
            }

            for (header in setCookieHeaders) {
                val parts = header.split(";")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.startsWith("Session_id=")) {
                        val value = trimmed.substringAfter("Session_id=")
                        if (value.isNotBlank()) {
                            return "Session_id=$value"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("YandexCookieService: Ошибка получения Session_id: ${e.message}")
            e.printStackTrace()
        } finally {
            client.close()
        }
        return null
    }
}
