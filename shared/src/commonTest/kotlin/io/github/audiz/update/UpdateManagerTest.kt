package io.github.audiz.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateManagerTest {
    @Test
    fun comparesNumerically() {
        assertTrue(UpdateManager.isNewer("1.10.0", "1.9.0"))
        assertTrue(UpdateManager.isNewer("v1.0.1", "1.0.0"))
        assertTrue(UpdateManager.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateManager.isNewer("1.0.0", "1.0"))
        assertFalse(UpdateManager.isNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateManager.isNewer("nightly", "1.0.0"))
    }
}
