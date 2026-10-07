package io.github.audiz.util

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * 🎧 Парсер аудиоформатов и метаданных контейнеров на JVM.
 * Извлекает длительность, определяет контейнеры MP4, MP3, FLAC без внешних зависимостей.
 */
object AudioHeaderParser {

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

    fun estimateMp3DurationMs(file: File): Long {
        return (file.length() * 8 * 1000) / 192000
    }

    /**
     * Считывает STREAMINFO блок FLAC для вычисления точной длительности в миллисекундах.
     */
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
                            return (totalSamples * 1000.0 / sampleRate).toLong()
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
        return (file.length() * 8 * 1000) / 850000
    }

    /**
     * Считывает атомы mvhd/moov MP4/M4A контейнера для вычисления точной длительности в миллисекундах.
     */
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
                        // Контейнер moov найден, ищем заголовок фильма mvhd внутри
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
                                    return (duration * 1000.0 / timescale).toLong()
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
        return (file.length() * 8 * 1000) / 256000
    }
}
