package io.github.audiz.api

expect fun decryptAesCtr(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray

expect fun decryptAesCtrChunk(ciphertext: ByteArray, key: ByteArray, byteOffset: Long): ByteArray

internal fun decryptAesCtrPureKotlin(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    val result = ByteArray(ciphertext.size)
    val counter = iv.copyOf()
    val rkeys = aesExpandKey128(key)
    val blockKeystream = ByteArray(16)

    var i = 0
    val total = ciphertext.size
    while (i < total) {
        val currentCounterBlock = counter.copyOf()
        aesEncryptBlock128(currentCounterBlock, rkeys, blockKeystream)

        for (j in 15 downTo 0) {
            counter[j] = (counter[j] + 1).toByte()
            if (counter[j] != 0.toByte()) break
        }

        val bytesToProcess = minOf(16, total - i)
        for (b in 0 until bytesToProcess) {
            result[i + b] = (ciphertext[i + b].toInt() xor blockKeystream[b].toInt()).toByte()
        }
        i += 16
    }
    return result
}

internal fun decryptAesCtrChunkPureKotlin(ciphertext: ByteArray, key: ByteArray, byteOffset: Long): ByteArray {
    if (ciphertext.isEmpty()) return ByteArray(0)
    val result = ByteArray(ciphertext.size)
    val rkeys = aesExpandKey128(key)
    val blockKeystream = ByteArray(16)

    val blockIndex = byteOffset / 16
    val offsetInFirstBlock = (byteOffset % 16).toInt()

    val counter = ByteArray(16)
    var temp = blockIndex
    for (j in 15 downTo 0) {
        counter[j] = (temp and 0xFF).toByte()
        temp = temp ushr 8
        if (temp == 0L) break
    }

    var bytesWritten = 0
    val total = ciphertext.size
    var firstBlock = true

    while (bytesWritten < total) {
        val currentCounterBlock = counter.copyOf()
        aesEncryptBlock128(currentCounterBlock, rkeys, blockKeystream)

        for (j in 15 downTo 0) {
            counter[j] = (counter[j] + 1).toByte()
            if (counter[j] != 0.toByte()) break
        }

        val startInBlock = if (firstBlock) offsetInFirstBlock else 0
        firstBlock = false
        val availableInBlock = 16 - startInBlock
        val bytesToCopy = minOf(availableInBlock, total - bytesWritten)

        for (b in 0 until bytesToCopy) {
            result[bytesWritten + b] = (ciphertext[bytesWritten + b].toInt() xor blockKeystream[startInBlock + b].toInt()).toByte()
        }
        bytesWritten += bytesToCopy
    }
    return result
}

private fun aesExpandKey128(key: ByteArray): IntArray {
    val rkeys = IntArray(44)
    for (i in 0..3) {
        rkeys[i] = ((key[4 * i].toInt() and 0xFF) shl 24) or
                ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((key[4 * i + 2].toInt() and 0xFF) shl 8) or
                (key[4 * i + 3].toInt() and 0xFF)
    }
    for (i in 4..43) {
        var temp = rkeys[i - 1]
        if (i % 4 == 0) {
            val rot = ((temp shl 8) or (temp ushr 24))
            val sub = (AES_SBOX[(rot ushr 24) and 0xFF] shl 24) or
                    (AES_SBOX[(rot ushr 16) and 0xFF] shl 16) or
                    (AES_SBOX[(rot ushr 8) and 0xFF] shl 8) or
                    AES_SBOX[rot and 0xFF]
            temp = sub xor (AES_RCON[i / 4] shl 24)
        }
        rkeys[i] = rkeys[i - 4] xor temp
    }
    return rkeys
}

private fun aesEncryptBlock128(input: ByteArray, rkeys: IntArray, output: ByteArray) {
    val state = IntArray(16) { input[it].toInt() and 0xFF }
    addRoundKeyInternal(state, rkeys, 0)
    for (round in 1..9) {
        subBytesInternal(state)
        shiftRowsInternal(state)
        mixColumnsInternal(state)
        addRoundKeyInternal(state, rkeys, round)
    }
    subBytesInternal(state)
    shiftRowsInternal(state)
    addRoundKeyInternal(state, rkeys, 10)
    for (i in 0..15) {
        output[i] = state[i].toByte()
    }
}

private fun addRoundKeyInternal(state: IntArray, rkeys: IntArray, round: Int) {
    val base = round * 4
    for (c in 0..3) {
        val rk = rkeys[base + c]
        state[c * 4 + 0] = state[c * 4 + 0] xor ((rk ushr 24) and 0xFF)
        state[c * 4 + 1] = state[c * 4 + 1] xor ((rk ushr 16) and 0xFF)
        state[c * 4 + 2] = state[c * 4 + 2] xor ((rk ushr 8) and 0xFF)
        state[c * 4 + 3] = state[c * 4 + 3] xor (rk and 0xFF)
    }
}

private fun subBytesInternal(state: IntArray) {
    for (i in 0..15) { state[i] = AES_SBOX[state[i]] }
}

private fun shiftRowsInternal(state: IntArray) {
    val t = state.copyOf()
    state[1] = t[5];  state[5] = t[9];  state[9] = t[13]; state[13] = t[1]
    state[2] = t[10]; state[10] = t[2]; state[6] = t[14]; state[14] = t[6]
    state[3] = t[15]; state[15] = t[11]; state[11] = t[7];  state[7] = t[3]
}

private fun mixColumnsInternal(state: IntArray) {
    for (i in 0..3) {
        val b = i * 4
        val s0 = state[b]; val s1 = state[b + 1]; val s2 = state[b + 2]; val s3 = state[b + 3]
        state[b]     = galoisMulInternal(2, s0) xor galoisMulInternal(3, s1) xor s2 xor s3
        state[b + 1] = s0 xor galoisMulInternal(2, s1) xor galoisMulInternal(3, s2) xor s3
        state[b + 2] = s0 xor s1 xor galoisMulInternal(2, s2) xor galoisMulInternal(3, s3)
        state[b + 3] = galoisMulInternal(3, s0) xor s1 xor s2 xor galoisMulInternal(2, s3)
    }
}

private fun galoisMulInternal(g: Int, value: Int): Int {
    if (g == 2) {
        val res = value shl 1
        return if ((value and 0x80) != 0) (res xor 0x11B) and 0xFF else res and 0xFF
    }
    if (g == 3) { return (galoisMulInternal(2, value) xor value) and 0xFF }
    return 0
}

private val AES_SBOX = intArrayOf(
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
)

private val AES_RCON = intArrayOf(
    0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36
)
