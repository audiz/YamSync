package io.github.audiz.api

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun decryptAesCtr(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    if (ciphertext.isEmpty()) return ByteArray(0)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    val secretKey = SecretKeySpec(key, "AES")
    val ivSpec = IvParameterSpec(iv)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
    return cipher.doFinal(ciphertext)
}

actual fun decryptAesCtrChunk(ciphertext: ByteArray, key: ByteArray, byteOffset: Long): ByteArray {
    if (ciphertext.isEmpty()) return ByteArray(0)

    val blockIndex = byteOffset / 16
    val offsetInFirstBlock = (byteOffset % 16).toInt()

    val counter = ByteArray(16)
    var temp = blockIndex
    for (j in 15 downTo 0) {
        counter[j] = (temp and 0xFF).toByte()
        temp = temp ushr 8
        if (temp == 0L) break
    }

    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    val secretKey = SecretKeySpec(key, "AES")
    val ivSpec = IvParameterSpec(counter)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

    return if (offsetInFirstBlock == 0) {
        cipher.doFinal(ciphertext)
    } else {
        val paddedInput = ByteArray(offsetInFirstBlock + ciphertext.size)
        System.arraycopy(ciphertext, 0, paddedInput, offsetInFirstBlock, ciphertext.size)
        val decryptedPadded = cipher.doFinal(paddedInput)
        decryptedPadded.copyOfRange(offsetInFirstBlock, decryptedPadded.size)
    }
}
