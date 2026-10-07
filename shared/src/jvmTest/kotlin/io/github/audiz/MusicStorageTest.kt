package io.github.audiz

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.io.File

class MusicStorageTest {

    @Test
    fun testScanDownloadedTracks() {
        val defaultDir = getDefaultMusicDir()
        println("Testing scan on default directory: $defaultDir")
        val tracks = scanDownloadedTracks(defaultDir)
        println("Found ${tracks.size} tracks from disk:")
        tracks.forEachIndexed { i, track ->
            val artist = track.artists.firstOrNull()?.name ?: "Unknown"
            val album = track.albums.firstOrNull()?.title ?: ""
            println("  #${i + 1}: $artist — ${track.title} [${track.durationMs}ms] ($album) id=${track.id}")
        }
        if (File(defaultDir).exists()) {
            assertTrue(tracks.isNotEmpty(), "Tracks should not be empty if defaultDir exists")
            assertTrue(tracks.all { it.id.startsWith("local:") }, "All tracks should have local: prefix")
            assertTrue(tracks.all { it.title.isNotBlank() }, "All tracks should have non-blank title")
        }
    }

    @Test
    fun testTrackSavingAndPlaylistCache() {
        val tempDir = java.nio.file.Files.createTempDirectory("music_storage_test").toFile()
        try {
            val artist = "Test Artist"
            val title = "Test Song"
            val fileName = "Test Artist — Test Song.mp3"
            val dummyBytes = "ID3dummycontent".toByteArray()

            // 1. Save track file
            saveTrackFile(tempDir.absolutePath, artist, fileName, dummyBytes)
            assertTrue(trackFileExists(tempDir.absolutePath, artist, fileName), "Track file should exist")

            val expectedFile = File(File(tempDir, "Test Artist"), fileName)
            assertTrue(localFileExists(expectedFile.absolutePath), "localFileExists should be true")

            // 2. Scan tracks
            val scanned = scanDownloadedTracks(tempDir.absolutePath)
            assertTrue(scanned.isNotEmpty(), "Scanned tracks should contain the saved file")
            assertTrue(scanned.any { it.title == title && it.artists.firstOrNull()?.name == artist })

            // 3. Playlist cache
            val testTracks = scanned
            val playlistName = "My Favorites"
            savePlaylistTracksCache(tempDir.absolutePath, playlistName, testTracks)
            val loadedTracks = loadPlaylistTracksCache(tempDir.absolutePath, playlistName)
            assertTrue(loadedTracks.size == testTracks.size, "Loaded cache should have same count")
            assertTrue(loadedTracks.firstOrNull()?.title == title, "Loaded cache track should match")

            // 4. Personal playlist cache
            val personalPlaylists = listOf(
                io.github.audiz.models.PersonalPlaylistItemData(
                    playlist = io.github.audiz.models.PersonalPlaylistInfo(
                        title = "Daily Mix",
                        uid = 123L,
                        kind = 3L
                    ),
                    playlistType = "playlist"
                )
            )
            savePersonalPlaylistsCache(tempDir.absolutePath, personalPlaylists)
            val loadedPersonal = loadPersonalPlaylistsCache(tempDir.absolutePath)
            assertTrue(loadedPersonal.size == 1, "Personal playlist cache should have 1 item")
            assertTrue(loadedPersonal.first().playlist?.title == "Daily Mix")

            // 5. Delete track
            val deleted = deleteTrackFile(tempDir.absolutePath, artist, title, expectedFile.absolutePath)
            assertTrue(deleted, "Track should be deleted")
            assertTrue(!trackFileExists(tempDir.absolutePath, artist, fileName), "Track should no longer exist")

            // 6. Clear playlists cache
            val clearedCount = clearPlaylistsCache(tempDir.absolutePath)
            assertTrue(clearedCount >= 1, "At least one cache file should be cleared")
            val cacheAfter = loadPlaylistTracksCache(tempDir.absolutePath, playlistName)
            assertTrue(cacheAfter.isEmpty(), "Cache should be empty after clear")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLocalPlaylistsAndM3u8Export() {
        val tempDir = java.nio.file.Files.createTempDirectory("local_playlist_test").toFile()
        try {
            val artist = "Rock Band"
            val title = "Awesome Track"
            val fileName = "Rock Band — Awesome Track.mp3"
            val dummyBytes = "ID3dummycontent".toByteArray()

            saveTrackFile(tempDir.absolutePath, artist, fileName, dummyBytes)
            val trackFile = File(File(tempDir, artist), fileName)
            assertTrue(trackFile.exists(), "Track file should exist")

            // 1. Get tracks from local paths
            val tracks = getTracksFromLocalPaths(listOf(trackFile.absolutePath))
            assertTrue(tracks.size == 1, "Should find 1 track")
            assertTrue(tracks[0].title == title)
            assertTrue(tracks[0].artists.first().name == artist)

            // 2. Save & Load local playlists
            val playlist = io.github.audiz.models.LocalPlaylist(
                id = "pl-1",
                title = "Road Trip",
                description = "For driving",
                createdAt = 1000L,
                updatedAt = 1000L,
                trackPaths = listOf(trackFile.absolutePath)
            )
            saveLocalPlaylists(tempDir.absolutePath, listOf(playlist))
            val loaded = loadLocalPlaylists(tempDir.absolutePath)
            assertTrue(loaded.size == 1, "Should load 1 local playlist")
            assertTrue(loaded[0].title == "Road Trip")
            assertTrue(loaded[0].trackPaths.size == 1)

            // 3. Export to M3U8
            val m3u8Path = exportPlaylistToM3u8(tempDir.absolutePath, loaded[0], tracks)
            assertTrue(m3u8Path.isNotBlank(), "M3U8 path should not be blank")
            val m3u8File = File(m3u8Path)
            assertTrue(m3u8File.exists(), "M3U8 file should exist")
            val content = m3u8File.readText()
            assertTrue(content.contains("#EXTM3U"), "Should contain #EXTM3U header")
            assertTrue(content.contains("#PLAYLIST:Road Trip"), "Should contain playlist name")
            assertTrue(content.contains(trackFile.absolutePath), "Should contain track file path")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testYandexPlaylistDiffJsonAndModels() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

        // 1. PlaylistInfo with revision
        val playlist = io.github.audiz.models.PlaylistInfo(
            uid = 123456L,
            title = "Cloud Hits",
            description = "My best hits",
            trackCount = 10,
            kind = 1001L,
            revision = 5
        )
        val encodedPlaylist = json.encodeToString(io.github.audiz.models.PlaylistInfo.serializer(), playlist)
        assertTrue(encodedPlaylist.contains("\"revision\":5"), "JSON should contain revision")
        val decodedPlaylist = json.decodeFromString(io.github.audiz.models.PlaylistInfo.serializer(), encodedPlaylist)
        assertTrue(decodedPlaylist.revision == 5, "Decoded playlist should have revision 5")

        // 2. YandexPlaylistDetails with tracks
        val details = io.github.audiz.models.YandexPlaylistDetails(
            uid = 123456L,
            kind = 1001L,
            title = "Cloud Hits",
            revision = 5,
            trackCount = 2,
            tracks = listOf(
                io.github.audiz.models.YandexPlaylistTrackItem(id = "111", albumId = 222L, title = "Song A", artist = "Artist A"),
                io.github.audiz.models.YandexPlaylistTrackItem(id = "333", albumId = 444L, title = "Song B", artist = "Artist B")
            )
        )
        val encodedDetails = json.encodeToString(io.github.audiz.models.YandexPlaylistDetails.serializer(), details)
        val decodedDetails = json.decodeFromString(io.github.audiz.models.YandexPlaylistDetails.serializer(), encodedDetails)
        assertTrue(decodedDetails.tracks.size == 2, "Should decode 2 tracks")
        assertTrue(decodedDetails.tracks[0].id == "111", "First track id should be 111")
        assertTrue(decodedDetails.tracks[1].artist == "Artist B", "Second track artist should be Artist B")

        // 3. Diff operations syntax validation
        val insertDiff = """[{"op": "insert", "at": 0, "tracks": [{"id": 111, "albumId": 222}]}]"""
        val deleteDiff = """[{"op": "delete", "from": 0, "to": 1}]"""
        val parsedInsert = json.parseToJsonElement(insertDiff)
        val parsedDelete = json.parseToJsonElement(deleteDiff)
        assertTrue(parsedInsert is kotlinx.serialization.json.JsonArray, "Insert diff should be JSON array")
        assertTrue(parsedDelete is kotlinx.serialization.json.JsonArray, "Delete diff should be JSON array")
    }

    @Test
    fun testYandexPlaylistRenameAndCacheUpdate() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "yandex_test_rename_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val initial = io.github.audiz.models.PlaylistInfo(
                uid = 99999L,
                title = "Original Name",
                description = "Custom description",
                trackCount = 15,
                kind = 1002L,
                revision = 3,
                coverUri = "avatars.yandex.net/get-music-content/123/%%"
            )
            saveUserPlaylistsCache(tempDir.absolutePath, listOf(initial))
            val loaded1 = loadUserPlaylistsCache(tempDir.absolutePath)
            assertEquals(1, loaded1.size)
            assertEquals("Original Name", loaded1[0].title)

            // Simulate rename with updated metadata and cache persistence
            val renamed = loaded1[0].copy(title = "Renamed Hits", revision = 4)
            saveUserPlaylistsCache(tempDir.absolutePath, listOf(renamed))
            val loaded2 = loadUserPlaylistsCache(tempDir.absolutePath)
            assertEquals(1, loaded2.size)
            assertEquals("Renamed Hits", loaded2[0].title)
            assertEquals(15, loaded2[0].trackCount)
            assertEquals("Custom description", loaded2[0].description)
            assertEquals(4, loaded2[0].revision)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testTrackPlaylistPresenceLogic() {
        val track1 = io.github.audiz.models.FullTrackInfo(
            id = "12345:678",
            realId = "/music/Band - Hit.mp3",
            title = "Hit",
            available = true,
            artists = listOf(io.github.audiz.models.FullArtistInfo(id = 1L, name = "Band"))
        )
        val track2 = io.github.audiz.models.FullTrackInfo(
            id = "99999",
            realId = null,
            title = "Solo Song",
            available = true,
            artists = listOf(io.github.audiz.models.FullArtistInfo(id = 2L, name = "Solo"))
        )

        val localPlaylist = io.github.audiz.models.LocalPlaylist(
            id = "local-1",
            title = "Favorites",
            description = "",
            createdAt = 0L,
            updatedAt = 0L,
            trackPaths = listOf("/music/Band - Hit.mp3")
        )

        val userPlaylistsTrackIds = mutableMapOf<Long, Set<String>>(
            1001L to setOf("99999", "88888"),
            1002L to setOf("12345")
        )

        val isTrackInLocal: (io.github.audiz.models.LocalPlaylist, io.github.audiz.models.FullTrackInfo) -> Boolean = { pl, tr ->
            val cleanPath = (tr.realId ?: tr.id).removePrefix("local:")
            val rawId = tr.id.removePrefix("local:")
            pl.trackPaths.any { path ->
                path == cleanPath || path == rawId || path == tr.id || (cleanPath.isNotBlank() && path.endsWith(cleanPath))
            }
        }

        val isTrackInAny: (io.github.audiz.models.FullTrackInfo) -> Boolean = { tr ->
            val inLocal = listOf(localPlaylist).any { isTrackInLocal(it, tr) }
            if (inLocal) true
            else {
                val cleanTrackId = (tr.realId?.ifBlank { null } ?: tr.id).removePrefix("local:").substringBefore(":")
                val rawTrackId = tr.id.removePrefix("local:").substringBefore(":")
                userPlaylistsTrackIds.values.any { ids ->
                    ids.contains(cleanTrackId) || ids.contains(rawTrackId) || ids.contains(tr.id)
                }
            }
        }

        val getCount: (io.github.audiz.models.FullTrackInfo) -> Int = { tr ->
            var count = listOf(localPlaylist).count { isTrackInLocal(it, tr) }
            val cleanTrackId = (tr.realId?.ifBlank { null } ?: tr.id).removePrefix("local:").substringBefore(":")
            val rawTrackId = tr.id.removePrefix("local:").substringBefore(":")
            for (ids in userPlaylistsTrackIds.values) {
                if (ids.contains(cleanTrackId) || ids.contains(rawTrackId) || ids.contains(tr.id)) {
                    count++
                }
            }
            count
        }

        assertTrue(isTrackInAny(track1), "track1 should be detected in playlist")
        assertEquals(2, getCount(track1), "track1 is in local-1 and cloud 1002L (by cleanId 12345)")

        assertTrue(isTrackInAny(track2), "track2 should be detected in cloud playlist 1001L")
        assertEquals(1, getCount(track2), "track2 is in 1 cloud playlist")

        val track3 = io.github.audiz.models.FullTrackInfo(
            id = "55555",
            realId = null,
            title = "Not in playlist",
            available = true
        )
        assertTrue(!isTrackInAny(track3), "track3 should not be in any playlist")
        assertEquals(0, getCount(track3), "track3 count should be 0")
    }

    @Test
    fun testYandexPlaylistRenameResponseParsing() {
        val rawJson = """{"owner":{"uid":23858391,"login":"audizx","name":"Artem","sex":"unknown","verified":false},"playlistUuid":"d8619525-d9e4-4aea-aebe-669579b9972d","available":true,"uid":23858391,"kind":1000,"title":"Electronic1","revision":21,"snapshot":25,"trackCount":14,"visibility":"public","collective":false,"created":"2026-09-26T05:55:13+00:00","modified":"2026-09-30T10:02:56+00:00","isBanner":false,"isPremiere":false,"durationMs":4000600,"cover":{"type":"mosaic","itemsUri":["avatars.yandex.net/get-music-content/5878680/38032d94.a.5287888-4/%%","avatars.yandex.net/get-music-content/4382806/28556188.a.13540795-1/%%","avatars.yandex.net/get-music-content/42108/6cbca21b.a.406471-1/%%","avatars.yandex.net/get-music-content/19035207/01dde9f5.a.43291110-1/%%"],"custom":false},"ogImage":"avatars.yandex.net/get-music-content/5878680/38032d94.a.5287888-4/%%"}"""

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val root = json.parseToJsonElement(rawJson).let { it as kotlinx.serialization.json.JsonObject }
        val obj = (root["result"] as? kotlinx.serialization.json.JsonObject) ?: root

        val pKind = obj["kind"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() } ?: 1000L
        val pTitle = obj["title"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
        val uuid = obj["playlistUuid"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val coverObj = obj["cover"] as? kotlinx.serialization.json.JsonObject
        val coverUri = (coverObj?.get("uri") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (coverObj?.get("itemsUri") as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: (obj["ogImage"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val revision = obj["revision"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0
        val trackCount = obj["trackCount"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0
        val pUid = obj["uid"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() } ?: 0L

        val playlistInfo = io.github.audiz.models.PlaylistInfo(
            uid = pUid,
            title = pTitle,
            trackCount = trackCount,
            kind = pKind,
            playlistUuid = uuid,
            coverUri = coverUri,
            revision = revision
        )

        assertEquals("Electronic1", playlistInfo.title)
        assertEquals(1000L, playlistInfo.kind)
        assertEquals(23858391L, playlistInfo.uid)
        assertEquals(21, playlistInfo.revision)
        assertEquals(14, playlistInfo.trackCount)
        assertEquals("d8619525-d9e4-4aea-aebe-669579b9972d", playlistInfo.playlistUuid)
        assertEquals("avatars.yandex.net/get-music-content/5878680/38032d94.a.5287888-4/%%", playlistInfo.coverUri)
    }
}

