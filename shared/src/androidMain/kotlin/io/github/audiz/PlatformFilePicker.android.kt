package io.github.audiz

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

actual val isPlatformPickerSupported: Boolean = true

object AndroidFilePickerBridge {
    var directoryPickerLauncher: ((onResult: (String?) -> Unit) -> Unit)? = null
    var filePickerLauncher: ((onResult: (String?) -> Unit) -> Unit)? = null

    fun resolveTreeUriToPath(context: Context, uri: Uri): String? {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Throwable) {}

        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
            if (docId.startsWith("primary:", ignoreCase = true)) {
                val relPath = docId.substringAfter(":")
                val base = Environment.getExternalStorageDirectory().absolutePath
                if (relPath.isBlank()) base else "$base/$relPath"
            } else if (docId.contains(":")) {
                val storageId = docId.substringBefore(":")
                val relPath = docId.substringAfter(":")
                "/storage/$storageId/$relPath"
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveDocUriToPath(context: Context, uri: Uri): String? {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Throwable) {}

        return try {
            val docId = DocumentsContract.getDocumentId(uri) ?: return null
            if (docId.startsWith("primary:", ignoreCase = true)) {
                val relPath = docId.substringAfter(":")
                val base = Environment.getExternalStorageDirectory().absolutePath
                if (relPath.isBlank()) base else "$base/$relPath"
            } else if (docId.contains(":")) {
                val storageId = docId.substringBefore(":")
                val relPath = docId.substringAfter(":")
                "/storage/$storageId/$relPath"
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}

actual fun pickDirectory(): String? {
    val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "YandexDownloader")
    return if (musicDir.exists()) musicDir.absolutePath else getDefaultMusicDir()
}

actual fun pickAudioOrPlaylistFile(): String? = null

actual fun pickSaveFile(defaultFileName: String, title: String): String? {
    val dir = getDefaultMusicDir()
    return "$dir/$defaultFileName"
}

actual fun launchDirectoryPicker(onResult: (String?) -> Unit) {
    val launcher = AndroidFilePickerBridge.directoryPickerLauncher
    if (launcher != null) {
        launcher(onResult)
    } else {
        onResult(getDefaultMusicDir())
    }
}

actual fun launchAudioOrPlaylistFilePicker(onResult: (String?) -> Unit) {
    val launcher = AndroidFilePickerBridge.filePickerLauncher
    if (launcher != null) {
        launcher(onResult)
    } else {
        onResult(null)
    }
}

actual fun getPlatformPresetDirectories(): List<Pair<String, String>> {
    val list = mutableListOf<Pair<String, String>>()
    val musicDir = File("/storage/emulated/0/Music")
    if (musicDir.exists() && musicDir.isDirectory) {
        list.add("📁 Папка Музыка" to musicDir.absolutePath)
    }
    val downloadsDir = File("/storage/emulated/0/Download")
    if (downloadsDir.exists() && downloadsDir.isDirectory) {
        list.add("📥 Папка Загрузки" to downloadsDir.absolutePath)
    }
    return list
}
