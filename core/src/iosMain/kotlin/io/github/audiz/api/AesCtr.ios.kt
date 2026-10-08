package io.github.audiz.api

actual fun decryptAesCtr(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    decryptAesCtrPureKotlin(ciphertext, key, iv)

actual fun decryptAesCtrChunk(ciphertext: ByteArray, key: ByteArray, byteOffset: Long): ByteArray =
    decryptAesCtrChunkPureKotlin(ciphertext, key, byteOffset)
