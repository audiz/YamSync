package io.github.audiz

import io.github.audiz.dsp.BiquadFilter
import io.github.audiz.dsp.EqualizerEngine
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqualizerTest {

    @Test
    fun testBiquadBypassLeavesSignalUntouched() {
        val filter = BiquadFilter()
        filter.setBypass()

        for (i in 0..100) {
            val input = (i / 100f) * 2f - 1f
            val outL = filter.processLeft(input)
            val outR = filter.processRight(input)
            assertEquals(input, outL, 0.0001f)
            assertEquals(input, outR, 0.0001f)
        }
    }

    @Test
    fun testHighPassFilterAttenuatesLowFrequencies() {
        val filter = BiquadFilter()
        val sampleRate = 44100f
        // Cutoff at 150 Hz
        filter.configureHighPass(sampleRate, 150f)

        // Generate 40 Hz sine wave (well below 150 Hz cutoff)
        val freq = 40f
        var maxIn = 0f
        var maxOut = 0f

        // Process 1 second of audio
        for (n in 0 until 44100) {
            val sample = sin(2.0 * PI * freq * n / sampleRate).toFloat()
            val out = filter.processLeft(sample)
            if (n > 4410) { // allow transient to settle (after 100ms)
                maxIn = maxOf(maxIn, abs(sample))
                maxOut = maxOf(maxOut, abs(out))
            }
        }

        // 40 Hz should be significantly attenuated by 150 Hz HPF (at least -6 dB, i.e. < 0.5 amplitude)
        assertTrue(maxOut < maxIn * 0.5f, "40 Hz must be attenuated by 150 Hz HPF. maxOut=$maxOut, maxIn=$maxIn")
    }

    @Test
    fun testLowPassFilterAttenuatesHighFrequencies() {
        val filter = BiquadFilter()
        val sampleRate = 44100f
        // Cutoff at 5000 Hz
        filter.configureLowPass(sampleRate, 5000f)

        // Generate 14000 Hz sine wave (well above 5000 Hz cutoff)
        val freq = 14000f
        var maxIn = 0f
        var maxOut = 0f

        for (n in 0 until 44100) {
            val sample = sin(2.0 * PI * freq * n / sampleRate).toFloat()
            val out = filter.processLeft(sample)
            if (n > 4410) {
                maxIn = maxOf(maxIn, abs(sample))
                maxOut = maxOf(maxOut, abs(out))
            }
        }

        // 14 kHz should be strongly attenuated by 5 kHz LPF (at least -10 dB, i.e. < 0.35 amplitude)
        assertTrue(maxOut < maxIn * 0.35f, "14 kHz must be attenuated by 5 kHz LPF. maxOut=$maxOut, maxIn=$maxIn")
    }

    @Test
    fun testPeakingFilterBoostsCenterFrequency() {
        val filter = BiquadFilter()
        val sampleRate = 44100f
        // Boost 1000 Hz by +12 dB
        filter.configurePeaking(sampleRate, 1000f, 12f)

        val freq = 1000f
        var maxIn = 0f
        var maxOut = 0f

        for (n in 0 until 44100) {
            val sample = 0.2f * sin(2.0 * PI * freq * n / sampleRate).toFloat()
            val out = filter.processLeft(sample)
            if (n > 4410) {
                maxIn = maxOf(maxIn, abs(sample))
                maxOut = maxOf(maxOut, abs(out))
            }
        }

        // +12 dB is approximately 3.98x amplification
        val ratio = maxOut / maxIn
        assertTrue(ratio > 3.0f, "1000 Hz should be boosted by ~+12 dB (expected ratio > 3.0, got $ratio)")
    }

    @Test
    fun testEqualizerEngineProcessPcmBuffer() {
        EqualizerEngine.setEnabled(true)
        EqualizerEngine.resetToFlat()

        // Create buffer with 16-bit PCM stereo sine wave
        val sampleRate = 44100
        val buffer = ByteArray(4000)
        for (i in 0 until buffer.size step 4) {
            val sampleVal = (sin(2.0 * PI * 440.0 * (i / 4) / sampleRate) * 10000.0).roundToInt().toShort()
            buffer[i] = (sampleVal.toInt() and 0xFF).toByte()
            buffer[i + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
            buffer[i + 2] = (sampleVal.toInt() and 0xFF).toByte()
            buffer[i + 3] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }

        // Processing should run without exception and preserve audio
        EqualizerEngine.processPcm(buffer, buffer.size)

        var hasNonZero = false
        for (b in buffer) {
            if (b != 0.toByte()) {
                hasNonZero = true
                break
            }
        }
        assertTrue(hasNonZero, "Buffer should have non-zero audio content after processing")

        EqualizerEngine.setEnabled(false)
    }

    @Test
    fun testCalculateEffectiveGainDb() {
        // 1. Bypass when disabled
        EqualizerEngine.setEnabled(false)
        assertEquals(0f, EqualizerEngine.calculateEffectiveGainDb(60f))
        assertEquals(0f, EqualizerEngine.calculateEffectiveGainDb(1000f))
        assertEquals(0f, EqualizerEngine.calculateEffectiveGainDb(14000f))

        // 2. Flat when enabled
        EqualizerEngine.setEnabled(true)
        EqualizerEngine.resetToFlat()
        EqualizerEngine.resetCutoffs()
        assertEquals(0f, EqualizerEngine.calculateEffectiveGainDb(60f), 0.05f)
        assertEquals(0f, EqualizerEngine.calculateEffectiveGainDb(1000f), 0.05f)

        // 3. Bass Boost preset
        val bassBoost = EqualizerEngine.PRESETS.first { it.name == "Bass Boost" }
        EqualizerEngine.applyPreset(bassBoost)
        val bassGain60 = EqualizerEngine.calculateEffectiveGainDb(60f)
        assertTrue(bassGain60 > 4.5f, "60 Hz should be boosted in Bass Boost preset (got $bassGain60 dB)")

        // 4. Low-Cut (HPF) filter
        EqualizerEngine.resetToFlat()
        EqualizerEngine.setHpfCutoff(120f)
        val hpfAtten60 = EqualizerEngine.calculateEffectiveGainDb(60f)
        assertTrue(hpfAtten60 < -10f, "60 Hz should be cut by ~-12 dB with 120 Hz HPF (got $hpfAtten60 dB)")

        // 5. High-Cut (LPF) filter
        EqualizerEngine.resetToFlat()
        EqualizerEngine.resetCutoffs()
        EqualizerEngine.setLpfCutoff(8000f)
        val lpfAtten16k = EqualizerEngine.calculateEffectiveGainDb(16000f)
        assertTrue(lpfAtten16k < -10f, "16000 Hz should be cut by ~-12 dB with 8000 Hz LPF (got $lpfAtten16k dB)")

        EqualizerEngine.setEnabled(false)
    }
}
