package io.github.audiz.sync

/**
 * 🔗 Данные для подключения и авторизации сессии YamSync, кодируемые в QR-код.
 */
data class YamSyncPairInfo(
    val ip: String,
    val port: Int,
    val token: String,
    val name: String,
    val platform: String
) {
    /** Сформировать URI для QR-кода */
    fun toUri(): String {
        val encodedName = encodeUrlParam(name)
        val encodedPlatform = encodeUrlParam(platform)
        return "yamsync://pair?ip=$ip&port=$port&token=$token&name=$encodedName&platform=$encodedPlatform"
    }

    companion object {
        /** Разобрать URI из отсканированного QR-кода или вставленного текста */
        fun parse(uriString: String): YamSyncPairInfo? {
            val trimmed = uriString.trim()
            if (!trimmed.startsWith("yamsync://pair")) return null
            val query = trimmed.substringAfter("?", "")
            if (query.isBlank()) return null

            val params = query.split("&").mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = part.substring(0, eq)
                    val v = part.substring(eq + 1)
                    k to decodeUrlParam(v)
                } else null
            }.toMap()

            val ip = params["ip"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val port = params["port"]?.toIntOrNull() ?: 43594
            val token = params["token"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val name = params["name"]?.trim()?.ifBlank { "Remote Device" } ?: "Remote Device"
            val platform = params["platform"]?.trim()?.ifBlank { "Mobile" } ?: "Mobile"

            return YamSyncPairInfo(
                ip = ip,
                port = port,
                token = token,
                name = name,
                platform = platform
            )
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
