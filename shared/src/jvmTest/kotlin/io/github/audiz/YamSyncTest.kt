package io.github.audiz

import io.github.audiz.models.*
import io.github.audiz.sync.QrCodeGenerator
import io.github.audiz.sync.YamSyncDiffEngine
import io.github.audiz.sync.YamSyncManager
import io.github.audiz.sync.YamSyncPairInfo
import kotlin.test.*

class YamSyncTest {

    @Test
    fun testYamSyncUriEncodingAndParsing() {
        val original = YamSyncPairInfo(
            ip = "192.168.1.105",
            port = 43594,
            token = "secret123",
            name = "MacBook Pro",
            platform = "Desktop"
        )

        val uri = original.toUri()
        assertTrue(uri.startsWith("yamsync://pair?"))
        assertTrue(uri.contains("ip=192.168.1.105"))
        assertTrue(uri.contains("token=secret123"))

        val parsed = YamSyncPairInfo.parse(uri)
        assertNotNull(parsed)
        assertEquals(original.ip, parsed.ip)
        assertEquals(original.port, parsed.port)
        assertEquals(original.token, parsed.token)
        assertEquals(original.name, parsed.name)
        assertEquals(original.platform, parsed.platform)
    }

    @Test
    fun testQrCodeGeneratorProducesValidMatrix() {
        val uri = "yamsync://pair?ip=192.168.1.45&port=43594&token=d8f9a2e1&name=Pixel+8&platform=Android"
        for (ecc in listOf(QrCodeGenerator.EccLevel.L, QrCodeGenerator.EccLevel.M, QrCodeGenerator.EccLevel.Q, QrCodeGenerator.EccLevel.H)) {
            val matrix = QrCodeGenerator.encode(uri, ecc)
            assertTrue(matrix.isNotEmpty())
            val size = matrix.size

            // Верхний левый маркер (Finder Pattern) должен иметь черный центр (3x3)
            assertTrue(matrix[3][3])
            assertTrue(matrix[0][0])
            assertTrue(matrix[0][6])
            assertTrue(matrix[6][0])

            // Декодируем эталонным декодером ZXing для 100% подтверждения соответствия стандарту ISO/IEC 18004
            val scale = 8
            val quietZone = 4 * scale
            val imgSize = size * scale + quietZone * 2
            val img = java.awt.image.BufferedImage(imgSize, imgSize, java.awt.image.BufferedImage.TYPE_BYTE_BINARY)
            val g = img.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, imgSize, imgSize)
            g.color = java.awt.Color.BLACK

            for (r in 0 until size) {
                for (c in 0 until size) {
                    if (matrix[r][c]) {
                        g.fillRect(quietZone + c * scale, quietZone + r * scale, scale, scale)
                    }
                }
            }
            g.dispose()

            val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
            val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
            val result = com.google.zxing.qrcode.QRCodeReader().decode(bitmap)
            assertEquals(uri, result.text)
        }
    }

    @Test
    fun testYamSyncDiffEngineModifiedAndMergeAll() {
        val trackA = YamSyncTrack(
            fileName = "artist_track_a.m4a",
            artist = "Artist A",
            title = "Track A",
            durationMs = 180000,
            fileSize = 5000000,
            checksum = "hash_a"
        )
        val trackB = YamSyncTrack(
            fileName = "artist_track_b.m4a",
            artist = "Artist B",
            title = "Track B",
            durationMs = 200000,
            fileSize = 6000000,
            checksum = "hash_b"
        )
        val trackC = YamSyncTrack(
            fileName = "artist_track_c.m4a",
            artist = "Artist C",
            title = "Track C",
            durationMs = 210000,
            fileSize = 7000000,
            checksum = "hash_c"
        )

        val localPlaylist = YamSyncPlaylist(
            id = "pl_1",
            title = "My Playlist",
            tracks = listOf(trackA, trackB)
        )
        val remotePlaylist = YamSyncPlaylist(
            id = "pl_1_remote",
            title = "My Playlist",
            tracks = listOf(trackB, trackC)
        )

        val localDevice = YamSyncDevice(id = "dev1", name = "PC", platform = "Desktop")
        val remoteDevice = YamSyncDevice(id = "dev2", name = "Phone", platform = "Mobile")

        val localManifest = YamSyncManifest(
            device = localDevice,
            playlists = listOf(localPlaylist),
            availableFiles = listOf(trackA, trackB)
        )
        val remoteManifest = YamSyncManifest(
            device = remoteDevice,
            playlists = listOf(remotePlaylist),
            availableFiles = listOf(trackB, trackC)
        )

        // 1. Вычисление diff
        val diffs = YamSyncDiffEngine.calculateDiff(localManifest, remoteManifest)
        assertEquals(1, diffs.size)
        val diff = diffs[0]
        assertEquals(YamSyncDiffState.MODIFIED, diff.state)
        assertEquals(listOf(trackA), diff.localOnlyTracks)
        assertEquals(listOf(trackC), diff.remoteOnlyTracks)
        assertEquals(listOf(trackB), diff.commonTracks)

        // 2. Слияние со стратегией MERGE_ALL
        val diffWithMerge = diff.copy(resolution = YamSyncResolution.MERGE_ALL)
        val merged = YamSyncDiffEngine.mergePlaylists(listOf(diffWithMerge))
        assertEquals(1, merged.size)
        val mergedPl = merged[0]
        // Должны быть trackA, trackB, trackC (без дублирования trackB)
        assertEquals(3, mergedPl.tracks.size)
        assertEquals(listOf(trackA, trackB, trackC), mergedPl.tracks)

        // 3. Поиск недостающих файлов для локального устройства
        val missing = YamSyncDiffEngine.findMissingFiles(
            mergedPlaylists = merged,
            localAvailableFiles = localManifest.availableFiles,
            remoteAvailableFiles = remoteManifest.availableFiles
        )
        assertEquals(1, missing.size)
        assertEquals(trackC, missing[0])
    }

    @Test
    fun testYamSyncDiffEngineKeepLocalAndTakeRemote() {
        val track1 = YamSyncTrack("1.m4a", "Artist 1", "Track 1")
        val track2 = YamSyncTrack("2.m4a", "Artist 2", "Track 2")

        val plLocal = YamSyncPlaylist("pl_1", "Rock", tracks = listOf(track1))
        val plRemote = YamSyncPlaylist("pl_2", "Rock", tracks = listOf(track2))

        val diff = YamSyncPlaylistDiff(
            playlistId = "pl_1",
            title = "Rock",
            state = YamSyncDiffState.MODIFIED,
            localPlaylist = plLocal,
            remotePlaylist = plRemote,
            resolution = YamSyncResolution.KEEP_LOCAL
        )

        // KEEP_LOCAL
        val resLocal = YamSyncDiffEngine.mergePlaylists(listOf(diff))
        assertEquals(listOf(track1), resLocal[0].tracks)

        // TAKE_REMOTE
        val resRemote = YamSyncDiffEngine.mergePlaylists(listOf(diff.copy(resolution = YamSyncResolution.TAKE_REMOTE)))
        assertEquals(listOf(track2), resRemote[0].tracks)
    }

    @Test
    fun testDeduplicationWithVaryingDurationAndDuplicateEntries() {
        // Track on device A with 0 duration (from playlist before download)
        val trackA0 = YamSyncTrack("Song.mp3", "Artist", "Song", durationMs = 0L)
        // Track on device B with full duration (from file tags)
        val trackAFull = YamSyncTrack("Song.mp3", "Artist", "Song", durationMs = 180000L)

        // They must have identical matchKey!
        assertEquals(trackA0.matchKey, trackAFull.matchKey)

        // Local playlist had duplicate entries of the same song (e.g. repeated merge attempts)
        val plWithDuplicates = YamSyncPlaylist("pl_1", "Hits", tracks = listOf(trackA0, trackA0, trackA0))
        val plRemote = YamSyncPlaylist("pl_1_remote", "Hits", tracks = listOf(trackAFull))

        val diff = YamSyncPlaylistDiff(
            playlistId = "pl_1",
            title = "Hits",
            state = YamSyncDiffState.MODIFIED,
            localPlaylist = plWithDuplicates,
            remotePlaylist = plRemote,
            resolution = YamSyncResolution.MERGE_ALL
        )

        val merged = YamSyncDiffEngine.mergePlaylists(listOf(diff))
        assertEquals(1, merged.size)
        // Must be deduplicated to exactly 1 track!
        assertEquals(1, merged[0].tracks.size)
        assertEquals("Song.mp3", merged[0].tracks[0].fileName)
    }

    @Test
    fun testYamSyncManagerPreservesUserResolutionAndFileSelectionsOnRefresh() {
        val manager = YamSyncManager(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            getMusicStoragePath = { "/tmp/music" },
            onPlaylistsUpdated = {}
        )

        val trackA = YamSyncTrack("trackA.mp3", "Artist", "Song A")
        val trackB = YamSyncTrack("trackB.mp3", "Artist", "Song B")
        val trackC = YamSyncTrack("trackC.mp3", "Artist", "Song C")

        val localPl = YamSyncPlaylist("pl_local_1", "Hits", tracks = listOf(trackA))
        val remotePl = YamSyncPlaylist("pl_remote_1", "Hits", tracks = listOf(trackB, trackC))

        val localManifest = YamSyncManifest(
            device = YamSyncDevice("d1", "PC", "Desktop"),
            playlists = listOf(localPl),
            availableFiles = listOf(trackA)
        )
        val remoteManifest = YamSyncManifest(
            device = YamSyncDevice("d2", "Phone", "Mobile"),
            playlists = listOf(remotePl),
            availableFiles = listOf(trackB, trackC)
        )

        // 1. Первоначальный diff
        manager.applyDiffs(localManifest, remoteManifest)
        assertEquals(1, manager.playlistDiffs.size)
        // По умолчанию для совпадающего названия: MERGE_ALL
        assertEquals(YamSyncResolution.MERGE_ALL, manager.playlistDiffs[0].resolution)
        assertEquals(2, manager.missingFiles.size) // trackB and trackC
        assertTrue(manager.selectedFiles.contains(trackB.matchKey))
        assertTrue(manager.selectedFiles.contains(trackC.matchKey))

        // 2. Пользователь меняет стратегию на KEEP_LOCAL
        manager.setResolution(manager.playlistDiffs[0].playlistId, YamSyncResolution.KEEP_LOCAL)
        assertEquals(YamSyncResolution.KEEP_LOCAL, manager.playlistDiffs[0].resolution)
        // Для KEEP_LOCAL чужие треки не добавляются, поэтому missingFiles пуст
        assertEquals(0, manager.missingFiles.size)

        // 3. Фоновое обновление (тихий опрос каждые 4 секунды)
        manager.applyDiffs(localManifest, remoteManifest)
        // 🔒 Выбор пользователя KEEP_LOCAL должен сохраниться!
        assertEquals(YamSyncResolution.KEEP_LOCAL, manager.playlistDiffs[0].resolution)
        assertEquals(0, manager.missingFiles.size)

        // 4. Пользователь переключает на MERGE_ALL и снимает галочку с trackC
        manager.setResolution(manager.playlistDiffs[0].playlistId, YamSyncResolution.MERGE_ALL)
        assertEquals(YamSyncResolution.MERGE_ALL, manager.playlistDiffs[0].resolution)
        assertEquals(2, manager.missingFiles.size)

        manager.toggleFileSelection(trackC.matchKey)
        assertFalse(manager.selectedFiles.contains(trackC.matchKey))
        assertTrue(manager.selectedFiles.contains(trackB.matchKey))

        // 5. Повторное фоновое обновление не должно сбрасывать галочку пользователя!
        manager.applyDiffs(localManifest, remoteManifest)
        assertEquals(YamSyncResolution.MERGE_ALL, manager.playlistDiffs[0].resolution)
        assertFalse(manager.selectedFiles.contains(trackC.matchKey))
        assertTrue(manager.selectedFiles.contains(trackB.matchKey))
    }

    @Test
    fun testFindMissingFilesMatchesByFileNameFallback() {
        // Track on remote peer where artist was parsed as "Keep-Shelly-In-Athens"
        val remoteTrack = YamSyncTrack(
            fileName = "Keep-Shelly-In-Athens-Nobody.mp3",
            artist = "Keep-Shelly-In-Athens",
            title = "Nobody",
            checksum = "keep-shelly-in-athens_nobody"
        )
        // Track in merged playlist where artist was Unknown
        val playlistTrack = YamSyncTrack(
            fileName = "Keep-Shelly-In-Athens-Nobody.mp3",
            artist = "Unknown Artist",
            title = "Keep-Shelly-In-Athens-Nobody",
            checksum = "unknown artist_keep-shelly-in-athens-nobody"
        )

        val mergedPlaylists = listOf(
            YamSyncPlaylist(
                id = "pl_1",
                title = "Road Trip",
                tracks = listOf(playlistTrack)
            )
        )

        // Local peer has no files
        val localAvailable = emptyList<YamSyncTrack>()
        // Remote peer has the file
        val remoteAvailable = listOf(remoteTrack)

        val missing = YamSyncDiffEngine.findMissingFiles(
            mergedPlaylists = mergedPlaylists,
            localAvailableFiles = localAvailable,
            remoteAvailableFiles = remoteAvailable
        )

        // Must find remoteTrack via fileName fallback even though checksum/matchKey differed!
        assertEquals(1, missing.size)
        assertEquals("Keep-Shelly-In-Athens-Nobody.mp3", missing[0].fileName)
        assertEquals(remoteTrack, missing[0])
    }

    @Test
    fun testFindMissingFilesDoesNotReportAlreadyDownloadedFileWithDifferentMetadata() {
        val localTrack = YamSyncTrack(
            fileName = "Keep-Shelly-In-Athens-Nobody.mp3",
            artist = "Local Artist",
            title = "Local Title",
            checksum = "local_artist_local_title"
        )
        val remoteTrack = YamSyncTrack(
            fileName = "Keep-Shelly-In-Athens-Nobody.mp3",
            artist = "Remote Artist",
            title = "Remote Title",
            checksum = "remote_artist_remote_title"
        )

        val mergedPlaylists = listOf(
            YamSyncPlaylist(
                id = "pl_1",
                title = "Road Trip",
                tracks = listOf(remoteTrack)
            )
        )

        val missing = YamSyncDiffEngine.findMissingFiles(
            mergedPlaylists = mergedPlaylists,
            localAvailableFiles = listOf(localTrack),
            remoteAvailableFiles = listOf(remoteTrack)
        )

        // The file is already on the local device, so it must NOT be marked as missing!
        assertEquals(0, missing.size)
    }

    @Test
    fun testBuildLocalManifestIncludesLocalPlaylistTracksNotInBasePathScan() {
        val baseDir = java.nio.file.Files.createTempDirectory("yamsync_base").toFile()
        val customDir = java.nio.file.Files.createTempDirectory("yamsync_custom").toFile()
        try {
            // Create a track inside custom directory (outside basePath)
            val customFile = java.io.File(customDir, "Keep-Shelly-In-Athens-Nobody.mp3")
            customFile.writeBytes("ID3dummy_audio_bytes_123456789".toByteArray())

            // Create a local playlist referencing this custom file
            val pl = LocalPlaylist(
                id = "pl_local",
                title = "В дорогу",
                trackPaths = listOf(customFile.absolutePath)
            )
            saveLocalPlaylists(baseDir.absolutePath, listOf(pl))

            val manager = YamSyncManager(
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
                getMusicStoragePath = { baseDir.absolutePath },
                onPlaylistsUpdated = {}
            )

            val manifest = manager.buildLocalManifest()

            // 1. Manifest availableFiles MUST contain this local file even though it's outside basePath!
            val available = manifest.availableFiles.firstOrNull { it.fileName == "Keep-Shelly-In-Athens-Nobody.mp3" }
            assertNotNull(available, "availableFiles must include local track from playlist")
            assertTrue(available.fileSize > 0, "fileSize must be positive")

            // 2. Playlist tracks must have valid track metadata
            val plTrack = manifest.playlists[0].tracks.firstOrNull { it.fileName == "Keep-Shelly-In-Athens-Nobody.mp3" }
            assertNotNull(plTrack, "playlist tracks must include the track")
            assertEquals(available.matchKey, plTrack.matchKey)
        } finally {
            baseDir.deleteRecursively()
            customDir.deleteRecursively()
        }
    }

    @Test
    fun testHttpPairUrlAndParsing() {
        val original = YamSyncPairInfo(
            ip = "192.168.1.105",
            port = 43594,
            token = "secret123",
            name = "MacBook Pro",
            platform = "Desktop"
        )

        val httpUrl = original.toHttpUrl()
        assertTrue(httpUrl.startsWith("http://192.168.1.105:43594/pair?"))
        assertTrue(httpUrl.contains("token=secret123"))

        val parsedFromHttp = YamSyncPairInfo.parse(httpUrl)
        assertNotNull(parsedFromHttp)
        assertEquals(original.ip, parsedFromHttp.ip)
        assertEquals(original.port, parsedFromHttp.port)
        assertEquals(original.token, parsedFromHttp.token)
        assertEquals(original.name, parsedFromHttp.name)
        assertEquals(original.platform, parsedFromHttp.platform)
    }

    @Test
    fun testFuzzyTrackMatchingSlugDifferences() {
        val clean1 = YamSyncTrack(
            fileName = "Keep Shelly In Athens — Nobody.mp3",
            artist = "Keep Shelly In Athens",
            title = "Nobody"
        )
        val slug1 = YamSyncTrack(
            fileName = "Keep-Shelly-In-Athens---Nobody.mp3",
            artist = "Keep Shelly In Athens",
            title = "Keep-Shelly-In-Athens---Nobody.mp3"
        )
        assertTrue(YamSyncDiffEngine.areTracksMatching(clean1, slug1), "Slug with '---' must match clean artist and title")

        val clean2 = YamSyncTrack(
            fileName = "Natasha Bedingfield — Pocketful of Sunshine.mp3",
            artist = "Natasha Bedingfield",
            title = "Pocketful of Sunshine"
        )
        val slug2 = YamSyncTrack(
            fileName = "Pocketful-of-Sunshine.mp3",
            artist = "Unknown Artist",
            title = "Pocketful-of-Sunshine"
        )
        assertTrue(YamSyncDiffEngine.areTracksMatching(clean2, slug2), "Hyphenated title must match full artist and title")
    }

    @Test
    fun testPlaylistDiffIdenticalWithSlugDifferences() {
        val localTracks = listOf(
            YamSyncTrack("Keep Shelly In Athens — Nobody.mp3", "Keep Shelly In Athens", "Nobody"),
            YamSyncTrack("Natasha Bedingfield — Pocketful of Sunshine.mp3", "Natasha Bedingfield", "Pocketful of Sunshine"),
            YamSyncTrack("Track3.mp3", "Artist 3", "Song 3"),
            YamSyncTrack("Track4.mp3", "Artist 4", "Song 4")
        )
        val remoteTracks = listOf(
            YamSyncTrack("Keep-Shelly-In-Athens---Nobody.mp3", "Keep Shelly In Athens", "Keep-Shelly-In-Athens---Nobody.mp3"),
            YamSyncTrack("Pocketful-of-Sunshine.mp3", "Unknown Artist", "Pocketful-of-Sunshine"),
            YamSyncTrack("Track3.mp3", "Artist 3", "Song 3"),
            YamSyncTrack("Track4.mp3", "Artist 4", "Song 4")
        )

        val localManifest = YamSyncManifest(
            device = YamSyncDevice("d1", "PC", "Desktop"),
            playlists = listOf(YamSyncPlaylist("pl1", "В дорогу", tracks = localTracks))
        )
        val remoteManifest = YamSyncManifest(
            device = YamSyncDevice("d2", "Phone", "iOS"),
            playlists = listOf(YamSyncPlaylist("pl1", "В дорогу", tracks = remoteTracks))
        )

        val diffs = YamSyncDiffEngine.calculateDiff(localManifest, remoteManifest)
        assertEquals(1, diffs.size)
        val diff = diffs[0]

        // Плейлист должен быть признан ИДЕНТИЧНЫМ, без ложных "+2 / -2" расхождений!
        assertEquals(YamSyncDiffState.IDENTICAL, diff.state)
        assertEquals(0, diff.localOnlyTracks.size)
        assertEquals(0, diff.remoteOnlyTracks.size)
        assertEquals(4, diff.commonTracks.size)
    }
}

