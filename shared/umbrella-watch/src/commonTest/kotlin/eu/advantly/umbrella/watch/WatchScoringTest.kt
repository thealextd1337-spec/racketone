package eu.advantly.umbrella.watch

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.CorrectionKind
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.PeerInfo
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.ScorerChoice
import eu.advantly.match.session.ThreadGuard
import eu.advantly.match.session.WallClock
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frische, leere Datenbank je Aufruf (JVM: JDBC in-memory, watchOS-Simulator: native-driver in-memory). */
internal expect fun testLocalData(): LocalData

/**
 * Uhr (über die Facade) und optional ein Handy (direkt über [MatchSession]) in einem In-Memory-Netz mit virtueller Zeit.
 * „Neustart“ = neue Session + neue Facade auf derselben Datenbank.
 */
private class Rig(
    private val demoEnabled: Boolean = false,
) {
    val scheduler = ManualScheduler()
    val network = InMemoryLinkNetwork(scheduler, latencyMs = 20)
    val clock = WallClock { 1_760_000_000_000L + scheduler.nowMs() }
    val watchData = testLocalData()
    val snapshots = mutableListOf<WatchScoreSnapshot?>()
    private var seed = 1
    var schedulerThread = true

    var watchSession: MatchSession = newSession("watch", PeerKind.WATCH, watchData)
    var watch: WatchScoring = facade()
    val phoneData: LocalData by lazy { testLocalData() }
    val phone: MatchSession by lazy { newSession("phone", PeerKind.PHONE, phoneData).also { settle() } }

    fun newSession(
        name: String,
        kind: PeerKind,
        data: LocalData,
    ): MatchSession {
        val link = ReliableDeviceLink(network.transport(name), scheduler, newMessageId = { "$name-${seed++}-${scheduler.nowMs()}" })
        return MatchSessions
            .create(
                data,
                link,
                scheduler,
                kind,
                clock = clock,
                ids = RandomIdGenerator(Random(seed++)),
                threadGuard = ThreadGuard { schedulerThread },
            ).also { it.start() }
    }

    fun facade(): WatchScoring =
        WatchScoring({ WatchScoring.Backend(watchSession, watchData) }, clock, RandomIdGenerator(Random(seed++)), demoEnabled).apply {
            setListener(
                object : WatchScoringListener {
                    override fun onSnapshot(snapshot: WatchScoreSnapshot?) {
                        snapshots += snapshot
                    }
                },
            )
            isAvailable()
        }

    /** App-Neustart der Uhr: alte Session schließen, neue auf derselben Datenbank. */
    fun restartWatch() {
        watchSession.close()
        watchSession = newSession("watch", PeerKind.WATCH, watchData)
        watch = facade()
    }

    fun settle(ms: Long = 2_000) = scheduler.advanceBy(ms)

    fun points(sequence: String): WatchScoreSnapshot? {
        var last: WatchScoreSnapshot? = null
        sequence.forEach { last = watch.point(sideA = it == 'A') }
        return last
    }
}

class WatchScoringTest {
    @Test
    fun liveOpeningChoiceCoversBothSportsAndTiebreakFormats() {
        for (preset in listOf("TENNIS_OPEN", "PADEL_GOLDEN_BO3", "TENNIS_TIEBREAK_ONLY_7", "PADEL_TIEBREAK_ONLY_10")) {
            val rig = Rig()
            val start = assertNotNull(rig.watch.startConfiguredMatch(preset, true, false, 0, true, "", "", "", "SELF"))
            val first = assertNotNull(start.serverChoiceContext, preset)
            assertTrue(start.serverChoicePrompt, preset)
            assertEquals(4, first.players.size)
            assertEquals("FIRST", first.phase)
            val chosen = assertNotNull(rig.watch.chooseServer(first.players.last(), first))
            assertFalse(chosen.serverChoicePrompt)
            assertEquals(0, chosen.pointsPlayed)
            rig.points(if (first.gameKind == "STANDARD") "AAAA" else "A")
            val second = assertNotNull(rig.watch.snapshot()).serverChoiceContext!!
            assertEquals("SECOND", second.phase)
            assertEquals(2, second.players.size)
            val before = assertNotNull(rig.watch.snapshot()).eventCount
            assertNull(rig.watch.chooseServer(second.players.first(), first))
            assertEquals(before, assertNotNull(rig.watch.snapshot()).eventCount)
            assertNotNull(rig.watch.chooseServer(second.players.last(), second))
        }
    }

    @Test
    fun flatChoiceContextRejectsStaleTapsAndKeepsRepeatedChoiceIdempotent() {
        val rig = Rig()
        val start = assertNotNull(rig.watch.startMatch("PADEL_GOLDEN_BO3", true, true, false))
        val first = assertNotNull(start.serverChoiceContext)
        assertEquals(start.matchId, first.matchId)
        assertEquals(0, first.setIndex)
        assertEquals("FIRST", first.phase)
        assertEquals("STANDARD", first.gameKind)
        assertEquals(start.server, first.defaultServer)
        assertEquals(start.serverChoicePlayers.map { it.id }, first.players)
        val opponent = start.serverChoicePlayers.last { it.side == "B" }.id
        val selected = assertNotNull(rig.watch.chooseServer(opponent, first))
        assertEquals(opponent, selected.serverChoiceContext?.chosen)
        assertEquals("B", selected.serverChoiceContext?.dueSide)
        assertEquals(selected.eventCount, assertNotNull(rig.watch.chooseServer(opponent, first)).eventCount)
        rig.points("BBBB")
        val second = assertNotNull(rig.watch.snapshot())
        val context = assertNotNull(second.serverChoiceContext)
        assertEquals("SECOND", context.phase)
        val ours = context.players.last()
        assertNull(rig.watch.chooseServer(ours, first), "in beiden Fenstern zulässig, aber stale")
        assertEquals("NOT_ALLOWED", rig.watch.lastError())
        assertEquals(second.eventCount, assertNotNull(rig.watch.snapshot()).eventCount)
        assertNotNull(rig.watch.chooseServer(ours, context))
        rig.watch.close()
        val next = assertNotNull(rig.watch.startMatch("PADEL_GOLDEN_BO3", true, true, false))
        assertNull(rig.watch.chooseServer(next.server, first))
        assertEquals(next.eventCount, assertNotNull(rig.watch.snapshot()).eventCount)
    }

