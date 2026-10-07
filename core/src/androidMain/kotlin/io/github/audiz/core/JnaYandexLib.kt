package io.github.audiz.core

import com.sun.jna.Library
import com.sun.jna.Pointer

interface JnaYandexLib : Library {
    fun yandex_sign_track_url(trackId: String, version: String, timestamp: String?, authToken: String): Pointer?
    fun yandex_sign_batch_url(trackIds: String, version: String, timestamp: String?, authToken: String): Pointer?
    fun yandex_free_string(ptr: Pointer)
}

