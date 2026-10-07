package io.github.audiz.core

import kotlinx.cinterop.*
import platform.posix.RTLD_DEFAULT
import platform.posix.dlsym

actual class NativeTrackSigner actual constructor() {

    @OptIn(ExperimentalForeignApi::class)
    private fun findSymbol(name: String): COpaquePointer? {
        return dlsym(RTLD_DEFAULT, name) ?: dlsym(RTLD_DEFAULT, "_$name")
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun signTrackUrl(trackId: String, quality: String, timestamp: String?): String? {
        val ts = timestamp ?: (platform.posix.time(null)).toString()
        val accessKey = SecurityConfig.getAccessKey()
        val authToken = Sha256.digest("$trackId:$ts:$accessKey")

        val bridge = NativeSignerBridge.delegate
        if (bridge != null) {
            val res = bridge.signTrack(trackId, quality, ts, authToken)
            if (res == null) {
                val msg = "Native provider returned null"
                CoreLogger.e("TrackSigner", msg)
                throw IllegalStateException(msg)
            }
            if (res.startsWith("Error")) {
                val msg = "Native provider returned error: $res"
                CoreLogger.e("TrackSigner", msg)
                throw IllegalStateException(msg)
            }
            return res
        }

        val sym = findSymbol("yandex_sign_track_url")
        if (sym == null) {
            val msg = "Native symbol not found"
            CoreLogger.e("TrackSigner", msg)
            throw IllegalStateException(msg)
        }

        val freeSym = findSymbol("yandex_free_string")

        return memScoped {
            val func = sym.reinterpret<CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?) -> CPointer<ByteVar>?>>()
            val freeFunc = freeSym?.reinterpret<CFunction<(CPointer<ByteVar>?) -> Unit>>()

            val trackIdPtr = trackId.cstr.ptr
            val qualityPtr = quality.cstr.ptr
            val tsPtr = ts.cstr.ptr
            val authPtr = authToken.cstr.ptr

            val resPtr = func(trackIdPtr, qualityPtr, tsPtr, authPtr)
            if (resPtr == null) {
                val msg = "Native provider returned null"
                CoreLogger.e("TrackSigner", msg)
                throw IllegalStateException(msg)
            }

            val result = resPtr.toKString()
            freeFunc?.invoke(resPtr)

            if (result.startsWith("Error") || result.isEmpty()) {
                val msg = "Native provider returned error: $result"
                CoreLogger.e("TrackSigner", msg)
                throw IllegalStateException(msg)
            }

            result
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun signBatchUrl(trackIds: String, quality: String, timestamp: String?): String? {
        val ts = timestamp ?: (platform.posix.time(null)).toString()
        val accessKey = SecurityConfig.getAccessKey()
        val authToken = Sha256.digest("$trackIds:$ts:$accessKey")

        val bridge = NativeSignerBridge.delegate
        if (bridge != null) {
            val res = bridge.signBatch(trackIds, quality, ts, authToken)
            if (res == null) {
                val msg = "Native provider returned null"
                CoreLogger.e("TrackSigner", msg)
                throw IllegalStateException(msg)
            }
            if (res.startsWith("Error")) {
                val msg = "Native provider returned error: $res"
                CoreLogger.e("TrackSigner", msg)
                throw IllegalStateException(msg)
            }
            return res
        }

        val sym = findSymbol("yandex_sign_batch_url")
        if (sym == null) {
            val msg = "Native symbol not found"
            CoreLogger.e("TrackSigner", msg)
            throw IllegalStateException(msg)
        }

        val freeSym = findSymbol("yandex_free_string")

        return memScoped {
            val func = sym.reinterpret<CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?) -> CPointer<ByteVar>?>>()
            val freeFunc = freeSym?.reinterpret<CFunction<(CPointer<ByteVar>?) -> Unit>>()

            val trackIdsPtr = trackIds.cstr.ptr
            val qualityPtr = quality.cstr.ptr
            val tsPtr = ts.cstr.ptr
            val authPtr = authToken.cstr.ptr

            val resPtr = func(trackIdsPtr, qualityPtr, tsPtr, authPtr)
            if (resPtr == null) {
                val msg = "Native provider returned null"
                CoreLogger.e("TrackSigner", msg)
                throw IllegalStateException(msg)
            }

            val result = resPtr.toKString()
            freeFunc?.invoke(resPtr)

            if (result.startsWith("Error") || result.isEmpty()) {
                val msg = "Native provider returned error: $result"
                CoreLogger.e("TrackSigner", msg)
                throw IllegalStateException(msg)
            }

            result
        }
    }
}
