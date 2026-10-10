package io.github.audiz.sync

import io.github.audiz.currentTimeMillis
import io.github.audiz.models.*

/**
 * 🔀 Движок вычисления расхождений и слияния медиатек в стиле «Merge Request».
 * Включает умное сопоставление треков с устойчивостью к различиям в именах файлов, слагах и тегах.
 */
object YamSyncDiffEngine {

    /**
     * Сравнить локальный и удаленный манифесты, сформировать список различий по плейлистам.
     */
    fun calculateDiff(
        localManifest: YamSyncManifest,
        remoteManifest: YamSyncManifest
    ): List<YamSyncPlaylistDiff> {
        val result = mutableListOf<YamSyncPlaylistDiff>()

        val localMap = localManifest.playlists.associateBy { normalizeTitle(it.title) }.toMutableMap()
        val remoteMap = remoteManifest.playlists.associateBy { normalizeTitle(it.title) }.toMutableMap()

        // Все уникальные названия плейлистов
        val allTitles = (localMap.keys + remoteMap.keys).toList()

        for (titleKey in allTitles) {
            val localPl = localMap[titleKey]
            val remotePl = remoteMap[titleKey]

            when {
                localPl != null && remotePl == null -> {
                    result.add(
                        YamSyncPlaylistDiff(
                            playlistId = localPl.id,
                            title = localPl.title,
                            state = YamSyncDiffState.NEW_LOCAL,
                            localPlaylist = localPl,
                            remotePlaylist = null,
                            localOnlyTracks = localPl.tracks,
                            remoteOnlyTracks = emptyList(),
                            commonTracks = emptyList(),
                            resolution = YamSyncResolution.KEEP_LOCAL
                        )
                    )
                }
                localPl == null && remotePl != null -> {
                    // Если на удаленном устройстве в плейлисте 0 реальных файлов на диске, не навязываем его
                    val hasFilesOnRemote = remotePl.tracks.isEmpty() || remotePl.tracks.any { remTrack ->
                        remoteManifest.availableFiles.any { areTracksMatching(it, remTrack) }
                    }
                    val defaultResolution = if (hasFilesOnRemote) YamSyncResolution.TAKE_REMOTE else YamSyncResolution.KEEP_LOCAL
                    result.add(
                        YamSyncPlaylistDiff(
                            playlistId = remotePl.id,
                            title = remotePl.title,
                            state = YamSyncDiffState.NEW_REMOTE,
                            localPlaylist = null,
                            remotePlaylist = remotePl,
                            localOnlyTracks = emptyList(),
                            remoteOnlyTracks = remotePl.tracks,
                            commonTracks = emptyList(),
                            resolution = defaultResolution
                        )
                    )
                }
                localPl != null && remotePl != null -> {
                    val unmatchedRemote = remotePl.tracks.toMutableList()
                    val matchedLocal = mutableListOf<YamSyncTrack>()
                    val commonTracks = mutableListOf<YamSyncTrack>()

                    for (localTrack in localPl.tracks) {
                        val matchIndex = unmatchedRemote.indexOfFirst { remoteTrack ->
                            areTracksMatching(localTrack, remoteTrack)
                        }
                        if (matchIndex >= 0) {
                            val remoteTrack = unmatchedRemote.removeAt(matchIndex)
                            val best = pickBestTrackRepresentation(localTrack, remoteTrack)
                            commonTracks.add(best)
                            matchedLocal.add(localTrack)
                        }
                    }

                    val localOnlyTracks = localPl.tracks.filter { it !in matchedLocal }
                    val remoteOnlyTracks = unmatchedRemote.toList()

                    val isIdentical = localOnlyTracks.isEmpty() && remoteOnlyTracks.isEmpty()
                    val state = if (isIdentical) YamSyncDiffState.IDENTICAL else YamSyncDiffState.MODIFIED

                    result.add(
                        YamSyncPlaylistDiff(
                            playlistId = localPl.id,
                            title = localPl.title,
                            state = state,
                            localPlaylist = localPl,
                            remotePlaylist = remotePl,
                            localOnlyTracks = localOnlyTracks,
                            remoteOnlyTracks = remoteOnlyTracks,
                            commonTracks = commonTracks,
                            resolution = if (isIdentical) YamSyncResolution.KEEP_LOCAL else YamSyncResolution.MERGE_ALL
                        )
                    )
                }
            }
        }

        // Сортировка: сначала измененные, потом новые, потом идентичные
        return result.sortedWith(
            compareBy<YamSyncPlaylistDiff> {
                when (it.state) {
                    YamSyncDiffState.MODIFIED -> 0
                    YamSyncDiffState.NEW_REMOTE -> 1
                    YamSyncDiffState.NEW_LOCAL -> 2
                    YamSyncDiffState.IDENTICAL -> 3
                }
            }.thenBy { it.title.lowercase() }
        )
    }

