package io.github.audiz.core

import com.sun.jna.Native
import com.sun.jna.Pointer

actual class NativeTrackSigner actual constructor() {

    private val libYandex: JnaYandexLib by lazy {
        val activityThread = Class.forName("android.app.ActivityThread")
        val currentApplicationMethod = activityThread.getMethod("currentApplication")
        val context = currentApplicationMethod.invoke(null) as android.content.Context

        System.setProperty("jna.boot.library.path", context.applicationInfo.nativeLibraryDir)
        System.loadLibrary("YandexPdfLib")

        val classLoader = JnaYandexLib::class.java.classLoader
        Native.load("YandexPdfLib", JnaYandexLib::class.java, mapOf(com.sun.jna.Library.OPTION_CLASSLOADER to classLoader)) as JnaYandexLib
    }

    actual fun signTrackUrl(trackId: String, quality: String, timestamp: String?): String? {
        val ts = timestamp ?: (System.currentTimeMillis() / 1000).toString()
        val accessKey = SecurityConfig.getAccessKey()
        val authToken = Sha256.digest("$trackId:$ts:$accessKey")

        val ptr = libYandex.yandex_sign_track_url(trackId, quality, ts, authToken) ?: return null
        val generatedUrl = ptr.getString(0, "UTF-8")
        libYandex.yandex_free_string(ptr)

        if (generatedUrl.startsWith("Error") || generatedUrl.isEmpty()) return null
        return generatedUrl
    }

    actual fun signBatchUrl(trackIds: String, quality: String, timestamp: String?): String? {
        val ts = timestamp ?: (System.currentTimeMillis() / 1000).toString()
        val accessKey = SecurityConfig.getAccessKey()
        val authToken = Sha256.digest("$trackIds:$ts:$accessKey")

        val ptr = libYandex.yandex_sign_batch_url(trackIds, quality, ts, authToken) ?: return null
        val generatedUrl = ptr.getString(0, "UTF-8")
        libYandex.yandex_free_string(ptr)

        if (generatedUrl.startsWith("Error") || generatedUrl.isEmpty()) return null
        return generatedUrl
    }
}

