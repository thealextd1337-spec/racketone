package eu.advantly.presentation.match

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.SidePair
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.device.link.CompanionState
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.testing.FakeCompanionDetector
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.SessionConfig
import eu.advantly.sync.protocol.PeerKind
import java.io.File
import java.util.Properties
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import eu.advantly.match.session.WallClock as SessionClock

private const val BASE_MS = 1_760_000_000_000L

/**
 * Handy (Adapter über echter Session) + Uhr (nackte Session) in einem In-Memory-Netz mit virtueller Zeit.
 * Die Wanduhr beider Geräte hängt an der virtuellen Zeit.
 */
private class Rig(
    phoneData: LocalData = createInMemoryJvmLocalData(),
    private val watchData: LocalData = createInMemoryJvmLocalData(),
    watchOnline: Boolean = true,
    val companion: FakeCompanionDetector = FakeCompanionDetector(),
) {
    val scheduler = ManualScheduler()
    val network = InMemoryLinkNetwork(scheduler, latencyMs = 20)
    val uiClock = WallClock { BASE_MS + scheduler.nowMs() }
    val mono = MonotonicClock { scheduler.nowMs() }
    private val sessionClock = SessionClock { BASE_MS + scheduler.nowMs() }
    private var messages = 0
    private var seeds = 0

    val watch: MatchSession = session("watch", PeerKind.WATCH, watchData)
    var phoneSession: MatchSession = session("phone", PeerKind.PHONE, phoneData)
        private set
    var phone: SessionMatchStore = SessionMatchStore(phoneSession, uiClock, CountingIds(), monotonic = mono, companion = companion)
        private set

    init {
        if (!watchOnline) offline("watch")
        settle()
    }

    fun session(
        name: String,
        kind: PeerKind,
        data: LocalData,
    ): MatchSession {
        val link = ReliableDeviceLink(network.transport(name), scheduler, newMessageId = { "$name-${++messages}" })
        val ids = RandomIdGenerator(Random(31 * ++seeds + name.length))
        return MatchSession(data, link, scheduler, SessionConfig(DeviceId(name), kind), sessionClock, ids).also { it.start() }
    }

    /** App-Neustart des Handys: Adapter und Session weg, neue Session über [data] (gleiche Datenbank-Datei). */
    fun restartPhone(data: LocalData) {
        phone.close()
        phoneSession.close()
        phoneSession = session("phone", PeerKind.PHONE, data)
        phone = SessionMatchStore(phoneSession, uiClock, CountingIds(n = 1_000), monotonic = mono, companion = companion)
        settle()
    }

    fun settle(ms: Long = 2_000) = scheduler.advanceBy(ms)

    fun offline(name: String) = network.setConnected(name, false)

    fun online(name: String) = network.setConnected(name, true)

    fun eventCount(
        data: LocalData,
        store: SessionMatchStore = phone,
    ): Long =
        store.active.value
            ?.matchId
            ?.let { data.events.count(it.value) } ?: 0
}

/** Session-Adapter (W1-I): Rollen, Übernahme, Persistenz, Löschen, Vertrag der Ports. */
class SessionMatchStoreTest {
    private val tennis = Presets.TENNIS_SINGLES_BO3.format()
    private val badminton15 = Presets.BADMINTON_1GAME_15.format()
    private val files = mutableListOf<File>()
    private val drivers = mutableListOf<SqlDriver>()

    @AfterTest
    fun cleanUp() {
        drivers.forEach { it.close() }
        files.forEach { it.delete() }
    }

