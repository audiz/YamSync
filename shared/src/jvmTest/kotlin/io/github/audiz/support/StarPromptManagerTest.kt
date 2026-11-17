package io.github.audiz.support

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarPromptManagerTest {
    @Test
    fun concurrentEventsAndRestartDoNotDuplicateDayOrOffer() = runBlocking {
        var saved: String? = Json.encodeToString(StarPromptState(activeDaysCount = 2, lastActiveDate = "2026-11-16"))
        fun manager() = StarPromptManager(read = { saved }, write = { saved = it }, today = { "2026-11-17" })
        val first = manager()
        List(10) { async { first.recordActiveDay() } }.awaitAll()
        assertEquals(3, first.state.value?.activeDaysCount)
        val claims = List(10) { async { first.claimShow() } }.awaitAll()
        assertEquals(1, claims.count { it })
        val restarted = manager()
        restarted.recordActiveDay()
        assertEquals(3, restarted.state.value?.activeDaysCount)
        assertFalse(restarted.claimShow())
        restarted.complete()
        val completed = manager()
        completed.recordActiveDay()
        assertTrue(completed.state.value?.completed == true)
    }

    @Test
    fun unreadableSavedPreferenceDoesNotRestartOffers() = runBlocking {
        val manager = StarPromptManager(read = { "broken preference" }, write = {}, today = { "2026-11-17" })
        manager.recordActiveDay()
        assertTrue(manager.state.value?.completed == true)
        assertFalse(manager.claimShow())
    }
}
