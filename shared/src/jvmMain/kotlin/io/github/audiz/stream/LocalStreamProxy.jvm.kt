package io.github.audiz.stream

import io.github.audiz.api.MusicRepository

/**
 * 🌐 JVM-реализация LocalStreamProxy для Desktop (Windows / Linux / macOS).
 *
 * На Desktop JVM плеер не использует локальный loopback HTTP-сервер, так как:
 * 1. Windows Media Foundation (WMF) в составе JavaFX Media на Windows порождает множественные
 *    параллельные probe-соединения к Range HTTP, что вызывало перегрузку Ktor CIO пулов,
 *    конкуренцию потоков и покадровое подтормаживание UI Compose Desktop.
 * 2. Прямая загрузка трека в 1 HTTP-запрос с мгновенным AES-NI декодированием и
 *    последующим локальным воспроизведением через файл работает аппаратно гладко (60 FPS)
 *    и бесшовно переключается через schedulePreload (0 мс задержки между треками).
 */
actual object LocalStreamProxy {
    actual fun start(
        repository: MusicRepository,
        getMusicStoragePath: () -> String,
        isRecordToDisk: () -> Boolean,
        onTrackSaved: ((trackId: String, filePath: String) -> Unit)?
    ) {
        // Desktop JVM использует прямое воспроизведение файлов и фоновую предзагрузку
    }

    actual fun stop() {
    }

    actual fun isRunning(): Boolean = false

    actual fun getStreamUrl(trackId: String, quality: String, title: String, artist: String): String = ""
}
