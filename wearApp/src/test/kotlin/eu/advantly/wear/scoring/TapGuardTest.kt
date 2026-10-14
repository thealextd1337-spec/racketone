package eu.advantly.wear.scoring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapGuardTest {
    @Test
    fun secondTapWithinLockoutIsRejected() {
        val guard = TapGuard(lockoutMillis = 600)
        assertTrue(guard.tryAccept(1_000))
        assertFalse(guard.tryAccept(1_100))
        assertFalse(guard.tryAccept(1_599))
        assertTrue(guard.tryAccept(1_600))
    }

    @Test
    fun rejectedTapDoesNotExtendLockout() {
        val guard = TapGuard(lockoutMillis = 600)
        assertTrue(guard.tryAccept(0))
        assertFalse(guard.tryAccept(500))
        assertTrue(guard.tryAccept(600))
    }

    @Test
    fun lockoutAppliesToBothSides() {
        // Die Sperre ist global (beide Flächen), TapGuard kennt keine Seite.
        val guard = TapGuard()
        assertTrue(guard.tryAccept(10_000))
        assertTrue(guard.isLocked(10_100))
        assertFalse(guard.isLocked(10_000 + TapGuard.DEFAULT_LOCKOUT_MILLIS))
    }

    @Test
    fun wakeFromAmbientBlocksWakeTap() {
        val guard = TapGuard(lockoutMillis = 600, wakeDelayMillis = 300)
        guard.onWake(5_000)
        assertFalse(guard.tryAccept(5_100))
        assertTrue(guard.tryAccept(5_300))
    }

    @Test
    fun wakeDoesNotShortenRunningLockout() {
        val guard = TapGuard(lockoutMillis = 600, wakeDelayMillis = 300)
        assertTrue(guard.tryAccept(0))
        guard.onWake(100) // Sperre bis 600 bleibt bestehen, nicht nur bis 400
        assertFalse(guard.tryAccept(500))
        assertTrue(guard.tryAccept(600))
    }
}
