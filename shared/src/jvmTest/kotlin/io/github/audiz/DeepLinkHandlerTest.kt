package io.github.audiz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkHandlerTest {

    @Test
    fun testYamSyncSchemeParsing() {
        assertEquals("12345", DeepLinkHandler.extractTrackId("yamsync://track/12345"))
        assertEquals("67890", DeepLinkHandler.extractTrackId("yamsync://67890"))
        assertEquals("99999", DeepLinkHandler.extractTrackId("yamsync://album/111/track/99999"))
        assertEquals("555", DeepLinkHandler.extractTrackId("  yamsync://track/555  "))
    }

    @Test
    fun testYandexMusicUrlParsing() {
        assertEquals("215334", DeepLinkHandler.extractTrackId("https://music.yandex.ru/track/215334"))
        assertEquals("215334", DeepLinkHandler.extractTrackId("https://music.yandex.ru/album/25442/track/215334"))
        assertEquals("77777", DeepLinkHandler.extractTrackId("http://music.yandex.ru/track/77777?from=share"))
    }

    @Test
    fun testInvalidUrlParsing() {
        assertNull(DeepLinkHandler.extractTrackId("invalid://url"))
        assertNull(DeepLinkHandler.extractTrackId("https://google.com"))
        assertNull(DeepLinkHandler.extractTrackId(""))
        assertNull(DeepLinkHandler.extractTrackId("some random query"))
    }

    @Test
    fun testSharedMessageAndNumericIdParsing() {
        // User's exact shared message
        val sharedMessage = """
            🎵 Earmake — Cosmic Hero
            Слушать в YamSync: yamsync://track/51990337
            Веб-ссылка: https://music.yandex.ru/track/51990337
        """.trimIndent()
        assertEquals("51990337", DeepLinkHandler.extractTrackId(sharedMessage))

        // Raw numeric track ID
        assertEquals("51990337", DeepLinkHandler.extractTrackId("51990337"))
        assertEquals("12345678", DeepLinkHandler.extractTrackId("  12345678  "))
    }

    @Test
    fun testConsumeTrackId() {
        DeepLinkHandler.handleUrl("yamsync://track/42")
        assertEquals("42", DeepLinkHandler.pendingTrackId.value)
        assertEquals("42", DeepLinkHandler.consumeTrackId())
        assertNull(DeepLinkHandler.pendingTrackId.value)
    }
}