    /**
     * Выполнить слияние на основе выбранных пользователем резолюций.
     * Возвращает список итоговых плейлистов, готовых к сохранению.
     */
    fun mergePlaylists(diffs: List<YamSyncPlaylistDiff>): List<YamSyncPlaylist> {
        val mergedList = mutableListOf<YamSyncPlaylist>()

        for (diff in diffs) {
            when (diff.resolution) {
                YamSyncResolution.KEEP_LOCAL -> {
                    diff.localPlaylist?.let { mergedList.add(it) }
                }
                YamSyncResolution.TAKE_REMOTE -> {
                    diff.remotePlaylist?.let { mergedList.add(it) }
                }
                YamSyncResolution.MERGE_ALL -> {
                    val local = diff.localPlaylist
                    val remote = diff.remotePlaylist

                    when {
                        local != null && remote != null -> {
                            val combinedTracks = mutableListOf<YamSyncTrack>()

                            // Сначала добавляем локальные треки с очисткой дубликатов
                            for (track in local.tracks) {
                                if (combinedTracks.none { areTracksMatching(it, track) }) {
                                    combinedTracks.add(track)
                                }
                            }

                            // Затем добавляем уникальные треки с удаленного устройства
                            for (remoteTrack in remote.tracks) {
                                val existingIndex = combinedTracks.indexOfFirst { areTracksMatching(it, remoteTrack) }
                                if (existingIndex >= 0) {
                                    // Обновляем метаданные на лучшую версию
                                    val best = pickBestTrackRepresentation(combinedTracks[existingIndex], remoteTrack)
                                    combinedTracks[existingIndex] = best
                                } else {
                                    combinedTracks.add(remoteTrack)
                                }
                            }

                            mergedList.add(
                                local.copy(
                                    tracks = combinedTracks,
                                    updatedAt = currentTimeMillis()
                                )
                            )
                        }
                        local != null -> {
                            val distinct = mutableListOf<YamSyncTrack>()
                            for (t in local.tracks) {
                                if (distinct.none { areTracksMatching(it, t) }) distinct.add(t)
                            }
                            mergedList.add(local.copy(tracks = distinct))
                        }
                        remote != null -> {
                            val distinct = mutableListOf<YamSyncTrack>()
                            for (t in remote.tracks) {
                                if (distinct.none { areTracksMatching(it, t) }) distinct.add(t)
                            }
                            mergedList.add(remote.copy(tracks = distinct))
                        }
                    }
                }
            }
        }

        return mergedList
    }

    /**
     * Найти аудиофайлы, которые входят в итоговые плейлисты, но отсутствуют на текущем устройстве.
     */
    fun findMissingFiles(
        mergedPlaylists: List<YamSyncPlaylist>,
        localAvailableFiles: List<YamSyncTrack>,
        remoteAvailableFiles: List<YamSyncTrack>
    ): List<YamSyncTrack> {
        val missingTracks = mutableListOf<YamSyncTrack>()
        val seenKeys = mutableSetOf<String>()

        for (pl in mergedPlaylists) {
            for (track in pl.tracks) {
                val isLocal = localAvailableFiles.any { areTracksMatching(it, track) }
                if (!isLocal && seenKeys.add(track.matchKey)) {
                    // Файл отсутствует у нас. Скачать можно только то, что физически есть на удаленном устройстве!
                    val remoteTrack = remoteAvailableFiles.firstOrNull { areTracksMatching(it, track) }
                    if (remoteTrack != null) {
                        missingTracks.add(remoteTrack)
                    }
                }
            }
        }

        return missingTracks.sortedBy { it.title.lowercase() }
    }

