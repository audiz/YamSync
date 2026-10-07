package io.github.audiz.api

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.random.Random

/**
 * ⚡ Сервис для работы с Yandex Music Ynison WebSocket API (Моя Волна / синхронизация очереди)
 */
class YnisonService(private val client: HttpClient) {

    companion object {
        private const val REDIRECTOR_URL = "wss://ynison.music.yandex.ru/redirector.YnisonRedirectService/GetRedirectToYnison"
        private const val BASE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val ORIGIN = "https://music.yandex.ru"

        fun rfc3986Encode(value: String): String {
            val hexChars = "0123456789ABCDEF"
            val sb = StringBuilder()
            for (b in value.encodeToByteArray()) {
                val c = b.toInt().toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                    sb.append(c)
                } else {
                    val u = b.toInt() and 0xFF
                    sb.append('%')
                    sb.append(hexChars[u shr 4])
                    sb.append(hexChars[u and 0x0F])
                }
            }
            return sb.toString()
        }

        fun generateUuid(): String {
            val hex = "0123456789abcdef"
            val bytes = ByteArray(16)
            Random.nextBytes(bytes)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val sb = StringBuilder(36)
            for (i in 0 until 16) {
                if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
                val b = bytes[i].toInt() and 0xff
                sb.append(hex[b shr 4])
                sb.append(hex[b and 0x0f])
            }
            return sb.toString()
        }
    }

    data class RedirectInfo(
        val host: String,
        val redirectTicket: String,
        val sessionId: String
    )

    /**
     * Шаг 1: Подключение к редиректору Ynison и получение тикета
     */
    suspend fun getRedirect(
        token: String,
        userId: String,
        deviceId: String,
        sessionId: String
    ): RedirectInfo? {
        val authHeader = if (token.startsWith("OAuth ", ignoreCase = true)) token else "OAuth $token"

        val metadataJson = buildJsonObject {
            put("Ynison-Device-Id", deviceId)
            put("Ynison-Device-Info", "{\"app_name\":\"Chrome\",\"app_version\":\"131.0.0.0\",\"type\":1}")
            put("X-Yandex-Music-Multi-Auth-User-Id", userId)
            put("Ynison-Session-Id", sessionId)
        }.toString()

        val encodedMetadata = rfc3986Encode(metadataJson)
        val protocolHeader = "Bearer, v2, $encodedMetadata"

        println("YnisonService: Подключение к редиректору...")
        var result: RedirectInfo? = null

        try {
            withTimeoutOrNull(15_000L) {
                client.webSocket(
                    urlString = REDIRECTOR_URL,
                    request = {
                        header("Origin", ORIGIN)
                        header("User-Agent", BASE_USER_AGENT)
                        header("Authorization", authHeader)
                        header("Sec-WebSocket-Protocol", protocolHeader)
                    }
                ) {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            println("YnisonService: Ответ редиректора: $text")
                            try {
                                val json = Json { ignoreUnknownKeys = true }
                                val root = json.parseToJsonElement(text).jsonObject
                                val host = root["host"]?.jsonPrimitive?.content
                                val ticket = root["redirect_ticket"]?.jsonPrimitive?.content
                                val respSessionId = root["session_id"]?.jsonPrimitive?.content ?: sessionId
                                if (!host.isNullOrBlank() && !ticket.isNullOrBlank()) {
                                    result = RedirectInfo(host, ticket, respSessionId)
                                }
                            } catch (e: Exception) {
                                println("YnisonService: Ошибка парсинга редиректора: ${e.message}")
                            }
                            break
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            println("YnisonService: Ошибка WebSocket редиректора: ${e.message}")
            e.printStackTrace()
        }

        return result
    }

    /**
     * Шаг 2 & 3: Подключение к PutYnisonState и извлечение session_id Моей волны
     */
    suspend fun getWaveSessionId(
        token: String,
        userId: String,
        deviceId: String = generateUuid(),
        initialSessionId: String = (Random.nextLong(1_000_000_000_000_000_000L, 8_999_999_999_999_999_999L)).toString()
    ): String? {
        // Шаг 1: Получаем тикет редиректора
        val redirect = getRedirect(token, userId, deviceId, initialSessionId) ?: return null

        val authHeader = if (token.startsWith("OAuth ", ignoreCase = true)) token else "OAuth $token"

        val metadataJson = buildJsonObject {
            put("Ynison-Device-Id", deviceId)
            put("Ynison-Redirect-Ticket", redirect.redirectTicket)
            put("Ynison-Session-Id", redirect.sessionId)
            put("Ynison-Device-Info", "{\"app_name\":\"Chrome\",\"app_version\":\"131.0.0.0\",\"type\":1}")
            put("X-Yandex-Music-Multi-Auth-User-Id", userId)
        }.toString()

        val encodedMetadata = rfc3986Encode(metadataJson)
        val protocolHeader = "Bearer, v2, $encodedMetadata"
        val stateUrl = "wss://${redirect.host}/ynison_state.YnisonStateService/PutYnisonState"

        println("YnisonService: Подключение к $stateUrl...")
        var waveSessionId: String? = null

        val rid = generateUuid()
        val versionLong = 5898445453701648000L

        // Запрос initial full state
        val updateStatePayload = """{"update_full_state":{"player_state":{"player_queue":{"current_playable_index":-1,"entity_id":"","entity_type":"VARIOUS","playable_list":[],"options":{"repeat_mode":"NONE"},"shuffle_optional":null,"entity_context":"BASED_ON_ENTITY_BY_DEFAULT","version":{"device_id":"$deviceId","version":$versionLong,"timestamp_ms":0},"from_optional":"","initial_entity_optional":null,"adding_options_optional":null,"queue":null},"status":{"duration_ms":0,"paused":true,"playback_speed":1,"progress_ms":0,"version":{"device_id":"$deviceId","version":$versionLong,"timestamp_ms":0}},"player_queue_inject_optional":null},"device":{"volume":0.5399999999999998,"capabilities":{"can_be_player":true,"can_be_remote_controller":false,"volume_granularity":20},"info":{"app_name":"Chrome","app_version":"131.0.0.0","title":"Browser Chrome","device_id":"$deviceId","type":"WEB"},"volume_info":{"volume":0.5399999999999998,"version":null},"is_shadow":true},"is_currently_active":false,"sync_state_from_eov_optional":null},"rid":"$rid","player_action_timestamp_ms":0,"activity_interception_type":"DO_NOT_INTERCEPT_BY_DEFAULT"}"""

        try {
            withTimeoutOrNull(20_000L) {
                client.webSocket(
                    urlString = stateUrl,
                    request = {
                        header("Origin", ORIGIN)
                        header("User-Agent", BASE_USER_AGENT)
                        header("Authorization", authHeader)
                        header("Sec-WebSocket-Protocol", protocolHeader)
                    }
                ) {
                    println("YnisonService: Соединение установлено. Отправка update_full_state...")
                    send(Frame.Text(updateStatePayload))

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            println("YnisonService: Получен ответ от PutYnisonState (длина ${text.length})")
                            
                            // 1. Попытка распарсить через Json
                            try {
                                val json = Json { ignoreUnknownKeys = true }
                                val root = json.parseToJsonElement(text).jsonObject
                                val playerState = root["player_state"]?.jsonObject
                                val playerQueue = playerState?.get("player_queue")?.jsonObject
                                
                                // radio_options -> session_id
                                val radioSessionId = playerQueue?.get("adding_options_optional")
                                    ?.jsonObject?.get("radio_options")
                                    ?.jsonObject?.get("session_id")
                                    ?.jsonPrimitive?.content

                                // wave_queue -> entity_options -> wave_entity_optional -> session_id
                                val waveSessionIdFromQueue = playerQueue?.get("queue")
                                    ?.jsonObject?.get("wave_queue")
                                    ?.jsonObject?.get("entity_options")
                                    ?.jsonObject?.get("wave_entity_optional")
                                    ?.jsonObject?.get("session_id")
                                    ?.jsonPrimitive?.content

                                val foundId = radioSessionId ?: waveSessionIdFromQueue
                                if (!foundId.isNullOrBlank()) {
                                    waveSessionId = foundId
                                    println("YnisonService: Успешно распарсен wave session_id (JSON): $waveSessionId")
                                    break
                                }
                            } catch (e: Exception) {
                                println("YnisonService: Ошибка парсинга JSON ответа: ${e.message}")
                            }

                            // 2. Fallback через регулярное выражение
                            val regexRadio = Regex(""""radio_options"\s*:\s*\{\s*"session_id"\s*:\s*"([^"]+)"""")
                            val matchRadio = regexRadio.find(text)
                            if (matchRadio != null) {
                                waveSessionId = matchRadio.groupValues[1]
                                println("YnisonService: Успешно распарсен wave session_id (Regex Radio): $waveSessionId")
                                break
                            }

                            val regexWave = Regex(""""wave_entity_optional"\s*:\s*\{\s*"session_id"\s*:\s*"([^"]+)"""")
                            val matchWave = regexWave.find(text)
                            if (matchWave != null) {
                                waveSessionId = matchWave.groupValues[1]
                                println("YnisonService: Успешно распарсен wave session_id (Regex Wave): $waveSessionId")
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            println("YnisonService: Ошибка при работе с PutYnisonState: ${e.message}")
            e.printStackTrace()
        }

        return waveSessionId
    }
}
