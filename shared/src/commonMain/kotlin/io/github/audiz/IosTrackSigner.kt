package io.github.audiz

import io.github.audiz.core.NativeSignerDelegate
import io.github.audiz.core.NativeSignerBridge

interface IosTrackSignerProvider {
    fun signTrack(trackId: String, quality: String, timestamp: String, authToken: String): String?
    fun signBatch(trackIds: String, quality: String, timestamp: String, authToken: String): String?
}

object IosTrackSignerRegistry {
    fun register(provider: IosTrackSignerProvider) {
        NativeSignerBridge.delegate = object : NativeSignerDelegate {
            override fun signTrack(trackId: String, quality: String, timestamp: String, authToken: String): String? {
                return provider.signTrack(trackId, quality, timestamp, authToken)
            }
            override fun signBatch(trackIds: String, quality: String, timestamp: String, authToken: String): String? {
                return provider.signBatch(trackIds, quality, timestamp, authToken)
            }
        }
    }
}