    @Test
    fun choiceContextWorksWithoutPeopleAndUsesOwnerPerspective() {
        val rig = Rig()
        val owner =
            rig.watchData.players
                .ensureSelf("Alex", 0) { PlayerId("owner") }
                .id
        val lineup = Lineup(listOf(PlayerId("missing1"), PlayerId("missing2")), listOf(owner, PlayerId("missing3")), owner)
        rig.watchSession.create(Presets.TENNIS_TIEBREAK_ONLY_7.format(Discipline.DOUBLES), lineup)
        val snap = assertNotNull(rig.watch.snapshot())
        val first = assertNotNull(snap.serverChoiceContext)
        assertTrue(snap.perspectiveSwapped)
        assertEquals("A", first.dueSide)
        assertEquals("MATCH_TIEBREAK", first.gameKind)
        assertEquals(4, snap.serverChoicePlayers.size)
        assertEquals(3, snap.serverChoicePlayers.count { it.displayName.isEmpty() })
        assertNotNull(rig.watch.chooseServer("missing3", first))
        rig.watch.point(true)
        val second = assertNotNull(assertNotNull(rig.watch.snapshot()).serverChoiceContext)
        assertEquals("SECOND", second.phase)
        assertEquals("B", second.dueSide)
        val chosen = assertNotNull(rig.watch.chooseServer("missing2", second))
        assertEquals("missing2", chosen.server)
        assertNotNull(rig.watch.undo())
        val reopened = assertNotNull(rig.watch.snapshot())
        assertEquals("FIRST", reopened.serverChoiceContext?.phase)
        assertEquals("missing2", reopened.serverChoiceContext?.defaultServer)
        assertEquals("missing2", reopened.serverChoiceContext?.chosen)
        assertNull(rig.watch.chooseServer("missing2", second))
        assertEquals(reopened.eventCount, assertNotNull(rig.watch.snapshot()).eventCount)
    }

    @Test
    fun safeChoiceAndCorrectionRequireScorerAndSchedulerThread() {
        val rig = Rig()
        val start = assertNotNull(rig.watch.startMatch("PADEL_GOLDEN_BO3", true, true, false))
        val first = assertNotNull(start.serverChoiceContext)
        rig.schedulerThread = false
        assertNull(rig.watch.chooseServer(first.players.last(), first))
        assertEquals("THREAD_VIOLATION", rig.watch.lastError())
        rig.schedulerThread = true
        assertEquals(start.eventCount, assertNotNull(rig.watch.snapshot()).eventCount)
        assertTrue(rig.phone.takeOverScoring())
        rig.settle()
        val display = assertNotNull(rig.watch.snapshot())
        assertFalse(display.isScorer)
        assertNull(rig.watch.chooseServer(first.players.last(), first))
        assertEquals("NOT_SCORER", rig.watch.lastError())
        assertNull(rig.watch.correctServer(first.players.last(), false))
        assertEquals("NOT_SCORER", rig.watch.lastError())
        assertEquals(display.eventCount, assertNotNull(rig.watch.snapshot()).eventCount)
    }

    @Test
    fun correctionReportsCollisionAndInvalidatesOpenChoiceContext() {
        val rig = Rig()
        val start = assertNotNull(rig.watch.startMatch("PADEL_GOLDEN_BO3", true, true, false))
        val selected = start.serverChoicePlayers.first { it.side == "A" && it.id != start.server }.id
        val chosen = assertNotNull(rig.watch.chooseServer(selected, assertNotNull(start.serverChoiceContext)))
        assertFalse(chosen.canCorrectServerRetroactively)
        assertNull(rig.watch.correctServer(start.server, true))
        assertEquals("NOT_ALLOWED", rig.watch.lastError())
        assertEquals(chosen.eventCount, assertNotNull(rig.watch.snapshot()).eventCount)
        val corrected = assertNotNull(rig.watch.correctServer(start.server, false))
        assertEquals("", corrected.serverChoiceContext?.chosen)
        assertEquals(start.server, corrected.serverChoiceContext?.defaultServer)
        assertNull(rig.watch.chooseServer(selected, assertNotNull(chosen.serverChoiceContext)))
        assertEquals(corrected.eventCount, assertNotNull(rig.watch.snapshot()).eventCount)
        assertNotNull(rig.watch.chooseServer(selected, assertNotNull(corrected.serverChoiceContext)))
        val point = assertNotNull(rig.watch.point(true))
        val other = start.serverChoicePlayers.last { it.side == "B" }.id
        val forward = assertNotNull(rig.watch.correctServer(other, false))
        assertEquals(other, forward.server)
        assertEquals(selected, forward.lastPointServer)
        assertEquals(point.lastPointId, forward.lastPointId)
    }

    @Test
    fun presetsPerSportWithDefaultFirst() {
        val s = Rig().watch
        assertEquals(listOf("TENNIS", "PADEL", "PICKLEBALL", "BADMINTON", "TABLE_TENNIS"), s.sports())
        val tennis = s.presets("TENNIS", 2026, 10, 15)
        assertEquals(14, tennis.size)
        assertEquals("TENNIS_SINGLES_BO3", tennis.first().id)
        assertTrue(tennis.first().isDefault)
        assertEquals(1, tennis.count { it.isDefault })
        assertTrue(tennis.single { it.id == "TENNIS_OPEN" }.openMode)
        assertFalse(s.presets("PADEL", 2026, 10, 15).first().singles)
        assertEquals(Presets.all.size, s.sports().sumOf { s.presets(it, 2026, 10, 15).size })
        assertEquals("BADMINTON_SINGLES_BO3_21", s.defaultPresetId("BADMINTON", 2027, 1, 3))
        assertEquals("BADMINTON_SINGLES_BO3_15", s.defaultPresetId("BADMINTON", 2027, 1, 4))
        assertEquals("", s.defaultPresetId("TABLE_TENNIS", 2026, 10, 15))
    }