    /**
     * Проверяет, относятся ли два объекта [YamSyncTrack] к одному и тому же аудиотреку.
     * Учитывает вариации названий файлов, слагов с дефисами (Keep-Shelly-In-Athens---Nobody),
     * различные дефисы и совпадения ключевых токенов.
     */
    fun areTracksMatching(a: YamSyncTrack, b: YamSyncTrack): Boolean {
        // 1. Быстрое совпадение по хешу или точному ключу
        if (a.checksum.isNotBlank() && b.checksum.isNotBlank() && a.checksum.equals(b.checksum, ignoreCase = true)) {
            return true
        }
        if (a.matchKey.isNotBlank() && a.matchKey.equals(b.matchKey, ignoreCase = true)) {
            return true
        }

        // 2. Точное совпадение имени файла (без расширения и без регистра)
        val fnA = a.fileName.substringBeforeLast('.').trim().lowercase()
        val fnB = b.fileName.substringBeforeLast('.').trim().lowercase()
        if (fnA.isNotBlank() && fnA == fnB) {
            return true
        }

        // 3. Токены исполнителя и названия
        val fullA = "${a.artist} ${a.title} $fnA"
        val fullB = "${b.artist} ${b.title} $fnB"
        val tokensA = normalizeTokens(fullA)
        val tokensB = normalizeTokens(fullB)

        if (tokensA.isNotEmpty() && tokensB.isNotEmpty()) {
            if (tokensA == tokensB) return true

            val intersection = tokensA.intersect(tokensB)
            val union = tokensA.union(tokensB)
            val jaccard = intersection.size.toDouble() / union.size.toDouble()

            // Высокое подобие токенов (от 0.60)
            if (jaccard >= 0.60) {
                if (a.durationMs > 0 && b.durationMs > 0) {
                    return kotlin.math.abs(a.durationMs - b.durationMs) <= 5000
                }
                return true
            }

            // Одно является подмножеством другого (например, заголовок без указания исполнителя в тегах)
            val minTokens = minOf(tokensA.size, tokensB.size)
            if (minTokens >= 2 && intersection.size >= minTokens) {
                if (a.durationMs > 0 && b.durationMs > 0) {
                    return kotlin.math.abs(a.durationMs - b.durationMs) <= 5000
                }
                val titleTokensA = normalizeTokens(a.title)
                val titleTokensB = normalizeTokens(b.title)
                if (titleTokensA.isNotEmpty() && titleTokensB.isNotEmpty()) {
                    val titleInter = titleTokensA.intersect(titleTokensB)
                    if (titleInter.size >= minOf(titleTokensA.size, titleTokensB.size)) {
                        return true
                    }
                }
                if (fnA.contains(fnB) || fnB.contains(fnA)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Извлечь набор значимых слов для сопоставления.
     * Удаляет расширения файлов, шумные слова и знаки препинания.
     */
    fun normalizeTokens(text: String): Set<String> {
        val clean = text.lowercase()
            .replace(Regex("""\.(mp3|m4a|flac|wav|aac|ogg)$"""), "")
            .replace(Regex("""[\-_—–/\\,.:;()\[\]{}'"`]"""), " ")

        val stopWords = setOf(
            "unknown", "artist", "track", "audio", "official", "video", "remastered", "remaster",
            "original", "mix", "edit", "version", "feat", "ft", "featuring", "flac", "mp3", "m4a",
            "320kbps", "kbps", "hq", "lq", "the", "a", "an", "of", "in", "on", "at", "by", "for", "with"
        )

        return clean.split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stopWords }
            .toSet()
    }

    /**
     * Выбрать вариант трека с более качественными и полными метаданными для плейлиста.
     */
    fun pickBestTrackRepresentation(local: YamSyncTrack, remote: YamSyncTrack): YamSyncTrack {
        val localScore = scoreTrackMetadata(local)
        val remoteScore = scoreTrackMetadata(remote)
        return if (localScore >= remoteScore) local else remote
    }

    private fun scoreTrackMetadata(track: YamSyncTrack): Int {
        var score = 0
        val artLower = track.artist.trim().lowercase()
        if (artLower.isNotBlank() && artLower != "unknown" && artLower != "unknown artist") score += 10
        val titleLower = track.title.trim().lowercase()
        if (titleLower.isNotBlank() && !titleLower.contains("---") && !titleLower.endsWith(".mp3") && !titleLower.endsWith(".m4a")) score += 10
        if (track.durationMs > 0) score += 5
        if (track.fileSize > 0) score += 2
        if (track.checksum.isNotBlank()) score += 3
        return score
    }

    internal fun normalizeTitle(title: String): String =
        title.trim().lowercase().replace(Regex("\\s+"), " ")
}
