package eu.advantly.gate.check

import eu.advantly.core.model.Side.A
import eu.advantly.core.model.Side.B
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrityCheckTest {
    private fun single(vararg snapshots: DeviceSnapshot): MatchIntegrity = IntegrityCheck.check(snapshots.toList()).single()

    @Test
    fun identicalDatabasesPass() {
        val log = LogBuilder().apply { points(WATCH, A, A, B, A, A) }
        val result = single(snapshot("handy", PHONE, MATCH to log.stored()), snapshot("uhr", WATCH, MATCH to log.stored()))

        assertEquals(Verdict.PASSED, result.verdict, result.findings.toString())
        assertEquals(5, result.union?.accepted)
        assertEquals(listOf("handy", "uhr"), result.devices.filter { it.matchesUnion }.map { it.label })
        assertEquals(result.devices[0].fold?.state, result.devices[1].fold?.state)
        assertEquals("uhr", result.authors.first { it.deviceId == WATCH }.label)
        assertEquals("TENNIS", result.sport)
    }

    @Test
    fun unconfirmedEventsOnlyOnWatchAreOpen() {
        val log = LogBuilder().apply { points(WATCH, A, B) }
        val phone = log.stored()
        log.points(WATCH, A, A) // Flugmodus: noch nicht beim Handy
        val watch = log.stored().mapIndexed { i, e -> if (i >= phone.size) e.copy(confirmed = false) else e }

        val result = single(snapshot("handy", PHONE, MATCH to phone), snapshot("uhr", WATCH, MATCH to watch))

        assertEquals(Verdict.OPEN, result.verdict)
        assertTrue(result.findings.any { it.verdict == Verdict.OPEN && "2 unbestätigte" in it.text }, result.findings.toString())
        assertEquals(2, result.devices.first { it.label == "uhr" }.unconfirmed)
        assertEquals(2, result.devices.first { it.label == "handy" }.missing)
    }

    @Test
    fun confirmedEventMissingOnPhoneFails() {
        val log = LogBuilder().apply { points(WATCH, A, B, A) }
        val watch = log.stored()
        val phone = watch.dropLast(1) // Uhr hält das letzte Event für bestätigt, das Handy hat es nicht

        val result = single(snapshot("handy", PHONE, MATCH to phone), snapshot("uhr", WATCH, MATCH to watch))

        assertEquals(Verdict.FAILED, result.verdict)
        assertTrue(result.findings.any { "als bestätigt markiert" in it.text }, result.findings.toString())
    }

    @Test
    fun sequenceGapOnAllDevicesFails() {
        val log = LogBuilder().apply { points(WATCH, A, B, A, B) }
        val lost = log.stored().filterNot { it.deviceId == WATCH && it.deviceSeq == 3L }

        val result = single(snapshot("handy", PHONE, MATCH to lost), snapshot("uhr", WATCH, MATCH to lost))

        assertEquals(Verdict.FAILED, result.verdict)
        assertEquals(listOf(3L..3L), result.authors.first { it.deviceId == WATCH }.gaps)
        assertTrue(result.findings.any { "Sequenzlücke bei uhr" in it.text }, result.findings.toString())
    }

    @Test
    fun differingCopiesFail() {
        val log = LogBuilder().apply { points(WATCH, A, B) }
        val phone = log.stored()
        val watch =
            phone.map {
                if (it.deviceSeq == 2L &&
                    it.deviceId == WATCH
                ) {
                    it.copy(payload = """{"type":"PointWon","side":"A"}""")
                } else {
                    it
                }
            }

        val result = single(snapshot("handy", PHONE, MATCH to phone), snapshot("uhr", WATCH, MATCH to watch))

        assertEquals(Verdict.FAILED, result.verdict)
        assertTrue(result.findings.any { "abweichenden Kopien" in it.text })
    }

    @Test
    fun lateEventsOfOldScorerAreRejectedButNotLost() {
        // Uhr zählt, Handy übernimmt (Uhr-Ausfall); die Uhr liefert danach verspätete Punkte nach
        val log =
            LogBuilder().apply {
                points(WATCH, A, A)
                handover(PHONE, PHONE)
                points(PHONE, B)
                points(WATCH, A, A) // verspätet, Uhr ist nicht mehr Zähler
                handover(PHONE, WATCH) // Rückgabe an die Uhr
                points(WATCH, B)
            }
        val stored = log.stored()

        val result = single(snapshot("handy", PHONE, MATCH to stored), snapshot("uhr", WATCH, MATCH to stored))

        assertEquals(Verdict.PASSED, result.verdict, result.findings.toString())
        assertEquals(2, result.union?.roleRejected)
        assertEquals(4, result.union?.accepted)
        assertEquals(2, result.authors.first { it.deviceId == WATCH }.roleRejected)
        assertEquals(listOf("handy", "uhr"), result.scorerChanges.map { it.toLabel })
        assertTrue(result.findings.any { "Rollenprüfung" in it.text })
    }

    @Test
    fun watchWithSubsetIsOpen() {
        val log = LogBuilder(scorer = PHONE).apply { points(PHONE, A, B, B) }
        val phone = log.stored()
        val watch = phone.take(2)

        val result = single(snapshot("handy", PHONE, MATCH to phone), snapshot("uhr", WATCH, MATCH to watch))

        assertEquals(Verdict.OPEN, result.verdict)
        assertTrue(result.findings.any { "uhr hat nur 2 von 4" in it.text }, result.findings.toString())
    }

    @Test
    fun matchOnlyOnPhonePasses() {
        val log = LogBuilder(scorer = PHONE).apply { points(PHONE, A) }

        val result = single(snapshot("handy", PHONE, MATCH to log.stored(confirmed = false)), snapshot("uhr", WATCH))

        assertEquals(Verdict.PASSED, result.verdict, result.findings.toString())
        assertEquals(false, result.devices.first { it.label == "uhr" }.present)
    }

    @Test
    fun deletedOnReferenceIsNotAGateMatch() {
        val log = LogBuilder().apply { points(WATCH, A) }

        val results =
            IntegrityCheck.check(
                listOf(
                    snapshot("handy", PHONE, tombstones = mapOf(MATCH to 5L)),
                    snapshot("uhr", WATCH, MATCH to log.stored(confirmed = false)),
                ),
            )

        assertEquals(Verdict.DELETED, results.single().verdict)
        assertEquals(Verdict.OPEN, IntegrityCheck.overall(results)) // nur Gelöschtes = kein Nachweis
    }

    @Test
    fun overallIsTheWorstVerdict() {
        val ok = LogBuilder("m-ok").apply { points(WATCH, A) }
        val open = LogBuilder("m-open").apply { points(WATCH, B) }
        val results =
            IntegrityCheck.check(
                listOf(
                    snapshot("handy", PHONE, "m-ok" to ok.stored(), "m-open" to open.stored().take(1)),
                    snapshot("uhr", WATCH, "m-ok" to ok.stored(), "m-open" to open.stored(confirmed = false)),
                ),
            )
        assertEquals(listOf(Verdict.PASSED, Verdict.OPEN), results.sortedBy { it.matchId }.map { it.verdict })
        assertEquals(Verdict.OPEN, IntegrityCheck.overall(results))
    }

    @Test
    fun filterRestrictsMatches() {
        val a = LogBuilder("m-a").apply { points(WATCH, A) }
        val b = LogBuilder("m-b").apply { points(WATCH, A) }
        val snap = snapshot("handy", PHONE, "m-a" to a.stored(), "m-b" to b.stored())
        assertEquals(listOf("m-b"), IntegrityCheck.check(listOf(snap)) { id, _ -> id == "m-b" }.map { it.matchId })
    }

    @Test
    fun headerWithoutAnyEventFails() {
        val header = HeaderInfo("TENNIS", "IN_PROGRESS", 1, 0, 0, null)
        val phone = snapshot("handy", PHONE).copy(matches = mapOf(MATCH to MatchData(emptyList(), emptySet(), header)))

        val result = single(phone, snapshot("uhr", WATCH))

        assertEquals(Verdict.FAILED, result.verdict)
        assertEquals("TENNIS", result.sport)
    }

    @Test
    fun gapsAreComputedFromOne() {
        assertEquals(emptyList(), IntegrityCheck.gapsOf(listOf(1L, 2L, 3L)))
        assertEquals(listOf(1L..1L, 4L..5L), IntegrityCheck.gapsOf(listOf(3L, 2L, 6L)))
    }

    @Test
    fun reportsContainVerdictAndScore() {
        val log = LogBuilder().apply { points(WATCH, A, A, A, A) }
        val result = single(snapshot("handy", PHONE, MATCH to log.stored()), snapshot("uhr", WATCH, MATCH to log.stored()))

        val index = IntegrityReport.index(listOf(result), listOf("handy" to "a.db", "uhr" to "b.db"), 0)
        val page = IntegrityReport.match(result)

        assertTrue("**Gesamt: BESTANDEN**" in index, index)
        assertTrue(IntegrityReport.fileName(result).endsWith("-tennis-${IntegrityCheck.short(MATCH)}.md"))
        assertTrue("laufend 1:0 (0:0)" in page, page) // ein gewonnenes Spiel
        assertTrue("Zähler am Ende: uhr" in page, page)
        assertTrue("| uhr | ja | 5 | 0 | 0 |" in page, page)
    }
}
