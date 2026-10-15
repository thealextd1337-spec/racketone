package eu.advantly.wear.live

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.ServerChosen
import eu.advantly.data.LocalData
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.PeerInfo
import eu.advantly.match.session.ScorerChoice
import eu.advantly.match.session.SyncIndicator
import eu.advantly.match.session.SyncStatus
import eu.advantly.presentation.match.UpdateRequired
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.testLocalData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reine Regeln der W2-Bedienung über eine lokale Session ohne Link. */
class OperationRulesTest {
    private val me = PlayerId("me")
    private val partner = PlayerId("partner")
    private val o1 = PlayerId("o1")
    private val o2 = PlayerId("o2")
    private val singles = Lineup(listOf(me), listOf(o1), me)
    private val doubles = Lineup(listOf(me, partner), listOf(o1, o2), me)

    private fun session(data: LocalData = testLocalData()): MatchSession =
        MatchSessions
            .create(data, link = null, scheduler = ManualScheduler(), kind = PeerKind.WATCH, clock = { 1_760_000_000_000L })
            .also { it.start() }

    /** `ServerChosen` aus dem gespeicherten Log, wie `WatchDirectory.serverChosen`. */
    private fun chosenFrom(data: LocalData): (MatchId) -> List<Pair<EventId, ServerChosen>> =
        { id -> data.events.scoreEvents(id).mapNotNull { e -> (e.payload as? ServerChosen)?.let { e.id to it } } }

    private fun sync(compatibility: CompatibilityStatus? = CompatibilityStatus.COMPATIBLE) =
        SyncStatus(
            link = LinkState.CONNECTED,
            peers = listOf(PeerInfo("node", DeviceId("phone"), PeerKind.PHONE, compatibility)),
            indicator = SyncIndicator.CONNECTED,
            scorerConnected = true,
            pendingToPeers = 0,
            lastEventReceivedAtMs = null,
            lastSyncAtMs = null,
        )

    private fun player(id: PlayerId): PlayerUi {
        val side = if (id == me || id == partner) Side.A else Side.B
        return PlayerUi(id, side, if (id == me || id == o1) 0 else 1, null)
    }

