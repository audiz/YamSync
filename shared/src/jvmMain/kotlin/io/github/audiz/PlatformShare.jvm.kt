package io.github.audiz

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

actual fun shareTrackFile(
    filePath: String,
    title: String,
    artist: String,
    trackId: String,
    albumId: Long?
) {
    val file = File(filePath)
    val caption = buildString {
        append("🎵 ").append(if (artist.isNotBlank()) "$artist — $title" else title).append("\n")
        append("▶️ Открыть в YamSync: http://127.0.0.1:48293/track/$trackId\n")
        append("🔗 Deep Link: yamsync://track/$trackId\n")
        append("🌐 Яндекс Музыка: https://music.yandex.ru/track/$trackId")
    }

    try {
        if (file.exists()) {
            val transferable = FileTransferable(listOf(file), caption)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
            println("📤 [YamSync] Файл '${file.name}' и ссылка скопированы в буфер обмена для вставки в чат (Ctrl+V)")
        } else {
            val stringSelection = java.awt.datatransfer.StringSelection(caption)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(stringSelection, null)
            println("📤 [YamSync] Ссылка на трек скопирована в буфер обмена: $caption")
        }
    } catch (e: Exception) {
        println("Ошибка копирования в буфер обмена: ${e.message}")
    }
}

private class FileTransferable(
    private val files: List<File>,
    private val text: String
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(
        DataFlavor.javaFileListFlavor,
        DataFlavor.stringFlavor
    )

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean {
        return flavor == DataFlavor.javaFileListFlavor || flavor == DataFlavor.stringFlavor
    }

    override fun getTransferData(flavor: DataFlavor): Any {
        return when (flavor) {
            DataFlavor.javaFileListFlavor -> files
            DataFlavor.stringFlavor -> text
            else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        }
    }
}
