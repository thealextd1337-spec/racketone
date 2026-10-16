package eu.advantly.core.model

import kotlin.random.Random
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
        assertEquals(TelemetryPreference(private, 0, null), TelemetryPreference())
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
    fun localChangeIsOneAboveTheLargestSeenCounter() {
        val first = TelemetryPreference().changedBy("phone", off)
        assertEquals(TelemetryPreference(off, 1, "phone"), first)
        val seen = first.merge(TelemetryPreference(share, 5, "watch"))
        assertEquals(TelemetryPreference(private, 6, "phone"), seen.changedBy("phone", private))
    }

    /** Eine Änderung, die die andere schon kannte, gilt – auch wenn sie weniger restriktiv ist. */
    @Test
    fun causallyLaterChangeWins() {
        val phoneOff = TelemetryPreference().changedBy("phone", off)
        val watchShareAfterSeeingIt = phoneOff.changedBy("watch", share)
        assertSame(watchShareAfterSeeingIt, phoneOff.merge(watchShareAfterSeeingIt))
        assertSame(watchShareAfterSeeingIt, watchShareAfterSeeingIt.merge(phoneOff))
    }

    /** Konkurrierende Offline-Änderungen mit gleichem Zähler: der restriktivere Wert gewinnt, Reihenfolge egal. */
    @Test
    fun concurrentChangesWithTheSameCounterResolveRestrictively() {
        val common = TelemetryPreference().changedBy("phone", private)
        val watchShare = common.changedBy("watch", share)
        val phoneOff = common.changedBy("phone", off)
        assertEquals(phoneOff, watchShare.merge(phoneOff))
        assertEquals(phoneOff, phoneOff.merge(watchShare))
        // gleicher Zähler und Wert, andere Herkunft: lexikografisch, aber auf allen Geräten gleich
        val a = TelemetryPreference(off, 3, "a")
        val b = TelemetryPreference(off, 3, "b")
        assertEquals(b, a.merge(b))
        assertEquals(b, b.merge(a))
    }

    /** Bewusster, dokumentierter Kompromiss: mehrere Offline-Änderungen schlagen eine einzelne konkurrierende. */
    @Test
    fun multipleOfflineChangesCanBeatASingleConcurrentOne() {
        val watch = TelemetryPreference().changedBy("watch", private).changedBy("watch", share)
        val phone = TelemetryPreference().changedBy("phone", off)
        assertEquals(share, watch.merge(phone).visibility)
    }

    /** Gegenbeispiel aus Review-Runde 2 (Versionsvektor war nicht assoziativ). */
    @Test
    fun reviewCounterexampleIsAssociativeNow() {
        val a = TelemetryPreference(off, 1, "phone")
        val b = TelemetryPreference(private, 2, "phone")
        val c = TelemetryPreference(share, 1, "watch")
        assertEquals(a.merge(c).merge(b), a.merge(c.merge(b)))
        assertEquals(b, a.merge(c).merge(b))
    }

    @Test
    fun defaultsNeverWin() {
        val changed = TelemetryPreference().changedBy("phone", share)
        assertSame(changed, changed.merge(TelemetryPreference()))
        assertSame(changed, TelemetryPreference().merge(changed))
    }

    /** Property-artig mit festem Seed: Halbverbandsgesetze über viele zufällige Zustände. */
    @Test
    fun mergeIsCommutativeAssociativeAndIdempotent() {
        val random = Random(47)
        val origins = listOf("phone", "watch", "tablet", "server")

        fun next(): TelemetryPreference =
            if (random.nextInt(8) == 0) {
                TelemetryPreference()
            } else {
                TelemetryPreference(TelemetryVisibility.entries.random(random), random.nextLong(1, 6), origins.random(random))
            }
        repeat(2_000) {
            val a = next()
            val b = next()
            val c = next()
            assertEquals(a.merge(b), b.merge(a), "kommutativ: $a $b")
            assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)), "assoziativ: $a $b $c")
            assertEquals(a, a.merge(a), "idempotent: $a")
            // Ergebnis ist immer einer der Eingänge (konstante Größe, nichts Erfundenes)
            assertTrue(a.merge(b) == a || a.merge(b) == b)
        }
    }

    /** Geräte, die Änderungen in beliebiger Reihenfolge sehen, enden beim selben Stand. */
    @Test
    fun anyDeliveryOrderConverges() {
        val random = Random(7)
        repeat(200) {
            val changes = List(5) { TelemetryPreference(TelemetryVisibility.entries.random(random), random.nextLong(1, 4), "d$it") }
            val results = List(6) { changes.shuffled(random).fold(TelemetryPreference()) { acc, next -> acc.merge(next) } }
            assertTrue(results.all { it == results.first() })
        }
    }
}
