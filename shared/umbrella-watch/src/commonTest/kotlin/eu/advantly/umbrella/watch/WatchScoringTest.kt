package eu.advantly.umbrella.watch

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.scoring.Presets
import eu.advantly.data.AppendResult
import eu.advantly.data.EventStore
import eu.advantly.data.StoredEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Einfacher EventStore für commonTest (läuft auf JVM und im watchOS-Simulator). */
internal class FakeEventStore : EventStore {
    val events = mutableListOf<StoredEvent>()

    override fun append(event: StoredEvent): AppendResult =
        when {
            events.any { it.id == event.id } -> {
                AppendResult.DUPLICATE
            }

            events.any { it.matchId == event.matchId && it.deviceId == event.deviceId && it.deviceSeq == event.deviceSeq } -> {
                AppendResult.SEQUENCE_CONFLICT
            }

            else -> {
                events += event
                AppendResult.INSERTED
            }
        }

    override fun eventsFor(matchId: String): List<StoredEvent> =
        events.filter { it.matchId == matchId }.sortedWith(compareBy({ it.lamport }, { it.deviceId }, { it.id }))

    override fun count(matchId: String): Long = events.count { it.matchId == matchId }.toLong()
}

class WatchScoringTest {
    private val store = FakeEventStore()
    private val prefs = InMemoryWatchPrefs()
    private var clock = 1_760_000_000_000L
    private val seen = mutableListOf<WatchScoreSnapshot>()

    private fun tick(): Long {
        clock += 1_000
        return clock
    }

    private fun scoring(seed: Int = 1): WatchScoring =
        WatchScoring(store, prefs, now = ::tick, random = Random(seed)).apply {
            setListener(
                object : WatchScoringListener {
                    override fun onSnapshot(snapshot: WatchScoreSnapshot) {
                        seen += snapshot
                    }
                },
            )
        }

    private fun WatchScoring.points(sequence: String): WatchScoreSnapshot? {
        var last: WatchScoreSnapshot? = null
        sequence.forEach { last = point(sideA = it == 'A') }
        return last
    }

    @Test
    fun presetsPerSportWithDefaultFirst() {
        val s = scoring()
        assertEquals(listOf("TENNIS", "PADEL", "PICKLEBALL", "BADMINTON", "TABLE_TENNIS"), s.sports())
        val tennis = s.presets("TENNIS", 2026, 10, 15)
        assertEquals(12, tennis.size)
        assertEquals("TENNIS_SINGLES_BO3", tennis.first().id)
        assertTrue(tennis.first().isDefault)
        assertEquals(1, tennis.count { it.isDefault })
        val open = tennis.single { it.id == "TENNIS_OPEN" }
        assertTrue(open.openMode)
        assertEquals(0, open.bestOf)
        assertTrue(open.singles && open.doubles)
        val padel = s.presets("PADEL", 2026, 10, 15).first()
        assertEquals("PADEL_PREMIER_2026", padel.id)
        assertFalse(padel.singles)
        assertTrue(padel.defaultDoubles)
        assertEquals(Presets.all.size, s.sports().sumOf { s.presets(it, 2026, 10, 15).size })
        assertTrue(s.presets("SQUASH", 2026, 10, 15).isEmpty())
    }

    @Test
    fun badmintonDefaultDependsOnDateAndTableTennisHasNone() {
        val s = scoring()
        assertEquals("BADMINTON_SINGLES_BO3_21", s.defaultPresetId("BADMINTON", 2027, 1, 3))
        assertEquals("BADMINTON_SINGLES_BO3_15", s.defaultPresetId("BADMINTON", 2027, 1, 4))
        assertEquals("", s.defaultPresetId("TABLE_TENNIS", 2026, 10, 15))
        assertTrue(s.presets("TABLE_TENNIS", 2026, 10, 15).none { it.isDefault })
        assertEquals("PICKLEBALL_DOUBLES_11_SIDEOUT", s.defaultPresetId("PICKLEBALL", 2026, 10, 15))
    }

    @Test
    fun startRejectsUnknownPresetAndWrongDiscipline() {
        val s = scoring()
        assertNull(s.startMatch("NOPE", doubles = false, weServeFirst = true))
        assertNull(s.startMatch("PADEL_PREMIER_2026", doubles = false, weServeFirst = true))
        assertFalse(s.hasActiveMatch())
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun startWritesGenesisAndReportsInitialState() {
        val s = scoring()
        val snap = assertNotNull(s.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = false))
        assertEquals("TENNIS", snap.sport)
        assertEquals("TENNIS_SINGLES_BO3", snap.presetId)
        assertEquals("IN_PROGRESS", snap.status)
        assertEquals("0", snap.pointsA)
        assertEquals("0", snap.pointsB)
        assertEquals(0, snap.gamesA)
        assertEquals("B", snap.serveSide)
        assertEquals("B1", snap.server)
        assertEquals("RIGHT", snap.serveCourt)
        assertEquals(3, snap.bestOf)
        assertFalse(snap.canUndo)
        assertEquals(listOf(snap), seen)
        assertEquals(1, store.events.size)
        assertTrue(
            store.events
                .single()
                .payload
                .contains("\"type\":\"MatchCreated\""),
        )
        assertEquals(snap.matchId, s.activeMatchId())
        assertTrue(s.hasActiveMatch())
    }

