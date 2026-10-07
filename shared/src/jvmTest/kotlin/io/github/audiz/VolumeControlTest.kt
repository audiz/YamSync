package io.github.audiz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolumeControlTest {

    @Test
    fun testVolumeToGainBoundaries() {
        assertEquals(0f, volumeToGain(0f))
        assertEquals(1f, volumeToGain(1f))
        assertEquals(0f, volumeToGain(-0.5f), "Negative volume should be clamped to 0")
        assertEquals(1f, volumeToGain(1.5f), "Volume above 1 should be clamped to 1")
    }

    @Test
    fun testVolumeToGainCubicCurve() {
        // At 50% slider position, gain is 0.5^3 = 0.125 (~ -18 dB, perceived as half loudness)
        val mid = volumeToGain(0.5f)
        assertEquals(0.125f, mid, 0.0001f)

        // At 10% slider position, gain is 0.1^3 = 0.001 (-60 dB, whisper quiet, avoiding pixel hunting)
        val quiet = volumeToGain(0.1f)
        assertEquals(0.001f, quiet, 0.00001f)

        // Verify strictly monotonic growth
        var prev = -1f
        for (i in 0..100) {
            val v = i / 100f
            val gain = volumeToGain(v)
            assertTrue(gain >= prev, "Gain must be monotonically increasing at step $i")
            prev = gain
        }
    }
}
