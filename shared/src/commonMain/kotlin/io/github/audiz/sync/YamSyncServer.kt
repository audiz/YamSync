package io.github.audiz.sync

import io.github.audiz.models.YamSyncDevice
import io.github.audiz.models.YamSyncManifest
import io.github.audiz.models.YamSyncMergePayload

/**
 * ⚡ Локальный HTTP-сервер сессии YamSync для взаимодействия по Wi-Fi.
 */
expect class YamSyncServer(
    initialPort: Int = 43594,
    token: String,
    localDevice: YamSyncDevice,
    getManifest: () -> YamSyncManifest,
    resolveFilePath: (fileName: String, checksum: String) -> String?,
    onMergeReceived: (YamSyncMergePayload) -> Unit,
    onClientConnected: (YamSyncDevice) -> Unit
) {
    val port: Int
    val isRunning: Boolean

    /** Запустить сервер. Возвращает реально назначенный порт */
    fun start(): Int

    /** Остановить сервер и освободить порт */
    fun stop()
}