    @Test
    fun pointsGamesAndBreakPointHint() {
        val s = scoring()
        s.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true)
        val fifteen = assertNotNull(s.point(sideA = true))
        assertEquals("15", fifteen.pointsA)
        assertEquals(listOf("POINT"), fifteen.effects)
        val game = assertNotNull(s.points("AAA"))
        assertEquals(1, game.gamesA)
        assertEquals("0", game.pointsA)
        assertTrue("GAME_WON" in game.effects)
        assertEquals("B", game.serveSide)
        // B schlägt auf, A führt 0:40 → 3 Breakbälle für A
        val breakPoint = assertNotNull(s.points("AAA"))
        assertEquals("40", breakPoint.pointsA)
        assertEquals("BREAK_POINT", breakPoint.hintCode)
        assertEquals("A", breakPoint.hintSide)
        assertEquals(3, breakPoint.hintCount)
        // Einstand ist ein neutraler Hinweis
        val deuce = assertNotNull(s.points("BBB"))
        assertEquals("DEUCE", deuce.hintCode)
        assertEquals("", deuce.hintSide)
        val adv = assertNotNull(s.point(sideA = true))
        assertEquals("AD", adv.pointsA)
        assertEquals("ADVANTAGE_RECEIVER", adv.callout)
        assertEquals("BREAK_POINT", adv.hintCode)
        assertEquals(1, adv.hintCount)
    }

    @Test
    fun undoRestoresAndWorksAcrossGames() {
        val s = scoring()
        s.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true)
        assertNull(s.undo())
        s.points("AAAA")
        val undone = assertNotNull(s.undo())
        assertEquals(0, undone.gamesA)
        assertEquals("40", undone.pointsA)
        assertTrue("UNDONE" in undone.effects)
        s.undo()
        s.undo()
        val start = assertNotNull(s.undo())
        assertEquals("0", start.pointsA)
        assertFalse(start.canUndo)
        assertNull(s.undo())
    }

    @Test
    fun openModeAsksForAnotherSetAndOffersMatchTiebreak() {
        val s = scoring()
        val start = assertNotNull(s.startMatch("TENNIS_OPEN", doubles = false, weServeFirst = true))
        assertTrue(start.openMode)
        assertTrue(start.offerMatchTiebreak)
        val setEnd = assertNotNull(s.points("A".repeat(24)))
        assertTrue(setEnd.pendingDecision)
        assertEquals("AWAITING_DECISION", setEnd.status)
        assertEquals(listOf(6), setEnd.setScoresA)
        assertEquals(listOf(0), setEnd.setScoresB)
        assertEquals(listOf(-1), setEnd.setTiebreaksA)
        assertEquals(listOf("A"), setEnd.setWinners)
        assertEquals("", setEnd.pointsA)
        assertTrue("ASK_ANOTHER_SET" in setEnd.effects)
        assertNull(s.point(sideA = true), "kein Punkt während des Satzende-Dialogs")
        val mtb = assertNotNull(s.continueDecision(anotherSet = true, matchTiebreak = true))
        assertEquals("MATCH_TIEBREAK", mtb.setKind)
        assertEquals("MATCH_TIEBREAK", mtb.hintCode)
        assertNull(s.continueDecision(anotherSet = false, matchTiebreak = false), "kein Dialog offen")
        s.points("A".repeat(10))
        val again = assertNotNull(s.snapshot())
        assertTrue(again.pendingDecision)
        assertEquals(listOf(6, 10), again.setScoresA)
        val done = assertNotNull(s.continueDecision(anotherSet = false, matchTiebreak = false))
        assertTrue(done.matchFinished)
        assertEquals("A", done.winner)
        assertEquals("COMPLETED", done.resultReason)
        // Matchende ist rückgängig machbar
        assertTrue(assertNotNull(s.undo()).pendingDecision)
    }

    @Test
    fun bestOfMatchFinishesWithMatchPointHint() {
        val s = scoring()
        s.startMatch("TENNIS_ONE_SET", doubles = false, weServeFirst = true)
        val matchPoint = assertNotNull(s.points("A".repeat(23)))
        assertEquals("MATCH_POINT", matchPoint.hintCode)
        assertEquals("A", matchPoint.hintSide)
        val done = assertNotNull(s.point(sideA = true))
        assertTrue(done.matchFinished)
        assertEquals("A", done.winner)
        assertTrue("MATCH_WON" in done.effects)
        assertEquals("", done.hintCode)
        assertNull(s.point(sideA = false))
    }

    @Test
    fun endAbandonsRunningMatchAndIsUndoable() {
        val s = scoring()
        s.startMatch("PADEL_PREMIER_2026", doubles = true, weServeFirst = true)
        s.points("AB")
        val ended = assertNotNull(s.end())
        assertTrue(ended.matchFinished)
        assertEquals("ABANDONED", ended.resultReason)
        assertEquals("", ended.winner)
        assertEquals("ABANDONED", assertNotNull(s.end()).resultReason, "zweites end() ist ein No-op")
        assertEquals("IN_PROGRESS", assertNotNull(s.undo()).status)
    }

    @Test
    fun endDuringSetEndDialogFinishesRegularly() {
        val s = scoring()
        s.startMatch("BADMINTON_OPEN", doubles = false, weServeFirst = true)
        val setPoint = assertNotNull(s.points("A".repeat(14)))
        assertEquals("SET_POINT", setPoint.hintCode)
        assertEquals(14, setPoint.hintCount)
        assertTrue(assertNotNull(s.point(sideA = true)).pendingDecision)
        val ended = assertNotNull(s.end())
        assertEquals("COMPLETED", ended.resultReason)
        assertEquals("A", ended.winner)
        assertEquals(listOf(15), ended.setScoresA)
    }

    @Test
    fun resumeAfterRestartRestoresStateAndKeepsCounting() {
        val first = scoring(seed = 1)
        first.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true)
        val before = assertNotNull(first.points("AAAAB"))
        // „Neustart“: neue Facade auf demselben Store und denselben Prefs
        val second = scoring(seed = 2)
        assertTrue(second.hasActiveMatch())
        val resumed = assertNotNull(second.resume())
        assertEquals(before.matchId, resumed.matchId)
        assertEquals(1, resumed.gamesA)
        assertEquals("15", resumed.pointsB)
        assertEquals(before.eventCount, resumed.eventCount)
        assertTrue(resumed.canUndo)
        val next = assertNotNull(second.point(sideA = false))
        assertEquals("30", next.pointsB)
        assertEquals(before.eventCount + 1, next.eventCount)
        assertEquals("15", assertNotNull(second.undo()).pointsB)
        // deviceSeq lückenlos, keine Sequenzkonflikte
        assertEquals((1L..store.events.size).toList(), store.events.map { it.deviceSeq })
        second.close()
        assertFalse(second.hasActiveMatch())
        assertNull(scoring().resume())
    }

    @Test
    fun resumeDropsPointerToUnreadableMatch() {
        prefs.set(WatchScoring.KEY_ACTIVE_MATCH, "missing")
        val s = scoring()
        assertNull(s.resume())
        assertFalse(s.hasActiveMatch())
    }

    @Test
    fun lastSetupRemembersPresetAndChoices() {
        val s = scoring()
        assertNull(s.lastSetup())
        s.startMatch("TENNIS_BO3_MTB10", doubles = true, weServeFirst = false)
        val setup = assertNotNull(scoring().lastSetup())
        assertEquals("TENNIS_BO3_MTB10", setup.presetId)
        assertEquals("TENNIS", setup.sport)
        assertTrue(setup.doubles)
        assertFalse(setup.weServeFirst)
    }

    @Test
    fun deviceIdIsStable() {
        val id = scoring().deviceId()
        assertEquals(36, id.length)
        assertEquals(id, scoring(seed = 9).deviceId())
    }

    @Test
    fun startFromFormatJson() {
        val s = scoring()
        val json =
            WatchScoring.eventJson.encodeToString(MatchFormat.serializer(), Presets.PICKLEBALL_RALLY_BO3_15.format(Discipline.SINGLES))
        val snap = assertNotNull(s.startMatchFromFormatJson(json, weServeFirst = true))
        assertEquals("PICKLEBALL", snap.sport)
        assertFalse(snap.doubles)
        assertEquals("0-0", snap.scoreCall)
        assertNull(s.startMatchFromFormatJson("{\"sport\":\"TENNIS\"}", weServeFirst = true))
        assertNull(s.startMatchFromFormatJson("kein json", weServeFirst = true))
        assertNull(s.lastSetup(), "Format-Starts überschreiben „Wie letztes Mal“ nicht")
    }

    @Test
    fun pickleballDoublesCallAndTableTennisServes() {
        val s = scoring()
        val pb = assertNotNull(s.startMatch("PICKLEBALL_DOUBLES_11_SIDEOUT", doubles = true, weServeFirst = true))
        assertEquals("0-0-2", pb.scoreCall)
        assertEquals(2, pb.serverNumber)
        val tt = assertNotNull(s.startMatch("TABLE_TENNIS_SINGLES_BO5", doubles = false, weServeFirst = true))
        assertEquals(2, tt.servesRemaining)
        assertEquals("", tt.serveCourt)
        assertEquals(-1, tt.gamesA)
        assertEquals("RALLY_GAME", tt.gameKind)
    }

    @Test
    fun storedPayloadsRoundTrip() {
        val s = scoring()
        s.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true)
        s.points("AB")
        s.undo()
        val types = store.events.map { Regex("\"type\":\"(\\w+)\"").findAll(it.payload).last().groupValues[1] }
        assertEquals(listOf("MatchCreated", "PointWon", "PointWon", "Undo"), types)
        assertEquals(listOf(1L, 2L, 3L, 4L), store.events.map { it.lamport })
    }
}
