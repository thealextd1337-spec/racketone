package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthRepositoryTest {
    private val watch = DeviceId("watch")
    private val phone = DeviceId("phone")

    private fun series(
        bpm: List<Int?>,
        steps: List<Long?> = bpm.map { null },
    ) = HealthSeries(1_000, 5_000, bpm, steps, -1_500, HealthQuality.GAPS)

    private fun LocalData.match(id: String): MatchId {
        val self = players.ensureSelf("Alex", 1) { PlayerId("self") }.id
        matches.insertIfAbsent(
            MatchId(id),
            Presets.BADMINTON_1GAME_15.format(),
            Lineup(listOf(self), listOf(PlayerId("x")), self),
            MatchMeta(),
            1,
        )
        return MatchId(id)
    }

    @Test
    fun roundTripKeepsGapsAndOnlyAHigherRevisionWins() {
        val data = LocalData(createTestDriver())
        val m = data.match("m")
        val first = StoredHealthSeries(m, watch, 2, series(listOf(90, null, 120), listOf(0, null, 17)))
        assertTrue(data.health.upsert(first))
        assertEquals(listOf(first), data.health.forMatch(m))
        assertFalse(data.health.upsert(first.copy(revision = 2, series = series(listOf(1)))))
        assertFalse(data.health.upsert(first.copy(revision = 1, series = series(listOf(1)))))
        assertEquals(listOf(first), data.health.forMatch(m))
        val newer = first.copy(revision = 3, series = series(listOf(null)))
        assertTrue(data.health.upsert(newer))
        val empty = StoredHealthSeries(m, phone, 1, series(emptyList()))
        assertTrue(data.health.upsert(empty))
        assertEquals(listOf(empty, newer), data.health.forMatch(m))
        assertEquals(listOf(Triple(m, phone, 1L), Triple(m, watch, 3L)), data.health.revisions())
    }

    @Test
    fun seriesWithoutMatchHeaderIsRejected() {
        val data = LocalData(createTestDriver())
        assertFalse(data.health.upsert(StoredHealthSeries(MatchId("unknown"), watch, 1, series(listOf(100)))))
        assertTrue(data.health.allSeries().isEmpty())
    }

    @Test
    fun deletingTheMatchRemovesItsSeries() {
        val data = LocalData(createTestDriver())
        val gone = data.match("gone")
        val kept = data.match("kept")
        data.health.upsert(StoredHealthSeries(gone, watch, 1, series(listOf(100))))
        data.health.upsert(StoredHealthSeries(kept, watch, 1, series(listOf(110))))
        data.deleteMatch(gone)
        assertEquals(listOf(kept), data.health.allSeries().map { it.matchId })
        // Gelöscht und mit Löschmarkierung: eine verspätete Serie legt keine verwaiste Zeile an.
        assertFalse(data.health.upsert(StoredHealthSeries(gone, watch, 2, series(listOf(100)))))
        data.health.deleteForMatch(kept)
        assertTrue(data.health.allSeries().isEmpty())
    }

    @Test
    fun recordsAndDeleteAll() {
        val data = LocalData(createTestDriver())
        assertEquals(HealthRecords(null, null), data.health.records())
        val a = data.match("a")
        val b = data.match("b")
        data.health.upsert(StoredHealthSeries(a, watch, 1, series(listOf(150, 171))))
        data.health.upsert(StoredHealthSeries(b, watch, 1, series(listOf(187, null))))
        data.health.upsert(StoredHealthSeries(b, phone, 1, series(listOf(null))))
        val records = data.health.records()
        assertEquals(HealthRecords(187, b), records)
        assertEquals("HealthRecords(<redacted>)", records.toString())
        assertFalse("187" in data.health.allSeries().toString())
        data.health.deleteAll()
        assertTrue(data.health.allSeries().isEmpty())
        assertEquals(
            listOf(a, b),
            data.matches
                .recent(10)
                .map { it.id }
                .sortedBy { it.value },
        )
    }

    @Test
    fun watchListsItsOwnSeriesDeletesSinglySourcesAndExpiresOldOnes() {
        val data = LocalData(createTestDriver())
        val m = data.match("m")
        val n = data.match("n")
        val own = StoredHealthSeries(m, watch, 1, series(listOf(100)))
        val old = StoredHealthSeries(n, watch, 1, series(listOf(100)).copy(startMs = 10))
        data.health.upsert(own)
        data.health.upsert(old)
        data.health.upsert(StoredHealthSeries(m, phone, 1, series(listOf(80))))
        assertEquals(listOf(own, old), data.health.forSource(watch))
        data.health.delete(m, phone)
        assertEquals(listOf(own), data.health.forMatch(m))
        data.health.deleteStartedBefore(500)
        assertEquals(listOf(own), data.health.forSource(watch))
    }

    /** Review H1: Zwei Konten auf einem Gerät, beide im selben Match – jedes sieht und löscht nur seine eigenen Serien. */
    @Test
    fun twoAccountsOnOneDeviceSeeOnlyTheirOwnSeries() {
        val data = LocalData(createTestDriver())
        val m = data.match("m")
        data.settings.put("identity.scope", "account-a")
        val mine = StoredHealthSeries(m, watch, 5, series(listOf(150)))
        assertTrue(data.health.upsert(mine))
        data.settings.put("identity.scope", "account-b")
        assertTrue(data.health.forMatch(m).isEmpty(), "Mitspieler-Konto sieht die Serie nicht")
        assertTrue(data.health.allSeries().isEmpty())
        assertTrue(data.health.revisions().isEmpty())
        assertTrue(data.health.forSource(watch).isEmpty())
        assertEquals(HealthRecords(null, null), data.health.records())
        // eigene Serie desselben Matches und derselben Quelle, auch mit niedrigerer Revision: kein Überschreiben
        val theirs = StoredHealthSeries(m, watch, 1, series(listOf(90)))
        assertTrue(data.health.upsert(theirs))
        assertEquals(listOf(theirs), data.health.forMatch(m))
        data.settings.put("identity.scope", "account-a")
        assertEquals(listOf(mine), data.health.forMatch(m))
        data.health.deleteAll(startedBeforeMs = 1_000)
        assertEquals(listOf(mine), data.health.allSeries(), "begann nicht vor dem Zeitpunkt")
        data.health.deleteAll()
        assertTrue(data.health.allSeries().isEmpty())
        data.settings.put("identity.scope", "account-b")
        assertEquals(listOf(theirs), data.health.allSeries(), "Löschen im anderen Konto lässt die Serie stehen")
        data.health.delete(m, watch)
        assertTrue(data.health.allSeries().isEmpty())
    }

    /** Vor dem ersten Kontobereich gespeichert (Uhr ohne Hello): gehört dem ersten Bereich des Geräts. */
    @Test
    fun seriesStoredBeforeTheFirstScopeBelongToIt() {
        val data = LocalData(createTestDriver())
        val m = data.match("m")
        val early = StoredHealthSeries(m, watch, 1, series(listOf(100)))
        assertTrue(data.health.upsert(early))
        data.accountIdentities.initialize("watch", "self", "WATCH")
        assertEquals("legacy:watch", data.accountIdentities.scope)
        assertEquals(listOf(early), data.health.forMatch(m))
    }
}
