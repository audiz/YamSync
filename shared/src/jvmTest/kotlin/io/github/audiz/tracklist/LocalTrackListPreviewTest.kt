package io.github.audiz.tracklist

import io.github.audiz.AppConfigKeys
import io.github.audiz.TracksListOrigin
import io.github.audiz.models.CustomMediaSource
import io.github.audiz.models.LocalSourceType
import io.github.audiz.TrackPlaySource
import io.github.audiz.api.MusicRepository
import io.github.audiz.download.DownloadManager
import io.github.audiz.loadAppConfig
import io.github.audiz.local.LocalMediaManager
import io.github.audiz.models.FullTrackInfo
import io.github.audiz.models.LastPlaybackSession
import io.github.audiz.models.LastPlaybackType
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.player.PlaybackManager
import io.github.audiz.player.PlaybackSessionManager
import io.github.audiz.playlist.PlaylistManager
import io.github.audiz.saveAppConfig
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalTrackListPreviewTest {
    private class Fixture {
        val directory = Files.createTempDirectory("playlist-preview").toFile()
        val file = File(directory, "Artist — Preview.wav").apply {
            val payloadSize = 3200
            val wav = ByteBuffer.allocate(44 + payloadSize).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + payloadSize).put("WAVEfmt ".toByteArray())
            wav.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            wav.put("data".toByteArray()).putInt(payloadSize)
            writeBytes(wav.array())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val repository = MusicRepository()
        val previousSession = loadAppConfig(AppConfigKeys.LAST_PLAYBACK_SESSION)
        val sessionManager = PlaybackSessionManager()
        val playingSession = LastPlaybackSession(type = LastPlaybackType.WAVE, title = "Playing queue")
        val playback = PlaybackManager(
            scope, repository,
            getMusicStoragePath = { directory.absolutePath }, getSelectedQuality = { "1" },
            isRecordToDisk = { false }, getLoadedTracks = { emptyList() }
        )
        val downloadManager = DownloadManager(
            scope, repository, getMusicStoragePath = { directory.absolutePath },
            getSelectedQuality = { "1" }, getPlayingTrackId = { null }, getPlayingTrackTitle = { "" },
            getPlayingArtistName = { "" }, getPlayingFilePath = { null }
        )
        val playlists = PlaylistManager(
            scope, repository, getMusicStoragePath = { directory.absolutePath },
            getAccessToken = { "" }, downloadManager = downloadManager
        )
        val finished = CompletableDeferred<Unit>()
        val playRequests = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val coordinator = TrackListCoordinator(
            scope, repository, playback, sessionManager, playlists, LocalMediaManager(),
            getMusicStoragePath = { directory.absolutePath }, getSearchResult = { null },
            onPlayTrack = { id: String, _: String, _: String, _: Long?, _: Boolean, _: Boolean, _: TrackPlaySource -> playRequests.add(id) },
            onSetTracksListVisible = {}, onLoadingChanged = { if (!it) finished.complete(Unit) },
            onError = { if (it != null) errors.add(it) }
        )
        val playlist = LocalPlaylist("preview", "Preview playlist", trackPaths = listOf(file.absolutePath))

        init {
            playback.setInitialTrack(FullTrackInfo(id = "playing", title = "Current track"))
            sessionManager.recordSession(playingSession)
        }

        suspend fun awaitLoaded() {
            withTimeout(5000) { finished.await() }
            assertTrue(errors.isEmpty(), errors.joinToString())
            assertEquals(1, coordinator.loadedTracks.size)
        }

        suspend fun close() {
            withContext(Dispatchers.Main) {
                coordinator.dispose()
                playback.release()
                scope.cancel()
            }
            saveAppConfig(AppConfigKeys.LAST_PLAYBACK_SESSION, previousSession.orEmpty())
            directory.deleteRecursively()
        }
    }

    @Test
    fun playlistPreviewDoesNotStartTrackOrReplacePlayerAndSession() = runBlocking {
        val fixture = Fixture()
        try {
            withContext(Dispatchers.Main) {
                fixture.coordinator.openLocalPlaylist(fixture.playlist, autoPlayFirst = true, previewOnly = true)
            }
            fixture.awaitLoaded()
            assertTrue(fixture.playRequests.isEmpty())
            assertEquals("playing", fixture.playback.trackId)
            assertEquals("Current track", fixture.playback.trackTitle)
            assertEquals(LastPlaybackType.WAVE, fixture.sessionManager.currentSession?.type)
            assertEquals("Playing queue", fixture.sessionManager.currentSession?.title)
        } finally { fixture.close() }
    }

    @Test
    fun folderPreviewDoesNotRestoreAnotherTrackOrReplaceSession() = runBlocking {
        val fixture = Fixture()
        try {
            withContext(Dispatchers.Main) {
                fixture.coordinator.openFolderPlaylist(
                    fixture.directory.absolutePath, "Preview folder",
                    restoreTrackId = fixture.file.absolutePath, previewOnly = true
                )
            }
            fixture.awaitLoaded()
            assertTrue(fixture.playRequests.isEmpty())
            assertEquals("playing", fixture.playback.trackId)
            assertEquals(LastPlaybackType.WAVE, fixture.sessionManager.currentSession?.type)
        } finally { fixture.close() }
    }

    @Test
    fun folderOpenedFromHomeReturnsHome() = checkFolderOrigin(TracksListOrigin.HOME)

    @Test
    fun folderOpenedFromBrowserReturnsToBrowser() = checkFolderOrigin(TracksListOrigin.FOLDER_BROWSER)

    private fun checkFolderOrigin(origin: TracksListOrigin) = runBlocking {
        val fixture = Fixture()
        try {
            val source = CustomMediaSource(
                id = "folder", type = LocalSourceType.FOLDER,
                name = "Local folder", path = fixture.directory.absolutePath
            )
            withContext(Dispatchers.Main) {
                if (origin == TracksListOrigin.HOME) {
                    fixture.coordinator.openFolderPlaylist(
                        source.path, source.name, source, previewOnly = true, origin = origin
                    )
                } else {
                    fixture.coordinator.openFolderPlaylist(source.path, source.name, source, previewOnly = true)
                }
            }
            fixture.awaitLoaded()
            assertEquals(origin, fixture.coordinator.tracksListOrigin)
            assertEquals(source, fixture.coordinator.activeBrowsedFolderSource)
        } finally { fixture.close() }
    }

    @Test
    fun explicitPlaylistPlayStillStartsItsTrack() = runBlocking {
        val fixture = Fixture()
        try {
            withContext(Dispatchers.Main) {
                fixture.coordinator.openLocalPlaylist(fixture.playlist, autoPlayFirst = true)
            }
            fixture.awaitLoaded()
            assertEquals(listOf("local:${fixture.file.absolutePath}"), fixture.playRequests)
            assertEquals(LastPlaybackType.LOCAL_PLAYLIST, fixture.sessionManager.currentSession?.type)
            assertEquals(fixture.playlist.id, fixture.sessionManager.currentSession?.id)
        } finally { fixture.close() }
    }
}
