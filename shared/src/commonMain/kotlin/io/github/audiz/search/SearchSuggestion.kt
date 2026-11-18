package io.github.audiz.search

import io.github.audiz.models.ArtistInfo
import io.github.audiz.models.TrackInfo
import io.github.audiz.models.TypedResult

enum class SearchSuggestionType(val label: String) {
    ARTIST("Артист"),
    TRACK("Трек"),
    QUERY("Запрос")
}

data class SearchSuggestion(
    val title: String,
    val type: SearchSuggestionType,
    val artist: ArtistInfo? = null,
    val track: TrackInfo? = null
)

internal fun buildSearchSuggestions(query: String, results: List<TypedResult>): List<SearchSuggestion> {
    val text = query.trim()
    if (text.isEmpty()) return emptyList()
    val seen = mutableSetOf<String>()
    val matches = results.asSequence().mapNotNull { result ->
        when (result.type) {
            "artist" -> result.artist?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }?.let {
                if (seen.add("artist:${it.id}")) SearchSuggestion(it.name, SearchSuggestionType.ARTIST, artist = it) else null
            }
            "track" -> result.track?.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }?.let {
                if (seen.add("track:${it.id}")) SearchSuggestion(it.title, SearchSuggestionType.TRACK, track = it) else null
            }
            else -> null
        }
    }.take(4).toList()
    return matches + SearchSuggestion(text, SearchSuggestionType.QUERY)
}
