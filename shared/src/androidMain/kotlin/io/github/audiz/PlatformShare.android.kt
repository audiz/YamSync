package io.github.audiz

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

actual fun shareTrackFile(
    filePath: String,
    title: String,
    artist: String,
    trackId: String,
    albumId: Long?
) {
    val context = try { AppContextHolder.appContext } catch (_: Throwable) { return }
    val file = File(filePath)
    val caption = buildString {
        append("🎵 ").append(if (artist.isNotBlank()) "$artist — $title" else title).append("\n")
        append("🔗 Deep Link: yamsync://track/$trackId\n")
        append("🌐 Яндекс Музыка: https://music.yandex.ru/track/$trackId")
    }

    try {
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Поделиться треком в мессенджер").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } else {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Поделиться треком в мессенджер").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    } catch (e: Exception) {
        println("Ошибка при отправке трека: ${e.message}")
        e.printStackTrace()
    }
}
