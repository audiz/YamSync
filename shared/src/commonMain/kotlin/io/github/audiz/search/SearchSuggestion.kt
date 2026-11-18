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
    val track: TrackInfo? = null,
    val subtitle: String? = null
)

internal fun formatArtistSubtitle(artist: ArtistInfo): String? {
    val genres = artist.genres.take(2).joinToString(", ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        .takeIf { it.isNotBlank() }
    val tracks = artist.counts?.tracks?.takeIf { it > 0 }?.let { "$it треков" }
    val likes = artist.likesCount?.takeIf { it > 0 }?.let {
        when {
            it >= 1_000_000 -> "${(it / 100_000) / 10.0}M лайков"
            it >= 1_000 -> "${it / 1_000}K лайков"
            else -> "$it лайков"
        }
    }
    return listOfNotNull(genres, tracks, likes).joinToString(" • ").takeIf { it.isNotBlank() }
}

internal fun formatTrackSubtitle(track: TrackInfo): String? {
    return track.artists.map { it.name }.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
}

internal fun buildSearchSuggestions(query: String, results: List<TypedResult>): List<SearchSuggestion> {
    val text = query.trim()
    if (text.isEmpty()) return emptyList()
    val seen = mutableSetOf<String>()
    val matches = results.asSequence().mapNotNull { result ->
        when (result.type) {
            "artist" -> result.artist?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }?.let {
                if (seen.add("artist:${it.id}")) {
                    SearchSuggestion(
                        title = it.name,
                        type = SearchSuggestionType.ARTIST,
                        artist = it,
                        subtitle = formatArtistSubtitle(it)
                    )
                } else null
            }
            "track" -> result.track?.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }?.let {
                if (seen.add("track:${it.id}")) {
                    SearchSuggestion(
                        title = it.title,
                        type = SearchSuggestionType.TRACK,
                        track = it,
                        subtitle = formatTrackSubtitle(it)
                    )
                } else null
            }
            else -> null
        }
    }.take(4).toList()
    return matches + SearchSuggestion(text, SearchSuggestionType.QUERY)
}
