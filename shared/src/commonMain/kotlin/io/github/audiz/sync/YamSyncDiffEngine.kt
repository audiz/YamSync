package io.github.audiz.sync

import io.github.audiz.currentTimeMillis
import io.github.audiz.models.*

/**
 * 🔀 Движок вычисления расхождений и слияния медиатек в стиле «Merge Request».
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

        val remoteAvailableKeys = remoteManifest.availableFiles.map { it.matchKey }.toSet()

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
                    val hasFilesOnRemote = remotePl.tracks.isEmpty() || remotePl.tracks.any { it.matchKey in remoteAvailableKeys }
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
                    val localKeys = localPl.tracks.map { it.matchKey }.toSet()
                    val remoteKeys = remotePl.tracks.map { it.matchKey }.toSet()

                    val commonKeys = localKeys.intersect(remoteKeys)
                    val localOnlyKeys = localKeys - commonKeys
                    val remoteOnlyKeys = remoteKeys - commonKeys

                    val localOnlyTracks = localPl.tracks.filter { it.matchKey in localOnlyKeys }
                    val remoteOnlyTracks = remotePl.tracks.filter { it.matchKey in remoteOnlyKeys }
                    val commonTracks = localPl.tracks.filter { it.matchKey in commonKeys }

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
                            val seenKeys = mutableSetOf<String>()
                            val seenFileNames = mutableSetOf<String>()

                            // Сначала добавляем локальные треки с очисткой дубликатов
                            for (track in local.tracks) {
                                val key = track.matchKey
                                val fn = track.fileName.trim().lowercase()
                                if (seenKeys.add(key) && seenFileNames.add(fn)) {
                                    combinedTracks.add(track)
                                }
                            }

                            // Затем добавляем уникальные треки с удаленного устройства
                            for (remoteTrack in remote.tracks) {
                                val key = remoteTrack.matchKey
                                val fn = remoteTrack.fileName.trim().lowercase()
                                if (seenKeys.add(key) && seenFileNames.add(fn)) {
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
                            val distinctTracks = local.tracks.distinctBy { it.matchKey }
                            mergedList.add(local.copy(tracks = distinctTracks))
                        }
                        remote != null -> {
                            val distinctTracks = remote.tracks.distinctBy { it.matchKey }
                            mergedList.add(remote.copy(tracks = distinctTracks))
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
        val localKeys = localAvailableFiles.map { it.matchKey }.toSet()
        val remoteMap = remoteAvailableFiles.associateBy { it.matchKey }

        val missingTracks = mutableListOf<YamSyncTrack>()
        val seenMissingKeys = mutableSetOf<String>()

        for (pl in mergedPlaylists) {
            for (track in pl.tracks) {
                if (track.matchKey !in localKeys && seenMissingKeys.add(track.matchKey)) {
                    // Файл отсутствует у нас. Скачать можно только то, что физически есть на удаленном устройстве!
                    val remoteTrack = remoteMap[track.matchKey]
                    if (remoteTrack != null) {
                        missingTracks.add(remoteTrack)
                    }
                }
            }
        }

        return missingTracks.sortedBy { it.title.lowercase() }
    }

    private fun normalizeTitle(title: String): String =
        title.trim().lowercase().replace(Regex("\\s+"), " ")
}
