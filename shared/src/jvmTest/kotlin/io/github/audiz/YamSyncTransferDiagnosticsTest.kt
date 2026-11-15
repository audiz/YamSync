package io.github.audiz

import com.sun.net.httpserver.HttpServer
import io.github.audiz.models.YamSyncTrack
import io.github.audiz.sync.YamSyncClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YamSyncTransferDiagnosticsTest {
    private val testToken = "diagnostic-test-token-do-not-log"
    private var originalUserHome = ""
    private lateinit var temporaryHome: java.io.File

    @BeforeTest
    fun isolateDiagnosticLog() {
        originalUserHome = System.getProperty("user.home")
        temporaryHome = Files.createTempDirectory("yamsync-diagnostics").toFile()
        System.setProperty("user.home", temporaryHome.absolutePath)
    }

    @AfterTest
    fun restoreDiagnosticLog() {
        System.setProperty("user.home", originalUserHome)
        temporaryHome.deleteRecursively()
    }

    private class RecordingWriter(private val cancelOnWrite: Boolean = false) : TrackFileWriter {
        val bytes = ByteArrayOutputStream()
        var closed = false
        var aborted = false
        override fun write(buffer: ByteArray, length: Int) {
            if (cancelOnWrite) throw CancellationException("test cancellation")
            bytes.write(buffer, 0, length)
        }
        override fun close() { closed = true }
        override fun abort() { aborted = true }
    }

    private fun logText() = java.io.File(temporaryHome, "Music/YandexDownloader/yamusic.log").readText()

    private fun withServer(status: Int, payload: ByteArray, block: (Int) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/yamsync/v1/file") { exchange ->
            exchange.use {
                it.sendResponseHeaders(status, if (payload.isEmpty()) -1L else payload.size.toLong())
                if (payload.isNotEmpty()) it.responseBody.write(payload)
            }
        }
        server.start()
        try { block(server.address.port) } finally { server.stop(0) }
    }

    @Test
    fun successfulTransferLogsByteCountsAndCloseWithoutCredentials() = withServer(200, "test-audio".toByteArray()) { port ->
        runBlocking {
            val http = HttpClient()
            try {
                val writer = RecordingWriter()
                val track = YamSyncTrack("test-success.m4a", "Artist", "Song", fileSize = 10)
                val result = YamSyncClient(http).downloadTrackToFile("127.0.0.1", port, testToken, track, writer) {}
                assertEquals(10L, result.getOrThrow())
                assertContentEquals("test-audio".toByteArray(), writer.bytes.toByteArray())
                assertTrue(writer.closed)
                assertFalse(writer.aborted)
                val log = logText()
                assertTrue(log.contains("event=http_file_response"))
                assertTrue(log.contains("receivedBytes=\"10\""))
                assertTrue(log.contains("manifestSizeMatches=\"true\""))
                assertTrue(log.contains("event=file_writer_closed"))
                assertFalse(log.contains(testToken))
            } finally { http.close() }
        }
    }

    @Test
    fun serverFailureLogsStatusAndAbortsFile() = withServer(503, byteArrayOf()) { port ->
        runBlocking {
            val http = HttpClient()
            try {
                val writer = RecordingWriter()
                val track = YamSyncTrack("test-error.m4a", "Artist", "Song", fileSize = 100)
                val result = YamSyncClient(http).downloadTrackToFile("127.0.0.1", port, testToken, track, writer) {}
                assertTrue(result.isFailure)
                assertTrue(writer.aborted)
                assertFalse(writer.closed)
                val log = logText()
                assertTrue(log.contains("status=\"503\""))
                assertTrue(log.contains("event=file_transfer_failed"))
                assertFalse(log.contains(testToken))
            } finally { http.close() }
        }
    }

    @Test
    fun emptyResponseLogsMismatchAgainstManifest() = withServer(200, byteArrayOf()) { port ->
        runBlocking {
            val http = HttpClient()
            try {
                val track = YamSyncTrack("test-empty.m4a", "Artist", "Song", fileSize = 100)
                YamSyncClient(http).downloadTrackToFile("127.0.0.1", port, testToken, track, RecordingWriter()) {}
                val log = logText()
                assertTrue(log.contains("receivedBytes=\"0\""))
                assertTrue(log.contains("manifestSizeMatches=\"false\""))
                assertFalse(log.contains(testToken))
            } finally { http.close() }
        }
    }

    @Test
    fun cancellationAbortsFileAndIsPropagated() = withServer(200, "test-audio".toByteArray()) { port ->
        runBlocking {
            val http = HttpClient()
            try {
                val writer = RecordingWriter(cancelOnWrite = true)
                val track = YamSyncTrack("test-cancelled.m4a", "Artist", "Song", fileSize = 10)
                assertFailsWith<CancellationException> {
                    YamSyncClient(http).downloadTrackToFile("127.0.0.1", port, testToken, track, writer) {}
                }
                assertTrue(writer.aborted)
                assertTrue(logText().contains("cancelled=\"true\""))
            } finally { http.close() }
        }
    }
}
