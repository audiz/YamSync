package io.github.audiz.player

import io.github.audiz.AppConfigKeys
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.saveAppConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackQueueManagerTest {

    @AfterTest
    fun tearDown() {
        saveAppConfig(AppConfigKeys.PLAYER_SHUFFLE, "false")
    }

    private fun createDummyTrack(id: String, title: String = "Track $id"): FullTrackInfo {
        return FullTrackInfo(
            id = id,
            title = title,
            artists = emptyList(),
            albums = emptyList(),
            durationMs = 180000L
        )
    }

    @Test
    fun testGetRecentTrackIdsWithEmptyHistory() {
        val queueManager = PlaybackQueueManager()
        val recent = queueManager.getRecentTrackIds(activeId = "1")
        assertEquals(listOf("1"), recent)
    }

    @Test
    fun testGetRecentTrackIdsLimitToFive() {
        val queueManager = PlaybackQueueManager()
        queueManager.recordPlayed("1")
        queueManager.recordPlayed("2")
        queueManager.recordPlayed("3")
        queueManager.recordPlayed("4")
        queueManager.recordPlayed("5")

        // Active track is "6", history has 1..5
        val recent = queueManager.getRecentTrackIds(activeId = "6", limit = 5)
        assertEquals(listOf("6", "5", "4", "3", "2"), recent)
        assertFalse(recent.contains("1"))
    }

    @Test
    fun testGetRecentTrackIdsDeduplication() {
        val queueManager = PlaybackQueueManager()
        queueManager.recordPlayed("1")
        queueManager.recordPlayed("2")
        queueManager.recordPlayed("1")
        queueManager.recordPlayed("3")
        queueManager.recordPlayed("4")

        // Active track is "5"
        val recent = queueManager.getRecentTrackIds(activeId = "5", limit = 5)
        assertEquals(listOf("5", "4", "3", "1", "2"), recent)
    }

    @Test
    fun testGetRecentTrackIdsLocalPrefixMatching() {
        val queueManager = PlaybackQueueManager()
        queueManager.recordPlayed("local:/music/track1.mp3")
        queueManager.recordPlayed("/music/track2.mp3")

        val recent = queueManager.getRecentTrackIds(activeId = "/music/track1.mp3", limit = 5)
        assertEquals(listOf("/music/track1.mp3", "/music/track2.mp3"), recent)
    }

    @Test
    fun testShuffleCandidatesWhenTracksGreaterThanFive() {
        val queueManager = PlaybackQueueManager()
        val tracks = (1..10).map { createDummyTrack(it.toString()) }

        // Simulate playing tracks 1, 2, 3, 4
        queueManager.recordPlayed("1")
        queueManager.recordPlayed("2")
        queueManager.recordPlayed("3")
        queueManager.recordPlayed("4")

        // Active track is 5
        val candidates = queueManager.getShuffleCandidates(activeId = "5", tracks = tracks)

        // Candidates must ONLY be 6, 7, 8, 9, 10
        val candidateIds = candidates.map { it.id }.toSet()
        assertEquals(setOf("6", "7", "8", "9", "10"), candidateIds)
        assertFalse(candidateIds.contains("1"))
        assertFalse(candidateIds.contains("2"))
        assertFalse(candidateIds.contains("3"))
        assertFalse(candidateIds.contains("4"))
        assertFalse(candidateIds.contains("5"))
    }

    @Test
    fun testShuffleCandidatesWhenTracksLessThanOrEqualToFive() {
        val queueManager = PlaybackQueueManager()
        val tracks = (1..5).map { createDummyTrack(it.toString()) }

        // Simulate playing track 1, active is 2
        queueManager.recordPlayed("1")

        val candidates = queueManager.getShuffleCandidates(activeId = "2", tracks = tracks)

        // When size <= 5, only active track is excluded to prevent candidate starvation
        val candidateIds = candidates.map { it.id }.toSet()
        assertEquals(setOf("1", "3", "4", "5"), candidateIds)
        assertFalse(candidateIds.contains("2"))
    }

    @Test
    fun testShuffleCandidatesSingleTrack() {
        val queueManager = PlaybackQueueManager()
        val tracks = listOf(createDummyTrack("1"))

        val candidates = queueManager.getShuffleCandidates(activeId = "1", tracks = tracks)
        assertEquals(1, candidates.size)
        assertEquals("1", candidates.first().id)
    }

    @Test
    fun testFifoTrackReavailabilityInShuffle() {
        val queueManager = PlaybackQueueManager()
        queueManager.isShuffleEnabled = true
        val tracks = (1..7).map { createDummyTrack(it.toString()) }

        // Play 1, 2, 3, 4, 5
        queueManager.recordPlayed("1")
        queueManager.recordPlayed("2")
        queueManager.recordPlayed("3")
        queueManager.recordPlayed("4")
        queueManager.recordPlayed("5")

        // Active track is 6. Recent 5 are: 6, 5, 4, 3, 2.
        // Track 1 should be eligible again because it is not in the last 5!
        val candidates = queueManager.getShuffleCandidates(activeId = "6", tracks = tracks)
        val candidateIds = candidates.map { it.id }.toSet()
        assertEquals(setOf("1", "7"), candidateIds)
        assertTrue(candidateIds.contains("1"))
        assertTrue(candidateIds.contains("7"))
    }

    @Test
    fun testGetNextFromListWithShuffleRespectsRecentFive() {
        val queueManager = PlaybackQueueManager()
        queueManager.isShuffleEnabled = true
        val tracks = (1..8).map { createDummyTrack(it.toString()) }

        queueManager.recordPlayed("1")
        queueManager.recordPlayed("2")
        queueManager.recordPlayed("3")
        queueManager.recordPlayed("4")

        // Current track is 5
        val next = queueManager.getNextFromList(activeId = "5", tracks = tracks)
        val validIds = setOf("6", "7", "8")
        assertTrue(validIds.contains(next?.id), "Expected next track in $validIds, but was ${next?.id}")
    }

    @Test
    fun testShufflePersistenceAcrossInstances() {
        val original = io.github.audiz.loadAppConfig(io.github.audiz.AppConfigKeys.PLAYER_SHUFFLE)
        try {
            val qm1 = PlaybackQueueManager()
            qm1.isShuffleEnabled = true
            assertEquals(true, qm1.isShuffleEnabled)

            val qm2 = PlaybackQueueManager()
            assertEquals(true, qm2.isShuffleEnabled, "Shuffle state should be restored from app config")

            qm2.toggleShuffle()
            assertEquals(false, qm2.isShuffleEnabled)

            val qm3 = PlaybackQueueManager()
            assertEquals(false, qm3.isShuffleEnabled, "Shuffle toggle should be reflected in new instance")
        } finally {
            io.github.audiz.saveAppConfig(io.github.audiz.AppConfigKeys.PLAYER_SHUFFLE, original ?: "")
        }
    }
}
