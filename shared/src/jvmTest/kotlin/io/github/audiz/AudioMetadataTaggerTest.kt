package io.github.audiz

import io.github.audiz.util.AudioHeaderParser
import io.github.audiz.util.AudioMetadataTagger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioMetadataTaggerTest {

    private val fakeJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
        0x00, 0x01, 0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00,
        0xFF.toByte(), 0xD9.toByte()
    )

    private val fakePng = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
        'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x00, 'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(),
        0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
    )

    @Test
    fun testMp3TagAndCoverEmbedding() {
        val dummyMp3Audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()) + ByteArray(256)
        val tagged = AudioMetadataTagger.embedMetadata(
            audioBytes = dummyMp3Audio,
            extension = "mp3",
            title = "Тестовая Песня",
            artist = "Тестовый Артист",
            album = "Тестовый Альбом",
            year = "2024",
            coverBytes = fakeJpeg
        )

        val tempFile = File.createTempFile("tagged_mp3_", ".mp3")
        try {
            tempFile.writeBytes(tagged)

            // Проверяем извлечение обложки
            val extractedCover = AudioHeaderParser.extractCoverArtBytes(tempFile)
            assertNotNull(extractedCover, "Embedded cover art must be extracted from MP3")
            assertEquals(fakeJpeg.size, extractedCover.size)
            assertTrue(fakeJpeg.contentEquals(extractedCover))

            // Проверяем метаданные ID3v2
            val meta = AudioHeaderParser.readId3v2Metadata(tempFile)
            assertEquals("Тестовая Песня", meta.title)
            assertEquals("Тестовый Артист", meta.artist)
            assertEquals("Тестовый Альбом", meta.album)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testMp3WithPngCover() {
        val dummyMp3Audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()) + ByteArray(256)
        val tagged = AudioMetadataTagger.embedMetadata(
            audioBytes = dummyMp3Audio,
            extension = "mp3",
            title = "Song With PNG",
            artist = "Artist",
            album = "Album",
            year = "2023",
            coverBytes = fakePng
        )

        val tempFile = File.createTempFile("tagged_png_", ".mp3")
        try {
            tempFile.writeBytes(tagged)
            val extractedCover = AudioHeaderParser.extractCoverArtBytes(tempFile)
            assertNotNull(extractedCover, "PNG cover art must be extracted from MP3")
            assertEquals(fakePng.size, extractedCover.size)
            assertTrue(fakePng.contentEquals(extractedCover))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testFlacTagAndCoverEmbedding() {
        // Минимальный валидный FLAC заголовок: 'fLaC' + STREAMINFO (34 байта) + 4 байта аудио
        val streamInfoPayload = ByteArray(34)
        val flacHeader = byteArrayOf(
            'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(),
            0x80.toByte(), // isLast = true, type = 0 (STREAMINFO)
            0x00, 0x00, 0x22 // length = 34
        ) + streamInfoPayload + byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x00, 0x00)

        val taggedFlac = AudioMetadataTagger.embedMetadata(
            audioBytes = flacHeader,
            extension = "flac",
            title = "FLAC Track",
            artist = "FLAC Artist",
            album = "FLAC Album",
            year = "2024",
            coverBytes = fakeJpeg
        )

        val tempFile = File.createTempFile("tagged_flac_", ".flac")
        try {
            tempFile.writeBytes(taggedFlac)
            val extractedCover = AudioHeaderParser.extractFlacCoverBytes(tempFile)
            assertNotNull(extractedCover, "Embedded cover art must be extracted from FLAC")
            assertEquals(fakeJpeg.size, extractedCover.size)
            assertTrue(fakeJpeg.contentEquals(extractedCover))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCorruptDataFallback() {
        val corruptedBytes = byteArrayOf(0x01, 0x02, 0x03)
        val result = AudioMetadataTagger.embedMetadata(
            audioBytes = corruptedBytes,
            extension = "unknown",
            title = "Test",
            artist = "Test"
        )
        // Должен безопасно вернуться либо исходный массив, либо с добавленным ID3v2
        assertTrue(result.isNotEmpty())
    }
}
