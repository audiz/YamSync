package io.github.audiz.util

/**
 * 🏷️ Кроссплатформенный (JVM, Android, iOS) модуль тегирования аудиофайлов.
 * Встраивает обложки альбомов и метаданные (название, артист, альбом, год)
 * напрямую в аудиоконтейнеры без внешних библиотек:
 * - MP3: ID3v2.3 теги (APIC, TIT2, TPE1, TALB, TYER)
 * - FLAC: METADATA_BLOCK_PICTURE (тип 6) и VORBIS_COMMENT (тип 4)
 * - M4A / MP4 / AAC: атомы covr, ©nam, ©ART, ©alb, ©day в moov.udta.meta.ilst
 *
 * Если формат неизвестен или происходит сбой парсинга, исходный аудиопоток
 * возвращается без изменений, предотвращая повреждение файлов.
 */
object AudioMetadataTagger {

    /**
     * Встроить метаданные и обложку в аудиоданные
     */
    fun embedMetadata(
        audioBytes: ByteArray,
        extension: String,
        title: String?,
        artist: String?,
        album: String? = null,
        year: String? = null,
        coverBytes: ByteArray? = null
    ): ByteArray {
        if (audioBytes.isEmpty()) return audioBytes
        val ext = extension.lowercase().trimStart('.')

        return try {
            when {
                ext == "mp3" || isMp3Header(audioBytes) -> {
                    embedId3v2(audioBytes, title, artist, album, year, coverBytes)
                }
                ext == "flac" || isFlacHeader(audioBytes) -> {
                    embedFlac(audioBytes, title, artist, album, year, coverBytes)
                }
                (ext in setOf("m4a", "mp4", "aac")) && isMp4Header(audioBytes) -> {
                    embedM4a(audioBytes, title, artist, album, year, coverBytes)
                }
                ext == "aac" -> {
                    // Raw ADTS AAC поток поддерживает добавление ID3v2 в начало файла
                    embedId3v2(audioBytes, title, artist, album, year, coverBytes)
                }
                else -> {
                    // По умолчанию для неизвестных потоков пробуем ID3v2 (стандарт de facto)
                    embedId3v2(audioBytes, title, artist, album, year, coverBytes)
                }
            }
        } catch (e: Exception) {
            println("AudioMetadataTagger: ⚠️ Ошибка встраивания метаданных: ${e.message}")
            audioBytes
        }
    }

    // =========================================================================
    // 🎵 MP3 / ID3v2.3 TAGGING
    // =========================================================================

    private fun isMp3Header(data: ByteArray): Boolean {
        if (data.size < 3) return false
        if (data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            return true
        }
        return data.size >= 2 && data[0] == 0xFF.toByte() && (data[1].toInt() and 0xE0) == 0xE0
    }

    private fun embedId3v2(
        audioBytes: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        year: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        val frames = ByteArrayBuilder()

        // 1. TIT2 (Название)
        if (!title.isNullOrBlank()) {
            writeId3TextFrame("TIT2", title, frames)
        }

        // 2. TPE1 (Исполнитель)
        if (!artist.isNullOrBlank()) {
            writeId3TextFrame("TPE1", artist, frames)
        }

        // 3. TALB (Альбом)
        if (!album.isNullOrBlank()) {
            writeId3TextFrame("TALB", album, frames)
        }

        // 4. TYER (Год)
        if (!year.isNullOrBlank()) {
            writeId3AsciiFrame("TYER", year.take(4), frames)
        }

        // 5. APIC (Обложка)
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            writeId3ApicFrame(coverBytes, frames)
        }

        val tagPayload = frames.toByteArray()
        if (tagPayload.isEmpty()) {
            return audioBytes
        }

        val tagBuilder = ByteArrayBuilder(10 + tagPayload.size)
        // Заголовок ID3v2.3 (10 байт)
        tagBuilder.write('I'.code.toByte())
        tagBuilder.write('D'.code.toByte())
        tagBuilder.write('3'.code.toByte())
        tagBuilder.write(3.toByte()) // v2.3
        tagBuilder.write(0.toByte()) // revision
        tagBuilder.write(0.toByte()) // flags

