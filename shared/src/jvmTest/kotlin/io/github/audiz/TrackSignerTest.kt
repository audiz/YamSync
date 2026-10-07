package io.github.audiz

import io.github.audiz.core.NativeTrackSigner
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackSignerTest {

    @Test
    fun testNativeTrackSigner() {
        val signer = NativeTrackSigner()
        val currentTs = (System.currentTimeMillis() / 1000).toString()

        val url = signer.signTrackUrl("12345", "2", currentTs)
        assertNotNull(url, "URL should not be null")
        assertTrue(url.contains("api.music.yandex.ru/get-file-info"), "URL must point to yandex api")
        assertTrue(url.contains("trackId=12345"), "URL must contain trackId")
        assertTrue(url.contains("quality=lossless"), "URL must contain lossless quality")
        assertTrue(url.contains("sign="), "URL must contain sign parameter")

        val batchUrl = signer.signBatchUrl("123,456", "2", currentTs)
        assertNotNull(batchUrl, "Batch URL should not be null")
        assertTrue(batchUrl.contains("api.music.yandex.ru/get-file-info/batch"), "Batch URL must point to batch endpoint")
        assertTrue(batchUrl.contains("trackIds=123,456"), "Batch URL must contain trackIds")
        assertTrue(batchUrl.contains("sign="), "Batch URL must contain sign parameter")
    }

    @Test
    fun testRejectionOnExpiredTimestamp() {
        val signer = NativeTrackSigner()
        val expiredTs = "1000000000"

        val expiredUrl = signer.signTrackUrl("12345", "2", expiredTs)
        assertNull(expiredUrl, "Expired timestamp should be rejected")
    }
}
