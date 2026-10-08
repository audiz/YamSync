package io.github.audiz

import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

actual val isPlatformPickerSupported: Boolean = true

private val osName: String = System.getProperty("os.name")?.lowercase() ?: ""
private val isLinux: Boolean = osName.contains("linux")
private val isMac: Boolean = osName.contains("mac")

private sealed class NativePickerResult {
    data class Selected(val path: String) : NativePickerResult()
    data object Cancelled : NativePickerResult()
    data object NotAvailable : NativePickerResult()
}

actual fun pickDirectory(): String? {
    val title = "Выберите папку с музыкой"

    // 1. Linux: системный zenity (GNOME/Cinnamon/GTK), затем kdialog (KDE)
    if (isLinux) {
        when (val res = pickDirectoryZenity(title)) {
            is NativePickerResult.Selected -> return res.path
            is NativePickerResult.Cancelled -> return null
            is NativePickerResult.NotAvailable -> {}
        }
        when (val res = pickDirectoryKDialog(title)) {
            is NativePickerResult.Selected -> return res.path
            is NativePickerResult.Cancelled -> return null
            is NativePickerResult.NotAvailable -> {}
        }
    }

    // 2. macOS: родной FileDialog (Cocoa NSOpenPanel)
    if (isMac) {
        when (val res = pickDirectoryMac(title)) {
            is NativePickerResult.Selected -> return res.path
            is NativePickerResult.Cancelled -> return null
            is NativePickerResult.NotAvailable -> {}
        }
    }

    // 3. Windows / общий системный через JavaFX (Windows Explorer IFileDialog)
    when (val res = pickDirectoryJavaFx(title)) {
        is NativePickerResult.Selected -> return res.path
        is NativePickerResult.Cancelled -> return null
        is NativePickerResult.NotAvailable -> {}
    }

    // 4. Резервный вариант: Swing JFileChooser
    return pickDirectorySwing(title)
}

actual fun pickAudioOrPlaylistFile(): String? {
    val title = "Выберите аудиофайл или плейлист"

    // 1. Linux: zenity, kdialog
    if (isLinux) {
        when (val res = pickFileZenity(title)) {
            is NativePickerResult.Selected -> return res.path
            is NativePickerResult.Cancelled -> return null
            is NativePickerResult.NotAvailable -> {}
        }
        when (val res = pickFileKDialog(title)) {
            is NativePickerResult.Selected -> return res.path
            is NativePickerResult.Cancelled -> return null
            is NativePickerResult.NotAvailable -> {}
        }
    }

    // 2. macOS: родной FileDialog
    if (isMac) {
        when (val res = pickFileMac(title)) {
            is NativePickerResult.Selected -> return res.path
            is NativePickerResult.Cancelled -> return null
            is NativePickerResult.NotAvailable -> {}
        }
    }

    // 3. Windows / JavaFX
    when (val res = pickFileJavaFx(title)) {
        is NativePickerResult.Selected -> return res.path
        is NativePickerResult.Cancelled -> return null
        is NativePickerResult.NotAvailable -> {}
    }

    // 4. Резервный вариант: Swing JFileChooser
    return pickFileSwing(title)
}

private fun resolveInitialSaveDirectory(): File? {
    val configuredPath = loadMusicStoragePath()
    if (!configuredPath.isNullOrBlank()) {
        val configuredDir = File(configuredPath)
        if (configuredDir.exists() && configuredDir.isDirectory) return configuredDir
    }
    val userHome = System.getProperty("user.home") ?: return null
    val home = File(userHome)
    val candidates = listOf("Music", "Музыка", "Downloads", "Загрузки")
    for (name in candidates) {
        val dir = File(home, name)
        if (dir.exists() && dir.isDirectory) return dir
    }
    return if (home.exists() && home.isDirectory) home else null
}

private fun appendExtensionIfMissing(path: String, defaultFileName: String): String {
    val file = File(path)
    return if (file.extension.isBlank()) {
        val defaultExt = File(defaultFileName).extension.ifBlank { "mp3" }
        "$path.$defaultExt"
    } else {
        path
    }
}

