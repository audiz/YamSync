package io.github.audiz.search

import io.github.audiz.models.ArtistInfo
import io.github.audiz.models.TrackInfo
import io.github.audiz.models.TypedResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchSuggestionTest {
    @Test
    fun preservesResultOrderAndSelectionMetadata() {
        val artist = ArtistInfo("1", "Ария")
        val track = TrackInfo("2", "Беспечный ангел", 240_000)
        val suggestions = buildSearchSuggestions(" ар ", listOf(
            TypedResult("artist", artist = artist),
            TypedResult("track", track = track)
        ))

        assertEquals(listOf("Ария", "Беспечный ангел", "ар"), suggestions.map { it.title })
        assertEquals(artist, suggestions[0].artist)
        assertEquals(track, suggestions[1].track)
        assertEquals(SearchSuggestionType.QUERY, suggestions.last().type)
    }

    @Test
    fun ignoresUnsupportedAndIncompleteResultsAndDeduplicatesByTypeAndId() {
        val artist = ArtistInfo("1", "Same name")
        val track = TrackInfo("1", "Same name", 1)
        val suggestions = buildSearchSuggestions("same", listOf(
            TypedResult("album"),
            TypedResult("artist"),
            TypedResult("artist", artist = ArtistInfo("", "Missing ID")),
            TypedResult("track", track = TrackInfo("2", " ", 1)),
            TypedResult("artist", artist = artist),
            TypedResult("artist", artist = artist),
            TypedResult("track", track = track),
            TypedResult("track", track = track)
        ))

        assertEquals(listOf(SearchSuggestionType.ARTIST, SearchSuggestionType.TRACK, SearchSuggestionType.QUERY),
            suggestions.map { it.type })
    }

    @Test
    fun limitsDropdownToFourMatchesAndOneQuery() {
        val results = (1..20).map { TypedResult("artist", artist = ArtistInfo("$it", "Artist $it")) }
        val suggestions = buildSearchSuggestions("artist", results)

        assertEquals(5, suggestions.size)
        assertEquals("Artist 4", suggestions[3].title)
        assertEquals(SearchSuggestionType.QUERY, suggestions.last().type)
    }

    @Test
    fun keepsSearchAvailableWhenNoMatchesAreReturned() {
        assertEquals(listOf(SearchSuggestion("unknown", SearchSuggestionType.QUERY)),
            buildSearchSuggestions(" unknown ", emptyList()))
        assertTrue(buildSearchSuggestions("  ", emptyList()).isEmpty())
    }
}
