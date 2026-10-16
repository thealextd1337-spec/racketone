package eu.advantly.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TelemetryVisibilityTest {
    private val share = TelemetryVisibility.SHARE
    private val private = TelemetryVisibility.PRIVATE
    private val off = TelemetryVisibility.OFF

    @Test
    fun wireValuesMatchTheServerAndDefaultIsPrivate() {
        assertEquals(listOf("share", "private", "off"), TelemetryVisibility.entries.map { it.wire })
        assertEquals(private, TelemetryVisibility.DEFAULT)
        assertEquals(private, TelemetryPreference().visibility)
        assertTrue(TelemetryPreference().isDefault)
        TelemetryVisibility.entries.forEach { assertEquals(it, TelemetryVisibility.fromWire(it.wire)) }
        assertNull(TelemetryVisibility.fromWire("friends"))
        assertNull(TelemetryVisibility.fromWire(null))
    }

    @Test
    fun onlyOffStopsLiveValuesAndOnlyShareIsMarkedForOthers() {
        assertTrue(share.allowsLive && private.allowsLive)
        assertFalse(off.allowsLive)
        assertTrue(share.sharesWithOthers)
        assertFalse(private.sharesWithOthers || off.sharesWithOthers)
    }

    @Test
    fun localChangesCountPerDevice() {
        val first = TelemetryPreference().changedBy("phone", off)
        assertEquals(mapOf("phone" to 1L), first.versions)
        val second = first.changedBy("watch", share).changedBy("phone", private)
        assertEquals(private, second.visibility)
        assertEquals(mapOf("phone" to 2L, "watch" to 1L), second.versions)
    }

    /** Eine Änderung, die die andere schon kannte, gilt – auch wenn sie weniger restriktiv ist. */
    @Test
    fun causallyLaterChangeWinsInBothDirections() {
        val phoneOff = TelemetryPreference().changedBy("phone", off)
        val watchShareAfterSeeingIt = phoneOff.changedBy("watch", share)
        assertEquals(watchShareAfterSeeingIt, phoneOff.merge(watchShareAfterSeeingIt))
        assertSame(watchShareAfterSeeingIt, watchShareAfterSeeingIt.merge(phoneOff))
    }

    /** Beide Seiten haben seit dem letzten gemeinsamen Stand geändert: der restriktivere Wert gewinnt, egal in welcher Reihenfolge. */
    @Test
    fun concurrentChangesResolveToTheMoreRestrictiveValue() {
        val common = TelemetryPreference().changedBy("phone", private)
        val watchShare = common.changedBy("watch", share)
        val phoneOff = common.changedBy("phone", off)
        val a = watchShare.merge(phoneOff)
        val b = phoneOff.merge(watchShare)
        assertEquals(a, b)
        assertEquals(off, a.visibility)
        assertEquals(mapOf("phone" to 2L, "watch" to 1L), a.versions)
        // danach eine bewusste Änderung auf Basis des gemeinsamen Stands gilt wieder
        val reopened = a.changedBy("watch", share)
        assertEquals(reopened, a.merge(reopened))
    }

    @Test
    fun mergeIsIdempotentAndDefaultsNeverWin() {
        val changed = TelemetryPreference().changedBy("phone", share)
        assertSame(changed, changed.merge(changed))
        assertSame(changed, changed.merge(TelemetryPreference()))
        assertEquals(changed, TelemetryPreference().merge(changed))
        // gleicher Vektor, anderer Wert (nur durch Zusammenführung möglich) → restriktiver Wert, kommutativ
        val sameVectorOff = TelemetryPreference(off, changed.versions)
        assertEquals(sameVectorOff, changed.merge(sameVectorOff))
        assertEquals(sameVectorOff, sameVectorOff.merge(changed))
    }

    /** Drei Geräte, beliebige Reihenfolge: alle enden beim selben Ergebnis. */
    @Test
    fun threeDevicesConvergeRegardlessOfOrder() {
        val base = TelemetryPreference().changedBy("phone", private)
        val states =
            listOf(
                base.changedBy("phone", off),
                base.changedBy("watch", share),
                base.changedBy("tablet", private),
            )
        val results =
            listOf(listOf(0, 1, 2), listOf(2, 1, 0), listOf(1, 0, 2), listOf(1, 2, 0)).map { order ->
                order.map(states::get).reduce { acc, next -> acc.merge(next) }
            }
        assertTrue(results.all { it == results.first() }, results.toString())
        assertEquals(off, results.first().visibility)
    }
}
