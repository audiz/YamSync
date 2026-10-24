package io.github.audiz

import io.github.audiz.api.MusicRepository
import io.github.audiz.download.DownloadManager
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.playlist.PlaylistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistManagerDeleteTest {

    @Test
    fun testDeleteLocalPlaylistSafeCleanup() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val tempStorageDir = java.nio.file.Files.createTempDirectory("music_app_storage").toFile()
        val userLibraryDir = java.nio.file.Files.createTempDirectory("user_personal_music").toFile()

        try {
            // Файл 1: Скачанный трек плейлиста A (должен удалиться)
            val hqDir = File(tempStorageDir, "HQ/TestArtist").apply { mkdirs() }
            val downloadedFile1 = File(hqDir, "TestArtist — Track1.mp3").apply { writeBytes("dummy_audio_1".toByteArray()) }

            // Файл 2: Скачанный трек, входящий в плейлист A и плейлист B (НЕ должен удалиться!)
            val downloadedFile2 = File(hqDir, "TestArtist — SharedTrack.mp3").apply { writeBytes("dummy_audio_2".toByteArray()) }

            // Файл 3: Личный трек пользователя из внешней папки (НЕ должен удалиться!)
            val userFile = File(userLibraryDir, "MyPersonalTrack.mp3").apply { writeBytes("dummy_user_audio".toByteArray()) }

            val downloadManager = DownloadManager(
                scope = scope,
                repository = MusicRepository(),
                getMusicStoragePath = { tempStorageDir.absolutePath },
                getSelectedQuality = { "2" },
                getPlayingTrackId = { null },
                getPlayingTrackTitle = { "" },
                getPlayingArtistName = { "" },
                getPlayingFilePath = { null }
            )

            var lastStatusMessage: String? = null
            val playlistManager = PlaylistManager(
                scope = scope,
                repository = MusicRepository(),
                getMusicStoragePath = { tempStorageDir.absolutePath },
                getAccessToken = { "" },
                downloadManager = downloadManager,
                onStatusMessage = { lastStatusMessage = it },
                onError = {}
            )

            val playlistA = LocalPlaylist(
                id = "pl_a",
                title = "Playlist A",
                trackPaths = listOf(downloadedFile1.absolutePath, downloadedFile2.absolutePath, userFile.absolutePath),
                createdAt = 1000L,
                updatedAt = 1000L
            )

            val playlistB = LocalPlaylist(
                id = "pl_b",
                title = "Playlist B",
                trackPaths = listOf(downloadedFile2.absolutePath),
                createdAt = 2000L,
                updatedAt = 2000L
            )

            playlistManager.localPlaylists.addAll(listOf(playlistA, playlistB))

            // Удаляем плейлист A с флагом deleteDownloadedTracks = true
            playlistManager.deleteLocalPlaylist("pl_a", deleteDownloadedTracks = true)

            // Ждем завершения корутины на DispatcherIO
            val start = System.currentTimeMillis()
            while (downloadedFile1.exists() && System.currentTimeMillis() - start < 2000) {
                Thread.sleep(50)
            }

            // 1. Плейлист A удален из списка, плейлист B остался
            assertEquals(1, playlistManager.localPlaylists.size)
            assertEquals("pl_b", playlistManager.localPlaylists[0].id)

            // 2. Скачанный файл 1 (принадлежал только плейлисту A) удален с диска
            assertFalse(downloadedFile1.exists(), "Downloaded track only in Playlist A should be deleted")

            // 3. Общий файл 2 (используется также в Playlist B) НЕ должен быть удален!
            assertTrue(downloadedFile2.exists(), "Shared track with Playlist B must NOT be deleted")

            // 4. Личный файл пользователя из внешней папки НЕ должен быть удален!
            assertTrue(userFile.exists(), "User personal file from outside app storage must NOT be deleted")

        } finally {
            tempStorageDir.deleteRecursively()
            userLibraryDir.deleteRecursively()
        }
    }
}