actual fun pickSaveFile(defaultFileName: String, title: String): String? {
    val rawResult: String? = run {
        // 1. Linux: системный zenity (SAVE mode), затем kdialog
        if (isLinux) {
            when (val res = pickSaveFileZenity(defaultFileName, title)) {
                is NativePickerResult.Selected -> return@run res.path
                is NativePickerResult.Cancelled -> return null
                is NativePickerResult.NotAvailable -> {}
            }
            when (val res = pickSaveFileKDialog(defaultFileName, title)) {
                is NativePickerResult.Selected -> return@run res.path
                is NativePickerResult.Cancelled -> return null
                is NativePickerResult.NotAvailable -> {}
            }
        }

        // 2. macOS: родной FileDialog (SAVE)
        if (isMac) {
            when (val res = pickSaveFileMac(defaultFileName, title)) {
                is NativePickerResult.Selected -> return@run res.path
                is NativePickerResult.Cancelled -> return null
                is NativePickerResult.NotAvailable -> {}
            }
        }

        // 3. Windows / JavaFX FileChooser (SaveDialog)
        when (val res = pickSaveFileJavaFx(defaultFileName, title)) {
            is NativePickerResult.Selected -> return@run res.path
            is NativePickerResult.Cancelled -> return null
            is NativePickerResult.NotAvailable -> {}
        }

        // 4. Резервный вариант: Swing JFileChooser
        pickSaveFileSwing(defaultFileName, title)
    }

    return rawResult?.let { appendExtensionIfMissing(it, defaultFileName) }
}

// ---------------- Linux: Zenity & KDialog ----------------

