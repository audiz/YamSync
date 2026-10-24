package io.github.audiz.backend

import io.github.audiz.util.AudioHeaderParser
import java.io.File

/**
 * 🛠️ Утилиты поиска и вызова внешних системных утилит (ffmpeg, ffprobe, ffplay, mpv)
 */
object FfmpegUtils {
    @Volatile private var cachedFfmpegPath: String? = null
    @Volatile private var ffmpegPathChecked: Boolean = false
    @Volatile private var cachedSystemPlayer: String? = null
    @Volatile private var systemPlayerChecked: Boolean = false

    fun getFfmpegPath(): String? {
        if (ffmpegPathChecked) return cachedFfmpegPath
        synchronized(this) {
            if (ffmpegPathChecked) return cachedFfmpegPath
            val path = detectFfmpegPath()
            cachedFfmpegPath = path
            ffmpegPathChecked = true
            return path
        }
    }

    fun hasFfmpeg(): Boolean = getFfmpegPath() != null

    private fun detectFfmpegPath(): String? {
        val isWin = System.getProperty("os.name", "").lowercase().contains("win")
        val cmds = if (isWin) listOf("ffmpeg.exe", "ffmpeg") else listOf("ffmpeg")

        // 1. Check standard PATH
        for (cmd in cmds) {
            try {
                val p = ProcessBuilder(cmd, "-version").start()
                if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) return cmd
            } catch (_: Exception) {}
        }

        // 2. On Windows, try finding it via where.exe if PATH is stale
        if (isWin) {
            try {
                val p = ProcessBuilder("where.exe", "ffmpeg").redirectErrorStream(true).start()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                val path = reader.readLine()?.trim()
                p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                if (!path.isNullOrEmpty() && File(path).exists()) {
                    return path
                }
            } catch (_: Exception) {}
        }

        return null
    }

    fun findSystemPlayer(): String? {
        if (systemPlayerChecked) return cachedSystemPlayer
        synchronized(this) {
            if (systemPlayerChecked) return cachedSystemPlayer
            val isWin = System.getProperty("os.name", "").lowercase().contains("win")
            val cmdsToTry = if (isWin) listOf("ffplay.exe", "ffplay", "mpv.exe", "mpv") else listOf("ffplay", "mpv")

            for (cmd in cmdsToTry) {
                try {
                    val p = ProcessBuilder(cmd, "-version").redirectErrorStream(true).start()
                    val exited = p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                    if (exited) {
                        cachedSystemPlayer = cmd
                        systemPlayerChecked = true
                        return cmd
                    }
                } catch (_: Exception) {}
            }

            if (isWin) {
                for (cmd in listOf("ffplay", "mpv")) {
                    try {
                        val p = ProcessBuilder("where.exe", cmd).redirectErrorStream(true).start()
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                        val path = reader.readLine()?.trim()
                        p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                        if (!path.isNullOrEmpty() && File(path).exists()) {
                            cachedSystemPlayer = path
                            systemPlayerChecked = true
                            return path
                        }
                    } catch (_: Exception) {}
                }

                val ffmpegPath = getFfmpegPath()
                if (ffmpegPath != null) {
                    val assumedFfplay = File(ffmpegPath).parentFile?.resolve("ffplay.exe")
                    if (assumedFfplay != null && assumedFfplay.exists()) {
                        val path = assumedFfplay.absolutePath
                        cachedSystemPlayer = path
                        systemPlayerChecked = true
                        return path
                    }
                }
            }

            systemPlayerChecked = true
            return null
        }
    }

    fun decodeToWavWithFfmpeg(file: File, tempFileProvider: (prefix: String, suffix: String) -> File): File? {
        return try {
            val ffmpeg = getFfmpegPath() ?: return null
            val tempWav = tempFileProvider("ya_extract_", ".wav")
            tempWav.deleteOnExit()

            val proc = ProcessBuilder(ffmpeg, "-y", "-i", file.absolutePath, tempWav.absolutePath)
                .redirectErrorStream(true)
                .start()
            proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)

            if (tempWav.exists() && tempWav.length() > 0) {
                tempWav
            } else {
                null
            }
        } catch (e: Exception) {
            println("FfmpegUtils: Ошибка декодирования через ffmpeg - ${e.message}")
            null
        }
    }

    fun getDurationWithFfmpeg(file: File): Long {
        try {
            val isWin = System.getProperty("os.name", "").lowercase().contains("win")
            val ffprobeCmds = if (isWin) listOf("ffprobe.exe", "ffprobe") else listOf("ffprobe")
            var ffprobePath: String? = null
            for (cmd in ffprobeCmds) {
                try {
                    val p = ProcessBuilder(cmd, "-version").start()
                    if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        ffprobePath = cmd
                        break
                    }
                } catch (_: Exception) {}
            }
            if (ffprobePath == null && isWin) {
                val ffmpeg = getFfmpegPath()
                if (ffmpeg != null) {
                    val probeCandidate = File(ffmpeg).parentFile?.resolve("ffprobe.exe")
                    if (probeCandidate != null && probeCandidate.exists()) {
                        ffprobePath = probeCandidate.absolutePath
                    }
                }
            }

            if (ffprobePath != null) {
                val pb = ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.absolutePath
                ).redirectErrorStream(true)
                val proc = pb.start()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
                val output = reader.readLine()?.trim()
                proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                val durSec = output?.toDoubleOrNull()
                if (durSec != null && durSec > 0.0) {
                    return (durSec * 1000).toLong()
                }
            }

            val ffmpeg = getFfmpegPath()
            if (ffmpeg != null) {
                val pb = ProcessBuilder(ffmpeg, "-i", file.absolutePath).redirectErrorStream(true)
                val proc = pb.start()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
                var line: String?
                var durMs = 0L
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    if (text.contains("Duration:")) {
                        val match = Regex("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(text)
                        if (match != null) {
                            val (h, m, s) = match.destructured
                            val totalSec = h.toLong() * 3600 + m.toLong() * 60 + s.toDouble()
                            durMs = (totalSec * 1000).toLong()
                            break
                        }
                    }
                }
                proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (durMs > 0L) return durMs
            }
        } catch (_: Exception) {}
        return 0L
    }

    fun preparePlayableFile(file: File, tempFileProvider: (prefix: String, suffix: String) -> File): File {
        if (file.name.endsWith(".aac", ignoreCase = true) && AudioHeaderParser.isMp4Container(file)) {
            try {
                val tempM4a = tempFileProvider("ya_play_", ".m4a")
                tempM4a.deleteOnExit()
                file.inputStream().use { input ->
                    tempM4a.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return tempM4a
            } catch (e: Exception) {
                println("FfmpegUtils: Не удалось создать временный .m4a файл: ${e.message}")
            }
        }
        return file
    }

    fun isMp4Container(file: File): Boolean = AudioHeaderParser.isMp4Container(file)
    fun isMp3File(file: File): Boolean = AudioHeaderParser.isMp3File(file)
    fun isFlacFile(file: File): Boolean = AudioHeaderParser.isFlacFile(file)
}