    @Test
    fun startOnWatchMakesTheWatchTheScorer() {
        val rig = Rig()
        val snap = assertNotNull(rig.watch.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = false, watchWorkout = true))
        assertTrue(snap.isScorer)
        assertEquals("WATCH", snap.scorerKind)
        assertEquals("IN_PROGRESS", snap.status)
        assertEquals("0", snap.pointsA)
        assertEquals("B", snap.serveSide)
        assertEquals(1, snap.serverNo)
        assertFalse(snap.canUndo)
        assertEquals(snap.matchId, rig.watch.activeMatchId())
        assertEquals(snap.matchId, rig.snapshots.last()?.matchId)
        assertEquals(36, rig.watch.deviceId().length)
    }

    @Test
    fun pointsHintsUndoAndRedo() {
        val rig = Rig()
        rig.watch.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true, watchWorkout = true)
        val game = assertNotNull(rig.points("AAAA"))
        assertEquals(1, game.gamesA)
        assertTrue("GAME_WON" in game.effects)
        val breakPoint = assertNotNull(rig.points("AAA"))
        assertEquals("BREAK_POINT", breakPoint.hintCode)
        assertEquals("A", breakPoint.hintSide)
        assertEquals(3, breakPoint.hintCount)
        assertEquals(7, breakPoint.pointsPlayed)
        val undone = assertNotNull(rig.watch.undo())
        assertEquals("30", undone.pointsA)
        assertTrue(undone.canRedo)
        assertEquals("40", assertNotNull(rig.watch.redo()).pointsA)
        assertNull(rig.watch.redo())
        assertEquals("NOT_ALLOWED", rig.watch.lastError())
    }

    @Test
    fun openModeSetEndAndMatchTiebreak() {
        val rig = Rig()
        assertTrue(
            assertNotNull(
                rig.watch.startMatch("TENNIS_OPEN", doubles = false, weServeFirst = true, watchWorkout = true),
            ).offerMatchTiebreak,
        )
        val setEnd = assertNotNull(rig.points("A".repeat(24)))
        assertTrue(setEnd.pendingDecision)
        assertEquals(listOf(6), setEnd.setScoresA)
        assertNull(rig.watch.point(sideA = true))
        assertEquals("NOT_ALLOWED", rig.watch.lastError())
        val mtb = assertNotNull(rig.watch.continueDecision(anotherSet = true, matchTiebreak = true))
        assertEquals("MATCH_TIEBREAK", mtb.setKind)
        rig.points("A".repeat(10))
        val done = assertNotNull(rig.watch.end())
        assertTrue(done.matchFinished)
        assertEquals("A", done.winner)
        assertEquals("COMPLETED", done.resultReason)
        assertTrue(assertNotNull(rig.watch.end()).matchFinished, "zweites end() liefert den Zustand")
    }

    @Test
    fun pauseAndResumePlay() {
        val rig = Rig()
        rig.watch.startMatch("TENNIS_OPEN", doubles = false, weServeFirst = true, watchWorkout = true)
        assertNull(rig.watch.resumePlay())
        val paused = assertNotNull(rig.watch.pause())
        assertTrue(paused.paused)
        assertFalse(assertNotNull(rig.watch.resumePlay()).paused)
        rig.watch.pause()
        assertEquals("IN_PROGRESS", assertNotNull(rig.watch.point(sideA = true)).status, "Punkt beendet die Pause")
    }

    @Test
    fun errorsAreReportedNotThrown() {
        val rig = Rig()
        assertNull(rig.watch.point(sideA = true))
        assertEquals("NO_MATCH", rig.watch.lastError())
        assertNull(rig.watch.startMatch("NOPE", doubles = false, weServeFirst = true, watchWorkout = true))
        assertEquals("INVALID_INPUT", rig.watch.lastError())
        assertNull(rig.watch.startMatch("PADEL_PREMIER_2026", doubles = false, weServeFirst = true, watchWorkout = true))
        assertEquals("INVALID_INPUT", rig.watch.lastError())
        assertNull(rig.watch.startMatchFromFormatJson("kein json", weServeFirst = true, watchWorkout = true))
        assertEquals("INVALID_INPUT", rig.watch.lastError())
        val broken = WatchScoring({ error("disk I/O error") })
        assertFalse(broken.isAvailable())
        assertNull(broken.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true, watchWorkout = true))
        assertEquals("STORE_READ", broken.lastError())
        assertNull(broken.startConfiguredMatch("TENNIS_OPEN", true, false, 0, true, "bea", "", "", "SELF"))
        assertEquals("STORE_READ", broken.lastError())
        assertNull(broken.restore())
        assertEquals("STORE_READ", broken.lastError())
        assertEquals("", broken.deviceId())
    }

    @Test
    fun startFromFormatJson() {
        val rig = Rig()
        val json = SyncCodec.json.encodeToString(MatchFormat.serializer(), Presets.PICKLEBALL_RALLY_BO3_15.format(Discipline.SINGLES))
        val snap = assertNotNull(rig.watch.startMatchFromFormatJson(json, weServeFirst = true, watchWorkout = true))
        assertEquals("PICKLEBALL", snap.sport)
        assertEquals("0-0", snap.scoreCall)
    }

    @Test
    fun restartRestoresTheOpenMatchAndKeepsCounting() {
        val rig = Rig()
        rig.watch.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true, watchWorkout = true)
        val before = assertNotNull(rig.points("AAAAB"))
        rig.restartWatch()
        assertTrue(rig.watch.hasActiveMatch())
        val restored = assertNotNull(rig.watch.restore())
        assertEquals(before.matchId, restored.matchId)
        assertEquals(1, restored.gamesA)
        assertEquals("15", restored.pointsB)
        assertTrue(restored.isScorer)
        assertEquals("30", assertNotNull(rig.watch.point(sideA = false)).pointsB)
        rig.watch.close()
        assertFalse(rig.watch.hasActiveMatch())
        rig.restartWatch()
        assertNull(rig.watch.restore())
        assertEquals("", rig.watch.lastError())
    }

    @Test
    fun restoreDropsPointerToMissingMatch() {
        val rig = Rig()
        rig.watchData.settings.put(WatchScoring.KEY_OPEN_MATCH, "missing")
        assertNull(rig.watch.restore())
        assertEquals("LOG_INVALID", rig.watch.lastError())
        assertFalse(rig.watch.hasActiveMatch())
    }

    @Test
    fun lastSetupAndLineupReuse() {
        val rig = Rig()
        assertNull(rig.watch.lastSetup())
        val first = assertNotNull(rig.watch.startMatch("TENNIS_BO3_MTB10", doubles = true, weServeFirst = false, watchWorkout = true))
        val setup = assertNotNull(rig.watch.lastSetup())
        assertEquals("TENNIS_BO3_MTB10", setup.presetId)
        assertTrue(setup.doubles)
        assertFalse(setup.weServeFirst)
        rig.watch.close()
        val second = assertNotNull(rig.watch.startMatch("TENNIS_BO3_MTB10", doubles = true, weServeFirst = true, watchWorkout = true))
        val lineup1 = assertNotNull(rig.watchData.matches.get(MatchId(first.matchId))).lineup
        val lineup2 = assertNotNull(rig.watchData.matches.get(MatchId(second.matchId))).lineup
        assertEquals(lineup1.a.first(), lineup2.a.first())
        assertTrue((lineup2.a.drop(1) + lineup2.b).all(rig.watchData::isPlaceholder))
        assertTrue((lineup2.a.drop(1) + lineup2.b).none { it in lineup1.a + lineup1.b })
        assertEquals(lineup2.a.first(), lineup2.firstServer)
        assertEquals(
            rig.watchData.players
                .self()
                ?.id,
            lineup2.a.first(),
            "A1 = Self-Player der Uhr",
        )
    }

    @Test
    fun scorerSyncsImmediatelyAndBuffersOffline() {
        val rig = Rig()
        val phone = rig.phone
        rig.watch.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true, watchWorkout = true)
        rig.points("AA")
        rig.settle()
        assertEquals(
            "30",
            phone.state.value.match
                ?.state
                ?.current
                ?.display
                ?.a,
        )
        val synced = assertNotNull(rig.watch.snapshot())
        assertEquals(0, synced.pendingToPeers)
        assertTrue(synced.linkConnected)
        rig.network.setConnected("watch", false)
        rig.settle()
        val offline = assertNotNull(rig.points("A"))
        // Punkt + automatischer „1. Aufschlag“ zum vorigen Punkt (Modus „Ja“, keine Eingabe)
        assertEquals(2, offline.pendingToPeers, "„2 ausstehend“")
        assertFalse(offline.linkConnected)
        rig.network.setConnected("watch", true)
        rig.settle()
        assertEquals(0, assertNotNull(rig.watch.snapshot()).pendingToPeers)
        assertEquals(
            "40",
            phone.state.value.match
                ?.state
                ?.current
                ?.display
                ?.a,
        )
    }

    @Test
    fun watchIsDisplayWhenThePhoneCountsAndCanTakeOver() {
        val rig = Rig()
        val phone = rig.phone
        val watchId = rig.watch.deviceId()
        assertTrue(watchId.isNotEmpty())
        phone.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me")))
        rig.settle()
        // Die Uhr folgt dem Match automatisch – als Anzeige
        val display = assertNotNull(rig.snapshots.last())
        assertFalse(display.isScorer)
        assertEquals("PHONE", display.scorerKind)
        assertTrue(display.scorerConnected)
        assertNull(rig.watch.point(sideA = true))
        assertEquals("NOT_SCORER", rig.watch.lastError())
        assertNull(rig.watch.undo())
        assertEquals("NOT_SCORER", rig.watch.lastError())
        phone.recordPoint(Side.A)
        rig.settle()
        val updated = assertNotNull(rig.snapshots.last())
        assertEquals("15", updated.pointsA)
        assertTrue(updated.lastEventReceivedAtMs > 0, "„Stand vor X s“")
        assertEquals(0L, updated.scorerDisconnectedSinceMs)
        // Handy weg → Angebot „Zählen übernehmen“ nach > 30 s (Zeitpunkt kommt aus dem Snapshot)
        rig.network.setConnected("watch", false)
        rig.settle(1_000)
        val lost = assertNotNull(rig.watch.snapshot())
        assertFalse(lost.scorerConnected)
        assertTrue(lost.scorerDisconnectedSinceMs > 0)
        // Das Handy zählt offline weiter (2 Punkte), die Uhr übernimmt und zählt selbst
        phone.recordPoint(Side.A)
        phone.recordPoint(Side.A)
        val taken = assertNotNull(rig.watch.takeOverScoring())
        assertTrue(taken.isScorer)
        assertEquals("WATCH", taken.scorerKind)
        assertNull(rig.watch.takeOverScoring())
        assertEquals("NOT_ALLOWED", rig.watch.lastError())
        assertEquals("30", assertNotNull(rig.watch.point(sideA = true)).pointsA)
        rig.network.setConnected("watch", true)
        rig.settle(5_000)
        // Gleichzeitige Eingaben: mindestens der zweite Handy-Punkt liegt hinter der Übernahme und wird verworfen
        // (der erste konkurriert mit ScorerChanged auf derselben Lamport-Zeit, Ordnung über die Geräte-ID)
        val merged = assertNotNull(rig.watch.snapshot())
        assertTrue(merged.rejectedByRoleCount >= 1, "„N Eingaben nicht übernommen“")
        val phoneMatch = assertNotNull(phone.state.value.match)
        assertFalse(phoneMatch.isScorer)
        assertEquals(
            phoneMatch.state.current
                ?.display
                ?.a,
            merged.pointsA,
            "beide Geräte konvergieren",
        )
        assertEquals(phoneMatch.rejectedByRoleCount, merged.rejectedByRoleCount)
    }

    @Test
    fun displayDemoStartsAMatchCountedElsewhere() {
        val rig = Rig(demoEnabled = true)
        val snap = assertNotNull(rig.watch.startDisplayDemo("TENNIS_OPEN"))
        assertFalse(snap.isScorer)
        assertEquals("PHONE", snap.scorerKind)
        assertNull(rig.watch.point(sideA = true))
    }

    @Test
    fun displayDemoIsBlockedWithoutFlagBeforeOpeningTheStore() {
        var opened = false
        val watch =
            WatchScoring({
                opened = true
                error("must not open")
            }, demoEnabled = false)
        assertNull(watch.startDisplayDemo("TENNIS_OPEN"))
        assertEquals("NOT_ALLOWED", watch.lastError())
        assertFalse(opened)
        val rig = Rig()
        assertNull(rig.watch.startDisplayDemo("TENNIS_OPEN"))
        assertNull(rig.watchData.settings.get("session.peerKind.${WatchScoring.DEMO_SCORER}"))
        assertFalse(rig.watch.hasActiveMatch())
    }

    @Test
    fun wrongThreadIsReportedSeparatelyAndDoesNotWriteAnEvent() {
        val rig = Rig()
        val before = assertNotNull(rig.watch.startMatch("TENNIS_OPEN", doubles = false, weServeFirst = true, watchWorkout = true))
        rig.schedulerThread = false
        assertNull(rig.watch.point(sideA = true))
        assertEquals("THREAD_VIOLATION", rig.watch.lastError())
        assertEquals(before.eventCount, assertNotNull(rig.watch.snapshot()).eventCount)
        rig.schedulerThread = true
        assertEquals("15", assertNotNull(rig.watch.point(sideA = true)).pointsA)
        assertEquals("", rig.watch.lastError())
    }

    @Test
    fun pendingCountOnlyIncludesTheOpenMatch() {
        val rig = Rig()
        rig.watch.startMatch("TENNIS_OPEN", doubles = false, weServeFirst = true, watchWorkout = true)
        rig.points("AAA")
        rig.watch.close()
        val next = assertNotNull(rig.watch.startMatch("TENNIS_OPEN", doubles = false, weServeFirst = true, watchWorkout = true))
        assertEquals(1, next.pendingToPeers, "nur die neue Genesis, nicht die sechs Events des alten Matches")
        assertEquals(1, assertNotNull(rig.snapshots.last()).pendingToPeers)
        // alt: Genesis, 3 Punkte, 2 automatische „1. Aufschlag“ (der letzte Punkt ist noch offen); neu: Genesis
        assertEquals(7, rig.watchSession.state.value.sync.pendingToPeers, "Session bleibt geräteweit")
        assertEquals(2, assertNotNull(rig.watch.point(sideA = true)).pendingToPeers)
        val id = next.matchId
        rig.watchData.events.markConfirmedUpTo(id, rig.watch.deviceId(), rig.watchData.events.maxDeviceSeq(id, rig.watch.deviceId()))
        // Die Zahl bleibt je Match, „synchron“ aber erst bei SyncStatus.settled (P-4, wie Wear)
        assertEquals(1, assertNotNull(rig.watch.snapshot()).pendingToPeers, "alter Rückstand: nicht synchron")
    }

    @Test
    fun lastLineupWithOwnerOnSideBReusesBothTeamsAndAliases() {
        val rig = Rig()
        val oldSelf =
            rig.watchData.players
                .ensureSelf("", rig.clock.nowMs()) { PlayerId("old-watch-self") }
                .id
        val owner = PlayerId("owner")
        rig.watchData.adoptSelf(Player(owner, "Alex"), rig.clock.nowMs())
        val opponents = listOf(PlayerId("opponent1"), PlayerId("opponent2"))
        val partner = PlayerId("partner")
        rig.watchSession.create(
            Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES),
            Lineup(opponents, listOf(partner, oldSelf), opponents.first()),
        )
        rig.watch.close()
        val next = assertNotNull(rig.watch.startMatch("TENNIS_BO3_MTB10", doubles = true, weServeFirst = false, watchWorkout = true))
        val lineup = assertNotNull(rig.watchData.matches.get(MatchId(next.matchId))).lineup
        assertEquals(listOf(partner, owner), lineup.a, "Besitzerteam von B übernehmen, Alias ersetzen, Reihenfolge behalten")
        assertEquals(opponents, lineup.b)
        assertEquals(opponents.first(), lineup.firstServer)
    }

    @Test
    fun doublesServeHelpExportsNumbersAndPositions() {
        val rig = Rig()
        val badminton =
            assertNotNull(rig.watch.startMatch("BADMINTON_DOUBLES_BO3_21", doubles = true, weServeFirst = true, watchWorkout = true))
        assertEquals("RIGHT", badminton.serveCourt)
        assertEquals(1, badminton.serverNo)
        assertEquals(1, badminton.rightNoA)
        assertEquals(2, badminton.leftNoA)
        val next = assertNotNull(rig.watch.point(sideA = true))
        assertEquals("LEFT", next.serveCourt)
        assertEquals(2, next.rightNoA)
        assertEquals(1, next.leftNoA)
        val tennis = assertNotNull(rig.watch.startMatch("TENNIS_DOUBLES_MTB10", doubles = true, weServeFirst = false, watchWorkout = true))
        assertEquals("B", tennis.serveSide)
        assertEquals(1, tennis.receiverNo)
        assertEquals(0, tennis.rightNoA)
        // Aufschläger je Game mit Team und Nummer (Anzeige „Name · R/L“), Feld: Summe gerade → von rechts
        val server = assertNotNull(tennis.serverPlayer)
        assertEquals("B", server.side)
        assertEquals(tennis.serverNo, server.number)
        assertEquals(tennis.server, server.id)
        assertEquals("RIGHT", tennis.serveCourt)
        assertEquals("LEFT", assertNotNull(rig.watch.point(sideA = true)).serveCourt)
    }

    @Test
    fun watchAdoptsPhoneSelfAndShowsOwnerSideAsUs() {
        val rig = Rig()
        val phone = rig.phone
        val phoneData = phoneDataOf(rig)
        val owner = phoneData.players.ensureSelf("Alex", 0) { PlayerId("owner") }.id
        val watchId = DeviceId(rig.watch.deviceId())
        // Uhr hat vorher schon einen eigenen Self (wird beim Hello Alias)
        rig.watch.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true, watchWorkout = true)
        rig.watch.close()
        val oldSelf = assertNotNull(rig.watchData.players.self()).id
        // Handy legt an: Besitzer auf Side B, die Uhr zählt
        phone.create(
            Presets.TENNIS_SINGLES_BO3.format(),
            Lineup(listOf(PlayerId("bea")), listOf(owner), PlayerId("bea")),
            scorer = ScorerChoice.Device(watchId),
        )
        rig.settle()
        assertEquals(
            owner,
            rig.watchData.players
                .self()
                ?.id,
            "Self des iPhones übernommen",
        )
        assertTrue(rig.watchData.isSelf(oldSelf), "alter Uhr-Self ist Alias")
        val snap = assertNotNull(rig.snapshots.last())
        assertTrue(snap.isScorer)
        assertTrue(snap.perspectiveSwapped)
        assertEquals("B", snap.serveSide, "bea (Gegner) schlägt auf")
        // „Wir“ = Engine-Side B
        val point = assertNotNull(rig.watch.point(sideA = true))
        assertEquals("15", point.pointsA)
        assertEquals("0", point.pointsB)
        rig.settle()
        assertEquals(
            "15",
            phone.state.value.match
                ?.state
                ?.current
                ?.display
                ?.b,
        )
        // Neues Match auf der Uhr: frischer Self (= Besitzer) auf A
        rig.watch.close()
        val own = assertNotNull(rig.watch.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true, watchWorkout = true))
        assertFalse(own.perspectiveSwapped)
        assertEquals(owner, assertNotNull(rig.watchData.matches.get(MatchId(own.matchId))).lineup.a.first())
    }

    @Test
    fun watchWorkoutWishTravelsInMatchMeta() {
        val rig = Rig()
        val phone = rig.phone
        val snap = assertNotNull(rig.watch.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true, watchWorkout = false))
        assertFalse(snap.watchWorkout)
        rig.settle()
        assertEquals(
            false,
            phone.state.value.match
                ?.meta
                ?.watchWorkout,
        )
        rig.watch.close()
        // vom Handy gestartet: dessen Wahl gilt
        phone.create(
            Presets.TENNIS_SINGLES_BO3.format(),
            Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me")),
            MatchMeta(watchWorkout = true),
            ScorerChoice.Device(DeviceId(rig.watch.deviceId())),
        )
        rig.settle()
        assertTrue(assertNotNull(rig.snapshots.last()).watchWorkout)
    }

    @Test
    fun myWatchPlaceholderHandsOverOnFirstHello() {
        val rig = Rig()
        rig.network.setConnected("watch", false)
        val phone = rig.phone
        phone.create(
            Presets.TENNIS_SINGLES_BO3.format(),
            Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me")),
            scorer = ScorerChoice.MyWatch,
        )
        rig.settle()
        assertEquals(
            MatchSession.PENDING_WATCH,
            phone.state.value.match
                ?.scorerDeviceId,
        )
        assertNull(rig.watch.snapshot(), "Uhr kennt das Match noch nicht")
        rig.network.setConnected("watch", true)
        rig.settle(5_000)
        val snap = assertNotNull(rig.watch.snapshot())
        assertTrue(snap.isScorer, "Uhr zählt nach dem ersten Hello")
        assertFalse(snap.scorerPending)
        assertEquals("15", assertNotNull(rig.watch.point(sideA = true)).pointsA)
    }

    @Test
    fun pendingWatchPlaceholderIsFlaggedInTheSnapshot() {
        val rig = Rig()
        val pending =
            rig.watchSession.create(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me")),
                scorer = ScorerChoice.Device(MatchSession.PENDING_WATCH),
            )
        val snap = assertNotNull(rig.watch.snapshot())
        assertEquals(pending.value, snap.matchId)
        assertTrue(snap.scorerPending)
        assertFalse(snap.isScorer)
    }

    /** F22: Das Handy führt mehrere Matches, die Apple-Watch-Facade zeigt nur das ihr zugewiesene. */
    @Test
    fun facadeShowsOnlyTheAssignedMatch() {
        val rig = Rig()
        val phone = rig.phone
        val singles = Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me"))
        val onWatch = phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles, scorer = ScorerChoice.MyWatch)
        rig.settle()
        assertEquals(onWatch.value, assertNotNull(rig.watch.snapshot()).matchId)
        phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles) // Nachbarplatz, Handy zählt
        rig.settle()
        phone.recordPoint(Side.A)
        rig.settle()
        val snap = assertNotNull(rig.watch.snapshot())
        assertEquals(onWatch.value, snap.matchId, "Uhr bleibt bei ihrem Match")
        assertTrue(snap.isScorer)
        assertEquals("15", assertNotNull(rig.watch.point(sideA = true)).pointsA)
    }

    /** Review W3-M R2 #5: verworfene Uhr-Eingaben nach bestätigter Übernahme kommen im Snapshot an, bis quittiert. */
    @Test
    fun droppedInputsAreExportedUntilAcknowledged() {
        val rig = Rig()
        val phone = rig.phone
        val singles = Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me"))
        phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles, scorer = ScorerChoice.MyWatch)
        rig.settle()
        assertNotNull(rig.watch.point(sideA = true))
        rig.settle()
        rig.network.setConnected("watch", false)
        rig.settle()
        phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles, scorer = ScorerChoice.MyWatch, takeOverWatchMatch = true)
        assertNotNull(rig.watch.point(sideA = true))
        rig.network.setConnected("watch", true)
        rig.settle(5_000)
        // der verworfene Punkt und der automatische „1. Aufschlag“ zum vorigen Punkt (Modus „Ja“)
        assertEquals(2, assertNotNull(rig.watch.snapshot()).droppedInputs)
        rig.watch.acknowledgeDroppedInputs()
        assertEquals(0, assertNotNull(rig.watch.snapshot()).droppedInputs)
    }

    /** F21: „Beenden, wie es steht“ am Handy – Sieger bzw. Gleichstand kommen als bekannte Codes an. */
    @Test
    fun stoppedOnThePhoneIsShownWithKnownCodes() {
        val rig = Rig()
        val phone = rig.phone
        val singles = Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me"))
        phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles)
        rig.settle()
        phone.recordPoint(Side.A)
        phone.recordPoint(Side.B)
        assertTrue(phone.endMatch(EndReason.STOPPED))
        rig.settle()
        val tie = assertNotNull(rig.watch.snapshot())
        assertTrue(tie.matchFinished)
        assertEquals("DRAW", tie.resultReason, "15:15 → Unentschieden")
        assertEquals("", tie.winner)
        assertTrue(phone.undo())
        phone.recordPoint(Side.A)
        assertTrue(phone.endMatch(EndReason.STOPPED))
        rig.settle()
        val won = assertNotNull(rig.watch.snapshot())
        assertEquals("STOPPED", won.resultReason)
        assertEquals("A", won.winner)
    }

    @Test
    fun unselectedAppleWatchPlacesAreAssignableAndFreshOnRepeat() {
        val rig = Rig()
        rig.watch.startMatch("TENNIS_BO3_MTB10", doubles = true, weServeFirst = false, watchWorkout = false)
        val first = assertNotNull(rig.watchSession.state.value.match)
        val slots = first.lineup.a.drop(1) + first.lineup.b
        assertTrue(slots.all(rig.watchData::isPlaceholder))
        rig.settle()
        val bea = rig.phoneData.players.addGuest(PlayerId("bea-assigned"), "Bea", 1)
        assertTrue(rig.phone.assignPlayer(first.id, slots.first(), bea.id))
        rig.settle()
        rig.watch.close()
        rig.watch.startMatch("TENNIS_BO3_MTB10", doubles = true, weServeFirst = false, watchWorkout = false)
        val second = assertNotNull(rig.watchSession.state.value.match)
        val fresh = second.lineup.a.drop(1) + second.lineup.b
        assertTrue(fresh.none { it in slots })
    }

    @Test
    fun removeSidePointAcrossSetBoundaryAndRedo() {
        val rig = Rig()
        rig.watch.startConfiguredMatch("TENNIS_OPEN", false, false, 1, true, "", "", "", "SELF")
        val end = assertNotNull(rig.points("A".repeat(8)))
        assertTrue(end.pendingDecision)
        assertTrue(end.canRemovePointA)
        assertFalse(end.canRemovePointB)
        assertNull(rig.watch.removeLastPointOf(false))
        val next = assertNotNull(rig.watch.startNextSet("REGULAR"))
        assertEquals(1, next.setIndex)
        rig.points("B")
        val removed = assertNotNull(rig.watch.removeLastPointOf(true))
        assertEquals(0, removed.setIndex)
        assertEquals("40", removed.pointsA)
        assertEquals("15", removed.pointsB)
        assertTrue(removed.canRedo)
        val restored = assertNotNull(rig.watch.redo())
        assertEquals(1, restored.setIndex)
        assertEquals("15", restored.pointsB)
    }

    @Test
    fun serveInfoValidationUpdatesAndDeletionPreserveRedo() {
        val rig = Rig()
        rig.watch.startMatch("TENNIS_OPEN", false, true, false)
        val us = assertNotNull(rig.watch.point(true))
        assertTrue(us.serveInfoEnabled)
        assertEquals("A", us.lastPointSide)
        assertEquals("A", us.lastPointServingSide)
        assertFalse(us.doubleFaultAllowed)
        assertTrue(us.lastPointId.isNotEmpty())
        assertEquals(us.server, us.lastPointServer)
        assertNull(rig.watch.setServeInfo(us.lastPointId, "DOUBLE_FAULT"))
        assertEquals("NOT_ALLOWED", rig.watch.lastError())
        assertEquals("FIRST_SERVE_IN", assertNotNull(rig.watch.setServeInfo(us.lastPointId, "FIRST_SERVE_IN")).lastPointServe)
        val them = assertNotNull(rig.watch.point(false))
        assertTrue(them.doubleFaultAllowed)
        assertEquals("DOUBLE_FAULT", assertNotNull(rig.watch.setServeInfo(them.lastPointId, "DOUBLE_FAULT")).lastPointServe)
        rig.watch.undo()
        assertTrue(assertNotNull(rig.watch.snapshot()).canRedo)
        assertTrue(assertNotNull(rig.watch.setServeInfo(us.lastPointId, "SECOND_SERVE_IN")).canRedo)
        assertEquals("", assertNotNull(rig.watch.setServeInfo(us.lastPointId, "")).lastPointServe)
        assertNotNull(rig.watch.redo())
        assertNull(rig.watch.setServeInfo("missing", "FIRST_SERVE_IN"))
        assertEquals("NOT_ALLOWED", rig.watch.lastError())
        assertNull(rig.watch.setServeInfo(us.lastPointId, "bad-code"))
        assertEquals("INVALID_INPUT", rig.watch.lastError())
    }

    @Test
    fun setupChoiceIsNotAskedAgainButCorrectionReopensItAfterRestart() {
        val rig = Rig()
        val start = assertNotNull(rig.watch.startConfiguredMatch("TENNIS_OPEN", true, false, 1, true, "", "", "", "PARTNER"))
        assertEquals(1, start.serverChoiceGame)
        assertEquals(4, start.serverChoicePlayers.size)
        assertEquals("", start.serverChoiceChosen)
        assertTrue(start.serverChoicePrompt)
        assertEquals(2, start.serverNo)
        assertTrue(
            rig.watchData.events
                .eventsFor(start.matchId)
                .none { it.type == "ServerChosen" },
        )
        val initialOther = start.serverChoicePlayers.first { it.side == "B" }
        assertTrue(rig.watchSession.correct(Correction(CorrectionKind.SERVER, player = PlayerId(initialOther.id))))
        assertTrue(assertNotNull(rig.watch.snapshot()).serverChoicePrompt)
        // Erst eine ausdrückliche Wahl im offenen Fenster wird nach Korrektur erneut erfragt.
        rig.watch.chooseServer(start.server)
        val other = start.serverChoicePlayers.first { it.side == "B" }
        assertTrue(rig.watchSession.correct(Correction(CorrectionKind.SERVER, player = PlayerId(other.id))))
        val corrected = assertNotNull(rig.watch.snapshot())
        assertEquals("", corrected.serverChoiceChosen)
        assertTrue(corrected.serverChoicePrompt)
        rig.restartWatch()
        val restored = assertNotNull(rig.watch.restore())
        assertTrue(restored.serverChoicePrompt)
        val chosen = assertNotNull(rig.watch.chooseServer(other.id))
        assertEquals(other.id, chosen.serverChoiceChosen)
        assertFalse(chosen.serverChoicePrompt)
        assertEquals(chosen.eventCount, assertNotNull(rig.watch.chooseServer(other.id)).eventCount)
        rig.points("AAAA")
        val second = assertNotNull(rig.watch.snapshot())
        assertEquals(2, second.serverChoiceGame)
        assertEquals(2, second.serverChoicePlayers.size)
        assertTrue(second.serverChoicePrompt)
        assertTrue(second.serverChoicePlayers.all { it.side == second.serverChoiceSide })
        rig.points("AAAA")
        val next = assertNotNull(rig.watch.startNextSet("REGULAR"))
        assertEquals(1, next.serverChoiceGame)
        assertTrue(next.serverChoicePrompt)
        assertEquals(4, next.serverChoicePlayers.size)
    }

    @Test
    fun nextSetOptionsAreSportSpecificAndTiebreaksHaveBracketData() {
        for ((kind, points) in listOf("TIEBREAK_7" to 7, "MATCH_TIEBREAK_10" to 10)) {
            val rig = Rig()
            rig.watch.startConfiguredMatch("PADEL_OPEN", true, false, 1, true, "", "", "", "SELF")
            val end = assertNotNull(rig.points("A".repeat(8)))
            assertEquals(listOf("REGULAR", "TIEBREAK_7", "MATCH_TIEBREAK_10"), end.nextSetOptions)
            assertEquals("MATCH_TIEBREAK", assertNotNull(rig.watch.startNextSet(kind)).setKind)
            val tb = assertNotNull(rig.points("B".repeat(points)))
            assertEquals(listOf("REGULAR", "MATCH_TIEBREAK"), tb.setKinds)
            assertEquals(points, tb.setScoresB.last())
            assertTrue(assertNotNull(rig.watch.end()).matchFinished)
        }
        val rig = Rig()
        rig.watch.startMatch("TABLE_TENNIS_OPEN", false, true, false)
        val end = assertNotNull(rig.points("A".repeat(11)))
        assertEquals(listOf("REGULAR"), end.nextSetOptions)
        assertNull(rig.watch.startNextSet("TIEBREAK_7"))
        assertEquals("NOT_ALLOWED", rig.watch.lastError())
        assertNull(rig.watch.startNextSet("nonsense"))
        assertEquals("INVALID_INPUT", rig.watch.lastError())
    }

    @Test
    fun configuredSetupUsesRecentPeopleAndFreshPlaceholdersAndRepeatsInOneCall() {
        val rig = Rig()
        val data = rig.watchData
        data.players.addGuest(PlayerId("bea"), "Bea", 1)
        data.players.addGuest(PlayerId("caro"), "Caro", 2)
        data.settings.put("players.recentRemote", "caro,bea,caro")
        assertEquals(listOf("caro", "bea"), rig.watch.recentPeople().map { it.id })
        val first = assertNotNull(rig.watch.startConfiguredMatch("TENNIS_OPEN", true, false, 3, false, "bea", "caro", "", "PARTNER"))
        val match = assertNotNull(rig.watchSession.state.value.match)
        assertEquals(3, match.format.set?.gamesToWin)
        assertEquals(3, match.format.set?.tiebreakAt)
        assertEquals("bea", match.lineup.firstServer.value)
        assertTrue(data.isPlaceholder(match.lineup.b[1]))
        assertFalse(first.serveInfoEnabled)
        val point = assertNotNull(rig.watch.point(true))
        assertNull(rig.watch.setServeInfo(point.lastPointId, "FIRST_SERVE_IN"))
        val last = assertNotNull(rig.watch.lastSetup())
        assertEquals(3, last.gamesPerSet)
        assertFalse(last.serveStats)
        rig.watch.close()
        rig.restartWatch()
        val repeated = assertNotNull(rig.watch.startLastMatch(false))
        val next = assertNotNull(rig.watchSession.state.value.match)
        assertEquals(match.format, next.format)
        assertEquals(match.lineup.a, next.lineup.a)
        assertEquals(match.lineup.b.first(), next.lineup.b.first())
        assertEquals("bea", next.lineup.firstServer.value)
        assertTrue(next.lineup.b[1] != match.lineup.b[1])
        assertFalse(repeated.serveInfoEnabled)
        // Ein neues Setup übernimmt keine Personen stillschweigend aus dem letzten Match.
        rig.watch.close()
        rig.watch.startConfiguredMatch("TENNIS_OPEN", true, false, 6, true, "", "", "", "SELF")
        val blank = assertNotNull(rig.watchSession.state.value.match)
        assertTrue((blank.lineup.a.drop(1) + blank.lineup.b).all(data::isPlaceholder))
        assertEquals(listOf("caro", "bea"), rig.watch.recentPeople().map { it.id })
    }

    @Test
    fun configuredSetupRejectsInvalidOptionsWithoutCreatingAMatch() {
        val rig = Rig()
        rig.watchData.players.addGuest(PlayerId("bea"), "Bea", 1)
        rig.watchData.settings.put("players.recentRemote", "bea")
        assertNull(rig.watch.startConfiguredMatch("TENNIS_OPEN", true, false, 3, true, "bea", "bea", "", "SELF"))
        assertNull(rig.watch.startConfiguredMatch("TENNIS_OPEN", true, false, 3, true, "missing", "", "", "SELF"))
        assertNull(rig.watch.startConfiguredMatch("TENNIS_OPEN", false, false, 3, true, "", "", "", "PARTNER"))
        assertNull(rig.watch.startConfiguredMatch("TENNIS_OPEN", false, false, 7, true, "", "", "", "SELF"))
        assertNull(rig.watch.startConfiguredMatch("TENNIS_TIEBREAK_ONLY_7", false, false, 6, true, "", "", "", "SELF"))
        assertEquals("INVALID_INPUT", rig.watch.lastError())
        assertFalse(rig.watch.hasActiveMatch())
        for (sport in listOf("TENNIS", "PADEL")) {
            for (points in listOf(7, 10)) {
                val snap =
                    assertNotNull(
                        rig.watch.startConfiguredMatch(
                            "${sport}_TIEBREAK_ONLY_$points",
                            sport == "PADEL",
                            false,
                            0,
                            true,
                            "",
                            "",
                            "",
                            "SELF",
                        ),
                    )
                assertEquals("MATCH_TIEBREAK", snap.setKind)
                assertEquals(1, snap.bestOf)
                assertTrue(assertNotNull(rig.points("A".repeat(points))).matchFinished)
                rig.watch.close()
            }
        }
    }

    @Test
    fun operationFieldsAndRemovalFollowOwnerPerspectiveAndRole() {
        val rig = Rig()
        val owner =
            rig.watchData.players
                .ensureSelf("Alex", 0) { PlayerId("owner") }
                .id
        rig.watchSession.create(Presets.TENNIS_OPEN.format(), Lineup(listOf(PlayerId("bea")), listOf(owner), PlayerId("bea")))
        val point = assertNotNull(rig.watch.point(true))
        assertTrue(point.perspectiveSwapped)
        assertEquals("A", point.lastPointSide)
        assertEquals("B", point.lastPointServingSide)
        assertTrue(point.doubleFaultAllowed)
        assertTrue(point.canRemovePointA)
        assertFalse(point.canRemovePointB)
        assertEquals("0", assertNotNull(rig.watch.removeLastPointOf(true)).pointsA)
        rig.watchSession.create(
            Presets.TENNIS_OPEN.format(),
            Lineup(listOf(owner), listOf(PlayerId("bea")), owner),
            scorer = ScorerChoice.Device(DeviceId("phone")),
        )
        assertNull(rig.watch.removeLastPointOf(true))
        assertEquals("NOT_SCORER", rig.watch.lastError())
        assertNull(rig.watch.chooseServer("bea"))
        assertEquals("NOT_SCORER", rig.watch.lastError())
        assertNull(rig.watch.setServeInfo("point", "FIRST_SERVE_IN"))
        assertEquals("NOT_SCORER", rig.watch.lastError())
        assertFalse(assertNotNull(rig.watch.snapshot()).canRemovePointA)
    }

    @Test
    fun unchangedGamesKeepShortSetPresetAndItsThreeAllTiebreak() {
        for (games in listOf(0, 4)) {
            val rig = Rig()
            val preset = Presets.TENNIS_SHORT_SETS_3ALL
            assertNotNull(rig.watch.startConfiguredMatch(preset.id, false, false, games, true, "", "", "", "SELF"))
            assertEquals(
                preset.format(Discipline.SINGLES),
                rig.watchSession.state.value.match
                    ?.format,
            )
            assertEquals(
                3,
                rig.watchSession.state.value.match
                    ?.format
                    ?.set
                    ?.tiebreakAt,
            )
            rig.watch.close()
            rig.watch.startLastMatch(false)
            assertEquals(
                preset.format(Discipline.SINGLES),
                rig.watchSession.state.value.match
                    ?.format,
            )
        }
    }

    @Test
    fun phoneStatusUpdatesWithoutAnOpenMatchAndWithoutSkie() {
        val rig = Rig()
        assertEquals("GRAY", rig.watch.phoneStatus().light)
        rig.phone
        rig.settle()
        assertEquals("YELLOW", rig.watch.phoneStatus().light, "Hello ohne App-Stand bleibt unbekannt")
        rig.network.setConnected("phone", false)
        rig.settle()
        assertEquals("RED", rig.watch.phoneStatus().light)
        rig.restartWatch()
        assertEquals("RED", rig.watch.phoneStatus().light)
    }

    /** Lifecycle-Revision und Sync-Abschluss als flache Werte, auch ohne offenes Match (kein Flow, kein SKIE). */
    @Test
    fun phoneStatusCarriesLifecycleRevisionAndSettledSync() {
        val rig = Rig()
        val state = rig.watchSession.state.value
        val status = rig.watch.phoneStatus()
        assertEquals(state.lifecycleRevision, status.lifecycleRevision)
        assertEquals(state.sync.settled, status.syncSettled)
        val changed = phoneStatusOf(state.copy(lifecycleRevision = state.lifecycleRevision + 1), null)
        assertEquals(state.lifecycleRevision + 1, changed.lifecycleRevision)
        assertTrue(phoneStatusOf(state, null).syncSettled)
        assertFalse(phoneStatusOf(state.copy(sync = state.sync.copy(lifecyclePendingToPeers = 1)), null).syncSettled)
        assertFalse(phoneStatusOf(state.copy(sync = state.sync.copy(lifecycleBlocked = true)), null).syncSettled)
    }

    @Test
    fun lifecycleBacklogCountsAsPendingAndRedactedPeopleAreNotSuggested() {
        val rig = Rig()
        rig.watchData.players.addGuest(PlayerId("bea"), "Bea", 1)
        rig.watchData.players.addGuest(PlayerId("caro"), "Caro", 1)
        rig.watchData.settings.put("players.recentRemote", "bea,caro")
        rig.watchData.players.rename(PlayerId("bea"), "")
        assertEquals(listOf("caro"), rig.watch.recentPeople().map { it.id })

        rig.watch.startMatch("TENNIS_OPEN", false, true, false)
        val match = assertNotNull(rig.watchSession.state.value.match)
        val sync =
            rig.watchSession.state.value.sync
                .copy(pendingToPeers = 0)
        assertEquals(0, buildSnapshot(match, sync, 0, pendingToPeers = 0).pendingToPeers)
        assertEquals(3, buildSnapshot(match, sync.copy(lifecyclePendingToPeers = 2), 0, pendingToPeers = 1).pendingToPeers)
        assertEquals(1, buildSnapshot(match, sync.copy(lifecycleBlocked = true), 0, pendingToPeers = 0).pendingToPeers)
        // P-4: „synchron“ heißt auf beiden Plattformen SyncStatus.settled; Rückstand anderer Matches ist nie „synchron“
        assertEquals(1, buildSnapshot(match, sync.copy(pendingToPeers = 4), 0, pendingToPeers = 0).pendingToPeers)
        assertEquals(2, buildSnapshot(match, sync.copy(pendingToPeers = 4), 0, pendingToPeers = 2).pendingToPeers)
    }

    @Test
    fun versionMismatchReachesFacadeWithoutAnOpenMatch() {
        for ((version, expected) in listOf(1 to "OTHER_DEVICE", 99 to "THIS_DEVICE")) {
            val rig = Rig()
            val peer = ReliableDeviceLink(rig.network.transport("incompatible"), rig.scheduler).apply { start() }
            rig.settle()
            peer.send("watch", SyncCodec.encode(Hello(DeviceId("peer"), PeerKind.PHONE, minV = version, v = version)))
            rig.settle()
            assertNull(rig.watch.snapshot())
            assertEquals(expected, rig.watch.updateRequired())
            assertEquals(expected, rig.watch.phoneStatus().updateRequired)
            assertEquals("RED", rig.watch.phoneStatus().light)
            peer.stop()
            rig.settle()
            assertEquals(expected, rig.watch.phoneStatus().updateRequired)
        }
    }

    @Test
    fun versionMismatchHasFlatUpdateCodesWithAndWithoutMatch() {
        val rig = Rig()
        val sync = rig.watchSession.state.value.sync
        assertEquals("", rig.watch.updateRequired())
        for ((compatibility, code) in listOf(
            CompatibilityStatus.PEER_TOO_OLD to "OTHER_DEVICE",
            CompatibilityStatus.PEER_TOO_NEW to "THIS_DEVICE",
        )) {
            val mismatch = sync.copy(peers = listOf(PeerInfo("phone", DeviceId("phone"), PeerKind.PHONE, compatibility)))
            assertEquals(code, updateCode(mismatch))
            rig.watch.startMatch("TENNIS_OPEN", false, true, false)
            val match = assertNotNull(rig.watchSession.state.value.match)
            assertEquals(code, buildSnapshot(match, mismatch, 0).updateRequired)
        }
    }

    private fun phoneDataOf(rig: Rig): LocalData = rig.phoneData
}