    private fun fileData(file: File): LocalData {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties(), AdvantlyDatabase.Schema)
        drivers += driver
        return LocalData(driver)
    }

    private fun Rig.watchScoredMatch() = phone.start(phone.setupFor(tennis), StartOptions(ScorerOption.MY_WATCH)).also { settle() }

    @Test
    fun watchIsOfferedOnlyWhileConnected() {
        val rig = Rig()
        assertTrue(rig.phone.watchAvailable.value)
        rig.offline("watch")
        rig.settle()
        assertFalse(rig.phone.watchAvailable.value)
        // ohne Uhr fällt „Meine Uhr“ auf den Director-Modus zurück
        rig.phone.start(rig.phone.setupFor(tennis), StartOptions(ScorerOption.MY_WATCH))
        assertEquals(
            LiveRole.SCORER,
            rig.phone.active.value!!
                .role,
        )
    }

    @Test
    fun watchScoresAndPhoneDisplays() {
        val rig = Rig()
        val id = rig.watchScoredMatch()
        val snapshot = assertNotNull(rig.phone.active.value)
        assertEquals(id, snapshot.matchId)
        assertEquals(LiveRole.DISPLAY, snapshot.role)
        assertEquals(DeviceKind.WATCH, snapshot.scorerKind)
        assertFalse(snapshot.canUndo)
        assertTrue(snapshot.sync.scorerConnected)
        assertEquals(
            id,
            rig.watch.state.value.match
                ?.id,
            "Uhr folgt dem neuen Match",
        )

        assertTrue(rig.watch.recordPoint(Side.A))
        assertTrue(rig.watch.recordPoint(Side.A))
        rig.settle()
        val shown = rig.phone.active.value!!
        assertEquals(SidePair("30", "0"), shown.state.current!!.display)
        assertNotNull(shown.sync.lastEventReceivedAtMs)
        assertFalse(shown.sync.offersTakeOver(rig.mono.nowMs() + 60_000), "verbunden: kein Übernahmeangebot")
        assertTrue(shown.revision > snapshot.revision)
    }

    @Test
    fun displayCommandsAreRejectedAndWriteNothing() {
        val rig = Rig()
        rig.watchScoredMatch()
        rig.watch.recordPoint(Side.A)
        rig.settle()
        val phone = rig.phone
        val before = rig.eventCount(rig.phoneSession.data)
        assertFalse(phone.recordPoint(Side.A))
        assertFalse(phone.undo())
        assertFalse(phone.redo())
        assertFalse(phone.pause())
        assertFalse(phone.endMatch(EndReason.ABANDONED))
        assertFalse(phone.correctServer(phone.self.value.id, retroactive = false))
        assertFalse(phone.continueDecision(anotherSet = true))
        assertNull(phone.createFollowUp())
        assertFalse(phone.dismiss())
        assertEquals(before, rig.eventCount(rig.phoneSession.data))
    }

    @Test
    fun takeOverAfterScorerIsGoneAndLateWatchInputsAreRejected() {
        val rig = Rig()
        rig.watchScoredMatch()
        rig.watch.recordPoint(Side.A)
        rig.settle()
        rig.offline("watch")
        rig.settle(1_000)
        val lost = rig.phone.active.value!!
        assertFalse(lost.sync.scorerConnected)
        assertFalse(lost.sync.offersTakeOver(rig.mono.nowMs()), "erst nach > 30 s")
        rig.settle(31_000)
        val stale = rig.phone.active.value!!
        assertTrue(stale.sync.offersTakeOver(rig.mono.nowMs()))
        assertTrue(stale.sync.staleForMs(rig.mono.nowMs())!! > SyncInfo.STALE_AFTER_MS)

        assertTrue(rig.phone.takeOverScoring())
        assertFalse(rig.phone.takeOverScoring(), "zählt schon")
        assertEquals(
            LiveRole.SCORER,
            rig.phone.active.value!!
                .role,
        )
        assertTrue(rig.phone.recordPoint(Side.B))
        // die Uhr weiß noch nichts und zählt offline weiter
        repeat(2) { assertTrue(rig.watch.recordPoint(Side.A)) }
        rig.online("watch")
        rig.settle()
        val after = rig.phone.active.value!!
        assertEquals(2, after.rejectedByRoleCount)
        assertEquals(SidePair("15", "15"), after.state.current!!.display)
        assertEquals(
            false,
            rig.watch.state.value.match
                ?.isScorer,
        )
        assertEquals(
            2,
            rig.watch.state.value.match
                ?.rejectedByRoleCount,
        )
    }

    @Test
    fun directorPhoneScoresAndWatchOnlyDisplays() {
        val rig = Rig()
        rig.phone.start(rig.phone.setupFor(tennis), StartOptions(ScorerOption.THIS_PHONE))
        // synchron sichtbar, ohne die Zeit laufen zu lassen
        assertTrue(rig.phone.recordPoint(Side.A))
        assertEquals(
            "15",
            rig.phone.active.value!!
                .state.current!!
                .display.a,
        )
        rig.settle()
        val watchMatch = assertNotNull(rig.watch.state.value.match)
        assertFalse(watchMatch.isScorer)
        assertFalse(rig.watch.recordPoint(Side.B), "Uhr-Eingaben im Director-Modus werden nicht angenommen")
        rig.settle()
        assertEquals(
            SidePair("15", "0"),
            rig.phone.active.value!!
                .state.current!!
                .display,
        )
        assertEquals(
            0,
            rig.phone.active.value!!
                .sync.pendingToPeers,
            "Uhr hat alles bestätigt",
        )
        assertEquals(
            SidePair("15", "0"),
            rig.watch.state.value.match!!
                .state.current!!
                .display,
        )
    }

    @Test
    fun pendingEventsAreCountedWhileWatchIsAway() {
        val rig = Rig()
        rig.phone.start(rig.phone.setupFor(tennis))
        rig.settle()
        rig.offline("watch")
        rig.settle()
        rig.phone.play("AAB")
        assertEquals(
            3,
            rig.phone.active.value!!
                .sync.pendingToPeers,
        )
        rig.online("watch")
        rig.settle()
        assertEquals(
            0,
            rig.phone.active.value!!
                .sync.pendingToPeers,
        )
    }

    @Test
    fun redoRulesAndPreconditionsWriteNothing() {
        val rig = Rig()
        val phone = rig.phone
        phone.start(phone.setupFor(tennis))
        phone.play("AA")
        assertTrue(phone.undo())
        assertTrue(phone.pause())
        assertTrue(phone.resume())
        assertTrue(phone.active.value!!.canRedo, "Pause/Fortsetzen leeren Redo nicht")
        assertTrue(phone.redo())
        assertTrue(phone.undo())
        phone.play("B")
        assertFalse(phone.active.value!!.canRedo, "Punkt leert Redo")

        val before = rig.eventCount(rig.phoneSession.data)
        assertFalse(phone.endMatch(EndReason.WALKOVER, Side.B), "Walkover nur ohne Ballwechsel")
        assertFalse(phone.endMatch(EndReason.RETIRED, null), "Aufgabe braucht eine Side")
        assertFalse(
            phone.correctServer(
                eu.advantly.core.model
                    .PlayerId("fremd"),
                retroactive = false,
            ),
        )
        assertFalse(phone.continueDecision(anotherSet = true))
        assertFalse(phone.dismiss(), "läuft noch")
        assertEquals(before, rig.eventCount(rig.phoneSession.data))
        assertTrue(phone.endMatch(EndReason.RETIRED, Side.B))
        assertEquals(
            ResultReason.RETIRED,
            phone.active.value!!
                .state.result!!
                .reason,
        )
    }

    @Test
    fun finishedMatchesGoToHistoryAndFollowUpKeepsTheScorer() {
        val rig = Rig()
        val phone = rig.phone
        val id = phone.start(phone.setupFor(Presets.BADMINTON_SINGLES_BO3_21.format()))
        phone.play("A".repeat(42))
        val finished = phone.active.value!!
        assertEquals(MatchStatus.FINISHED, finished.state.status)
        assertNotNull(finished.finishedAtMs)
        assertEquals(listOf(id), phone.matches.value.map { it.matchId })
        val record = assertNotNull(phone.record(id))
        assertEquals(43, record.events.size, "Genesis + 42 Punkte")
        assertEquals(SidePair(2, 0), record.summary.setsWon)

        val next = assertNotNull(phone.createFollowUp())
        assertEquals(
            id,
            phone.active.value!!
                .setup.previousMatchId,
        )
        assertEquals(next, phone.active.value!!.matchId)
        assertEquals(LiveRole.SCORER, phone.active.value!!.role)
    }

    @Test
    fun watchStartedMatchAppearsOnThePhone() {
        val rig = Rig()
        val watchSetup = rig.phone.setupFor(badminton15)
        rig.watch.create(watchSetup.format, watchSetup.lineup)
        rig.settle()
        val shown = assertNotNull(rig.phone.active.value, "Handy folgt dem Match der Uhr")
        assertEquals(LiveRole.DISPLAY, shown.role)
        repeat(15) { rig.watch.recordPoint(Side.B) }
        rig.settle()
        assertEquals(
            MatchStatus.FINISHED,
            rig.phone.active.value!!
                .state.status,
        )
        assertEquals(1, rig.phone.matches.value.size)
        assertEquals(
            Side.B,
            rig.phone.matches.value
                .single()
                .result.winner,
        )
    }

    @Test
    fun startingWhileTheWatchScoresAbandonsTheOldMatchEverywhere() {
        val rig = Rig()
        val old = rig.watchScoredMatch()
        rig.watch.recordPoint(Side.A)
        rig.settle()
        val next = rig.phone.start(rig.phone.setupFor(tennis), StartOptions(ScorerOption.MY_WATCH))
        rig.settle()
        assertEquals(
            MatchStatus.FINISHED,
            rig.phoneSession.data.matches
                .get(old)!!
                .status,
        )
        assertEquals(
            next,
            rig.watch.state.value.match
                ?.id,
            "Uhr folgt dem neuen Match",
        )
        assertEquals(
            true,
            rig.watch.state.value.match
                ?.isScorer,
        )
        assertEquals(
            LiveRole.DISPLAY,
            rig.phone.active.value!!
                .role,
        )
    }

    @Test
    fun deletingReplicatesToTheWatch() {
        val rig = Rig()
        val phone = rig.phone
        val id = phone.start(phone.setupFor(badminton15))
        phone.play("A".repeat(15))
        rig.settle()
        assertNotNull(
            rig.watch.data.matches
                .get(id),
            "Uhr hat das Match",
        )
        assertTrue(phone.delete(id))
        assertNull(phone.active.value, "aktives beendetes Match vorher geschlossen")
        assertTrue(phone.matches.value.isEmpty())
        assertFalse(phone.delete(id), "schon gelöscht")
        rig.settle()
        assertNull(
            rig.watch.data.matches
                .get(id),
        )
        assertTrue(
            rig.watch.data.matches
                .isDeleted(id),
        )
    }

    @Test
    fun runningMatchesCannotBeDeleted() {
        val rig = Rig()
        val id = rig.phone.start(rig.phone.setupFor(tennis))
        assertFalse(rig.phone.delete(id))
        assertNotNull(rig.phone.active.value)
    }

    @Test
    fun playersFavoritesAndHistorySurviveARestart() {
        val file = File.createTempFile("advantly-phone", ".db").also { files += it }
        file.delete()
        val rig = Rig(phoneData = fileData(file))
        val phone = rig.phone
        val selfId = phone.self.value.id
        val setup = phone.setupFor(badminton15)
        val done = phone.start(setup, StartOptions(ScorerOption.THIS_PHONE, watchWorkout = false))
        phone.play("A".repeat(15))
        phone.dismiss()
        phone.saveFavorite(setup)
        val bea = phone.addGuest("Bea")
        rig.settle(60_000)
        val running = phone.start(phone.setupFor(tennis))
        phone.play("AB")
        assertEquals(false, phone.watchWorkout(done))
        assertEquals(true, phone.watchWorkout(running))

        rig.restartPhone(fileData(file))
        val after = rig.phone
        assertEquals(selfId, after.self.value.id)
        assertEquals(listOf(done), after.matches.value.map { it.matchId })
        assertEquals(
            "Jonas",
            after.matches.value
                .single()
                .setup
                .team(Side.B)
                .single()
                .displayName,
        )
        assertEquals(1, after.favorites.value.size)
        assertEquals(
            setup.format,
            after.favorites.value
                .single()
                .setup.format,
        )
        assertTrue(after.guests.value.any { it.id == bea.id && it.displayName == "Bea" })
        assertEquals(tennis, after.lastSetup.value?.format)
        // das laufende Match wird nach dem Neustart wieder geöffnet (Resume)
        val resumed = assertNotNull(after.active.value)
        assertEquals(running, resumed.matchId)
        assertEquals(SidePair("15", "15"), resumed.state.current!!.display)
        assertTrue(after.recordPoint(Side.A))
        assertEquals(false, after.watchWorkout(done))
    }

    /** Review W1-I #1: Die Uhr löscht ein am Handy geschlossenes Match → Verlauf aktualisiert sich sofort. */
    @Test
    fun deletionOnTheWatchUpdatesThePhoneHistory() {
        val rig = Rig()
        val phone = rig.phone
        val id = phone.start(phone.setupFor(badminton15))
        phone.play("A".repeat(15))
        assertTrue(phone.dismiss())
        rig.settle()
        assertEquals(listOf(id), phone.matches.value.map { it.matchId })
        rig.watch.deleteMatch(id)
        rig.settle()
        assertTrue(phone.matches.value.isEmpty())
        assertNull(phone.record(id))
        assertNull(phone.lastSetup.value)
    }

    /** Review W1-I #4: Offline-Alter übersteht einen Neustart des Handys (kein Zurücksetzen auf 0). */
    @Test
    fun stalenessSurvivesARestart() {
        val file = File.createTempFile("advantly-stale", ".db").also { files += it }
        file.delete()
        val rig = Rig(phoneData = fileData(file))
        rig.watchScoredMatch()
        rig.watch.recordPoint(Side.A)
        rig.settle()
        rig.offline("watch")
        rig.settle(65_000)
        assertTrue(
            rig.phone.active.value!!
                .sync
                .offersTakeOver(rig.mono.nowMs()),
        )

        rig.restartPhone(fileData(file))
        val after = rig.phone.active.value!!
        val stale = assertNotNull(after.sync.staleForMs(rig.mono.nowMs()))
        assertTrue(stale >= 65_000, "Alter $stale")
        assertTrue(after.sync.offersTakeOver(rig.mono.nowMs()))
    }

    /** Ohne gemerkten Kontakt ist die Aktualität unbekannt; das Angebot kommt nach 30 s eigener Beobachtung. */
    @Test
    fun unknownStalenessIsNotZero() {
        val rig = Rig()
        rig.offline("watch")
        rig.settle()
        val setup = rig.phone.setupFor(tennis)
        // Match der Uhr, das das Handy nur als Kopf/Log kennt (z. B. aus einem früheren Abgleich): direkt eingespielt
        rig.online("watch")
        rig.settle()
        rig.watch.create(setup.format, setup.lineup)
        rig.settle()
        rig.offline("watch")
        rig.phone.close()
        rig.phoneSession.data.settings.all().keys.filter { it.startsWith("phone.scorerContact.") }.forEach {
            rig.phoneSession.data.settings
                .remove(it)
        }
        rig.restartPhone(rig.phoneSession.data)
        val shown = rig.phone.active.value!!
        assertNull(shown.sync.staleForMs(rig.mono.nowMs()), "unbekannt, nicht 0")
        assertFalse(shown.sync.offersTakeOver(rig.mono.nowMs()))
        rig.settle(31_000)
        assertTrue(
            rig.phone.active.value!!
                .sync
                .offersTakeOver(rig.mono.nowMs()),
        )
    }

    /** Review W1-I #6: Matches, die die Uhr mit ihrem alten Self angelegt hat, zeigen am Handy den Besitzer. */
    @Test
    fun watchSelfIsShownAsTheOwnerNotAsAGuest() {
        val watchData = createInMemoryJvmLocalData()
        val watchSelf =
            watchData.players.ensureSelf("", 1) {
                eu.advantly.core.model
                    .PlayerId("watch-self")
            }
        val rig = Rig(watchData = watchData)
        rig.offline("watch")
        rig.settle()
        val bea = rig.phone.addGuest("Bea")
        rig.watch.create(
            tennis,
            eu.advantly.core.model
                .Lineup(listOf(watchSelf.id), listOf(bea.id), watchSelf.id),
        )
        rig.online("watch")
        rig.settle()
        val shown = assertNotNull(rig.phone.active.value)
        assertEquals(rig.phone.self.value.displayName, shown.setup.player(watchSelf.id)!!.displayName)
        assertTrue(
            rig.phone.guests.value
                .none { it.id == watchSelf.id },
            "kein Gast",
        )
        assertFalse(rig.phone.canRename(watchSelf.id))
        assertEquals(rig.phone.self.value.id, watchData.players.self()?.id, "Uhr übernimmt die Handy-Identität")
    }

    /** Review W1-I #7: Workout-Wunsch geht als MatchMeta mit dem Match an die Uhr. */
    @Test
    fun workoutChoiceReachesTheWatch() {
        val rig = Rig()
        val id = rig.phone.start(rig.phone.setupFor(tennis), StartOptions(ScorerOption.MY_WATCH, watchWorkout = false))
        rig.settle()
        assertEquals(false, rig.phone.watchWorkout(id))
        assertEquals(
            false,
            rig.watch.data.matches
                .get(id)
                ?.meta
                ?.watchWorkout,
        )
        assertEquals(
            false,
            rig.watch.state.value.match
                ?.meta
                ?.watchWorkout,
        )
    }

    /** Review W1-I #8: „Eingaben ansehen“ listet die verworfenen Eingaben des Altzählers. */
    @Test
    fun rejectedInputsAreListed() {
        val rig = Rig()
        rig.watchScoredMatch()
        rig.offline("watch")
        rig.settle(31_000)
        assertTrue(rig.phone.takeOverScoring())
        assertTrue(rig.watch.recordPoint(Side.A))
        assertTrue(rig.watch.recordPoint(Side.B))
        rig.online("watch")
        rig.settle()
        val inputs = rig.phone.rejectedInputs()
        assertEquals(listOf(InputKind.POINT, InputKind.POINT), inputs.map { it.kind })
        assertEquals(listOf(Side.A, Side.B), inputs.map { it.side })
        assertTrue(inputs.all { it.device == DeviceKind.WATCH && it.matchTimeMs > 0 })
    }

    /** Feldtest W1-I: Uhr mit installierter App ist wählbar, auch ohne laufende Uhr-App/Verbindung. */
    @Test
    fun watchIsAvailableWithoutALiveSession() {
        val rig = Rig(watchOnline = false, companion = FakeCompanionDetector(CompanionState.APP_NOT_INSTALLED))
        assertFalse(rig.phone.watchAvailable.value)
        assertEquals(WatchStatus.APP_NOT_INSTALLED, rig.phone.watchStatus.value)
        rig.companion.state = CompanionState.APP_INSTALLED
        assertTrue(rig.phone.watchAvailable.value)
        assertEquals(WatchStatus.AVAILABLE, rig.phone.watchStatus.value)
        val before = rig.companion.refreshes
        rig.phone.refreshWatchStatus()
        assertEquals(before + 1, rig.companion.refreshes)
    }

    /** Start mit „Meine Uhr“ ohne Verbindung, die Uhr war nie verbunden → sie zählt, sobald sie sich meldet. */
    @Test
    fun startWithoutConnectionThenTheWatchConnectsAndScores() {
        val rig = Rig(watchOnline = false, companion = FakeCompanionDetector(CompanionState.APP_INSTALLED))
        rig.phone.start(rig.phone.setupFor(tennis), StartOptions(ScorerOption.MY_WATCH))
        val waiting = rig.phone.active.value!!
        assertEquals(LiveRole.DISPLAY, waiting.role)
        assertEquals(DeviceKind.WATCH, waiting.scorerKind)
        assertTrue(waiting.waitingForScorer)
        assertFalse(rig.phone.recordPoint(Side.A))

        rig.online("watch")
        rig.settle()
        assertTrue(
            rig.watch.state.value.match
                ?.isScorer == true,
        )
        assertTrue(rig.watch.recordPoint(Side.A))
        rig.settle()
        val live = rig.phone.active.value!!
        assertFalse(live.waitingForScorer)
        assertEquals(
            "15",
            live.state.current!!
                .display.a,
        )
    }

    /** „Doch am Handy zählen“ während des Wartens. */
    @Test
    fun countingOnThePhoneWhileWaitingForTheWatch() {
        val rig = Rig(watchOnline = false, companion = FakeCompanionDetector(CompanionState.APP_INSTALLED))
        rig.phone.start(rig.phone.setupFor(tennis), StartOptions(ScorerOption.MY_WATCH))
        assertTrue(
            rig.phone.active.value!!
                .waitingForScorer,
        )
        assertTrue(rig.phone.takeOverScoring())
        assertEquals(
            LiveRole.SCORER,
            rig.phone.active.value!!
                .role,
        )
        assertFalse(
            rig.phone.active.value!!
                .waitingForScorer,
        )
        assertTrue(rig.phone.recordPoint(Side.A))
        rig.online("watch")
        rig.settle()
        assertEquals(
            LiveRole.SCORER,
            rig.phone.active.value!!
                .role,
            "Uhr übernimmt nicht mehr automatisch",
        )
    }

    @Test
    fun guestsCanBeRenamedButNotTheOwner() {
        val rig = Rig()
        val phone = rig.phone
        phone.start(phone.setupFor(tennis))
        val jonas = phone.guests.value.first { it.displayName == "Jonas" }
        assertFalse(phone.canRename(phone.self.value.id))
        assertFalse(phone.renameGuest(phone.self.value.id, "Ich"))
        assertTrue(phone.canRename(jonas.id))
        assertFalse(phone.renameGuest(jonas.id, " "))
        assertTrue(phone.renameGuest(jonas.id, "Jonas K."))
        assertEquals(
            "Jonas K.",
            phone.active.value!!
                .setup
                .player(jonas.id)!!
                .displayName,
        )
        assertTrue(phone.guests.value.any { it.displayName == "Jonas K." })
    }

    @Test
    fun favoritesCanBeRemoved() {
        val rig = Rig()
        val phone = rig.phone
        val favorite = phone.saveFavorite(phone.setupFor(tennis))
        assertEquals(listOf(favorite.id), phone.favorites.value.map { it.id })
        phone.removeFavorite("unbekannt")
        phone.removeFavorite(favorite.id)
        assertTrue(phone.favorites.value.isEmpty())
    }
}
