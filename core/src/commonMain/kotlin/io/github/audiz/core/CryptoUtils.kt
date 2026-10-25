package io.github.audiz.core

/**
 * Сравнение строк за постоянное время (Constant-Time Comparison) для защиты от атак по времени (CWE-208 / Side-Channel Timing Attack).
 */
fun constantTimeEquals(a: String?, b: String?): Boolean {
    if (a == null || b == null) {
        return a == b
    }
    val aBytes = a.encodeToByteArray()
    val bBytes = b.encodeToByteArray()
    if (aBytes.size != bBytes.size) {
        var dummy = 0
        for (i in aBytes.indices) {
            dummy = dummy or (aBytes[i].toInt() xor aBytes[i].toInt())
        }
        return false
    }
    var diff = 0
    for (i in aBytes.indices) {
        diff = diff or (aBytes[i].toInt() xor bBytes[i].toInt())
    }
    return diff == 0
}
