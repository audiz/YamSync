package io.github.audiz

import io.github.audiz.dsp.BeatTracker
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class BeatTrackerTest {

    /** Синтетический трек: удар бочки и щелчок на каждую долю, ровный фон между ними. */
    private fun runClickTrack(bpm: Float, seconds: Float, frameSec: Float = 0.0232f): BeatTracker {
        val tracker = BeatTracker()
        val period = 60f / bpm
        var t = 0f
        while (t < seconds) {
            val sinceBeat = t % period
            val hit = sinceBeat < frameSec
            val bands = FloatArray(25) { i -> if (hit) (if (i <= 7) 0.9f else 0.6f) else 0.3f }
            tracker.process(bands, frameSec)
            t += frameSec
        }
        return tracker
    }

    @Test
    fun testDetectsSteadyTempo() {
        for (bpm in listOf(85f, 100f, 128f)) {
            val tracker = runClickTrack(bpm, seconds = 12f)
            assertTrue(abs(tracker.bpm - bpm) < 3f, "Ожидали ~$bpm BPM, получили ${tracker.bpm}")
            assertTrue(tracker.confidence > 0.5f, "Уверенность в ровном ритме должна быть высокой: ${tracker.confidence}")
        }
    }

    @Test
    fun testAndroidFrameRateKeepsTempo() {
        // Android Visualizer отдаёт ~20 кадров/с: темп не должен уезжать из-за редких кадров
        val tracker = runClickTrack(100f, seconds = 12f, frameSec = 0.05f)
        assertTrue(abs(tracker.bpm - 100f) < 4f, "Ожидали ~100 BPM при 20 кадрах/с, получили ${tracker.bpm}")
    }

    @Test
    fun testFastTempoIsShownAsHalfTime() {
        val tracker = runClickTrack(170f, seconds = 12f)
        assertTrue(tracker.bpm in 80f..90f, "170 BPM должен отображаться через долю (~85): ${tracker.bpm}")
    }

    @Test
    fun testGridAlignsWithBeats() {
        val tracker = BeatTracker()
        val frameSec = 0.0232f
        val period = 0.6f
        var t = 0f
        val onBeatWeights = ArrayList<Float>()
        val offBeatWeights = ArrayList<Float>()
        while (t < 14f) {
            val sinceBeat = t % period
            val hit = sinceBeat < frameSec
            val bands = FloatArray(25) { i -> if (hit) (if (i <= 7) 0.9f else 0.6f) else 0.3f }
            tracker.process(bands, frameSec)
            if (t > 9f) {
                if (hit) onBeatWeights.add(tracker.gridWeight())
                if (abs(sinceBeat - period * 0.25f) < frameSec) offBeatWeights.add(tracker.gridWeight())
            }
            t += frameSec
        }
        assertTrue(onBeatWeights.average() > 0.7, "На долях сетка должна пропускать удары: $onBeatWeights")
        assertTrue(offBeatWeights.average() < 0.3, "Между долями сетка должна закрываться: $offBeatWeights")
    }
}
