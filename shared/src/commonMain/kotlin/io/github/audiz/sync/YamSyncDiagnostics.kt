package io.github.audiz.sync

import io.github.audiz.DispatcherIO
import io.github.audiz.currentTimeMillis
import io.github.audiz.util.AppLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** События передачи для сопоставления HTTP-ответа, записи файла и путей плейлиста. */
internal object YamSyncDiagnostics {
    suspend fun record(event: String, vararg fields: Pair<String, Any?>) {
        withContext(NonCancellable + DispatcherIO) {
            val details = fields.joinToString(" ") { (key, value) ->
                val text = value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\r", "\\r").replace("\n", "\\n")
                "$key=\"$text\""
            }
            AppLogger.i("YamSyncTransfer", "timeMs=${currentTimeMillis()} event=$event $details")
        }
    }
}
