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
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.ScorerChoice
import eu.advantly.match.session.ThreadGuard
import eu.advantly.match.session.WallClock
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
        assertEquals(1, offline.pendingToPeers, "„1 ausstehend“")
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
        assertEquals(1, next.pendingToPeers, "nur die neue Genesis, nicht die vier Events des alten Matches")
        assertEquals(1, assertNotNull(rig.snapshots.last()).pendingToPeers)
        assertEquals(5, rig.watchSession.state.value.sync.pendingToPeers, "Session bleibt geräteweit")
        assertEquals(2, assertNotNull(rig.watch.point(sideA = true)).pendingToPeers)
        val id = next.matchId
        rig.watchData.events.markConfirmedUpTo(id, rig.watch.deviceId(), rig.watchData.events.maxDeviceSeq(id, rig.watch.deviceId()))
        assertEquals(0, assertNotNull(rig.watch.snapshot()).pendingToPeers, "synchron trotz altem Rückstand")
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
        assertEquals(1, assertNotNull(rig.watch.snapshot()).droppedInputs)
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

    private fun phoneDataOf(rig: Rig): LocalData = rig.phoneData
}
