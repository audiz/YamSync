package io.github.audiz

import io.github.audiz.api.decryptAesCtr
import io.github.audiz.api.decryptAesCtrChunk
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AesCtrChunkTest {

    @Test
    fun testChunkedDecryptionMatchesFullDecryption() {
        val random = Random(42)
        val key = ByteArray(16) { random.nextInt(256).toByte() }
        val iv = ByteArray(16)
        val data = ByteArray(5000) { random.nextInt(256).toByte() }

        val decryptedFull = decryptAesCtr(data, key, iv)

        val chunkSizes = listOf(15, 16, 17, 100, 256, 513, 1024, 73, 2000)
        var offset = 0
        val reconstructed = ByteArray(data.size)

        for (chunkSize in chunkSizes) {
            val length = minOf(chunkSize, data.size - offset)
            if (length <= 0) break

            val slice = data.copyOfRange(offset, offset + length)
            val decryptedChunk = decryptAesCtrChunk(slice, key, offset.toLong())

            assertEquals(length, decryptedChunk.size)
            System.arraycopy(decryptedChunk, 0, reconstructed, offset, length)
            offset += length
        }

        if (offset < data.size) {
            val remaining = data.copyOfRange(offset, data.size)
            val decryptedRemaining = decryptAesCtrChunk(remaining, key, offset.toLong())
            System.arraycopy(decryptedRemaining, 0, reconstructed, offset, remaining.size)
        }

        assertContentEquals(decryptedFull, reconstructed)
    }

    @Test
    fun testSingleByteDecryption() {
        val random = Random(123)
        val key = ByteArray(16) { random.nextInt(256).toByte() }
        val data = ByteArray(64) { random.nextInt(256).toByte() }

        val decryptedFull = decryptAesCtr(data, key, ByteArray(16))
        val decryptedBytes = ByteArray(data.size)

        for (i in data.indices) {
            val singleByte = byteArrayOf(data[i])
            val dec = decryptAesCtrChunk(singleByte, key, i.toLong())
            decryptedBytes[i] = dec[0]
        }

        assertContentEquals(decryptedFull, decryptedBytes)
    }
}