    @Test
    fun serveBarOffersDoubleFaultOnlyWhenTheReceiverWonThePoint() {
        val s = session()
        s.create(Presets.TENNIS_SINGLES_BO3.format(), singles) // wir schlagen auf
        s.recordPoint(Side.A)
        val won = s.state.value.match!!
        val ours = OperationRules.serveBar(won, won.lastPoint!!.id)!!
        assertEquals(listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN), ours.options)
        assertNull(ours.selected)
        s.recordPoint(Side.B)
        val lost = s.state.value.match!!
        val theirs = OperationRules.serveBar(lost, lost.lastPoint!!.id)!!
        assertEquals(ServeOutcome.entries.toList(), theirs.options)
        assertTrue(s.setServeInfo(lost.lastPoint!!.id, ServeOutcome.DOUBLE_FAULT))
        val marked = s.state.value.match!!
        assertEquals(ServeOutcome.DOUBLE_FAULT, OperationRules.serveBar(marked, marked.lastPoint!!.id)?.selected)
        assertNull("anderer Punkt", OperationRules.serveBar(marked, ours.pointId))
        assertNull("nicht sichtbar", OperationRules.serveBar(marked, null))
    }

    @Test
    fun serveBarNeedsServeStatsAndTheScorerRole() {
        val off = session()
        off.create(Presets.TENNIS_SINGLES_BO3.format(), singles, MatchMeta(serveStats = false))
        off.recordPoint(Side.A)
        val noStats = off.state.value.match!!
        assertNull(OperationRules.serveBar(noStats, noStats.lastPoint!!.id))

        val badminton = session()
        badminton.create(Presets.BADMINTON_1GAME_21.format(Discipline.SINGLES), singles)
        badminton.recordPoint(Side.A)
        val rally = badminton.state.value.match!!
        assertNull("nur Tennis/Padel", OperationRules.serveBar(rally, rally.lastPoint?.id))

        val display = session()
        display.create(Presets.TENNIS_SINGLES_BO3.format(), singles, scorer = ScorerChoice.Device(DeviceId("phone")))
        val shown = display.state.value.match!!
        assertFalse(shown.isScorer)
        assertNull(OperationRules.serveBar(shown, EventId("x")))
    }

    @Test
    fun serverChoicePromptsBeforeTheOpponentsFirstServiceGame() {
        val s = session()
        s.create(Presets.TENNIS_DOUBLES_MTB10.format(Discipline.DOUBLES), doubles)
        val start = OperationRules.serverChoice(s.state.value.match!!, ::player)!!
        assertEquals(1, start.game)
        assertEquals(4, start.players.size)
        assertFalse("Game 1 des 1. Satzes entscheidet der Start", start.prompt)
        s.recordPoint(Side.A)
        assertNull("Game 1 läuft, wir schlagen auf", OperationRules.serverChoice(s.state.value.match!!, ::player))
        repeat(3) { s.recordPoint(Side.A) } // Game 1 vorbei
        val second = OperationRules.serverChoice(s.state.value.match!!, ::player)!!
        assertEquals(2, second.game)
        assertEquals(Side.B, second.side)
        assertEquals(listOf(o1, o2), second.players.map { it.id })
        assertTrue(second.prompt)
        assertTrue(s.chooseServer(o2))
        val chosen = OperationRules.serverChoice(s.state.value.match!!, ::player)!!
        assertEquals(o2, chosen.chosen)
        assertFalse("gewählt – kein Hinweis mehr", chosen.prompt)
    }

    @Test
    fun liftedOpeningChoiceIsRebuiltFromTheEventLog() {
        val data = testLocalData()
        val s = session(data)
        s.create(Presets.TENNIS_DOUBLES_MTB10.format(Discipline.DOUBLES), doubles)
        assertFalse(OpeningChoiceCheck(chosenFrom(data)).choiceMade(s.state.value.match))
        assertTrue(s.chooseServer(partner))
        assertFalse("Wahl wirksam – nichts zu fragen", OpeningChoiceCheck(chosenFrom(data)).choiceMade(s.state.value.match))
        assertTrue(
            s.correct(
                eu.advantly.core.scoring.Correction
                    .servingOrder(Side.A, listOf(me, partner)),
            ),
        )
        val lifted = s.state.value.match!!
        assertNull(lifted.serverChoice?.chosen)
        // frischer Check (z. B. nach Prozessverlust): nur das gespeicherte Log zählt
        val made = OpeningChoiceCheck(chosenFrom(data)).choiceMade(lifted)
        assertTrue("im Eröffnungsfenster schon gewählt", made)
        assertTrue(OperationRules.serverChoice(lifted, ::player, made)!!.prompt)
        assertFalse("ohne Wahl im Fenster: Start hat entschieden", OperationRules.serverChoice(lifted, ::player)!!.prompt)
        repeat(4) { s.recordPoint(Side.A) } // Game 2: kein Eröffnungsfenster mehr
        assertFalse(OpeningChoiceCheck(chosenFrom(data)).choiceMade(s.state.value.match))
        assertFalse(OpeningChoiceCheck(chosenFrom(data)).choiceMade(null))
    }

    @Test
    fun ignoredChoicesDoNotCount() {
        val s = session()
        s.create(Presets.TENNIS_DOUBLES_MTB10.format(Discipline.DOUBLES), doubles)
        val state =
            s.state.value.match!!
                .state
        val valid = EventId("v") to ServerChosen(0, Side.A, partner)
        assertTrue(OperationRules.choiceMadeInSet(listOf(valid), 0, state))
        assertFalse("anderer Satz", OperationRules.choiceMadeInSet(listOf(valid), 1, state))
        val ignored = state.copy(ignoredEvents = listOf(EventId("v")))
        assertFalse(OperationRules.choiceMadeInSet(listOf(valid), 0, ignored))
        val rejected = state.copy(rejectedByRole = listOf(EventId("v")))
        assertFalse(OperationRules.choiceMadeInSet(listOf(valid), 0, rejected))
    }

    @Test
    fun serverChoiceIsOnlyForTheScorerInDoubles() {
        val s = session()
        s.create(Presets.TENNIS_SINGLES_BO3.format(), singles)
        assertNull(OperationRules.serverChoice(s.state.value.match!!, ::player))
        val display = session()
        display.create(Presets.TENNIS_DOUBLES_MTB10.format(Discipline.DOUBLES), doubles, scorer = ScorerChoice.Device(DeviceId("phone")))
        assertNull(OperationRules.serverChoice(display.state.value.match!!, ::player))
    }

    @Test
    fun stripActionPriorities() {
        val s = session()
        s.create(Presets.TENNIS_DOUBLES_MTB10.format(Discipline.DOUBLES), doubles)
        repeat(4) { s.recordPoint(Side.A) } // Game 2: Wahl der Gegner offen
        val match = s.state.value.match!!
        val live = LiveModel.build(match, sync(), { null }, null, null, serveBarPoint = match.lastPoint!!.id)
        assertTrue(OperationRules.stripAction(live, null) is StripAction.Serve)
        val noBar = live.copy(serveBar = null)
        assertEquals(StripAction.ChooseServer, OperationRules.stripAction(noBar, null))
        assertEquals(
            "Rückgängig nur mit Redo-Ziel",
            StripAction.ChooseServer,
            OperationRules.stripAction(noBar, WatchToast.PointRemoved(Side.A)),
        )
        assertEquals(StripAction.UndoRemoval, OperationRules.stripAction(noBar.copy(canRedo = true), WatchToast.PointRemoved(Side.A)))
        assertNull("gesperrt", OperationRules.stripAction(live.copy(locked = true), null))
        assertNull("Rückfrage offen", OperationRules.stripAction(live.copy(overlay = LiveOverlay.CONFIRM_END), null))
        assertNull("Anzeige", OperationRules.stripAction(live.copy(role = LiveRole.DISPLAY), null))
        assertNull(OperationRules.stripAction(noBar.copy(serverChoice = null), null))
    }

    @Test
    fun serveOptionMapsSegments() {
        val bar = ServeBarUi(EventId("p"), listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN), null)
        assertEquals(ServeOutcome.FIRST_SERVE_IN, OperationRules.serveOption(bar, 0))
        assertEquals(ServeOutcome.SECOND_SERVE_IN, OperationRules.serveOption(bar, 1))
        assertNull(OperationRules.serveOption(bar, 2))
    }

    @Test
    fun updateRequiredFollowsTheVersionMismatch() {
        assertNull(OperationRules.updateRequired(sync()))
        assertNull(OperationRules.updateRequired(sync(compatibility = null)))
        assertEquals(UpdateRequired.OTHER_DEVICE, OperationRules.updateRequired(sync(CompatibilityStatus.PEER_TOO_OLD)))
        assertEquals(UpdateRequired.THIS_DEVICE, OperationRules.updateRequired(sync(CompatibilityStatus.PEER_TOO_NEW)))
    }

    @Test
    fun serveBarTimerShowsForAFewSecondsAndShortensAfterAChoice() {
        val timer = ServeBarTimer(visibleMillis = 5_000, afterChoiceMillis = 1_500)
        val p1 = EventId("p1")
        assertNull(timer.visiblePoint(0))
        timer.onPoint(p1, 1_000)
        assertEquals(p1, timer.visiblePoint(5_999))
        assertNull(timer.visiblePoint(6_000))
        timer.onPoint(p1, 10_000)
        timer.onChosen(11_000)
        assertEquals(p1, timer.visiblePoint(12_499))
        assertNull(timer.visiblePoint(12_500))
        timer.onPoint(p1, 20_000)
        timer.onChosen(24_000) // verlängert nicht über die 5 s hinaus
        assertNull(timer.visiblePoint(25_000))
        timer.onPoint(p1, 30_000)
        timer.dismiss()
        assertNull(timer.visiblePoint(30_001))
    }
}
