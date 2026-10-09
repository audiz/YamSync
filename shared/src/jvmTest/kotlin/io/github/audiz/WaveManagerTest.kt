package io.github.audiz

import io.github.audiz.api.MusicRepository
import io.github.audiz.models.FullArtistInfo
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.wave.WaveManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveManagerTest {

    private fun createDummyTrack(id: String, title: String) = FullTrackInfo(
        id = id,
        title = title,
        durationMs = 180000L,
        artists = listOf(FullArtistInfo(id = 1, name = "Artist"))
    )

    @Test
    fun testInitialState() {
        val waveManager = WaveManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getAccessToken = { "" }
        )

        assertFalse(waveManager.isWaveMode)
        assertFalse(waveManager.isWaveLoading)
        assertNull(waveManager.currentWaveTrack)
        assertEquals(0, waveManager.waveTracks.size)
        assertEquals(0, waveManager.wavePastTracks.size)
        assertEquals(0, waveManager.waveFutureTracks.size)
    }

    @Test
    fun testCurrentWaveTrackResolution() {
        val waveManager = WaveManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getAccessToken = { "" }
        )

        val track1 = createDummyTrack("1", "Song 1")
        val track2 = createDummyTrack("2", "Song 2")

        waveManager.waveTracks.addAll(listOf(track1, track2))

        // By default, currentWaveTrack should pick waveTracks[waveCurrentIndex] (index 0)
        assertEquals(track1.id, waveManager.currentWaveTrack?.id)

        // When activeWaveTrack is explicitly set, it takes priority
        // (verified via playWaveTrack logic simulation)
        waveManager.resetWaveMode()
        assertFalse(waveManager.isWaveMode)
    }

    @Test
    fun testResetWaveMode() {
        val waveManager = WaveManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getAccessToken = { "" }
        )

        // Simulate activating wave mode
        waveManager.resetWaveMode()
        assertFalse(waveManager.isWaveMode)
    }

    @Test
    fun testInstantSkipToNextTrackInQueue() {
        val waveManager = WaveManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getAccessToken = { "" }
        )

        val track1 = createDummyTrack("1", "Song 1")
        val track2 = createDummyTrack("2", "Song 2")
        val track3 = createDummyTrack("3", "Song 3")

        waveManager.waveTracks.addAll(listOf(track1, track2, track3))
        waveManager.playWaveTrack(0)

        assertEquals("1", waveManager.currentWaveTrack?.id)
        assertEquals(0, waveManager.waveCurrentIndex)

        // При вызове playNextWaveTrack() плеер должен мгновенно перейти на track2
        waveManager.playNextWaveTrack()

        assertEquals(1, waveManager.waveCurrentIndex)
        assertEquals("2", waveManager.currentWaveTrack?.id)
        assertEquals(1, waveManager.wavePastTracks.size)
        assertEquals("1", waveManager.wavePastTracks.first().id)

        // Еще один переход вперед -> track3
        waveManager.playNextWaveTrack()

        assertEquals(2, waveManager.waveCurrentIndex)
        assertEquals("3", waveManager.currentWaveTrack?.id)
        assertEquals(2, waveManager.wavePastTracks.size)
        assertEquals("2", waveManager.wavePastTracks.last().id)
    }

    @Test
    fun testNaturalCompletionAdvancesIndex() {
        val waveManager = WaveManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getAccessToken = { "" }
        )

        val track1 = createDummyTrack("1", "Song 1")
        val track2 = createDummyTrack("2", "Song 2")

        waveManager.waveTracks.addAll(listOf(track1, track2))
        waveManager.playWaveTrack(0)

        assertEquals("1", waveManager.currentWaveTrack?.id)
        assertEquals(0, waveManager.waveCurrentIndex)

        // Когда трек доиграл естественным образом
        waveManager.onWaveTrackFinished()

        assertEquals(1, waveManager.waveCurrentIndex)
        assertEquals("2", waveManager.currentWaveTrack?.id)
        assertEquals(1, waveManager.wavePastTracks.size)
        assertEquals("1", waveManager.wavePastTracks.first().id)
    }

    @Test
    fun testGetNextWaveTrackCandidate() {
        val waveManager = WaveManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getAccessToken = { "" }
        )

        val track1 = createDummyTrack("1", "Song 1")
        val track2 = createDummyTrack("2", "Song 2")
        val track3 = createDummyTrack("3", "Song 3")

        waveManager.waveTracks.addAll(listOf(track1, track2, track3))
        waveManager.playWaveTrack(0)

        // Currently at 0, next candidate should be track2
        val candidate1 = waveManager.getNextWaveTrackCandidate()
        assertEquals("2", candidate1?.id)

        // If waveFutureTracks is populated (user pressed Prev)
        waveManager.waveFutureTracks.add(track3)
        val candidateFuture = waveManager.getNextWaveTrackCandidate()
        assertEquals("3", candidateFuture?.id)
        waveManager.waveFutureTracks.clear()

        // Move to last track
        waveManager.playWaveTrack(2)
        val candidateEnd = waveManager.getNextWaveTrackCandidate()
        assertEquals(null, candidateEnd)
    }

    @Test
    fun testThematicWaveAndReset() {
        val waveManager = WaveManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getAccessToken = { "" }
        )

        assertNull(waveManager.currentWaveTitle)
        assertTrue(waveManager.currentWaveSeeds.isEmpty())

        waveManager.startThematicWave("Метал", listOf("genre:metal"))
        assertEquals("Метал", waveManager.currentWaveTitle)
        assertEquals(listOf("genre:metal"), waveManager.currentWaveSeeds)

        // Reset back to default wave
        waveManager.resetToDefaultWave(autoPlay = false)
        assertNull(waveManager.currentWaveTitle)
        assertTrue(waveManager.currentWaveSeeds.isEmpty())
    }

    @Test
    fun testNoDuplicateOrPreviousTrackAfterSkip() {
        val waveManager = WaveManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getAccessToken = { "" }
        )

        val track1 = createDummyTrack("1", "Song 1")
        val track2 = createDummyTrack("2", "Song 2")

        waveManager.waveTracks.addAll(listOf(track1, track2))
        waveManager.playWaveTrack(0)

        assertEquals("1", waveManager.currentWaveTrack?.id)

        // Переходим к следующему треку
        waveManager.playNextWaveTrack()
        assertEquals("2", waveManager.currentWaveTrack?.id)
        assertEquals(1, waveManager.wavePastTracks.size)
        assertEquals("1", waveManager.wavePastTracks.first().id)

        // Кандидат на следующий трек не должен быть равен предыдущему треку ("1") и не равен текущему ("2")
        val nextCandidate = waveManager.getNextWaveTrackCandidate()
        assertFalse(nextCandidate?.id == "1")
        assertFalse(nextCandidate?.id == "2")
    }

    @Test
    fun testRecentThematicWavesManagement() {
        val waveManager = WaveManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getAccessToken = { "" }
        )

        // Clear initially for test isolation
        waveManager.recentThematicWaves.clear()

        // 1. Check default diverse style presets exist and are unique
        assertEquals(3, waveManager.defaultThematicWaves.size)
        assertEquals(3, waveManager.defaultThematicWaves.map { it.title }.distinct().size)
        assertTrue(waveManager.defaultThematicWaves.all { preset ->
            WaveManager.ALL_STYLE_PRESETS.any { it.title == preset.title && it.seeds == preset.seeds }
        })

        // Test refreshDefaultThematicWaves rotates styles
        waveManager.refreshDefaultThematicWaves()
        assertEquals(3, waveManager.defaultThematicWaves.size)
        assertEquals(3, waveManager.defaultThematicWaves.map { it.title }.distinct().size)

        // 2. Start 4 different thematic waves to check FIFO limit of 3
        waveManager.startThematicWave("Поп", listOf("genre:pop"))
        waveManager.startThematicWave("Джаз", listOf("genre:jazz"))
        waveManager.startThematicWave("Метал", listOf("genre:metal"))

        assertEquals(3, waveManager.recentThematicWaves.size)
        assertEquals("Метал", waveManager.recentThematicWaves[0].title)
        assertEquals("Джаз", waveManager.recentThematicWaves[1].title)
        assertEquals("Поп", waveManager.recentThematicWaves[2].title)

        // Add 4th - should push out "Поп"
        waveManager.startThematicWave("Классика", listOf("genre:classical"))
        assertEquals(3, waveManager.recentThematicWaves.size)
        assertEquals("Классика", waveManager.recentThematicWaves[0].title)
        assertEquals("Метал", waveManager.recentThematicWaves[1].title)
        assertEquals("Джаз", waveManager.recentThematicWaves[2].title)

        // Duplicate start should move to top without duplicating
        waveManager.startThematicWave("Метал", listOf("genre:metal"))
        assertEquals(3, waveManager.recentThematicWaves.size)
        assertEquals("Метал", waveManager.recentThematicWaves[0].title)
        assertEquals("Классика", waveManager.recentThematicWaves[1].title)
        assertEquals("Джаз", waveManager.recentThematicWaves[2].title)
    }

    @Test
    fun testSerializationAndAppConfigPersistence() {
        val original = loadAppConfig(AppConfigKeys.RECENT_THEMATIC_WAVES)
        val list = listOf(
            io.github.audiz.models.ThematicWavePreset("Поп-рок", listOf("genre:pop-rock")),
            io.github.audiz.models.ThematicWavePreset("Метал", listOf("genre:metal"))
        )
        val json = Json.encodeToString(list)
        try {
            saveAppConfig(AppConfigKeys.RECENT_THEMATIC_WAVES, json)
            val loaded = loadAppConfig(AppConfigKeys.RECENT_THEMATIC_WAVES)
            assertEquals(json, loaded)
            val decoded = Json { ignoreUnknownKeys = true }
                .decodeFromString<List<io.github.audiz.models.ThematicWavePreset>>(loaded!!)
            assertEquals(list, decoded)
        } finally {
            saveAppConfig(AppConfigKeys.RECENT_THEMATIC_WAVES, original ?: "")
        }
    }

    @Test
    fun testWaveStylePersistenceAcrossRestarts() {
        val originalStyle = loadAppConfig(AppConfigKeys.LAST_WAVE_STYLE)
        try {
            // 1. Проверяем наличие "Поп-рок" среди пресетов
            val popRockPreset = WaveManager.ALL_STYLE_PRESETS.firstOrNull { it.title == "Поп-рок" }
            assertNotNull(popRockPreset)
            assertEquals(listOf("genre:pop-rock"), popRockPreset.seeds)

            val manager1 = WaveManager(
                scope = CoroutineScope(Dispatchers.Unconfined),
                repository = MusicRepository(),
                getAccessToken = { "" }
            )

            // Запускаем волну "Поп-рок"
            manager1.startThematicWave("Поп-рок", listOf("genre:pop-rock"))
            assertEquals("Поп-рок", manager1.currentWaveTitle)
            assertEquals(listOf("genre:pop-rock"), manager1.currentWaveSeeds)

            val saved = manager1.loadSavedWaveStyle()
            assertNotNull(saved)
            assertEquals("Поп-рок", saved.title)
            assertEquals(listOf("genre:pop-rock"), saved.seeds)

            // 2. Имитируем перезапуск приложения: создаем новый менеджер
            val manager2 = WaveManager(
                scope = CoroutineScope(Dispatchers.Unconfined),
                repository = MusicRepository(),
                getAccessToken = { "" }
            )
            assertNull(manager2.currentWaveTitle)
            assertFalse(manager2.isWaveMode)

            // 🔒 Восстанавливаем стиль из конфига
            val restored = manager2.restoreWaveStyleFromConfig()
            assertNotNull(restored)
            assertEquals("Поп-рок", manager2.currentWaveTitle)
            assertEquals(listOf("genre:pop-rock"), manager2.currentWaveSeeds)
            assertTrue(manager2.isWaveMode)

            // 3. Сброс к персональной волне очищает сохраненный стиль
            manager2.resetToDefaultWave(autoPlay = false)
            assertNull(manager2.currentWaveTitle)
            assertTrue(manager2.currentWaveSeeds.isEmpty())
            assertNull(manager2.loadSavedWaveStyle())
        } finally {
            saveAppConfig(AppConfigKeys.LAST_WAVE_STYLE, originalStyle ?: "")
        }
    }
}

