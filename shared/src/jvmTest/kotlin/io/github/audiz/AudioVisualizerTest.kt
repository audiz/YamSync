package io.github.audiz

import io.github.audiz.dsp.AudioVisualizer
import io.github.audiz.dsp.PcmSpectrumAnalyzer
import io.github.audiz.dsp.TwentyFiveBandVisualizer
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
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
    fun testSpectrumLatencyCompensation() {
        var simTime = 2000L
        AudioVisualizer.timeProvider = { simTime }
        AudioVisualizer.latencyCompensationMs = 120L
        AudioVisualizer.reset()

        val raw25 = FloatArray(25) { if (it == 8) 0.95f else 0.1f }
        AudioVisualizer.updateSpectrum(raw25)

        // At 60 ms later
        simTime = 2060L
        val frameEarly = AudioVisualizer.advanceSpectrumFrame(0.060f, isPlaying = true)
        assertEquals(25, frameEarly.levels.size, "Spectrum frame should contain 25 bands")
        assertEquals(AudioVisualizer.RESTING_LEVEL, frameEarly.levels[8], 0.001f, "Band 8 should remain at resting level before 120ms")

        // At 120 ms later
        simTime = 2120L
        val frameMatured = AudioVisualizer.advanceSpectrumFrame(0.060f, isPlaying = true)
        assertEquals(25, frameMatured.levels.size, "Spectrum frame should contain 25 bands")
        assertTrue(frameMatured.levels[8] > AudioVisualizer.RESTING_LEVEL + 0.3f, "Band 8 should trigger after 120ms latency: ${frameMatured.levels[8]}")
    }

    @Test
    fun testNoSoundRestsAtBottomWithoutSineWave() {
        AudioVisualizer.reset()
        // При отсутствии звука оба визуализатора должны лежать на дне RESTING_LEVEL
        repeat(10) {
            val frame5 = AudioVisualizer.advanceFrame(0.033f, isPlaying = true)
            val frame25 = AudioVisualizer.advanceSpectrumFrame(0.033f, isPlaying = true)
            assertTrue(frame5.levels.all { it == AudioVisualizer.RESTING_LEVEL }, "5-полосный визуализатор должен лежать внизу без синусоиды")
            assertTrue(frame25.levels.all { it == AudioVisualizer.RESTING_LEVEL }, "25-полосный визуализатор должен лежать внизу без синусоиды")
        }
    }

    @Test
    fun testDerivationOf5BandsFrom25Bands() {
        AudioVisualizer.reset()
        AudioVisualizer.latencyCompensationMs = 0L

        // 1. Подаем 25-полосный спектральный сигнал с сильным суб-басом/бочкой (полосы 0..3)
        val bassSpectrum = FloatArray(25) { if (it in 0..3) 0.95f else 0.05f }
        AudioVisualizer.updateSpectrum(bassSpectrum)

        // Атака сглажена: даём столбику несколько кадров на подъём
        val frame25 = AudioVisualizer.advanceSpectrumFrame(0.080f, isPlaying = true)
        assertEquals(25, frame25.levels.size)
        assertTrue(frame25.levels[0] > 0.50f, "Полоса 0 в 25-полосном спектре активна: ${frame25.levels[0]}")

        // 5-полосный визуализатор должен автоматически вывестись из 25 полос:
        // b1 (Sub-bass/Kick) должен быть высоким, а b5 (Treble) — значительно ниже
        val derivedLevels = AudioVisualizer.levels.value
        assertEquals(5, derivedLevels.size)
        assertTrue(derivedLevels[0] > 0.60f, "Полоса 0 (Sub-bass) в 5-полосном индикаторе должна вывестись из спектра: ${derivedLevels[0]}")
        assertTrue(derivedLevels[4] < derivedLevels[0], "Высокие частоты в 5-полосном индикаторе должны быть значительно ниже баса: ${derivedLevels[4]}")

        // 2. Подаем высокочастотный спектральный сигнал (полосы 21..24)
        AudioVisualizer.reset()
        val trebleSpectrum = FloatArray(25) { if (it in 21..24) 0.95f else 0.05f }
        AudioVisualizer.updateSpectrum(trebleSpectrum)

        val derivedTrebleLevels = AudioVisualizer.levels.value
        assertTrue(derivedTrebleLevels[4] > 0.60f, "Полоса 4 (Treble) в 5-полосном индикаторе должна вывестись из высоких частот: ${derivedTrebleLevels[4]}")
        assertTrue(derivedTrebleLevels[0] < derivedTrebleLevels[4], "Низкие частоты должны быть ниже высоких: ${derivedTrebleLevels[0]} vs ${derivedTrebleLevels[4]}")
    }

    private fun stereoSinePcm(freqHz: Double, numFrames: Int, sampleRate: Int = 44100): ByteArray {
        val buf = ByteArray(numFrames * 4)
        for (i in 0 until numFrames) {
            val v = (sin(2.0 * PI * freqHz * i / sampleRate) * 28000.0).toInt()
            for (ch in 0..1) {
                buf[i * 4 + ch * 2] = (v and 0xFF).toByte()
                buf[i * 4 + ch * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        return buf
    }

    @Test
    fun testLowBandsAreResolvedIndividually() {
        val analyzer = PcmSpectrumAnalyzer()
        var spectrum = FloatArray(0)
        analyzer.onSpectrumReady = { spectrum = it.copyOf() }

        val pcm30 = stereoSinePcm(30.0, 4096)
        analyzer.processPcmChunk(pcm30, pcm30.size)
        val low30 = spectrum
        analyzer.reset()
        val pcm70 = stereoSinePcm(70.0, 4096)
        analyzer.processPcmChunk(pcm70, pcm70.size)
        val low70 = spectrum

        assertTrue(low30[1] > low30[5] + 0.2f, "30 Гц должен выделять полосу 1, а не 5: ${low30.toList()}")
        assertTrue(low70[4] > low70[1] + 0.2f, "70 Гц должен выделять полосу 4, а не 1: ${low70.toList()}")
        val distinctLow = (0..4).map { low30[it] }.toSet()
        assertTrue(distinctLow.size >= 4, "Низкие полосы не должны дублировать один бин: $distinctLow")
    }

    @Test
    fun testPerBandAgcRevealsKicksOverSteadyBackground() {
        var simTime = 1000L
        val viz = TwentyFiveBandVisualizer(timeProvider = { simTime })

        // Ровный фон 0.55 во всех полосах и удары до 0.75 в полосе 3 каждые ~500 мс
        var kickLevel = 0f
        var gapLevel = 1f
        repeat(200) { step ->
            simTime += 23L
            val isKick = step % 22 == 0
            val spectrum = FloatArray(25) { 0.55f }
            if (isKick) spectrum[3] = 0.75f
            viz.updateSpectrum(spectrum)
            val frame = viz.advanceFrame(0.023f, isPlaying = true)
            if (step > 150) {
                if (step % 22 in 0..4) kickLevel = max(kickLevel, frame.levels[3])
                if (step % 22 == 21) gapLevel = min(gapLevel, frame.levels[3])
            }
        }
        assertTrue(kickLevel > 0.55f, "Удар должен подниматься высоко: $kickLevel")
        assertTrue(kickLevel - gapLevel > 0.15f, "Между ударами столбик должен опускаться: kick=$kickLevel gap=$gapLevel")
    }

    @Test
    fun testAutoLatencyFollowsMeasuredOutputBuffer() {
        AudioVisualizer.latencyCompensationMs = AudioVisualizer.LATENCY_AUTO
        repeat(60) { AudioVisualizer.reportOutputBufferedMs(200f) }
        assertEquals(200L + AudioVisualizer.DEVICE_OUTPUT_LATENCY_MS, AudioVisualizer.effectiveLatencyMs)

        AudioVisualizer.latencyCompensationMs = 90L
        assertEquals(90L, AudioVisualizer.effectiveLatencyMs, "Ручное значение имеет приоритет над измерением")
    }
}
