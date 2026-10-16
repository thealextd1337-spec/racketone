package eu.advantly.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryVisibilityTest {
    @Test
    fun wireValuesMatchTheServerAndDefaultIsPrivate() {
        assertEquals(listOf("share", "private", "off"), TelemetryVisibility.entries.map { it.wire })
        assertEquals(TelemetryVisibility.PRIVATE, TelemetryVisibility.DEFAULT)
        assertEquals(TelemetryVisibility.PRIVATE, TelemetryPreference().visibility)
        TelemetryVisibility.entries.forEach { assertEquals(it, TelemetryVisibility.fromWire(it.wire)) }
        assertNull(TelemetryVisibility.fromWire("friends"))
        assertNull(TelemetryVisibility.fromWire(null))
    }

    @Test
    fun onlyOffStopsLiveValuesAndOnlyShareIsMarkedForOthers() {
        assertTrue(TelemetryVisibility.SHARE.allowsLive && TelemetryVisibility.PRIVATE.allowsLive)
        assertFalse(TelemetryVisibility.OFF.allowsLive)
        assertTrue(TelemetryVisibility.SHARE.sharesWithOthers)
        assertFalse(TelemetryVisibility.PRIVATE.sharesWithOthers || TelemetryVisibility.OFF.sharesWithOthers)
    }

    @Test
    fun latestChangeWinsAndTiesPreferTheMoreRestrictiveValue() {
        val share = TelemetryPreference(TelemetryVisibility.SHARE, 200)
        val off = TelemetryPreference(TelemetryVisibility.OFF, 100)
        assertEquals(share, share.merge(off))
        assertEquals(share, off.merge(share))
        val tieOff = TelemetryPreference(TelemetryVisibility.OFF, 200)
        assertEquals(tieOff, share.merge(tieOff))
        assertEquals(tieOff, tieOff.merge(share))
        assertEquals(share, share.merge(share))
        // Ein nie geänderter Standard verdrängt keine Änderung – auch nicht „Teilen“ durch das restriktivere „Nur für mich“
        assertEquals(share, share.merge(TelemetryPreference()))
        assertEquals(share, TelemetryPreference().merge(share))
    }

    @Test
    fun localChangeIsAlwaysNewerEvenWhenTheClockJumpedBack() {
        val stored = TelemetryPreference(TelemetryVisibility.SHARE, 5_000)
        val changed = stored.changedTo(TelemetryVisibility.OFF, nowMs = 1_000)
        assertEquals(TelemetryVisibility.OFF, changed.visibility)
        assertEquals(5_001, changed.changedAtMs)
        assertEquals(changed, stored.merge(changed))
        assertEquals(9_000, stored.changedTo(TelemetryVisibility.PRIVATE, 9_000).changedAtMs)
    }
}
