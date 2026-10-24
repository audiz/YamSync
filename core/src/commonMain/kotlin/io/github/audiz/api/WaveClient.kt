package io.github.audiz.api

import io.github.audiz.models.RotorSessionResponse
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * 🌊 Клиент «Моей Волны» (Rotor API и сессии Ynison)
 */
class WaveClient(
    private val context: ApiSessionContext,
    val ynisonService: YnisonService = YnisonService(context.client)
) {
    private val client get() = context.client
    private val rotorHosts = listOf("api.music.yandex.ru", "api.music.yandex.net")

    val lastYnisonHost: String? get() = ynisonService.lastYnisonHost
    val lastYnisonError: String? get() = ynisonService.lastYnisonError

    private fun HttpRequestBuilder.applyAuthHeaders() {
        with(context) { applyAuthHeaders() }
    }

    /**
     * 🌊 Получить session_id Моей волны через Ynison WebSocket
     */
    suspend fun fetchYnisonWaveSessionId(): String? {
        val token = context.accessToken
        if (token.isBlank()) {
            println("WaveClient: токен пуст, пропускаем Ynison")
            return null
        }
        return try {
            val uid = context.getAccountUid() ?: ""
            ynisonService.getWaveSessionId(token, uid)
        } catch (t: Throwable) {
            println("WaveClient: Не удалось получить сессию Ynison: ${t.message}")
            null
        }
    }

    suspend fun postRotorWithFallback(path: String, payload: String): HttpResponse {
        var lastException: Throwable? = null
        for (host in rotorHosts) {
            try {
                val url = "https://$host$path"
                val response: HttpResponse = client.post(url) {
                    applyAuthHeaders()
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                if (response.status.value !in 500..599) {
                    return response
                }
                println("WaveClient: $host вернул HTTP ${response.status}, пробуем резервный хост...")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastException = e
                println("WaveClient: Ошибка связи с $host: ${e.message}")
            }
        }
        if (lastException != null) throw lastException
        throw IllegalStateException("Все серверы Яндекс Музыки временно недоступны")
    }

    /**
     * 🔄 Клонировать Rotor-сессию по waveSessionId
     */
    suspend fun cloneRotorSessionRaw(waveSessionId: String): String {
        val safeSid = waveSessionId.encodeURLPathPart()
        println("WaveClient: POST /rotor/session/$safeSid/clone")
        val response = postRotorWithFallback("/rotor/session/$safeSid/clone", "{}")
        val text = response.bodyAsText()
        println("WaveClient: Ответ clone (HTTP ${response.status}), длина: ${text.length}")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ошибка клонирования сессии Волны (HTTP ${response.status})")
        }
        return text
    }

    /**
     * 📦 Парсер ответов Rotor (обрабатывает и корневой JSON, и вложенный в 'result')
     */
    fun parseRotorResponse(jsonStr: String): RotorSessionResponse {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val element = json.parseToJsonElement(jsonStr).jsonObject
        val targetObj = if (element.containsKey("result") && element["result"] is JsonObject) {
            element["result"]!!.jsonObject
        } else {
            element
        }
        return json.decodeFromJsonElement(RotorSessionResponse.serializer(), targetObj)
    }

    /**
     * 🔄 Клонировать Rotor-сессию и получить начальные 5 треков
     */
    suspend fun cloneRotorSession(waveSessionId: String): RotorSessionResponse {
        val raw = cloneRotorSessionRaw(waveSessionId)
        return parseRotorResponse(raw)
    }

    /**
     * 🌊 Создать Rotor-сессию по списку seeds (например, для жанровой Волны: ["genre:metal"])
     */
    suspend fun createRotorSessionRaw(seeds: List<String>): String {
        println("WaveClient: Создание Rotor-сессии with seeds: $seeds")
        val seedsJson = seeds.joinToString(",") { "\"$it\"" }
        val payload = """{"seeds":[$seedsJson],"includeTracksInResponse":true}"""
        val response = postRotorWithFallback("/rotor/session/new", payload)
        val text = response.bodyAsText()
        println("WaveClient: Ответ session/new (HTTP ${response.status}), длина: ${text.length}")
        if (response.status.value !in 200..299) {
            val json = Json { ignoreUnknownKeys = true }
            val errMsg = try {
                val root = json.parseToJsonElement(text).jsonObject
                root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    ?: root["result"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            } catch (_: Exception) { null }
            if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Unauthorized) {
                throw IllegalStateException(errMsg ?: "Для выбранной волны требуется активная подписка Яндекс Плюс")
            }
            throw IllegalStateException(errMsg ?: "Ошибка создания сессии Волны (HTTP ${response.status})")
        }
        return text
    }

    /**
     * 🌊 Создать Rotor-сессию с треками по списку seeds
     */
    suspend fun createRotorSession(seeds: List<String>): RotorSessionResponse {
        val raw = createRotorSessionRaw(seeds)
        var parsed = parseRotorResponse(raw)
        val sid = parsed.radioSessionId
        if (parsed.sequence.isEmpty() && !sid.isNullOrBlank()) {
            println("WaveClient: sequence пуст в ответе session/new, запрашиваем tracks напрямую...")
            parsed = getRotorNextTracks(sid, feedbacks = emptyList())
        }
        return parsed
    }

    /**
     * ⏭ Получить следующие треки Волны (список фидбеков: skip или накопленные trackFinished)
     */
    suspend fun getRotorNextTracks(
        radioSessionId: String,
        feedbacks: List<RotorFeedbackItem>,
        remainingQueue: List<String> = emptyList()
    ): RotorSessionResponse {
        val raw = getRotorNextTracksRaw(
            radioSessionId = radioSessionId,
            feedbacks = feedbacks,
            remainingQueue = remainingQueue
        )
        return parseRotorResponse(raw)
    }

    /**
     * Перегрузка для отправки одиночного фидбека (обратная совместимость)
     */
    suspend fun getRotorNextTracks(
        radioSessionId: String,
        batchId: String,
        currentTrackIdWithAlbum: String,
        playedSeconds: Double,
        remainingQueue: List<String> = emptyList(),
        eventType: String = "skip"
    ): RotorSessionResponse {
        return getRotorNextTracks(
            radioSessionId = radioSessionId,
            feedbacks = listOf(
                RotorFeedbackItem(
                    batchId = batchId,
                    trackId = currentTrackIdWithAlbum,
                    eventType = eventType,
                    totalPlayedSeconds = playedSeconds
                )
            ),
            remainingQueue = remainingQueue
        )
    }

    /**
     * ⏭ Получить следующие треки / отправить фидбеки (сырой JSON)
     */
    suspend fun getRotorNextTracksRaw(
        radioSessionId: String,
        feedbacks: List<RotorFeedbackItem>,
        remainingQueue: List<String> = emptyList()
    ): String {
        val safeSid = radioSessionId.encodeURLPathPart()
        val path = "/rotor/session/$safeSid/tracks"
        val defaultTime = currentIsoUtcTimestamp()

        val feedbacksJson = feedbacks.joinToString(",") { fb ->
            val time = fb.timestamp ?: defaultTime
            val lengthField = if (fb.trackLengthSeconds != null) ",\"trackLengthSeconds\":${fb.trackLengthSeconds}" else ""
            """{"batchId":"${fb.batchId}","event":{"type":"${fb.eventType}","timestamp":"$time","trackId":"${fb.trackId}","totalPlayedSeconds":${fb.totalPlayedSeconds}$lengthField},"from":"${fb.from}"}"""
        }
        val queueJson = remainingQueue.joinToString(",") { "\"$it\"" }
        val payload = """{"queue":[$queueJson],"feedbacks":[$feedbacksJson]}"""

        val eventTypes = feedbacks.map { it.eventType }
        println("WaveClient: POST $path ($eventTypes, queue: ${remainingQueue.size} items)")
        val response = postRotorWithFallback(path, payload)
        val text = response.bodyAsText()
        println("WaveClient: Ответ next tracks ($eventTypes) [HTTP ${response.status}], длина: ${text.length}")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ошибка получения треков Волны (HTTP ${response.status})")
        }
        return text
    }

    /**
     * 🚀 Отправить фидбек trackStarted при старте трека в Моей волне
     * POST https://api.music.yandex.ru/rotor/session/{radioSessionId}/feedback/
     */
    suspend fun sendRotorFeedbackTrackStarted(
        radioSessionId: String,
        batchId: String,
        trackIdWithAlbum: String,
        timestamp: String = currentIsoUtcTimestamp()
    ): Boolean {
        val safeSid = radioSessionId.encodeURLPathPart()
        val path = "/rotor/session/$safeSid/feedback/"
        val payload = """{"event":{"type":"trackStarted","timestamp":"$timestamp","trackId":"$trackIdWithAlbum"},"batchId":"$batchId","from":"web-wave_landing_screen-my_wave-radio-default"}"""
        println("WaveClient: POST $path (trackStarted: $trackIdWithAlbum)")
        return try {
            val response = postRotorWithFallback(path, payload)
            val status = response.status.value in 200..299
            println("WaveClient: trackStarted response status: ${response.status}")
            status
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("WaveClient: Ошибка при отправке trackStarted: ${e.message}")
            false
        }
    }

    /**
     * 📊 Отправить отчет о прослушивании трека в Яндекс Музыку
     * POST https://api.music.yandex.ru/plays?clientNow={ISO}
     */
    suspend fun sendPlayReport(
        trackId: String,
        albumId: String,
        radioSessionId: String,
        batchId: String,
        totalPlayedSeconds: Double,
        endPositionSeconds: Double,
        trackLengthSeconds: Double,
        startTimestamp: String,
        endTimestamp: String = currentIsoUtcTimestamp(),
        changeReason: String = "finish", // "finish" | "skip"
        playId: String = generatePlayUuid()
    ): Boolean {
        val path = "/plays?clientNow=$endTimestamp"
        val addTracksTime = "${kotlin.random.Random.nextLong(1000000000000000L, 9999999999999999L)}-${kotlin.random.Random.nextInt(1000000000, 2000000000)}"
        val payload = """{"plays":[{"playId":"$playId","from":"web-wave_landing_screen-my_wave-radio-default","totalPlayedSeconds":$totalPlayedSeconds,"endPositionSeconds":$endPositionSeconds,"trackLengthSeconds":$trackLengthSeconds,"timestamp":"$endTimestamp","albumId":"$albumId","context":"radio","contextItem":"user:onyourwave","addTracksToPlayerTime":"$addTracksTime","fromCache":false,"isRestored":false,"audioAuto":"none","audioOutputName":"Динамик","audioOutputType":"Speaker","trackId":"$trackId","radioSessionId":"$radioSessionId","batchId":"$batchId","isFromAutoflow":false,"isFromPumpkin":false,"seek":true,"pause":false,"startTimestamp":"$startTimestamp","maxPlayerStage":"play","isRepeated":false,"changeReason":"$changeReason","isLivePlayableIndex":true}]}"""
        println("WaveClient: POST $path ($changeReason, trackId: $trackId, played: ${totalPlayedSeconds}s)")
        return try {
            val response = postRotorWithFallback(path, payload)
            val status = response.status.value in 200..299
            println("WaveClient: /plays response status: ${response.status}")
            status
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("WaveClient: Ошибка при отправке /plays: ${e.message}")
            false
        }
    }
}

