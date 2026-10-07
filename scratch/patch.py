import os
import re

file_path = r"C:\Users\Public\Documents\YandexMusicDownloader\shared\src\jvmMain\kotlin\io\github\audiz\AudioPlayer.jvm.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add currentFile
content = content.replace(
    "    private var javaSoundLine: javax.sound.sampled.SourceDataLine? = null",
    "    private var javaSoundLine: javax.sound.sampled.SourceDataLine? = null\n    private var currentFile: File? = null"
)

# 2. Assign currentFile
content = content.replace(
    """        if (!file.exists()) {
            println("AudioPlayer: Файл не найден: $filePath")
            isPlayingState = false
            return
        }""",
    """        if (!file.exists()) {
            println("AudioPlayer: Файл не найден: $filePath")
            isPlayingState = false
            return
        }
        currentFile = file"""
)

# 3. Fix setOnError and catch
content = content.replace(
    """                        println("AudioPlayer: Ошибка JavaFX Media: ${err?.message}. Пробуем fallback...")
                        isPlayingState = false
                        playWithFallback(file)""",
    """                        println("AudioPlayer: Ошибка JavaFX Media: ${err?.message}. Пробуем fallback...")
                        fxPlayer = null
                        player.dispose()
                        playWithFallback(file)"""
)
content = content.replace(
    """                println("AudioPlayer: Исключение запуска JavaFX: ${e.message}. Пробуем fallback...")
                isPlayingState = false
                playWithFallback(file)""",
    """                println("AudioPlayer: Исключение запуска JavaFX: ${e.message}. Пробуем fallback...")
                fxPlayer = null
                playWithFallback(file)"""
)

# 4. playWithFallback signature and ending
content = content.replace(
    "private fun playWithFallback(file: File) {",
    "private fun playWithFallback(file: File, seekPosMs: Long = 0L) {"
)
content = content.replace(
    "playMp3WithJLayer(file)",
    "playMp3WithJLayer(file, seekPosMs)"
)
content = content.replace(
    "playWithJavaSound(targetFile)",
    "playWithJavaSound(targetFile, seekPosMs)"
)
content = content.replace(
    "playWithSystemProcess(systemPlayer, file)",
    "playWithSystemProcess(systemPlayer, file, seekPosMs)"
)
content = content.replace(
    """        println("AudioPlayer Fallback: Не удалось воспроизвести ${file.name}. Установите ffplay/mpv или gstreamer-plugins.")
    }""",
    """        println("AudioPlayer Fallback: Не удалось воспроизвести ${file.name}. Установите ffplay/mpv или gstreamer-plugins.")
        isPlayingState = false
    }"""
)
content = content.replace(
    """        val systemPlayer = findSystemPlayer()
        if (systemPlayer != null) {""",
    """        val systemPlayer = findSystemPlayer()
        if (systemPlayer != null) {
            if (durationMs == 0L) {
                if (file.name.endsWith(".m4a", ignoreCase = true) || file.name.endsWith(".aac", ignoreCase = true)) {
                    durationMs = estimateM4aDurationMs(file)
                } else if (isFlacFile(file)) {
                    durationMs = estimateFlacDurationMs(file)
                } else if (isMp3File(file)) {
                    durationMs = estimateMp3DurationMs(file)
                }
            }"""
)

# 5. playMp3WithJLayer
content = content.replace(
    "private fun playMp3WithJLayer(file: File) {",
    "private fun playMp3WithJLayer(file: File, seekPosMs: Long = 0L) {"
)
content = content.replace(
    """        val bytes = file.readBytes()
        durationMs = estimateMp3DurationMs(bytes)""",
    """        val bytes = file.readBytes()
        durationMs = estimateMp3DurationMs(file)"""
)
content = content.replace(
    """                var header: javazoom.jl.decoder.Header? = firstHeader
                audioDevice.open(decoder)
                isPlayingState = true
                isPausedState = false
                jlayerStartTimeNano = System.nanoTime()""",
    """                var header: javazoom.jl.decoder.Header? = firstHeader
                
                if (seekPosMs > 0 && msPerFrame > 0) {
                    val framesToSkip = (seekPosMs / msPerFrame).toInt()
                    for (i in 0 until framesToSkip) {
                        bitstream.closeFrame()
                        header = bitstream.readFrame()
                        if (header == null) break
                    }
                }

                audioDevice.open(decoder)
                isPlayingState = true
                isPausedState = false
                jlayerStartTimeNano = System.nanoTime()
                jlayerPauseOffsetMs = seekPosMs"""
)

