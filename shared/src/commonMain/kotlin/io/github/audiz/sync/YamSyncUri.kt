package io.github.audiz.sync

import kotlinx.serialization.Serializable

/**
 * 🔗 Данные для подключения и авторизации сессии YamSync, кодируемые в QR-код.
 */
@Serializable
data class YamSyncPairInfo(
    val ip: String,
    val port: Int,
    val token: String,
    val name: String,
    val platform: String
) {
    /** Сформировать URI со схемой приложения */
    fun toUri(): String {
        val encodedName = encodeUrlParam(name)
        val encodedPlatform = encodeUrlParam(platform)
        return "yamsync://pair?ip=$ip&port=$port&token=$token&name=$encodedName&platform=$encodedPlatform"
    }

    /** Сформировать стандартный HTTP URL для распознавания системной камерой iOS/Android */
    fun toHttpUrl(): String {
        val encodedName = encodeUrlParam(name)
        val encodedPlatform = encodeUrlParam(platform)
        return "http://$ip:$port/pair?token=$token&name=$encodedName&platform=$encodedPlatform"
    }

    companion object {
        /** Разобрать URI из отсканированного QR-кода, HTTP-ссылки или вставленного текста */
        fun parse(uriString: String): YamSyncPairInfo? {
            val trimmed = uriString.trim().trim('"', '\'', '`')
            if (trimmed.isBlank()) return null

            // 1. Формат стандартной веб-ссылки: http://<ip>:<port>/pair?token=... или https://...
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                val afterScheme = trimmed.substringAfter("://")
                val hostPort = afterScheme.substringBefore("/").substringBefore("?")
                val ip = hostPort.substringBefore(":").trim()
                if (ip.isBlank()) return null
                val port = hostPort.substringAfter(":", "").toIntOrNull() ?: 43594
                val query = afterScheme.substringAfter("?", "")
                val params = parseQueryParams(query)
                val token = params["token"] ?: params["t"] ?: return null
                val name = params["name"] ?: params["n"] ?: "Remote Device"
                val platform = params["platform"] ?: params["p"] ?: "Desktop"
                return YamSyncPairInfo(ip = ip, port = port, token = token, name = name, platform = platform)
            }

            // 2. Формат глубокой схемы приложения: yamsync://pair?ip=...&port=...&token=...
            if (trimmed.startsWith("yamsync://", ignoreCase = true)) {
                val query = trimmed.substringAfter("?", "")
                val params = parseQueryParams(query)
                val ip = params["ip"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val port = params["port"]?.toIntOrNull() ?: 43594
                val token = params["token"] ?: params["t"] ?: return null
                val name = params["name"] ?: params["n"] ?: "Remote Device"
                val platform = params["platform"] ?: params["p"] ?: "Mobile"
                return YamSyncPairInfo(ip = ip, port = port, token = token, name = name, platform = platform)
            }

            // 3. Сырая строка параметров без схемы: ip=...&token=...
            if (trimmed.contains("token=") || trimmed.contains("ip=")) {
                val query = trimmed.substringAfter("?", trimmed)
                val params = parseQueryParams(query)
                val ip = params["ip"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val port = params["port"]?.toIntOrNull() ?: 43594
                val token = params["token"] ?: params["t"] ?: return null
                val name = params["name"] ?: params["n"] ?: "Remote Device"
                val platform = params["platform"] ?: params["p"] ?: "Mobile"
                return YamSyncPairInfo(ip = ip, port = port, token = token, name = name, platform = platform)
            }

            return null
        }

        private fun parseQueryParams(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split("&").mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = part.substring(0, eq).trim().lowercase()
                    val v = part.substring(eq + 1).trim()
                    k to decodeUrlParam(v)
                } else null
            }.toMap()
        }
    }
}

private fun encodeUrlParam(s: String): String {
    val sb = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt().toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
            sb.append(c)
        } else {
            val hex = (b.toInt() and 0xFF).toString(16).uppercase()
            sb.append('%')
            if (hex.length == 1) sb.append('0')
            sb.append(hex)
        }
    }
    return sb.toString()
}

private fun decodeUrlParam(s: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < s.length) {
        if (s[i] == '%' && i + 2 < s.length) {
            val hex = s.substring(i + 1, i + 3)
            val byteVal = hex.toIntOrNull(16)
            if (byteVal != null) {
                bytes.add(byteVal.toByte())
                i += 3
                continue
            }
        } else if (s[i] == '+') {
            bytes.add(' '.code.toByte())
            i++
            continue
        }
        bytes.add(s[i].code.toByte())
        i++
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * 📱 Сохраненное устройство, с которым ранее была успешная синхронизация
 */
@Serializable
data class YamSyncKnownDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val token: String,
    val platform: String,
    val lastSeenMs: Long
) {
    fun toPairInfo(): YamSyncPairInfo = YamSyncPairInfo(
        ip = ip,
        port = port,
        token = token,
        name = name,
        platform = platform
    )
}
