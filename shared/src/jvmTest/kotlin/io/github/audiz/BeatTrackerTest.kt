package io.github.audiz

import io.github.audiz.dsp.BeatTracker
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class BeatTrackerTest {

    /** Синтетический трек: удар бочки и щелчок на каждую долю, ровный фон между ними. */
    private fun runClickTrack(bpm: Float, seconds: Float, frameSec: Float = 0.0232f): Pair<BeatTracker, List<Float>> {
        val tracker = BeatTracker()
        val beatTimes = ArrayList<Float>()
        val period = 60f / bpm
        var t = 0f
        while (t < seconds) {
            val sinceBeat = t % period
            val hit = sinceBeat < frameSec
            val bands = FloatArray(25) { i -> if (hit) (if (i <= 7) 0.9f else 0.6f) else 0.3f }
            if (tracker.process(bands, frameSec) > 0f) beatTimes.add(t)
            t += frameSec
        }
        return tracker to beatTimes
    }

    @Test
    fun testDetectsSteadyTempo() {
        for (bpm in listOf(85f, 100f, 128f)) {
            val (tracker, _) = runClickTrack(bpm, seconds = 12f)
            assertTrue(abs(tracker.bpm - bpm) < 3f, "Ожидали ~$bpm BPM, получили ${tracker.bpm}")
            assertTrue(tracker.confidence > 0.5f, "Уверенность в ровном ритме должна быть высокой: ${tracker.confidence}")
        }
    }

    @Test
    fun testBeatsFireOncePerBeat() {
        val (_, beats) = runClickTrack(100f, seconds = 12f)
        val late = beats.filter { it > 6f }
        val intervals = late.zipWithNext { a, b -> b - a }
        assertTrue(intervals.isNotEmpty(), "Доли должны срабатывать")
        assertTrue(intervals.all { abs(it - 0.6f) < 0.08f }, "Интервал между долями ~0.6 с: $intervals")
    }

    @Test
    fun testFastTempoIsShownAsHalfTime() {
        val (tracker, _) = runClickTrack(170f, seconds = 12f)
        assertTrue(tracker.bpm in 80f..90f, "170 BPM должен отображаться через долю (~85): ${tracker.bpm}")
    }
}