# 6. playWithJavaSound
content = content.replace(
    "private fun playWithJavaSound(file: File) {",
    "private fun playWithJavaSound(file: File, seekPosMs: Long = 0L) {"
)
content = content.replace(
    """        val bytes = file.readBytes()
        durationMs = estimateFlacDurationMs(bytes)""",
    """        durationMs = estimateFlacDurationMs(file)"""
)
content = content.replace(
    """                applyJavaSoundVolume()
                line.start()
                
                isPlayingState = true
                isPausedState = false
                jlayerStartTimeNano = System.nanoTime()""",
    """                applyJavaSoundVolume()
                
                if (seekPosMs > 0) {
                    val frameRate = decodedFormat.frameRate
                    val frameSize = decodedFormat.frameSize
                    val framesToSkip = (seekPosMs * frameRate / 1000f).toLong()
                    val bytesToSkip = framesToSkip * frameSize
                    var skipped = 0L
                    while (skipped < bytesToSkip) {
                        val s = inStream.skip(bytesToSkip - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                }
                
                line.start()
                
                isPlayingState = true
                isPausedState = false
                jlayerStartTimeNano = System.nanoTime()
                jlayerPauseOffsetMs = seekPosMs"""
)
content = content.replace(
    """            } catch (e: Exception) {
                println("AudioPlayer Fallback FLAC Error: ${e.message}")
                
                // 🔥 JFlac failed...
                // Let's try the system player as a last resort!
                val systemPlayer = findSystemPlayer()
                if (systemPlayer != null) {
                    println("AudioPlayer Fallback: Переключаемся на $systemPlayer...")
                    playWithSystemProcess(systemPlayer, file)
                }
            } finally {""",
    """            } catch (e: Exception) {
                if (Thread.currentThread() == jlayerThread) {
                    println("AudioPlayer Fallback FLAC Error: ${e.message}")
                    
                    // 🔥 JFlac failed...
                    val systemPlayer = findSystemPlayer()
                    if (systemPlayer != null) {
                        println("AudioPlayer Fallback: Переключаемся на $systemPlayer...")
                        playWithSystemProcess(systemPlayer, file)
                    }
                }
            } finally {"""
)

# 7. playWithSystemProcess
content = content.replace(
    "private fun playWithSystemProcess(playerCmd: String, file: File) {",
    "private fun playWithSystemProcess(playerCmd: String, file: File, seekPosMs: Long = 0L) {"
)
content = content.replace(
    """            val args = when (playerCmd) {
                "ffplay" -> listOf("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", file.absolutePath)
                "mpv" -> listOf("mpv", "--no-video", "--really-quiet", file.absolutePath)
                else -> listOf(playerCmd, file.absolutePath)
            }""",
    """            val args = mutableListOf<String>()
            when (playerCmd) {
                "ffplay" -> {
                    args.addAll(listOf("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet"))
                    if (seekPosMs > 0) {
                        args.addAll(listOf("-ss", "${seekPosMs / 1000f}"))
                    }
                    args.add(file.absolutePath)
                }
                "mpv" -> {
                    args.addAll(listOf("mpv", "--no-video", "--really-quiet"))
                    if (seekPosMs > 0) {
                        args.addAll(listOf("--start=${seekPosMs / 1000f}"))
                    }
                    args.add(file.absolutePath)
                }
                else -> args.addAll(listOf(playerCmd, file.absolutePath))
            }"""
)
content = content.replace(
    """            fallbackProcess = proc
            isPlayingState = true
            isPausedState = false
            jlayerStartTimeNano = System.nanoTime()""",
    """            fallbackProcess = proc
            isPlayingState = true
            isPausedState = false
            jlayerStartTimeNano = System.nanoTime()
            jlayerPauseOffsetMs = seekPosMs"""
)

# 8. estimate functions
content = content.replace(
    """    private fun estimateMp3DurationMs(bytes: ByteArray): Long {
        return (bytes.size.toLong() * 8 * 1000) / 192000
    }

    private fun estimateFlacDurationMs(bytes: ByteArray): Long {
        // FLAC bitrate varies strongly (usually ~700-1000 kbps)
        // Average 850 kbps for estimation.
        return (bytes.size.toLong() * 8 * 1000) / 850000
    }""",
    """    private fun estimateMp3DurationMs(file: File): Long {
        return (file.length() * 8 * 1000) / 192000
    }

    private fun estimateFlacDurationMs(file: File): Long {
        return (file.length() * 8 * 1000) / 850000
    }

    private fun estimateM4aDurationMs(file: File): Long {
        return (file.length() * 8 * 1000) / 160000
    }"""
)

# 9. seekTo
content = content.replace(
    """    actual fun seekTo(positionMs: Long) {
        runOnFxThread {
            try {
                fxPlayer?.seek(javafx.util.Duration.millis(positionMs.toDouble()))
            } catch (_: Exception) {}
        }
        // Note: Basic JavaSound implementation doesn't support seeking efficiently yet.
        currentPositionMs = positionMs
    }""",
    """    actual fun seekTo(positionMs: Long) {
        runOnFxThread {
            try {
                fxPlayer?.seek(javafx.util.Duration.millis(positionMs.toDouble()))
            } catch (_: Exception) {}
        }
        
        if (fxPlayer == null) {
            val file = currentFile
            if (file != null && file.exists()) {
                val wasPaused = isPausedState
                stop()
                currentFile = file
                isPlayingState = true
                isPausedState = false
                
                val playFile = preparePlayableFile(file)
                playWithFallback(playFile, positionMs)
                
                if (wasPaused) {
                    pause()
                }
            }
        }
        
        currentPositionMs = positionMs
    }"""
)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Patch applied successfully.")
