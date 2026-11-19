package io.github.audiz.update

import io.github.audiz.AppConfigKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class UpdateCacheTest {
    private class Fixture {
        var clock = 10 * UpdateManager.CHECK_INTERVAL_MS
        var requests = 0
        var response: UpdateInfo? = UpdateInfo("1.2.0", "https://example.test/releases/1.2.0")
        var cancel = false
        val config = mutableMapOf<String, String>()
        val manager = UpdateManager(currentVersion = "1.0.0", now = { clock },
            readConfig = { config[it] }, writeConfig = { key, value -> config[key] = value },
            fetchRelease = {
                requests++
                if (cancel) throw CancellationException("cancelled")
                response
            })

        fun cache(version: String = "1.1.0", age: Long = 0L) {
            config[AppConfigKeys.KNOWN_LATEST_VERSION] = version
            config[AppConfigKeys.LAST_UPDATE_CHECK_AT] = (clock - age).toString()
        }
    }

    @Test fun freshCacheAvoidsNetworkAndSchedulesDailyRefresh() = runBlocking {
        val f = Fixture().apply { cache() }
        f.manager.checkForUpdates()
        assertEquals(0, f.requests)
        assertEquals("1.1.0", f.manager.availableUpdate?.version)
        assertEquals(f.clock + UpdateManager.CHECK_INTERVAL_MS, f.manager.nextCheckAt)
    }

    @Test fun expiredCacheRefreshesEvenWhenItAlreadyContainsAnUpdate() = runBlocking {
        val f = Fixture().apply { cache(age = UpdateManager.CHECK_INTERVAL_MS) }
        f.manager.checkForUpdates()
        assertEquals(1, f.requests)
        assertEquals("1.2.0", f.manager.availableUpdate?.version)
        assertEquals("1.2.0", f.config[AppConfigKeys.KNOWN_LATEST_VERSION])
        assertEquals(f.clock.toString(), f.config[AppConfigKeys.LAST_UPDATE_CHECK_AT])
        assertEquals(f.response?.url, f.config[AppConfigKeys.KNOWN_LATEST_RELEASE_URL])
    }

    @Test fun upToDateResponseIsCachedToo() = runBlocking {
        val f = Fixture().apply { response = UpdateInfo("1.0.0", "https://example.test/current") }
        f.manager.checkForUpdates()
        f.manager.checkForUpdates()
        assertEquals(1, f.requests)
        assertNull(f.manager.availableUpdate)
        f.clock += UpdateManager.CHECK_INTERVAL_MS
        f.manager.checkForUpdates()
        assertEquals(2, f.requests)
    }

    @Test fun oldCacheWithoutTimestampIsMigratedByRefreshing() = runBlocking {
        val f = Fixture()
        f.config[AppConfigKeys.KNOWN_LATEST_VERSION] = "1.1.0"
        f.manager.checkForUpdates()
        assertEquals(1, f.requests)
    }

    @Test fun failedRefreshKeepsCachedBannerAndSuccessfulCheckTimestamp() = runBlocking {
        val f = Fixture().apply { cache(age = UpdateManager.CHECK_INTERVAL_MS); response = null }
        val previousTimestamp = f.config[AppConfigKeys.LAST_UPDATE_CHECK_AT]
        f.manager.checkForUpdates()
        assertEquals("1.1.0", f.manager.availableUpdate?.version)
        assertEquals(previousTimestamp, f.config[AppConfigKeys.LAST_UPDATE_CHECK_AT])
        f.manager.checkForUpdates()
        assertEquals(1, f.requests)
        assertTrue(f.manager.nextCheckAt!! > f.clock)
    }

    @Test fun dismissedVersionStaysHiddenButANewerReleaseAppears() = runBlocking {
        val f = Fixture().apply { cache() }
        f.manager.checkForUpdates()
        f.manager.dismiss()
        f.manager.checkForUpdates()
        assertNull(f.manager.availableUpdate)
        f.clock += UpdateManager.CHECK_INTERVAL_MS
        f.manager.checkForUpdates()
        assertEquals("1.2.0", f.manager.availableUpdate?.version)
    }

    @Test fun futureTimestampDoesNotPreventRefreshingAfterClockChange() = runBlocking {
        val f = Fixture().apply { cache(age = -UpdateManager.CHECK_INTERVAL_MS) }
        f.manager.checkForUpdates()
        assertEquals(1, f.requests)
    }

    @Test fun cancellationDoesNotMarkCheckSuccessful() = runBlocking {
        val f = Fixture().apply { cancel = true }
        assertFailsWith<CancellationException> { f.manager.checkForUpdates() }
        assertNull(f.config[AppConfigKeys.LAST_UPDATE_CHECK_AT])
    }
}
