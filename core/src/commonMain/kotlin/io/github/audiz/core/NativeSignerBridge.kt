package io.github.audiz.core

interface NativeSignerDelegate {
    fun signTrack(trackId: String, quality: String, timestamp: String, authToken: String): String?
    fun signBatch(trackIds: String, quality: String, timestamp: String, authToken: String): String?
}

object NativeSignerBridge {
    var delegate: NativeSignerDelegate? = null
}