        // Размер полезной нагрузки (syncsafe integer 4 байта)
        val tagSize = tagPayload.size
        tagBuilder.write(((tagSize ushr 21) and 0x7F).toByte())
        tagBuilder.write(((tagSize ushr 14) and 0x7F).toByte())
        tagBuilder.write(((tagSize ushr 7) and 0x7F).toByte())
        tagBuilder.write((tagSize and 0x7F).toByte())

        tagBuilder.write(tagPayload)

        // Пропускаем старый ID3v2 заголовок, если он уже присутствовал в аудио
        var audioOffset = 0
        if (audioBytes.size >= 10 &&
            audioBytes[0] == 'I'.code.toByte() &&
            audioBytes[1] == 'D'.code.toByte() &&
            audioBytes[2] == '3'.code.toByte()
        ) {
            val oldTagSize = ((audioBytes[6].toInt() and 0x7F) shl 21) or
                    ((audioBytes[7].toInt() and 0x7F) shl 14) or
                    ((audioBytes[8].toInt() and 0x7F) shl 7) or
                    (audioBytes[9].toInt() and 0x7F)
            val fullOldHeader = 10 + oldTagSize
            if (fullOldHeader in 10..audioBytes.size) {
                audioOffset = fullOldHeader
            }
        }

        val id3Bytes = tagBuilder.toByteArray()
        val remainingAudioSize = audioBytes.size - audioOffset
        val result = ByteArray(id3Bytes.size + remainingAudioSize)
        id3Bytes.copyInto(result, 0)
        audioBytes.copyInto(result, id3Bytes.size, audioOffset, audioBytes.size)
        return result
    }

    private fun writeId3TextFrame(frameId: String, text: String, out: ByteArrayBuilder) {
        // UTF-16 с BOM (Little-Endian: 0xFF, 0xFE) для универсальной совместимости
        val payloadSize = 1 + 2 + text.length * 2
        out.write(frameId.encodeToByteArray())
        // В ID3v2.3 размер фрейма — стандартный 32-битный BE integer
        writeInt32Be(payloadSize, out)
        out.write(0.toByte()) // flags
        out.write(0.toByte())

        out.write(1.toByte()) // encoding: UTF-16 with BOM
        out.write(0xFF.toByte()) // Little-Endian BOM
        out.write(0xFE.toByte())
        for (i in text.indices) {
            val c = text[i].code
            out.write((c and 0xFF).toByte())
            out.write(((c ushr 8) and 0xFF).toByte())
        }
    }

    private fun writeId3AsciiFrame(frameId: String, text: String, out: ByteArrayBuilder) {
        val textBytes = text.encodeToByteArray()
        val payloadSize = 1 + textBytes.size
        out.write(frameId.encodeToByteArray())
        writeInt32Be(payloadSize, out)
        out.write(0.toByte())
        out.write(0.toByte())

        out.write(0.toByte()) // encoding ISO-8859-1
        out.write(textBytes)
    }

    private fun writeId3ApicFrame(coverBytes: ByteArray, out: ByteArrayBuilder) {
        val isPng = isPngImage(coverBytes)
        val mimeStr = if (isPng) "image/png" else "image/jpeg"
        val mimeBytes = mimeStr.encodeToByteArray()

        // Формат APIC: enc(1) + mime + 0x00 + picType(1) + desc(0x00) + imageData
        val payloadSize = 1 + mimeBytes.size + 1 + 1 + 1 + coverBytes.size
        out.write("APIC".encodeToByteArray())
        writeInt32Be(payloadSize, out)
        out.write(0.toByte())
        out.write(0.toByte())

        out.write(0.toByte()) // encoding ISO-8859-1
        out.write(mimeBytes)
        out.write(0.toByte()) // null-терминатор mime
        out.write(3.toByte()) // picture type = 3 (Front Cover)
        out.write(0.toByte()) // пустой description с null-терминатором
        out.write(coverBytes)
    }

    // =========================================================================
    // 🎼 FLAC TAGGING (METADATA_BLOCK_PICTURE & VORBIS_COMMENT)
    // =========================================================================

    private fun isFlacHeader(data: ByteArray): Boolean {
        return data.size >= 4 &&
                data[0] == 'f'.code.toByte() &&
                data[1] == 'L'.code.toByte() &&
                data[2] == 'a'.code.toByte() &&
                data[3] == 'C'.code.toByte()
    }

    private fun embedFlac(
        audioBytes: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        year: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        if (!isFlacHeader(audioBytes)) return audioBytes

        class FlacBlock(val type: Int, val payload: ByteArray)

        val preservedBlocks = mutableListOf<FlacBlock>()
        var pos = 4
        var isLast = false

        while (!isLast && pos + 4 <= audioBytes.size) {
            val b0 = audioBytes[pos].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val blockLen = ((audioBytes[pos + 1].toInt() and 0xFF) shl 16) or
                    ((audioBytes[pos + 2].toInt() and 0xFF) shl 8) or
                    (audioBytes[pos + 3].toInt() and 0xFF)

            pos += 4
            if (pos + blockLen > audioBytes.size) break
            val payload = audioBytes.copyOfRange(pos, pos + blockLen)
            pos += blockLen

            // Сохраняем все блоки кроме старых VORBIS_COMMENT (4) и PICTURE (6)
            if (blockType != 4 && blockType != 6) {
                preservedBlocks.add(FlacBlock(blockType, payload))
            }
        }

        if (pos > audioBytes.size) return audioBytes // Некорректный FLAC — возвращаем оригинал
        val audioFramesStart = pos

        val newBlocks = mutableListOf<FlacBlock>()
        // 1. STREAMINFO (тип 0) обязан быть первым
        val streamInfo = preservedBlocks.firstOrNull { it.type == 0 }
        if (streamInfo != null) {
            newBlocks.add(streamInfo)
        }
        for (b in preservedBlocks) {
            if (b.type != 0) newBlocks.add(b)
        }

        // 2. Добавляем Vorbis Comments (тип 4)
        val commentPayload = buildFlacVorbisComment(title, artist, album, year)
        newBlocks.add(FlacBlock(4, commentPayload))

        // 3. Добавляем Picture (тип 6)
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val picPayload = buildFlacPicture(coverBytes)
            newBlocks.add(FlacBlock(6, picPayload))
        }

        // Сборка нового FLAC
        val out = ByteArrayBuilder(audioBytes.size + (coverBytes?.size ?: 0) + 2048)
        out.write('f'.code.toByte())
        out.write('L'.code.toByte())
        out.write('a'.code.toByte())
        out.write('C'.code.toByte())

        for (i in newBlocks.indices) {
            val block = newBlocks[i]
            val isLastBlock = (i == newBlocks.lastIndex)
            val headerB0 = (block.type and 0x7F) or (if (isLastBlock) 0x80 else 0)
            out.write(headerB0.toByte())
            writeInt24Be(block.payload.size, out)
            out.write(block.payload)
        }

        out.write(audioBytes, audioFramesStart, audioBytes.size - audioFramesStart)
        return out.toByteArray()
    }

    private fun buildFlacVorbisComment(
        title: String?,
        artist: String?,
        album: String?,
        year: String?
    ): ByteArray {
        val out = ByteArrayBuilder()
        val vendor = "Yam".encodeToByteArray()
        // Длина строки vendor (Little-Endian 32 бит)
        writeInt32Le(vendor.size, out)
        out.write(vendor)

        val comments = mutableListOf<ByteArray>()
        if (!title.isNullOrBlank()) comments.add("TITLE=$title".encodeToByteArray())
        if (!artist.isNullOrBlank()) comments.add("ARTIST=$artist".encodeToByteArray())
        if (!album.isNullOrBlank()) comments.add("ALBUM=$album".encodeToByteArray())
        if (!year.isNullOrBlank()) comments.add("DATE=$year".encodeToByteArray())

        writeInt32Le(comments.size, out)
        for (c in comments) {
            writeInt32Le(c.size, out)
            out.write(c)
        }
        return out.toByteArray()
    }

    private fun buildFlacPicture(coverBytes: ByteArray): ByteArray {
        val isPng = isPngImage(coverBytes)
        val mime = if (isPng) "image/png" else "image/jpeg"
        val mimeBytes = mime.encodeToByteArray()

        val out = ByteArrayBuilder(32 + mimeBytes.size + coverBytes.size)
        // 1. Picture type (32 бит BE): 3 = Front Cover
        writeInt32Be(3, out)
        // 2. MIME length (32 бит BE)
        writeInt32Be(mimeBytes.size, out)
        // 3. MIME bytes
        out.write(mimeBytes)
        // 4. Description length (32 бит BE): 0
        writeInt32Be(0, out)
        // 5. Width, Height, Color depth, Colors (всего 16 байт)
        writeInt32Be(0, out) // width
        writeInt32Be(0, out) // height
        writeInt32Be(24, out) // depth
        writeInt32Be(0, out) // colors
        // 6. Data length (32 бит BE)
        writeInt32Be(coverBytes.size, out)
        // 7. Data bytes
        out.write(coverBytes)
        return out.toByteArray()
    }

    // =========================================================================
    // 🎧 M4A / MP4 CONTAINER TAGGING (moov.udta.meta.ilst.covr)
    // =========================================================================

    private fun isMp4Header(data: ByteArray): Boolean {
        if (data.size < 12) return false
        return data[4] == 'f'.code.toByte() && data[5] == 't'.code.toByte() &&
                data[6] == 'y'.code.toByte() && data[7] == 'p'.code.toByte()
    }

    private fun embedM4a(
        audioBytes: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        year: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        val rootBoxes = parseMp4Boxes(audioBytes, 0, audioBytes.size)
        val moovBox = rootBoxes.firstOrNull { it.type == "moov" } ?: return audioBytes
        val mdatBox = rootBoxes.firstOrNull { it.type == "mdat" }

        val moovStart = moovBox.offset
        val moovEnd = moovBox.offset + moovBox.size
        val moovHeaderSize = moovBox.headerSize

        // Дочерние боксы внутри moov
        val moovChildren = parseMp4Boxes(audioBytes, moovStart + moovHeaderSize, moovEnd)

        // Строим новый бокс udta с метаданными и обложкой
        val newUdtaPayload = buildM4aUdtaBox(title, artist, album, year, coverBytes)

        // Собираем новое содержимое moov: сохраняем все боксы кроме старого udta, затем добавляем новый udta
        val newMoovPayloadBuilder = ByteArrayBuilder()
        for (child in moovChildren) {
            if (child.type != "udta") {
                newMoovPayloadBuilder.write(audioBytes, child.offset, child.size)
            }
        }
        newMoovPayloadBuilder.write(newUdtaPayload)

        val newMoovPayload = newMoovPayloadBuilder.toByteArray()
        val newMoovSize = 8 + newMoovPayload.size
        val newMoovBuilder = ByteArrayBuilder(newMoovSize)
        writeInt32Be(newMoovSize, newMoovBuilder)
        newMoovBuilder.write("moov".encodeToByteArray())
        newMoovBuilder.write(newMoovPayload)

        val newMoovBytes = newMoovBuilder.toByteArray()
        val delta = newMoovBytes.size - moovBox.size

        // Если moov расположен ДО mdat, сдвиг mdat требует коррекции chunk offset таблиц (stco / co64)
        if (delta != 0 && mdatBox != null && moovBox.offset < mdatBox.offset) {
            adjustChunkOffsets(newMoovBytes, delta)
        }

        // Собираем итоговый MP4 файл
        val result = ByteArrayBuilder(audioBytes.size + delta)
        result.write(audioBytes, 0, moovBox.offset)
        result.write(newMoovBytes)
        val remainingStart = moovBox.offset + moovBox.size
        if (remainingStart < audioBytes.size) {
            result.write(audioBytes, remainingStart, audioBytes.size - remainingStart)
        }
        return result.toByteArray()
    }

    private class Mp4Box(val type: String, val offset: Int, val size: Int, val headerSize: Int)

    private fun parseMp4Boxes(data: ByteArray, start: Int, end: Int): List<Mp4Box> {
        val boxes = mutableListOf<Mp4Box>()
        var pos = start
        while (pos + 8 <= end) {
            val sizeRaw = readInt32Be(data, pos).toLong() and 0xFFFFFFFFL
            val typeStr = data.decodeToString(startIndex = pos + 4, endIndex = pos + 8)
            val (boxSize, headerSize) = when (sizeRaw) {
                1L -> {
                    if (pos + 16 > end) break
                    val ext = readInt64Be(data, pos + 8)
                    ext.toInt() to 16
                }
                0L -> (end - pos) to 8
                else -> sizeRaw.toInt() to 8
            }
            if (boxSize < headerSize || pos + boxSize > end) break
            boxes.add(Mp4Box(typeStr, pos, boxSize, headerSize))
            pos += boxSize
        }
        return boxes
    }

    private fun buildM4aUdtaBox(
        title: String?,
        artist: String?,
        album: String?,
        year: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        val ilstPayload = ByteArrayBuilder()
        if (!title.isNullOrBlank()) {
            writeM4aTextItem(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), title, ilstPayload)
        }
        if (!artist.isNullOrBlank()) {
            writeM4aTextItem(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), artist, ilstPayload)
        }
        if (!album.isNullOrBlank()) {
            writeM4aTextItem(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), album, ilstPayload)
        }
        if (!year.isNullOrBlank()) {
            writeM4aTextItem(byteArrayOf(0xA9.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte()), year.take(4), ilstPayload)
        }
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            writeM4aCoverItem(coverBytes, ilstPayload)
        }

        val ilstBytes = ilstPayload.toByteArray()
        val ilstSize = 8 + ilstBytes.size
        val ilstBox = ByteArrayBuilder(ilstSize)
        writeInt32Be(ilstSize, ilstBox)
        ilstBox.write("ilst".encodeToByteArray())
        ilstBox.write(ilstBytes)

        // hdlr бокс (33 байта) для контейнера meta
        val hdlrBox = ByteArrayBuilder()
        writeInt32Be(33, hdlrBox)
        hdlrBox.write("hdlr".encodeToByteArray())
        writeInt32Be(0, hdlrBox) // version & flags
        writeInt32Be(0, hdlrBox) // pre-defined
        hdlrBox.write("mdir".encodeToByteArray()) // handler type
        hdlrBox.write("appl".encodeToByteArray()) // manufacturer
        writeInt32Be(0, hdlrBox)
        writeInt32Be(0, hdlrBox)
        hdlrBox.write(0.toByte()) // component name

        val metaPayload = ByteArrayBuilder()
        writeInt32Be(0, metaPayload) // meta fullbox version & flags (4 байта)
        metaPayload.write(hdlrBox.toByteArray())
        metaPayload.write(ilstBox.toByteArray())

        val metaPayloadBytes = metaPayload.toByteArray()
        val metaSize = 8 + metaPayloadBytes.size
        val metaBox = ByteArrayBuilder(metaSize)
        writeInt32Be(metaSize, metaBox)
        metaBox.write("meta".encodeToByteArray())
        metaBox.write(metaPayloadBytes)

        val udtaPayload = metaBox.toByteArray()
        val udtaSize = 8 + udtaPayload.size
        val udtaBox = ByteArrayBuilder(udtaSize)
        writeInt32Be(udtaSize, udtaBox)
        udtaBox.write("udta".encodeToByteArray())
        udtaBox.write(udtaPayload)
        return udtaBox.toByteArray()
    }

    private fun writeM4aTextItem(typeBytes: ByteArray, text: String, out: ByteArrayBuilder) {
        val textBytes = text.encodeToByteArray()
        val dataBoxSize = 16 + textBytes.size
        val itemSize = 8 + dataBoxSize

        writeInt32Be(itemSize, out)
        out.write(typeBytes)
        // data box
        writeInt32Be(dataBoxSize, out)
        out.write("data".encodeToByteArray())
        writeInt32Be(1, out) // type 1 = UTF-8 text
        writeInt32Be(0, out) // locale
        out.write(textBytes)
    }

    private fun writeM4aCoverItem(coverBytes: ByteArray, out: ByteArrayBuilder) {
        val isPng = isPngImage(coverBytes)
        val typeCode = if (isPng) 14 else 13 // 13 = JPEG, 14 = PNG
        val dataBoxSize = 16 + coverBytes.size
        val itemSize = 8 + dataBoxSize

        writeInt32Be(itemSize, out)
        out.write("covr".encodeToByteArray())
        // data box
        writeInt32Be(dataBoxSize, out)
        out.write("data".encodeToByteArray())
        writeInt32Be(typeCode, out)
        writeInt32Be(0, out) // locale
        out.write(coverBytes)
    }

    private fun adjustChunkOffsets(moovBytes: ByteArray, delta: Int) {
        var pos = 0
        while (pos + 8 <= moovBytes.size) {
            if (moovBytes[pos] == 's'.code.toByte() &&
                moovBytes[pos + 1] == 't'.code.toByte() &&
                moovBytes[pos + 2] == 'c'.code.toByte() &&
                moovBytes[pos + 3] == 'o'.code.toByte()
            ) {
                // stco atom: offset - 4 это размер, после 'stco' идут 4 байта version/flags и 4 байта count
                val countOffset = pos + 8
                if (countOffset + 4 <= moovBytes.size) {
                    val count = readInt32Be(moovBytes, countOffset)
                    var tablePos = countOffset + 4
                    for (i in 0 until count) {
                        if (tablePos + 4 > moovBytes.size) break
                        val oldOffset = readInt32Be(moovBytes, tablePos)
                        writeInt32BeAt(moovBytes, tablePos, oldOffset + delta)
                        tablePos += 4
                    }
                }
            } else if (moovBytes[pos] == 'c'.code.toByte() &&
                moovBytes[pos + 1] == 'o'.code.toByte() &&
                moovBytes[pos + 2] == '6'.code.toByte() &&
                moovBytes[pos + 3] == '4'.code.toByte()
            ) {
                val countOffset = pos + 8
                if (countOffset + 4 <= moovBytes.size) {
                    val count = readInt32Be(moovBytes, countOffset)
                    var tablePos = countOffset + 4
                    for (i in 0 until count) {
                        if (tablePos + 8 > moovBytes.size) break
                        val oldOffset = readInt64Be(moovBytes, tablePos)
                        writeInt64BeAt(moovBytes, tablePos, oldOffset + delta.toLong())
                        tablePos += 8
                    }
                }
            }
            pos++
        }
    }

    // =========================================================================
    // 🛠️ ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ И БИТОВЫЕ ОПЕРАЦИИ
    // =========================================================================

    private fun isPngImage(bytes: ByteArray): Boolean {
        return bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() &&
                bytes[3] == 'G'.code.toByte()
    }

    private fun writeInt32Be(v: Int, out: ByteArrayBuilder) {
        out.write(((v ushr 24) and 0xFF).toByte())
        out.write(((v ushr 16) and 0xFF).toByte())
        out.write(((v ushr 8) and 0xFF).toByte())
        out.write((v and 0xFF).toByte())
    }

    private fun writeInt32BeAt(data: ByteArray, offset: Int, v: Int) {
        data[offset] = ((v ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        data[offset + 3] = (v and 0xFF).toByte()
    }

    private fun writeInt64BeAt(data: ByteArray, offset: Int, v: Long) {
        for (i in 7 downTo 0) {
            data[offset + (7 - i)] = ((v ushr (i * 8)) and 0xFFL).toByte()
        }
    }

    private fun writeInt32Le(v: Int, out: ByteArrayBuilder) {
        out.write((v and 0xFF).toByte())
        out.write(((v ushr 8) and 0xFF).toByte())
        out.write(((v ushr 16) and 0xFF).toByte())
        out.write(((v ushr 24) and 0xFF).toByte())
    }

    private fun writeInt24Be(v: Int, out: ByteArrayBuilder) {
        out.write(((v ushr 16) and 0xFF).toByte())
        out.write(((v ushr 8) and 0xFF).toByte())
        out.write((v and 0xFF).toByte())
    }

    private fun readInt32Be(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun readInt64Be(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return result
    }

    /**
     * Быстрый саморасширяющийся буфер байтов без внешних зависимостей
     */
    private class ByteArrayBuilder(initialCapacity: Int = 1024) {
        private var buffer = ByteArray(initialCapacity)
        var size = 0
            private set

        fun write(b: Byte) {
            ensureCapacity(size + 1)
            buffer[size++] = b
        }

        fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            if (length <= 0) return
            ensureCapacity(size + length)
            bytes.copyInto(buffer, destinationOffset = size, startIndex = offset, endIndex = offset + length)
            size += length
        }

        private fun ensureCapacity(required: Int) {
            if (required > buffer.size) {
                var newCapacity = buffer.size * 2
                if (newCapacity < required) newCapacity = required
                val newBuf = ByteArray(newCapacity)
                buffer.copyInto(newBuf, 0, 0, size)
                buffer = newBuf
            }
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
