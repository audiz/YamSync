@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.audiz

import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.*

actual fun shareTrackFile(
    filePath: String,
    title: String,
    artist: String,
    trackId: String,
    albumId: Long?
) {
    val caption = buildString {
        append("🎵 ").append(if (artist.isNotBlank()) "$artist — $title" else title).append("\n")
        append("🔗 Deep Link: yamsync://track/$trackId\n")
        append("🌐 Яндекс Музыка: https://music.yandex.ru/track/$trackId")
    }

    // 1. Копируем текст в системный буфер обмена iOS
    try {
        UIPasteboard.generalPasteboard.string = caption
    } catch (_: Throwable) {}

    // 2. Открываем системный диалог «Поделиться» (UIActivityViewController)
    try {
        val items = mutableListOf<Any>()
        val fileManager = NSFileManager.defaultManager
        if (filePath.isNotBlank() && fileManager.fileExistsAtPath(filePath)) {
            items.add(NSURL.fileURLWithPath(filePath))
        }
        items.add(caption)

        val window = UIApplication.sharedApplication.keyWindow
            ?: (UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)
        val rootVC = window?.rootViewController

        if (rootVC != null) {
            val activityVC = UIActivityViewController(activityItems = items, applicationActivities = null)
            activityVC.popoverPresentationController?.sourceView = rootVC.view
            activityVC.popoverPresentationController?.sourceRect = rootVC.view.bounds
            rootVC.presentViewController(activityVC, animated = true, completion = null)
        }
    } catch (e: Throwable) {
        println("PlatformShare iOS: Ошибка вызова UIActivityViewController: ${e.message}")
    }
}
