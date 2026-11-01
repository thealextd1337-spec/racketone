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
}
