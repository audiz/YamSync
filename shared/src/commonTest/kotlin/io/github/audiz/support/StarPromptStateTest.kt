package io.github.audiz.support

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarPromptStateTest {
    @Test
    fun countsDistinctUsageDaysRatherThanLaunchesOrElapsedDays() {
        val first = StarPromptState().recordDay("2026-11-01")
        assertEquals(first, first.recordDay("2026-11-01"))
        val second = first.recordDay("2026-11-10")
        assertEquals(2, second.activeDaysCount)
        assertFalse(second.eligible)
        assertTrue(second.recordDay("2026-11-17").eligible)
    }

    @Test
    fun clockRollbackDoesNotCountPreviouslyUsedDaysAgain() {
        val state = StarPromptState().recordDay("2026-11-17")
        assertEquals(state, state.recordDay("2026-11-16").recordDay("2026-11-17"))
        assertEquals(2, state.recordDay("2026-11-18").activeDaysCount)
    }

    @Test
    fun reminderSurvivesRestartAndRequiresFourMoreActiveDays() {
        var state = StarPromptState(activeDaysCount = 3, lastActiveDate = "2026-11-03").shown()
        state = Json.decodeFromString<StarPromptState>(Json.encodeToString(state))
        assertEquals(1, state.shownCount)
        assertFalse(state.eligible)
        for (day in 4..6) {
            state = state.recordDay("2026-11-0$day")
            assertFalse(state.eligible)
        }
        state = state.recordDay("2026-11-07")
        assertTrue(state.eligible)
        state = state.shown()
        assertEquals(2, state.shownCount)
        assertFalse(state.copy(activeDaysCount = 100).eligible)
    }

    @Test
    fun completionPermanentlyDisablesOffers() {
        val completed = StarPromptState(activeDaysCount = 3, completed = true)
        assertFalse(completed.eligible)
        assertEquals(completed, completed.shown())
        assertFalse(completed.recordDay("2026-11-17").eligible)
    }

    @Test
    fun cannotReserveOfferBeforeThirdDay() {
        val state = StarPromptState(activeDaysCount = 2)
        assertEquals(state, state.shown())
    }
}
