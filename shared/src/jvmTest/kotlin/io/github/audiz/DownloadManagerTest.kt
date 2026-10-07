package io.github.audiz

import io.github.audiz.api.MusicRepository
import io.github.audiz.download.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadManagerTest {

    private fun createDownloadManager(storagePath: String = "/tmp"): DownloadManager {
        return DownloadManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = MusicRepository(),
            getMusicStoragePath = { storagePath },
            getSelectedQuality = { "1" },
            getPlayingTrackId = { null },
            getPlayingTrackTitle = { "" },
            getPlayingArtistName = { "" },
            getPlayingFilePath = { null }
        )
    }

    @Test
    fun testSanitizeKeepSpaces() {
        val manager = createDownloadManager()
        val original = "AC/DC - Highway to Hell: Live*?\"<>|"
        val sanitized = manager.sanitizeKeepSpaces(original)
        assertEquals("AC_DC - Highway to Hell_ Live______", sanitized)
        // Spaces are preserved
        assertTrue(sanitized.contains(" - Highway to Hell_ Live"))
    }

    @Test
    fun testInitialStateAndIncrementVersion() {
        val manager = createDownloadManager()
        assertFalse(manager.isTrackDownloading)
        assertNull(manager.downloadingTrackId)
        assertEquals(0, manager.downloadVersion)

        manager.incrementDownloadVersion()
        assertEquals(1, manager.downloadVersion)
    }

    @Test
    fun testIsTrackDownloadedWithTempDir() {
        val tempDir = Files.createTempDirectory("download_manager_test").toFile()
        try {
            val manager = createDownloadManager(tempDir.absolutePath)
            val artist = "Queen"
            val title = "Bohemian Rhapsody"

            assertFalse(manager.isTrackDownloaded(artist, title))

            // Save file in HQ folder
            val hqFolder = java.io.File(tempDir, "HQ")
            hqFolder.mkdirs()
            val fileName = "$artist — $title.m4a"
            saveTrackFile(hqFolder.absolutePath, artist, fileName, "dummy_audio".toByteArray())

            assertTrue(manager.isTrackDownloaded(artist, title))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