/**
 * 🕒 Получить текущую дату-время в формате ISO-8601 UTC с миллисекундами
 */
fun currentIsoUtcTimestamp(): String {
    val date = io.ktor.util.date.GMTDate()
    val millis = (io.ktor.util.date.getTimeMillis() % 1000).toString().padStart(3, '0')
    val y = date.year
    val m = (date.month.ordinal + 1).toString().padStart(2, '0')
    val d = date.dayOfMonth.toString().padStart(2, '0')
    val h = date.hours.toString().padStart(2, '0')
    val min = date.minutes.toString().padStart(2, '0')
    val s = date.seconds.toString().padStart(2, '0')
    return "${y}-${m}-${d}T${h}:${min}:${s}.${millis}Z"
}

/**
 * 🔑 Сгенерировать случайный UUID v4
 */
fun generatePlayUuid(): String {
    val chars = "0123456789abcdef"
    val randomBytes = kotlin.random.Random.nextBytes(16)
    randomBytes[6] = ((randomBytes[6].toInt() and 0x0f) or 0x40).toByte()
    randomBytes[8] = ((randomBytes[8].toInt() and 0x3f) or 0x80).toByte()
    return buildString(36) {
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            val b = randomBytes[i].toInt() and 0xff
            append(chars[b ushr 4])
            append(chars[b and 0x0f])
        }
    }
}

/**
 * 🎵 Элемент отзыва (фидбека) для Моей Волны (Rotor)
 */
data class RotorFeedbackItem(
    val batchId: String,
    val trackId: String,
    val eventType: String, // "skip" | "trackFinished"
    val totalPlayedSeconds: Double,
    val trackLengthSeconds: Double? = null,
    val timestamp: String? = null,
    val from: String = "web-wave_landing_screen-my_wave-radio-default"
)