private fun pickDirectoryZenity(title: String): NativePickerResult {
    return try {
        val userHome = System.getProperty("user.home")
        val cmd = mutableListOf(
            "zenity",
            "--file-selection",
            "--directory",
            "--title=$title"
        )
        if (!userHome.isNullOrBlank()) {
            val homeFile = File(userHome)
            if (homeFile.exists()) {
                cmd.add("--filename=${homeFile.absolutePath}/")
            }
        }
        val process = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.isNotBlank() && File(output).exists()) {
            NativePickerResult.Selected(output)
        } else if (exitCode == 1) {
            NativePickerResult.Cancelled
        } else {
            NativePickerResult.NotAvailable
        }
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

private fun pickFileZenity(title: String): NativePickerResult {
    return try {
        val userHome = System.getProperty("user.home")
        val cmd = mutableListOf(
            "zenity",
            "--file-selection",
            "--title=$title",
            "--file-filter=Аудиофайлы и плейлисты | *.mp3 *.flac *.m4a *.aac *.opus *.wav *.ogg *.m3u *.m3u8",
            "--file-filter=Все файлы | *"
        )
        if (!userHome.isNullOrBlank()) {
            val homeFile = File(userHome)
            if (homeFile.exists()) {
                cmd.add("--filename=${homeFile.absolutePath}/")
            }
        }
        val process = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.isNotBlank() && File(output).exists()) {
            NativePickerResult.Selected(output)
        } else if (exitCode == 1) {
            NativePickerResult.Cancelled
        } else {
            NativePickerResult.NotAvailable
        }
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

private fun pickDirectoryKDialog(title: String): NativePickerResult {
    return try {
        val userHome = System.getProperty("user.home") ?: "."
        val cmd = listOf("kdialog", "--getexistingdirectory", userHome, "--title", title)
        val process = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.isNotBlank() && File(output).exists()) {
            NativePickerResult.Selected(output)
        } else if (exitCode == 1) {
            NativePickerResult.Cancelled
        } else {
            NativePickerResult.NotAvailable
        }
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

private fun pickFileKDialog(title: String): NativePickerResult {
    return try {
        val userHome = System.getProperty("user.home") ?: "."
        val filter = "Аудиофайлы и плейлисты (*.mp3 *.flac *.m4a *.aac *.opus *.wav *.ogg *.m3u *.m3u8)|*.mp3 *.flac *.m4a *.aac *.opus *.wav *.ogg *.m3u *.m3u8\nВсе файлы (*)|*"
        val cmd = listOf("kdialog", "--getopenfilename", userHome, filter, "--title", title)
        val process = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.isNotBlank() && File(output).exists()) {
            NativePickerResult.Selected(output)
        } else if (exitCode == 1) {
            NativePickerResult.Cancelled
        } else {
            NativePickerResult.NotAvailable
        }
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

private fun pickSaveFileZenity(defaultFileName: String, title: String): NativePickerResult {
    return try {
        val baseDir = resolveInitialSaveDirectory()
        val initialPath = if (baseDir != null) {
            File(baseDir, defaultFileName).absolutePath
        } else {
            defaultFileName
        }

        val cmd = mutableListOf(
            "zenity",
            "--file-selection",
            "--save",
            "--confirm-overwrite",
            "--title=$title",
            "--filename=$initialPath",
            "--file-filter=Аудиофайлы (*.mp3, *.m4a, *.flac) | *.mp3 *.flac *.m4a *.aac *.opus *.wav *.ogg",
            "--file-filter=Все файлы | *"
        )
        val process = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.isNotBlank()) {
            NativePickerResult.Selected(output)
        } else if (exitCode == 1) {
            NativePickerResult.Cancelled
        } else {
            NativePickerResult.NotAvailable
        }
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

private fun pickSaveFileKDialog(defaultFileName: String, title: String): NativePickerResult {
    return try {
        val baseDir = resolveInitialSaveDirectory()
        val initialPath = if (baseDir != null) {
            File(baseDir, defaultFileName).absolutePath
        } else {
            defaultFileName
        }
        val filter = "Аудиофайлы (*.mp3 *.flac *.m4a)|*.mp3 *.flac *.m4a\nВсе файлы (*)|*"
        val cmd = listOf("kdialog", "--getsavefilename", initialPath, filter, "--title", title)
        val process = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.isNotBlank()) {
            NativePickerResult.Selected(output)
        } else if (exitCode == 1) {
            NativePickerResult.Cancelled
        } else {
            NativePickerResult.NotAvailable
        }
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

// ---------------- macOS: FileDialog ----------------

private fun pickDirectoryMac(title: String): NativePickerResult {
    return try {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        dialog.isVisible = true
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
        val dir = dialog.directory
        val file = dialog.file
        if (dir != null) {
            val selected = if (file != null) File(dir, file) else File(dir)
            if (selected.exists()) NativePickerResult.Selected(selected.absolutePath)
            else NativePickerResult.Cancelled
        } else {
            NativePickerResult.Cancelled
        }
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

private fun pickFileMac(title: String): NativePickerResult {
    return try {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        val allowedExtensions = setOf("mp3", "flac", "m4a", "aac", "opus", "wav", "ogg", "m3u", "m3u8")
        dialog.setFilenameFilter { _, name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext in allowedExtensions
        }
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        if (dir != null && file != null) {
            val selected = File(dir, file)
            if (selected.exists()) NativePickerResult.Selected(selected.absolutePath)
            else NativePickerResult.Cancelled
        } else {
            NativePickerResult.Cancelled
        }
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

private fun pickSaveFileMac(defaultFileName: String, title: String): NativePickerResult {
    return try {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
        dialog.file = defaultFileName
        val baseDir = resolveInitialSaveDirectory()
        if (baseDir != null) {
            dialog.directory = baseDir.absolutePath
        }
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        if (dir != null && file != null) {
            val selected = File(dir, file)
            NativePickerResult.Selected(selected.absolutePath)
        } else {
            NativePickerResult.Cancelled
        }
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

// ---------------- JavaFX (Windows & Native Fallback) ----------------

private fun pickDirectoryJavaFx(title: String): NativePickerResult {
    return try {
        try {
            javafx.application.Platform.startup {}
        } catch (_: IllegalStateException) {}

        val future = CompletableFuture<NativePickerResult>()
        javafx.application.Platform.runLater {
            try {
                val chooser = javafx.stage.DirectoryChooser().apply {
                    this.title = title
                    val userHome = System.getProperty("user.home")
                    if (!userHome.isNullOrBlank()) {
                        val homeFile = File(userHome)
                        if (homeFile.exists() && homeFile.isDirectory) {
                            initialDirectory = homeFile
                        }
                    }
                }
                val dir = chooser.showDialog(null)
                if (dir != null && dir.exists()) {
                    future.complete(NativePickerResult.Selected(dir.absolutePath))
                } else {
                    future.complete(NativePickerResult.Cancelled)
                }
            } catch (_: Throwable) {
                future.complete(NativePickerResult.NotAvailable)
            }
        }
        future.get()
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

private fun pickFileJavaFx(title: String): NativePickerResult {
    return try {
        try {
            javafx.application.Platform.startup {}
        } catch (_: IllegalStateException) {}

        val future = CompletableFuture<NativePickerResult>()
        javafx.application.Platform.runLater {
            try {
                val chooser = javafx.stage.FileChooser().apply {
                    this.title = title
                    val userHome = System.getProperty("user.home")
                    if (!userHome.isNullOrBlank()) {
                        val homeFile = File(userHome)
                        if (homeFile.exists() && homeFile.isDirectory) {
                            initialDirectory = homeFile
                        }
                    }
                    extensionFilters.addAll(
                        javafx.stage.FileChooser.ExtensionFilter(
                            "Аудиофайлы и плейлисты",
                            listOf("*.mp3", "*.flac", "*.m4a", "*.aac", "*.opus", "*.wav", "*.ogg", "*.m3u", "*.m3u8")
                        ),
                        javafx.stage.FileChooser.ExtensionFilter("Все файлы", listOf("*.*"))
                    )
                }
                val file = chooser.showOpenDialog(null)
                if (file != null && file.exists()) {
                    future.complete(NativePickerResult.Selected(file.absolutePath))
                } else {
                    future.complete(NativePickerResult.Cancelled)
                }
            } catch (_: Throwable) {
                future.complete(NativePickerResult.NotAvailable)
            }
        }
        future.get()
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

private fun pickSaveFileJavaFx(defaultFileName: String, title: String): NativePickerResult {
    return try {
        try {
            javafx.application.Platform.startup {}
        } catch (_: IllegalStateException) {}

        val future = CompletableFuture<NativePickerResult>()
        javafx.application.Platform.runLater {
            try {
                val chooser = javafx.stage.FileChooser().apply {
                    this.title = title
                    initialFileName = defaultFileName
                    val baseDir = resolveInitialSaveDirectory()
                    if (baseDir != null) {
                        initialDirectory = baseDir
                    }
                    extensionFilters.addAll(
                        javafx.stage.FileChooser.ExtensionFilter(
                            "Аудиофайлы",
                            listOf("*.mp3", "*.m4a", "*.flac", "*.wav", "*.ogg")
                        ),
                        javafx.stage.FileChooser.ExtensionFilter("Все файлы", listOf("*.*"))
                    )
                }
                val file = chooser.showSaveDialog(null)
                if (file != null) {
                    future.complete(NativePickerResult.Selected(file.absolutePath))
                } else {
                    future.complete(NativePickerResult.Cancelled)
                }
            } catch (_: Throwable) {
                future.complete(NativePickerResult.NotAvailable)
            }
        }
        future.get()
    } catch (_: Throwable) {
        NativePickerResult.NotAvailable
    }
}

// ---------------- Swing Fallback ----------------

private fun pickDirectorySwing(title: String): String? {
    return try {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {}

        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else null
    } catch (e: Exception) {
        println("PlatformFilePicker: ❌ Ошибка выбора папки (Swing): ${e.message}")
        null
    }
}

private fun pickFileSwing(title: String): String? {
    return try {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {}

        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            val filter = FileNameExtensionFilter(
                "Аудиофайлы и плейлисты (*.mp3, *.flac, *.m4a, *.wav, *.ogg, *.m3u, *.m3u8)",
                "mp3", "flac", "m4a", "aac", "opus", "wav", "ogg", "m3u", "m3u8"
            )
            fileFilter = filter
            isAcceptAllFileFilterUsed = true
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else null
    } catch (e: Exception) {
        println("PlatformFilePicker: ❌ Ошибка выбора файла (Swing): ${e.message}")
        null
    }
}

private fun pickSaveFileSwing(defaultFileName: String, title: String): String? {
    return try {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {}

        val chooser = JFileChooser().apply {
            dialogTitle = title
            val baseDir = resolveInitialSaveDirectory()
            if (baseDir != null) {
                currentDirectory = baseDir
            }
            selectedFile = File(defaultFileName)
        }
        val result = chooser.showSaveDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else null
    } catch (e: Exception) {
        println("PlatformFilePicker: ❌ Ошибка Save File (Swing): ${e.message}")
        null
    }
}
