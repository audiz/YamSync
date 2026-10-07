package io.github.audiz

import io.github.audiz.dsp.AudioVisualizer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioVisualizerTest {

    @BeforeTest
    fun setUp() {
        AudioVisualizer.latencyCompensationMs = 0L
        AudioVisualizer.timeProvider = { io.github.audiz.currentTimeMillis() }
        AudioVisualizer.reset()
    }

    @Test
    fun testInstantAttackAndWinampProBallistics() {
        AudioVisualizer.reset()

        val initial = AudioVisualizer.levels.value
        assertEquals(5, initial.size)
        assertTrue(initial.all { it == AudioVisualizer.RESTING_LEVEL })

        // 1. Instant Attack: моментальный взлет на 1.0f (0 мс)
        AudioVisualizer.updateBands(0f, 0f, 1.0f, 0f, 0f)
        val peek = AudioVisualizer.levels.value
        assertEquals(1.0f, peek[2], 0.001f, "Полоса должна моментально взлетать до 1.0")

        // 2. В тишине: во время первых 40 мс столбик удерживается в пике (Bar Hold = 60ms)
        AudioVisualizer.updateBands(0f, 0f, 0f, 0f, 0f)
        val frameHold = AudioVisualizer.advanceFrame(0.040f, isPlaying = true)
        assertEquals(1.0f, frameHold.levels[2], 0.001f, "Столбик должен удерживать пик 1.0 во время hold time")
        assertEquals(1.0f, frameHold.peakCaps[2], 0.001f, "Пиковая засечка также удерживается в 1.0")

        // 3. Через 120 мс: столбик уже падает по гравитации, а пиковая засечка все еще парит в воздухе (Hold = 280ms)
        val frameFloating = AudioVisualizer.advanceFrame(0.080f, isPlaying = true)
        assertTrue(frameFloating.levels[2] < 1.0f, "Столбик начал гравитационное падение: ${frameFloating.levels[2]}")
        assertEquals(1.0f, frameFloating.peakCaps[2], 0.001f, "Пиковая засечка продолжает парить на пике")
        assertTrue(frameFloating.levels[2] < frameFloating.peakCaps[2], "Столбик опустился ниже парящей засечки")

        // 4. Через 350 мс: засечка тоже начинает плавный спуск за столбиком
        val frameLater = AudioVisualizer.advanceFrame(0.200f, isPlaying = true)
        assertTrue(frameLater.peakCaps[2] < 1.0f, "Пиковая засечка начала спуск после 280 мс удержания")

        // 5. Сброс в исходное состояние
        AudioVisualizer.reset()
        val afterReset = AudioVisualizer.levels.value
        assertTrue(afterReset.all { it == AudioVisualizer.RESTING_LEVEL }, "Сброс восстанавливает уровень покоя")
    }

    @Test
    fun testPcmFrequencyBandSeparation() {
        AudioVisualizer.reset()

        val sampleRate = 44100
        val numSamples = 2048 // ~46ms

        // 1. Generate 60 Hz pure sub-bass sine wave
        val subBassBuffer = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val sampleVal = (sin(2.0 * PI * 60.0 * i / sampleRate) * 28000.0).toInt().toShort()
            subBassBuffer[i * 2] = (sampleVal.toInt() and 0xFF).toByte()
            subBassBuffer[i * 2 + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }
        AudioVisualizer.processPcmChunk(subBassBuffer, subBassBuffer.size)
        val subLevels = AudioVisualizer.levels.value
        assertTrue(subLevels[0] > subLevels[3] && subLevels[0] > subLevels[4],
            "60 Hz sine wave should trigger sub-bass band higher than treble (sub=${subLevels[0]}, treble=${subLevels[4]})")

        // 2. Generate 10 kHz pure treble sine wave
        AudioVisualizer.reset()
        val trebleBuffer = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val sampleVal = (sin(2.0 * PI * 10000.0 * i / sampleRate) * 28000.0).toInt().toShort()
            trebleBuffer[i * 2] = (sampleVal.toInt() and 0xFF).toByte()
            trebleBuffer[i * 2 + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }
        AudioVisualizer.processPcmChunk(trebleBuffer, trebleBuffer.size)
        val trebleLevels = AudioVisualizer.levels.value
        assertTrue(trebleLevels[4] > trebleLevels[0],
            "10 kHz sine wave should trigger treble band higher than sub-bass (treble=${trebleLevels[4]}, sub=${trebleLevels[0]})")
    }

    @Test
    fun testAdaptiveSensitivityLiftsQuietTracks() {
        AudioVisualizer.reset()

        // Имитируем спокойный/тихий трек (пики около 0.28)
        // Без авто-гейна 0.28^2 = 0.078 упало бы ниже пола RESTING_LEVEL (0.15)
        repeat(5) {
            AudioVisualizer.advanceFrame(0.4f, isPlaying = true)
            AudioVisualizer.updateBands(0.28f, 0.25f, 0.22f, 0.20f, 0.18f)
        }
        val levels = AudioVisualizer.levels.value

        // Благодаря адаптивному авто-гейну столбик поднят высоко над полом покоя (0.15)
        assertTrue(levels[0] > 0.40f,
            "Адаптивная чувствительность должна поднимать тихий трек (level0=${levels[0]})")
    }

    @Test
    fun testIndependentBandResponseAndSoftCap() {
        AudioVisualizer.reset()

        // 1. Проверяем работу эластичного софт-лимитера бочки (b1 = 1.0f -> soft-capped до ~0.90)
        AudioVisualizer.updateBands(1.0f, 0.70f, 0.50f, 0.40f, 0.30f)
        val levels = AudioVisualizer.levels.value

        // У бочки есть 10% запас динамического хода (0.85 + (1.0 - 0.85) * 0.35 = 0.9025)
        assertTrue(levels[0] in 0.80f..0.95f, "Бочка не должна намертво залипать в 1.0, сохраняя запас хода: ${levels[0]}")

        // 2. Вторая полоса (бас) реагирует независимо и активно (~0.7^2 = 0.49), не подавляясь бочкой
        assertTrue(levels[1] > 0.35f, "Бас-гитара не должна глушиться ударом бочки: ${levels[1]}")
    }

    @Test
    fun testGetLevelsForDisplay() {
        AudioVisualizer.reset()

        val stoppedLevels = AudioVisualizer.getLevelsForDisplay(isPlaying = false)
        assertEquals(5, stoppedLevels.size)
        assertTrue(stoppedLevels.all { it == AudioVisualizer.RESTING_LEVEL })

        val playingLevels = AudioVisualizer.getLevelsForDisplay(isPlaying = true)
        assertEquals(5, playingLevels.size)
        assertTrue(playingLevels.all { it in 0.12f..1.0f })
    }

    @Test
    fun testLatencyCompensationQueueHoldsAndReleasesFrames() {
        var simTime = 1000L
        AudioVisualizer.timeProvider = { simTime }
        AudioVisualizer.latencyCompensationMs = 140L
        AudioVisualizer.reset()

        // 1. Enqueue peak at simTime = 1000L
        AudioVisualizer.updateBands(0f, 0f, 1.0f, 0f, 0f)

        // At 50 ms later (simTime = 1050L), 140ms has not passed yet
        simTime = 1050L
        val frame50 = AudioVisualizer.advanceFrame(0.050f, isPlaying = true)
        assertEquals(AudioVisualizer.RESTING_LEVEL, frame50.levels[2], 0.001f, "Band should remain at resting level before latency expires")

        // At 140 ms later (simTime = 1140L), snapshot matures and pops from queue
        simTime = 1140L
        val frame140 = AudioVisualizer.advanceFrame(0.090f, isPlaying = true)
        assertEquals(1.0f, frame140.levels[2], 0.001f, "Band should pop to 1.0f after latency compensation expires")

        // 2. Test clearDelayQueue discards buffered frames (e.g. on seek/pause)
        AudioVisualizer.updateBands(1.0f, 0f, 0f, 0f, 0f)
        AudioVisualizer.clearDelayQueue()
        simTime = 1300L
        val frameCleared = AudioVisualizer.advanceFrame(0.160f, isPlaying = true)
        assertTrue(frameCleared.levels[0] < 0.5f, "Band 0 should not pop because queue was cleared")
    }

    @Test
    fun testSpectrum19LatencyCompensation() {
        var simTime = 2000L
        AudioVisualizer.timeProvider = { simTime }
        AudioVisualizer.latencyCompensationMs = 120L
        AudioVisualizer.reset()

        val raw19 = FloatArray(19) { if (it == 5) 0.95f else 0.1f }
        AudioVisualizer.updateSpectrum19(raw19)

        // At 60 ms later
        simTime = 2060L
        val frameEarly = AudioVisualizer.advanceSpectrum19Frame(0.060f, isPlaying = true)
        assertEquals(AudioVisualizer.RESTING_LEVEL, frameEarly.levels[5], 0.001f, "Band 5 should remain at resting level before 120ms")

        // At 120 ms later
        simTime = 2120L
        val frameMatured = AudioVisualizer.advanceSpectrum19Frame(0.060f, isPlaying = true)
        assertTrue(frameMatured.levels[5] > 0.70f, "Band 5 should trigger after 120ms latency")
    }
}
