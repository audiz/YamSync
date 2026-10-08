package io.github.audiz.util

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * 🎧 Кроссплатформенный (JVM и Android) парсер аудиоформатов и метаданных контейнеров.
 * Извлекает точную длительность, определяет контейнеры MP3, FLAC, M4A/AAC, WAV, OGG
 * и считывает ID3v2 теги (название, артист, альбом) без внешних зависимостей.
 */
object AudioHeaderParser {

    data class AudioMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0L
    )

    /**
     * Извлечь метаданные трека (название, артист, длительность) из локального файла
     */
    fun extractAudioMetadata(file: File): AudioMetadata {
        if (!file.exists() || file.length() <= 0L) return AudioMetadata()

        val ext = file.extension.lowercase()
        val duration = extractAudioDuration(file)

        return when {
            ext == "mp3" || isMp3File(file) -> {
                val id3 = readId3v2Metadata(file)
                AudioMetadata(
                    title = id3.title,
                    artist = id3.artist,
                    album = id3.album,
                    durationMs = if (id3.durationMs > 0L) id3.durationMs else duration
                )
            }
            else -> AudioMetadata(durationMs = duration)
        }
    }

    /**
     * Быстрое и точное определение длительности любого аудиофайла в миллисекундах
     */
    fun extractAudioDuration(file: File): Long {
        if (!file.exists() || file.length() <= 0L) return 0L
        val ext = file.extension.lowercase()

        val dur = when (ext) {
            "mp3" -> getExactMp3DurationMs(file)
            "flac" -> getExactFlacDurationMs(file)
            "m4a", "aac", "mp4" -> getExactM4aDurationMs(file)
            "wav" -> getExactWavDurationMs(file)
            "ogg", "opus" -> getExactOggDurationMs(file)
            else -> 0L
        }
        if (dur > 0L) return dur

        // Если расширение нестандартное, определяем по сигнатуре файла
        return when {
            isMp3File(file) -> getExactMp3DurationMs(file)
            isFlacFile(file) -> getExactFlacDurationMs(file)
            isMp4Container(file) -> getExactM4aDurationMs(file)
            isWavFile(file) -> getExactWavDurationMs(file)
            isOggFile(file) -> getExactOggDurationMs(file)
            else -> 0L
        }
    }

    // --- Проверки типов контейнеров ---

    fun isMp4Container(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                read >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                        header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isMp3File(file: File): Boolean {
        if (file.name.endsWith(".mp3", ignoreCase = true)) return true
        return try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(4)
                val r = input.read(buf)
                if (r >= 3 && buf[0] == 'I'.code.toByte() && buf[1] == 'D'.code.toByte() && buf[2] == '3'.code.toByte()) {
                    true
                } else if (r >= 2 && buf[0] == 0xFF.toByte() && (buf[1].toInt() and 0xE0) == 0xE0) {
                    true
                } else false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isFlacFile(file: File): Boolean {
        if (file.name.endsWith(".flac", ignoreCase = true)) return true
        return try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(4)
                val r = input.read(buf)
                r >= 4 && buf[0] == 'f'.code.toByte() && buf[1] == 'L'.code.toByte() && buf[2] == 'a'.code.toByte() && buf[3] == 'C'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isWavFile(file: File): Boolean {
        if (file.name.endsWith(".wav", ignoreCase = true)) return true
        return try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(12)
                val r = input.read(buf)
                r >= 12 && buf[0] == 'R'.code.toByte() && buf[1] == 'I'.code.toByte() && buf[2] == 'F'.code.toByte() && buf[3] == 'F'.code.toByte() &&
                        buf[8] == 'W'.code.toByte() && buf[9] == 'A'.code.toByte() && buf[10] == 'V'.code.toByte() && buf[11] == 'E'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isOggFile(file: File): Boolean {
        if (file.name.endsWith(".ogg", ignoreCase = true) || file.name.endsWith(".opus", ignoreCase = true)) return true
        return try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(4)
                val r = input.read(buf)
                r >= 4 && buf[0] == 'O'.code.toByte() && buf[1] == 'g'.code.toByte() && buf[2] == 'g'.code.toByte() && buf[3] == 'S'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    // --- Точный парсинг MP3 ---

    /**
     * Вычисляет точную длительность MP3 файла.
     * Поддерживает:
     * 1. ID3v2 тег TLEN (длина в миллисекундах)
     * 2. Заголовок VBR Xing / Info (количество фреймов)
     * 3. Заголовок VBR VBRI (Fraunhofer)
     * 4. Расчет по точному битрейту первого MPEG фрейма (для CBR файлов)
     */
    fun getExactMp3DurationMs(file: File): Long {
        if (!file.exists() || file.length() < 128) return 0L
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                var audioStartOffset = 0L

                // 1. Проверяем заголовок ID3v2
                val id3Header = ByteArray(10)
                raf.seek(0)
                if (raf.read(id3Header) == 10 && id3Header[0] == 'I'.code.toByte() && id3Header[1] == 'D'.code.toByte() && id3Header[2] == '3'.code.toByte()) {
                    val flags = id3Header[5].toInt()
                    val tagDataSize = ((id3Header[6].toInt() and 0x7F) shl 21) or
                            ((id3Header[7].toInt() and 0x7F) shl 14) or
                            ((id3Header[8].toInt() and 0x7F) shl 7) or
                            (id3Header[9].toInt() and 0x7F)
                    audioStartOffset = 10L + tagDataSize + (if ((flags and 0x10) != 0) 10L else 0L)
                }

                // 2. Ищем первый валидный MPEG аудио фрейм в пределах первых 64 КБ аудиопотока
                raf.seek(audioStartOffset)
                val scanBufSize = minOf(65536, (fileLen - audioStartOffset).toInt()).coerceAtLeast(0)
                if (scanBufSize < 4) return 0L
                val scanBuf = ByteArray(scanBufSize)
                raf.readFully(scanBuf)

                var syncIdx = -1
                for (i in 0 until (scanBufSize - 4)) {
                    if (scanBuf[i] == 0xFF.toByte() && (scanBuf[i + 1].toInt() and 0xE0) == 0xE0) {
                        val b1 = scanBuf[i + 1].toInt() and 0xFF
                        val b2 = scanBuf[i + 2].toInt() and 0xFF
                        val ver = (b1 ushr 3) and 3
                        val layer = (b1 ushr 1) and 3
                        val brIdx = (b2 ushr 4) and 15
                        val srIdx = (b2 ushr 2) and 3
                        if (ver != 1 && layer != 0 && brIdx != 0 && brIdx != 15 && srIdx != 3) {
                            syncIdx = i
                            break
                        }
                    }
                }

                if (syncIdx == -1) return 0L

                val frameStartInRaf = audioStartOffset + syncIdx
                val b1 = scanBuf[syncIdx + 1].toInt() and 0xFF
                val b2 = scanBuf[syncIdx + 2].toInt() and 0xFF
                val b3 = scanBuf[syncIdx + 3].toInt() and 0xFF

                val ver = (b1 ushr 3) and 3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
                val layer = (b1 ushr 1) and 3 // 3 = Layer I, 2 = Layer II, 1 = Layer III
                val brIdx = (b2 ushr 4) and 15
                val srIdx = (b2 ushr 2) and 3
                val channel = (b3 ushr 6) and 3 // 3 = Mono, others = Stereo

                val sampleRate = when (ver) {
                    3 -> when (srIdx) { 0 -> 44100; 1 -> 48000; 2 -> 32000; else -> 0 }
                    2 -> when (srIdx) { 0 -> 22050; 1 -> 24000; 2 -> 16000; else -> 0 }
                    0 -> when (srIdx) { 0 -> 11025; 1 -> 12000; 2 -> 8000; else -> 0 }
                    else -> 0
                }
                if (sampleRate <= 0) return 0L

                val bitrateKbps = when {
                    ver == 3 && layer == 1 -> when (brIdx) {
                        1 -> 32; 2 -> 40; 3 -> 48; 4 -> 56; 5 -> 64; 6 -> 80; 7 -> 96; 8 -> 112; 9 -> 128;
                        10 -> 160; 11 -> 192; 12 -> 224; 13 -> 256; 14 -> 320; else -> 0
                    }
                    ver == 3 && layer == 2 -> when (brIdx) {
                        1 -> 32; 2 -> 48; 3 -> 56; 4 -> 64; 5 -> 80; 6 -> 96; 7 -> 112; 8 -> 128; 9 -> 160;
                        10 -> 192; 11 -> 224; 12 -> 256; 13 -> 320; 14 -> 384; else -> 0
                    }
                    ver == 3 && layer == 3 -> when (brIdx) {
                        1 -> 32; 2 -> 64; 3 -> 96; 4 -> 128; 5 -> 160; 6 -> 192; 7 -> 224; 8 -> 256; 9 -> 288;
                        10 -> 320; 11 -> 352; 12 -> 384; 13 -> 416; 14 -> 448; else -> 0
                    }
                    else -> when (brIdx) {
                        1 -> 8; 2 -> 16; 3 -> 24; 4 -> 32; 5 -> 40; 6 -> 48; 7 -> 56; 8 -> 64; 9 -> 80;
                        10 -> 96; 11 -> 112; 12 -> 128; 13 -> 144; 14 -> 160; else -> 0
                    }
                }

                val samplesPerFrame = when (layer) {
                    1 -> if (ver == 3) 1152 else 576 // Layer III (MP3)
                    2 -> 1152 // Layer II
                    3 -> 384  // Layer I
                    else -> 1152
                }

                // 3. Проверяем заголовок Xing / Info
                val xingOffset = when {
                    ver == 3 -> if (channel == 3) 21 else 36
                    else -> if (channel == 3) 13 else 21
                }

                raf.seek(frameStartInRaf + xingOffset)
                val xingTag = ByteArray(4)
                if (raf.read(xingTag) == 4) {
                    val tagStr = String(xingTag, Charsets.US_ASCII)
                    if (tagStr == "Xing" || tagStr == "Info") {
                        val flags = raf.readInt()
                        if ((flags and 0x01) != 0) {
                            val totalFrames = raf.readInt().toLong() and 0xFFFFFFFFL
                            if (totalFrames > 0L) {
                                return (totalFrames * samplesPerFrame * 1000L) / sampleRate
                            }
                        }
                    }
                }

                // 4. Проверяем заголовок VBRI (всегда смещение 36 от начала фрейма)
                raf.seek(frameStartInRaf + 36)
                val vbriTag = ByteArray(4)
                if (raf.read(vbriTag) == 4 && String(vbriTag, Charsets.US_ASCII) == "VBRI") {
                    raf.skipBytes(10) // version(2), delay(2), quality(2), streamBytes(4)
                    val totalFrames = raf.readInt().toLong() and 0xFFFFFFFFL
                    if (totalFrames > 0L) {
                        return (totalFrames * samplesPerFrame * 1000L) / sampleRate
                    }
                }

                // 5. Если VBR-заголовков нет — это CBR файл. Считаем по битрейту первого фрейма.
                if (bitrateKbps > 0) {
                    var audioDataLen = fileLen - frameStartInRaf
                    // Проверяем наличие 128-байтного ID3v1 в конце файла
                    if (fileLen >= 128) {
                        raf.seek(fileLen - 128)
                        val id3v1 = ByteArray(3)
                        if (raf.read(id3v1) == 3 && id3v1[0] == 'T'.code.toByte() && id3v1[1] == 'A'.code.toByte() && id3v1[2] == 'G'.code.toByte()) {
                            audioDataLen -= 128
                        }
                    }
                    if (audioDataLen > 0) {
                        val bitrateBps = bitrateKbps * 1000L
                        return (audioDataLen * 8L * 1000L) / bitrateBps
                    }
                }

                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Алиас для обратной совместимости
     */
    fun estimateMp3DurationMs(file: File): Long {
        val exact = getExactMp3DurationMs(file)
        if (exact > 0L) return exact
        return (file.length() * 8L * 1000L) / 256000L
    }

    // --- Точный парсинг FLAC ---

    fun getExactFlacDurationMs(file: File): Long {
        return try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(42)
                val read = input.read(buf)
                if (read >= 42 && buf[0] == 'f'.code.toByte() && buf[1] == 'L'.code.toByte() && buf[2] == 'a'.code.toByte() && buf[3] == 'C'.code.toByte()) {
                    val blockType = buf[4].toInt() and 0x7F
                    if (blockType == 0) {
                        val b18 = buf[18].toLong() and 0xFF
                        val b19 = buf[19].toLong() and 0xFF
                        val b20 = buf[20].toLong() and 0xFF
                        val sampleRate = (b18 shl 12) or (b19 shl 4) or (b20 ushr 4)

                        val b21 = buf[21].toLong() and 0x0F
                        val b22 = buf[22].toLong() and 0xFF
                        val b23 = buf[23].toLong() and 0xFF
                        val b24 = buf[24].toLong() and 0xFF
                        val b25 = buf[25].toLong() and 0xFF
                        val totalSamples = (b21 shl 32) or (b22 shl 24) or (b23 shl 16) or (b24 shl 8) or b25

                        if (sampleRate > 0 && totalSamples > 0) {
                            return (totalSamples * 1000L) / sampleRate
                        }
                    }
                }
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun estimateFlacDurationMs(file: File): Long {
        val exact = getExactFlacDurationMs(file)
        if (exact > 0) return exact
        return (file.length() * 8L * 1000L) / 850000L
    }

    // --- Точный парсинг M4A / AAC (MP4 контейнер) ---

    fun getExactM4aDurationMs(file: File): Long {
        if (!file.exists() || file.length() < 16) return 0L
        return try {
            RandomAccessFile(file, "r").use { raf ->
                var pos = 0L
                val fileLen = raf.length()
                while (pos + 8 <= fileLen) {
                    raf.seek(pos)
                    val sizeRaw = raf.readInt().toLong() and 0xFFFFFFFFL
                    val type = ByteArray(4)
                    raf.readFully(type)
                    val typeStr = String(type, Charsets.US_ASCII)

                    val (boxSize, headerSize) = when (sizeRaw) {
                        1L -> {
                            if (pos + 16 > fileLen) break
                            val extSize = raf.readLong()
                            extSize to 16L
                        }
                        0L -> (fileLen - pos) to 8L
                        else -> sizeRaw to 8L
                    }

                    if (boxSize < headerSize) break

                    if (typeStr == "moov") {
                        var moovPos = pos + headerSize
                        val moovEnd = pos + boxSize
                        while (moovPos + 8 <= moovEnd) {
                            raf.seek(moovPos)
                            val childSizeRaw = raf.readInt().toLong() and 0xFFFFFFFFL
                            val childType = ByteArray(4)
                            raf.readFully(childType)
                            val childTypeStr = String(childType, Charsets.US_ASCII)

                            val (childBoxSize, childHeaderSize) = when (childSizeRaw) {
                                1L -> {
                                    if (moovPos + 16 > moovEnd) break
                                    val extSize = raf.readLong()
                                    extSize to 16L
                                }
                                0L -> (moovEnd - moovPos) to 8L
                                else -> childSizeRaw to 8L
                            }

                            if (childBoxSize < childHeaderSize) break

                            if (childTypeStr == "mvhd") {
                                val version = raf.readByte().toInt() and 0xFF
                                raf.skipBytes(3) // flags
                                val (timescale, duration) = if (version == 1) {
                                    raf.skipBytes(16) // creation + modification time
                                    val ts = raf.readInt().toLong() and 0xFFFFFFFFL
                                    val dur = raf.readLong()
                                    ts to dur
                                } else {
                                    raf.skipBytes(8) // creation + modification time
                                    val ts = raf.readInt().toLong() and 0xFFFFFFFFL
                                    val dur = raf.readInt().toLong() and 0xFFFFFFFFL
                                    ts to dur
                                }
                                if (timescale > 0L && duration > 0L) {
                                    return (duration * 1000L) / timescale
                                }
                            }
                            moovPos += childBoxSize
                        }
                        break
                    }
                    pos += boxSize
                }
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun estimateM4aDurationMs(file: File): Long {
        val exact = getExactM4aDurationMs(file)
        if (exact > 0L) return exact
        return (file.length() * 8L * 1000L) / 256000L
    }

    // --- Точный парсинг WAV ---

    fun getExactWavDurationMs(file: File): Long {
        if (!file.exists() || file.length() < 44) return 0L
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "RIFF") return 0L
                raf.skipBytes(4) // file size - 8
                val wave = ByteArray(4)
                raf.readFully(wave)
                if (String(wave, Charsets.US_ASCII) != "WAVE") return 0L

                var byteRate = 0L
                var dataSize = 0L
                val fileLen = raf.length()

                while (raf.filePointer + 8 <= fileLen) {
                    val chunkId = ByteArray(4)
                    raf.readFully(chunkId)
                    val chunkSize = java.lang.Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
                    val chunkName = String(chunkId, Charsets.US_ASCII)

                    when (chunkName) {
                        "fmt " -> {
                            raf.skipBytes(8) // audioFormat(2), numChannels(2), sampleRate(4)
                            val br = java.lang.Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
                            byteRate = br
                            raf.skipBytes((chunkSize - 12).toInt().coerceAtLeast(0))
                        }
                        "data" -> {
                            dataSize = chunkSize
                            break
                        }
                        else -> {
                            raf.skipBytes(chunkSize.toInt().coerceAtLeast(0))
                        }
                    }
                }

                if (byteRate > 0L && dataSize > 0L) {
                    (dataSize * 1000L) / byteRate
                } else {
                    0L
                }
            }
        } catch (_: Exception) {
            0L
        }
    }

    // --- Точный парсинг OGG / OPUS ---

    fun getExactOggDurationMs(file: File): Long {
        if (!file.exists() || file.length() < 128) return 0L
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                val seekStart = maxOf(0L, fileLen - 65536)
                raf.seek(seekStart)
                val buf = ByteArray((fileLen - seekStart).toInt())
                raf.readFully(buf)

                // Ищем последний 'OggS' заголовок страницы
                var lastOggs = -1
                for (i in (buf.size - 14) downTo 0) {
                    if (buf[i] == 'O'.code.toByte() && buf[i + 1] == 'g'.code.toByte() &&
                        buf[i + 2] == 'g'.code.toByte() && buf[i + 3] == 'S'.code.toByte()
                    ) {
                        lastOggs = i
                        break
                    }
                }

                if (lastOggs != -1 && lastOggs + 14 <= buf.size) {
                    var granulePos = 0L
                    for (b in 0 until 8) {
                        granulePos = granulePos or ((buf[lastOggs + 6 + b].toLong() and 0xFF) shl (b * 8))
                    }
                    if (granulePos > 0L) {
                        val isOpus = file.name.endsWith(".opus", ignoreCase = true)
                        val sampleRate = if (isOpus) 48000L else 44100L
                        return (granulePos * 1000L) / sampleRate
                    }
                }
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    // --- Чтение ID3v2 тегов ---

    /**
     * Считывает ID3v2 теги (TIT2=название, TPE1=артист, TALB=альбом, TLEN=длительность)
     */
    fun readId3v2Metadata(file: File): AudioMetadata {
        if (!file.exists() || file.length() < 10) return AudioMetadata()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(10)
                if (raf.read(header) < 10 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                    return AudioMetadata()
                }

                val ver = header[3].toInt() and 0xFF
                val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)

                val readLimit = minOf(tagSize, 262144) // Ограничиваемся первыми 256 КБ тегов
                val data = ByteArray(readLimit)
                raf.readFully(data)

                var title: String? = null
                var artist: String? = null
                var album: String? = null
                var tlenMs = 0L

                var pos = 0
                while (pos + 10 <= readLimit) {
                    val frameId = String(data, pos, 4, Charsets.ISO_8859_1)
                    if (frameId.isBlank() || frameId[0] == '\u0000') break

                    val frameSize = if (ver == 4) {
                        ((data[pos + 4].toInt() and 0x7F) shl 21) or
                                ((data[pos + 5].toInt() and 0x7F) shl 14) or
                                ((data[pos + 6].toInt() and 0x7F) shl 7) or
                                (data[pos + 7].toInt() and 0x7F)
                    } else {
                        ((data[pos + 4].toInt() and 0xFF) shl 24) or
                                ((data[pos + 5].toInt() and 0xFF) shl 16) or
                                ((data[pos + 6].toInt() and 0xFF) shl 8) or
                                (data[pos + 7].toInt() and 0xFF)
                    }

                    pos += 10
                    if (pos + frameSize > readLimit || frameSize <= 1) {
                        pos += frameSize.coerceAtLeast(0)
                        continue
                    }

                    val enc = data[pos].toInt() and 0xFF
                    val rawTextBytes = data.copyOfRange(pos + 1, pos + frameSize)
                    pos += frameSize

                    val text = decodeId3String(rawTextBytes, enc)

                    when (frameId) {
                        "TIT2" -> if (title == null && text.isNotBlank()) title = text
                        "TPE1" -> if (artist == null && text.isNotBlank()) artist = text
                        "TALB" -> if (album == null && text.isNotBlank()) album = text
                        "TLEN" -> {
                            val parsed = text.toLongOrNull()
                            if (parsed != null && parsed > 0L) tlenMs = parsed
                        }
                    }
                }

                AudioMetadata(title = title, artist = artist, album = album, durationMs = tlenMs)
            }
        } catch (_: Exception) {
            AudioMetadata()
        }
    }

    private fun decodeId3String(bytes: ByteArray, encoding: Int): String {
        return try {
            val str = when (encoding) {
                0 -> String(bytes, Charsets.ISO_8859_1)
                1 -> String(bytes, Charsets.UTF_16)
                2 -> String(bytes, Charsets.UTF_16BE)
                3 -> String(bytes, Charsets.UTF_8)
                else -> String(bytes, Charsets.UTF_8)
            }
            str.trim { it <= ' ' || it == '\u0000' }
        } catch (_: Exception) {
            ""
        }
    }

    // --- Извлечение встроенных и локальных обложек треков ---

    /**
     * Извлечь байты обложки из аудиофайла (MP3 APIC, FLAC Picture, M4A covr, файлы в папке альбома)
     */
    fun extractCoverArtBytes(file: File): ByteArray? {
        if (!file.exists() || file.length() <= 0L) return null

        val ext = file.extension.lowercase()
        val embeddedBytes = when {
            ext == "mp3" || isMp3File(file) -> extractId3CoverBytes(file)
            ext == "flac" || isFlacFile(file) -> extractFlacCoverBytes(file)
            ext in listOf("m4a", "mp4", "aac") || isMp4Container(file) -> extractM4aCoverBytes(file)
            isWavFile(file) -> extractId3CoverBytes(file)
            else -> null
        }
        if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
            return embeddedBytes
        }

        // Резервный поиск обложки в папке альбома (cover.jpg, folder.jpg и т.д.)
        val folderCover = findFolderCoverFile(file)
        if (folderCover != null) {
            try {
                return folderCover.readBytes()
            } catch (_: Exception) {}
        }

        // Если файл MP3/FLAC/M4A не вернул обложку напрямую (или это другой контейнер), пробуем ffmpeg
        return extractCoverViaFfmpeg(file)
    }

    /**
     * Извлечение фрейма APIC / PIC из ID3v2 тегов (MP3, WAV)
     */
    fun extractId3CoverBytes(file: File): ByteArray? {
        if (!file.exists() || file.length() < 10) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(10)
                if (raf.read(header) < 10 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                    return null
                }
                val ver = header[3].toInt() and 0xFF
                val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)

                val tagEnd = 10L + tagSize
                val maxScan = minOf(tagEnd, raf.length())

                if (ver == 2) {
                    // ID3v2.2: 3-символьные ID, 3 байта размер
                    while (raf.filePointer + 6 <= maxScan) {
                        val frameHeader = ByteArray(6)
                        if (raf.read(frameHeader) < 6) break
                        val frameId = String(frameHeader, 0, 3, Charsets.ISO_8859_1)
                        if (frameId.isBlank() || frameId[0] == '\u0000') break
                        val frameSize = ((frameHeader[3].toInt() and 0xFF) shl 16) or
                                ((frameHeader[4].toInt() and 0xFF) shl 8) or
                                (frameHeader[5].toInt() and 0xFF)
                        if (frameSize <= 0 || raf.filePointer + frameSize > maxScan) break
                        if (frameId == "PIC") {
                            val data = ByteArray(frameSize)
                            raf.readFully(data)
                            val img = parsePicFrameBytes(data)
                            if (img != null) return img
                        } else {
                            raf.skipBytes(frameSize)
                        }
                    }
                } else if (ver == 3 || ver == 4) {
                    // ID3v2.3 / ID3v2.4: 4-символьные ID, 4 байта размер
                    while (raf.filePointer + 10 <= maxScan) {
                        val frameHeader = ByteArray(10)
                        if (raf.read(frameHeader) < 10) break
                        val frameId = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
                        if (frameId.isBlank() || frameId[0] == '\u0000') break
                        val frameSize = if (ver == 4) {
                            ((frameHeader[4].toInt() and 0x7F) shl 21) or
                                    ((frameHeader[5].toInt() and 0x7F) shl 14) or
                                    ((frameHeader[6].toInt() and 0x7F) shl 7) or
                                    (frameHeader[7].toInt() and 0x7F)
                        } else {
                            ((frameHeader[4].toInt() and 0xFF) shl 24) or
                                    ((frameHeader[5].toInt() and 0xFF) shl 16) or
                                    ((frameHeader[6].toInt() and 0xFF) shl 8) or
                                    (frameHeader[7].toInt() and 0xFF)
                        }
                        if (frameSize <= 0 || raf.filePointer + frameSize > maxScan) break
                        if (frameId == "APIC") {
                            val data = ByteArray(frameSize)
                            raf.readFully(data)
                            val img = parseApicFrameBytes(data)
                            if (img != null) return img
                        } else {
                            raf.skipBytes(frameSize)
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseApicFrameBytes(data: ByteArray): ByteArray? {
        if (data.size < 5) return null
        val enc = data[0].toInt() and 0xFF
        var pos = 1
        // Пропускаем MIME type (строка, оканчивающаяся на 0x00)
        while (pos < data.size && data[pos] != 0.toByte()) {
            pos++
        }
        if (pos >= data.size) return null
        pos++ // пропускаем 0x00

        if (pos >= data.size) return null
        pos++ // пропускаем picture type (1 байт)

        // Пропускаем description (строка, оканчивающаяся на 0x00 или 0x00 0x00)
        if (enc == 1 || enc == 2) {
            while (pos + 1 < data.size) {
                if (data[pos] == 0.toByte() && data[pos + 1] == 0.toByte()) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < data.size && data[pos] != 0.toByte()) {
                pos++
            }
            if (pos < data.size) pos++
        }

        var imgStart = pos
        if (imgStart >= data.size || !isImageMagic(data, imgStart)) {
            val found = findImageMagicOffset(data, 1)
            if (found != -1) imgStart = found
        }

        if (imgStart < data.size && isImageMagic(data, imgStart)) {
            return data.copyOfRange(imgStart, data.size)
        }
        return if (imgStart < data.size && (data.size - imgStart) > 16) {
            data.copyOfRange(imgStart, data.size)
        } else null
    }

    private fun parsePicFrameBytes(data: ByteArray): ByteArray? {
        if (data.size < 6) return null
        val enc = data[0].toInt() and 0xFF
        var pos = 4 // пропуск enc (1) + format (3)
        pos++ // пропуск picType (1)
        if (enc == 1 || enc == 2) {
            while (pos + 1 < data.size) {
                if (data[pos] == 0.toByte() && data[pos + 1] == 0.toByte()) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < data.size && data[pos] != 0.toByte()) {
                pos++
            }
            if (pos < data.size) pos++
        }
        var imgStart = pos
        if (imgStart >= data.size || !isImageMagic(data, imgStart)) {
            val found = findImageMagicOffset(data, 1)
            if (found != -1) imgStart = found
        }
        return if (imgStart < data.size) data.copyOfRange(imgStart, data.size) else null
    }

    /**
     * Извлечение блока METADATA_BLOCK_PICTURE (тип 6) из FLAC
     */
    fun extractFlacCoverBytes(file: File): ByteArray? {
        if (!file.exists() || file.length() < 42) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                if (raf.read(magic) < 4 || magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
                    magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()) {
                    return null
                }
                var isLast = false
                while (!isLast && raf.filePointer + 4 <= raf.length()) {
                    val header = ByteArray(4)
                    if (raf.read(header) < 4) break
                    isLast = (header[0].toInt() and 0x80) != 0
                    val blockType = header[0].toInt() and 0x7F
                    val blockLen = ((header[1].toInt() and 0xFF) shl 16) or
                            ((header[2].toInt() and 0xFF) shl 8) or
                            (header[3].toInt() and 0xFF)

                    if (blockLen <= 0 || raf.filePointer + blockLen > raf.length()) break

                    if (blockType == 6) {
                        val payload = ByteArray(blockLen)
                        raf.readFully(payload)
                        val img = parseFlacPictureBlock(payload)
                        if (img != null) return img
                    } else {
                        raf.skipBytes(blockLen)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFlacPictureBlock(payload: ByteArray): ByteArray? {
        if (payload.size < 32) return null
        var pos = 0
        val picType = readInt32Be(payload, pos); pos += 4
        val mimeLen = readInt32Be(payload, pos); pos += 4
        if (mimeLen < 0 || pos + mimeLen > payload.size) return null
        pos += mimeLen
        if (pos + 4 > payload.size) return null
        val descLen = readInt32Be(payload, pos); pos += 4
        if (descLen < 0 || pos + descLen > payload.size) return null
        pos += descLen
        pos += 16 // width(4), height(4), depth(4), colors(4)
        if (pos + 4 > payload.size) return null
        val dataLen = readInt32Be(payload, pos); pos += 4
        if (dataLen <= 0 || pos + dataLen > payload.size) return null
        return payload.copyOfRange(pos, pos + dataLen)
    }

    private fun readInt32Be(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    /**
     * Извлечение атома covr из MP4 / M4A / AAC контейнера
     */
    fun extractM4aCoverBytes(file: File): ByteArray? {
        if (!file.exists() || file.length() < 16) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                var pos = 0L
                while (pos + 8 <= fileLen) {
                    raf.seek(pos)
                    val sizeRaw = raf.readInt().toLong() and 0xFFFFFFFFL
                    val type = ByteArray(4)
                    raf.readFully(type)
                    val typeStr = String(type, Charsets.US_ASCII)

                    val (boxSize, headerSize) = when (sizeRaw) {
                        1L -> {
                            if (pos + 16 > fileLen) break
                            val extSize = raf.readLong()
                            extSize to 16L
                        }
                        0L -> (fileLen - pos) to 8L
                        else -> sizeRaw to 8L
                    }
                    if (boxSize < headerSize) break

                    if (typeStr == "moov") {
                        val result = scanMoovForCover(raf, pos + headerSize, pos + boxSize)
                        if (result != null) return result
                    }
                    pos += boxSize
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scanMoovForCover(raf: RandomAccessFile, start: Long, end: Long): ByteArray? {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4)
            raf.readFully(type)
            val typeStr = String(type, Charsets.US_ASCII)
            val boxSize = if (size == 1L) raf.readLong() else if (size == 0L) (end - pos) else size
            val headerSize = if (size == 1L) 16L else 8L
            if (boxSize < headerSize) break

            when (typeStr) {
                "udta" -> {
                    val result = scanMoovForCover(raf, pos + headerSize, pos + boxSize)
                    if (result != null) return result
                }
                "meta" -> {
                    val metaHeader = headerSize + 4L
                    if (boxSize >= metaHeader) {
                        val result = scanMoovForCover(raf, pos + metaHeader, pos + boxSize)
                        if (result != null) return result
                    }
                }
                "ilst" -> {
                    val result = scanIlstForCover(raf, pos + headerSize, pos + boxSize)
                    if (result != null) return result
                }
                "covr" -> {
                    val result = readCovrDataBox(raf, pos + headerSize, pos + boxSize)
                    if (result != null) return result
                }
            }
            pos += boxSize
        }
        return null
    }

    private fun scanIlstForCover(raf: RandomAccessFile, start: Long, end: Long): ByteArray? {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4)
            raf.readFully(type)
            val typeStr = String(type, Charsets.US_ASCII)
            val boxSize = if (size == 1L) raf.readLong() else if (size == 0L) (end - pos) else size
            val headerSize = if (size == 1L) 16L else 8L
            if (boxSize < headerSize) break

            if (typeStr == "covr") {
                val result = readCovrDataBox(raf, pos + headerSize, pos + boxSize)
                if (result != null) return result
            }
            pos += boxSize
        }
        return null
    }

    private fun readCovrDataBox(raf: RandomAccessFile, start: Long, end: Long): ByteArray? {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4)
            raf.readFully(type)
            val typeStr = String(type, Charsets.US_ASCII)
            val boxSize = if (size == 1L) raf.readLong() else if (size == 0L) (end - pos) else size
            val headerSize = if (size == 1L) 16L else 8L
            if (boxSize < headerSize) break

            if (typeStr == "data") {
                val payloadSize = (boxSize - headerSize - 8L).toInt()
                if (payloadSize > 0) {
                    raf.seek(pos + headerSize + 8L)
                    val imgData = ByteArray(payloadSize)
                    raf.readFully(imgData)
                    return imgData
                }
            }
            pos += boxSize
        }
        return null
    }

    /**
     * Поиск файла обложки в папке альбома (cover.jpg, folder.jpg и т.д.)
     */
    fun findFolderCoverFile(file: File): File? {
        val parent = file.parentFile ?: return null
        if (!parent.exists() || !parent.isDirectory) return null
        val candidates = listOf(
            "cover.jpg", "cover.jpeg", "cover.png", "cover.webp",
            "folder.jpg", "folder.jpeg", "folder.png", "folder.webp",
            "front.jpg", "front.jpeg", "front.png", "front.webp",
            "album.jpg", "album.jpeg", "album.png", "album.webp",
            "artwork.jpg", "artwork.jpeg", "artwork.png", "artwork.webp"
        )
        for (name in candidates) {
            val f = File(parent, name)
            if (f.exists() && f.isFile && f.length() > 0L) return f
        }
        val files = parent.listFiles() ?: return null
        for (f in files) {
            if (f.isFile && f.length() > 0L) {
                val lower = f.name.lowercase()
                if (lower in candidates) return f
            }
        }
        return null
    }

    /**
     * Резервное извлечение обложки через процесс ffmpeg (JVM)
     */
    fun extractCoverViaFfmpeg(file: File): ByteArray? {
        return try {
            val pb = ProcessBuilder(
                "ffmpeg",
                "-v", "error",
                "-y",
                "-i", file.absolutePath,
                "-an",
                "-vcodec", "copy",
                "-f", "image2",
                "pipe:1"
            )
            val proc = pb.start()
            val bytes = proc.inputStream.readBytes()
            proc.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (proc.isAlive) proc.destroyForcibly()
            if (bytes.size > 100 && isImageMagic(bytes, 0)) bytes else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isImageMagic(data: ByteArray, offset: Int): Boolean {
        if (offset + 3 > data.size) return false
        // JPEG: FF D8 FF
        if (data[offset] == 0xFF.toByte() && data[offset + 1] == 0xD8.toByte() && data[offset + 2] == 0xFF.toByte()) return true
        // PNG: 89 50 4E 47 (\x89PNG)
        if (offset + 4 <= data.size &&
            data[offset] == 0x89.toByte() && data[offset + 1] == 'P'.code.toByte() &&
            data[offset + 2] == 'N'.code.toByte() && data[offset + 3] == 'G'.code.toByte()
        ) return true
        // WEBP: RIFF....WEBP
        if (offset + 12 <= data.size &&
            data[offset] == 'R'.code.toByte() && data[offset + 1] == 'I'.code.toByte() &&
            data[offset + 2] == 'F'.code.toByte() && data[offset + 3] == 'F'.code.toByte() &&
            data[offset + 8] == 'W'.code.toByte() && data[offset + 9] == 'E'.code.toByte() &&
            data[offset + 10] == 'B'.code.toByte() && data[offset + 11] == 'P'.code.toByte()
        ) return true
        // GIF: GIF8
        if (offset + 4 <= data.size &&
            data[offset] == 'G'.code.toByte() && data[offset + 1] == 'I'.code.toByte() &&
            data[offset + 2] == 'F'.code.toByte() && data[offset + 3] == '8'.code.toByte()
        ) return true
        return false
    }

    private fun findImageMagicOffset(data: ByteArray, startOffset: Int): Int {
        for (i in startOffset until (data.size - 3)) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
                return i
            }
            if (i + 4 <= data.size &&
                data[i] == 0x89.toByte() && data[i + 1] == 'P'.code.toByte() &&
                data[i + 2] == 'N'.code.toByte() && data[i + 3] == 'G'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }
}

