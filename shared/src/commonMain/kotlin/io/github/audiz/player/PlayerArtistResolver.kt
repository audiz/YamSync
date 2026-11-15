package io.github.audiz.player

import io.github.audiz.models.ArtistInfo
import io.github.audiz.models.FullArtistInfo
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.isLocalTrackId

/** Восстанавливает ID исполнителя, если плеер получил только имя из локального файла. */
internal suspend fun resolvePlayerArtist(
    track: FullTrackInfo,
    fallbackName: String,
    getTrackDetails: suspend (String) -> FullTrackInfo?,
    searchArtists: suspend (String) -> List<ArtistInfo>
): FullArtistInfo? {
    track.artists.firstOrNull { it.id > 0 }?.let { return it }
    if (track.id.isNotBlank() && !isLocalTrackId(track.id)) {
        getTrackDetails(track.id)?.artists?.firstOrNull { it.id > 0 }?.let { return it }
    }
    val name = track.artists.firstOrNull { it.name.isNotBlank() }?.name?.trim()
        ?: fallbackName.trim()
    if (name.isBlank() || name.equals("Unknown Artist", ignoreCase = true)) return null
    val artist = searchArtists(name).firstOrNull {
        it.name.trim().equals(name, ignoreCase = true) && (it.id.toLongOrNull() ?: 0L) > 0
    } ?: return null
    return FullArtistInfo(id = artist.id.toLong(), name = artist.name)
}
