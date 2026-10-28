package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.ServeTracking
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.PointDetailSet
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.ServerChoice
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.data.scoreEvents
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Session-Kommandos „Bedienung & Statistik“ (Feldtest 2026-10-15): Rolle, Vorbedingungen, Snapshot-Felder, Sync. */
class OperationSessionTest {
    private val doubles =
        Lineup(listOf(PlayerId("me"), PlayerId("max")), listOf(PlayerId("bea"), PlayerId("cem")), PlayerId("me"))

    private fun cluster(): Triple<Cluster, Device, Device> {
        val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        return Triple(cluster, cluster["phone"], cluster["watch"])
    }

    @Test
    fun offlineChoicesConvergeWithDuplicatesAndConfirmedScorerHandover() {
        val presets =
            listOf(
                Presets.TENNIS_BO3_MTB10,
                Presets.TENNIS_SHORT_SETS,
                Presets.PADEL_GOLDEN_BO3,
                Presets.PADEL_PREMIER_2026,
                Presets.TENNIS_TIEBREAK_ONLY_7,
                Presets.TENNIS_TIEBREAK_ONLY_10,
                Presets.PADEL_TIEBREAK_ONLY_7,
                Presets.PADEL_TIEBREAK_ONLY_10,
            )
        for (preset in presets) {
            for (origin in listOf("phone", "watch")) {
                for (index in 0..3) {
                    val (cluster, phone, watch) = cluster()
                    phone.session.create(preset.format(Discipline.DOUBLES), doubles, scorer = ScorerChoice.Device(DeviceId(origin)))
                    cluster.settle()
                    val scorer = cluster[origin]
                    val display = if (origin == "phone") watch else phone
                    cluster.offline(display.name)
                    val first = assertNotNull(scorer.match.serverChoiceContext)
                    val selected = first.players[index]
                    assertTrue(scorer.session.chooseServer(selected, first))
                    val count = scorer.match.eventCount
                    assertTrue(scorer.session.chooseServer(selected, first))
                    assertEquals(count, scorer.match.eventCount)
                    repeat(if (first.gameKind == GameKind.STANDARD) 4 else 1) { assertTrue(scorer.session.recordPoint(Side.A)) }
                    val second = assertNotNull(scorer.match.serverChoiceContext)
                    assertEquals(ServerChoicePhase.SECOND, second.phase)
                    // Alter v3-Aufrufer ohne erwarteten Kontext erzeugt dasselbe Event.
                    assertTrue(scorer.session.chooseServer(second.players.last()))
                    assertTrue(scorer.session.recordPoint(Side.B))
                    cluster.network.duplicateRate = 1.0
                    cluster.online(display.name)
                    cluster.settle(5_000)
                    cluster.assertConverged()
                    assertEquals(ScoringEngine.fold(scorer.data.events.scoreEvents(scorer.match.id)), display.score)
                    assertTrue(display.session.takeOverScoring())
                    cluster.settle()
                    assertTrue(display.match.isScorer)
                    assertFalse(scorer.match.isScorer)
                    assertFalse(scorer.session.chooseServer(selected, first))
                    assertTrue(display.session.recordPoint(Side.A))
                    cluster.settle()
                    cluster.assertConverged()
                }
            }
        }
    }

    @Test
    fun removeLastPointOfSideAcrossSetBoundaryWithRedo() {
        val (cluster, phone, watch) = cluster()
        phone.session.create(Presets.TENNIS_SHORT_SETS.format(), SINGLES)
        assertEquals(SidePair(false, false), phone.match.canRemovePoint)
        assertFalse(phone.session.removeLastPointOf(Side.A), "noch kein Punkt")
        repeat(16) { phone.session.recordPoint(Side.A) }
        repeat(2) { phone.session.recordPoint(Side.B) }
        cluster.settle()
        assertEquals(SidePair(true, true), phone.match.canRemovePoint)
        assertEquals(SidePair(false, false), watch.match.canRemovePoint, "Anzeige-Gerät")
        assertFalse(watch.session.removeLastPointOf(Side.A), "nur der Zähler")

        assertTrue(phone.session.removeLastPointOf(Side.A))
        cluster.settle()
        assertEquals(emptyList(), watch.score.sets, "Satzball abgezogen")
        assertEquals(SidePair(3, 2), watch.score.current?.points)
        assertTrue(phone.match.canRedo)
        assertTrue(phone.session.redo())
        cluster.settle()
        assertEquals(1, watch.score.sets.size)
        cluster.assertConverged()
    }

    @Test
    fun doublesServerChoiceOnlyOfferedPlayers() {
        val (cluster, phone, watch) = cluster()
        phone.session.create(Presets.PADEL_GOLDEN_BO3.format(), doubles)
        cluster.settle()
        assertTrue(phone.match.awaitingServerChoice)
        assertTrue(watch.match.awaitingServerChoice, "Anzeige sieht die offene Wahl")
        assertFalse(watch.session.chooseServer(PlayerId("cem")), "nur der Zähler")
        assertFalse(phone.session.chooseServer(PlayerId("nobody")))
        assertTrue(phone.session.chooseServer(PlayerId("cem")))
        assertTrue(phone.session.chooseServer(PlayerId("cem")), "gleiche Wahl = No-op")
        cluster.settle()
        assertEquals(PlayerId("cem"), watch.score.serve?.player)
        assertFalse(watch.match.awaitingServerChoice)
        repeat(4) { phone.session.recordPoint(Side.B) }
        cluster.settle()
        assertEquals(ServerChoice(2, Side.A, listOf(PlayerId("me"), PlayerId("max")), null), watch.match.serverChoice)
        assertFalse(phone.session.chooseServer(PlayerId("bea")), "Team B hat schon aufgeschlagen")
        assertTrue(phone.session.chooseServer(PlayerId("max")))
        cluster.settle()
        assertEquals(PlayerId("max"), watch.score.serve?.player)
        // ohne Wahlfenster nichts schreiben
        phone.session.recordPoint(Side.A)
        val events = phone.data.events.count(phone.match.id.value)
        assertFalse(phone.session.chooseServer(PlayerId("me")))
        assertEquals(events, phone.data.events.count(phone.match.id.value))
        cluster.settle()
        cluster.assertConverged()
    }

    /** Review R1: Nach einer Korrektur ist die alte Wahl aufgehoben; dieselbe Wahl erneut schreibt ein Event und wirkt. */
    @Test
    fun chooseServerAgainAfterCorrectionTakesEffect() {
        val (_, phone, _) = cluster()
        phone.session.create(Presets.PADEL_GOLDEN_BO3.format(), doubles)
        assertTrue(phone.session.chooseServer(PlayerId("max")))
        assertTrue(
            phone.session.correct(
                eu.advantly.core.scoring.Correction
                    .servingOrder(Side.A, listOf(PlayerId("me"), PlayerId("max"))),
            ),
        )
        assertEquals(PlayerId("me"), phone.score.serve?.player)
        assertTrue(phone.match.awaitingServerChoice, "Wahl aufgehoben")
        val before = phone.data.events.count(phone.match.id.value)
        assertTrue(phone.session.chooseServer(PlayerId("max")))
        assertEquals(before + 1, phone.data.events.count(phone.match.id.value), "kein No-op")
        assertEquals(PlayerId("max"), phone.score.serve?.player)
        assertEquals(PlayerId("max"), phone.match.serverChoice?.chosen)
    }

    @Test
    fun expectedChoiceContextRejectsStalePhaseSetMatchAndRoleWithoutEvents() {
        val (cluster, phone, watch) = cluster()
        phone.session.create(Presets.TENNIS_SHORT_SETS.format(Discipline.DOUBLES), doubles)
        cluster.settle()
        val first = assertNotNull(phone.match.serverChoiceContext)
        assertEquals(ServerChoicePhase.FIRST, first.phase)
        assertEquals(GameKind.STANDARD, first.gameKind)
        assertEquals(PlayerId("me"), first.defaultServer)
        assertEquals(Side.A, first.dueSide)
        val remoteCount = watch.data.events.count(phone.match.id.value)
        assertFalse(watch.session.chooseServer(PlayerId("cem"), first))
        assertEquals(remoteCount, watch.data.events.count(phone.match.id.value))
        repeat(4) { phone.session.recordPoint(Side.A) }
        val second = assertNotNull(phone.match.serverChoiceContext)
        assertEquals(ServerChoicePhase.SECOND, second.phase)
        assertEquals(listOf(PlayerId("bea"), PlayerId("cem")), second.players)
        assertEquals(PlayerId("bea"), second.defaultServer)
        val count = phone.data.events.count(phone.match.id.value)
        assertFalse(phone.session.chooseServer(PlayerId("cem"), first), "Spieler wäre in neuem Fenster ebenfalls erlaubt")
        assertFalse(phone.session.chooseServer(PlayerId("cem"), second.copy(players = listOf(PlayerId("cem")))))
        assertFalse(phone.session.chooseServer(PlayerId("cem"), second.copy(dueSide = Side.A)))
        assertFalse(phone.session.chooseServer(PlayerId("nobody"), second))
        assertEquals(count, phone.data.events.count(phone.match.id.value))
        assertTrue(phone.session.chooseServer(PlayerId("cem"), second))
        repeat(12) { phone.session.recordPoint(Side.A) }
        assertEquals(1, phone.score.current?.setIndex)
        val next = assertNotNull(phone.match.serverChoiceContext)
        val nextCount = phone.data.events.count(phone.match.id.value)
        assertFalse(phone.session.chooseServer(PlayerId("max"), first), "gleiche Phase, anderer Satz")
        assertEquals(nextCount, phone.data.events.count(phone.match.id.value))
        cluster.settle()
        cluster.assertConverged()
        phone.session.create(Presets.PADEL_GOLDEN_BO3.format(), doubles)
        val newCount = phone.data.events.count(phone.match.id.value)
        assertFalse(phone.session.chooseServer(PlayerId("max"), next), "anderes Match mit gleichen Spielern")
        assertFalse(phone.session.chooseServer(PlayerId("max"), first))
        assertEquals(newCount, phone.data.events.count(phone.match.id.value))
    }

    @Test
    fun expectedChoiceIsIdempotentEvenWhenOpeningTeamChangesAndKeepsRedo() {
        val (_, phone, _) = cluster()
        phone.session.create(Presets.PADEL_GOLDEN_BO3.format(), doubles)
        phone.session.recordPoint(Side.A)
        phone.session.undo()
        val first = assertNotNull(phone.match.serverChoiceContext)
        val count = phone.match.eventCount
        assertTrue(phone.session.chooseServer(PlayerId("cem"), first))
        assertEquals(count + 1, phone.match.eventCount)
        assertEquals(Side.B, phone.match.serverChoiceContext?.dueSide)
        assertTrue(phone.session.chooseServer(PlayerId("cem"), first), "Doppeltap mit ursprünglichem Kontext")
        assertEquals(count + 1, phone.match.eventCount)
        assertTrue(phone.match.canRedo)
        assertTrue(phone.session.redo())
        assertEquals(PlayerId("me"), phone.match.lastPoint?.server, "Punkt steht im Log vor der Wahl")
        assertNull(phone.match.serverChoiceContext, "nach Redo wird die spätere Wahl wieder zur frühzeitigen zweiten Wahl")
    }

    @Test
    fun correctionInvalidatesExpectedChoiceAndRetroactiveCollisionIsRejected() {
        val (cluster, phone, watch) = cluster()
        phone.session.create(Presets.PADEL_GOLDEN_BO3.format(), doubles)
        assertTrue(phone.match.canCorrectServerRetroactively)
        assertTrue(phone.session.chooseServer(PlayerId("max")))
        val chosen = assertNotNull(phone.match.serverChoiceContext)
        assertFalse(phone.match.canCorrectServerRetroactively)
        val count = phone.match.eventCount
        assertFalse(phone.session.correctServer(PlayerId("cem"), retroactive = true))
        assertFalse(phone.session.correct(Correction.initialServer(PlayerId("cem"))), "auch direkter Korrekturpfad")
        assertEquals(count, phone.match.eventCount)
        assertTrue(phone.session.correctServer(PlayerId("me"), retroactive = false))
        assertNull(phone.match.serverChoiceContext?.chosen)
        assertEquals(PlayerId("me"), phone.match.serverChoiceContext?.defaultServer)
        val correctedCount = phone.match.eventCount
        assertFalse(phone.session.chooseServer(PlayerId("max"), chosen), "vor Korrektur geöffnetes Sheet")
        assertEquals(correctedCount, phone.match.eventCount)
        assertTrue(phone.session.chooseServer(PlayerId("max"), assertNotNull(phone.match.serverChoiceContext)))
        phone.session.recordPoint(Side.A)
        val point = assertNotNull(phone.match.lastPoint)
        assertTrue(phone.session.correctServer(PlayerId("cem"), retroactive = false))
        assertEquals(PlayerId("cem"), phone.score.serve?.player)
        assertEquals(PlayerId("max"), phone.match.lastPoint?.server)
        assertEquals(point.id, phone.match.lastPoint?.id)
        cluster.settle()
        assertFalse(watch.session.correctServer(PlayerId("me"), retroactive = false))
        assertEquals(phone.score.serve, watch.score.serve)
        cluster.assertConverged()
    }

    @Test
    fun choiceContextUsesGameKindForTiebreakAndReopensAfterFirstPointUndo() {
        for (preset in listOf(Presets.TENNIS_TIEBREAK_ONLY_7, Presets.PADEL_TIEBREAK_ONLY_10)) {
            val (_, phone, _) = cluster()
            phone.session.create(preset.format(Discipline.DOUBLES), doubles)
            val first = assertNotNull(phone.match.serverChoiceContext)
            assertEquals(GameKind.MATCH_TIEBREAK, first.gameKind)
            assertTrue(phone.session.chooseServer(PlayerId("max"), first))
            phone.session.recordPoint(Side.A)
            val second = assertNotNull(phone.match.serverChoiceContext)
            assertEquals(ServerChoicePhase.SECOND, second.phase)
            assertTrue(phone.session.chooseServer(PlayerId("cem"), second))
            phone.session.undo()
            val reopened = assertNotNull(phone.match.serverChoiceContext)
            assertEquals(ServerChoicePhase.FIRST, reopened.phase)
            assertEquals(PlayerId("cem"), reopened.chosen)
            assertEquals(PlayerId("cem"), reopened.defaultServer)
            assertEquals(4, reopened.players.size)
            val count = phone.match.eventCount
            assertFalse(phone.session.chooseServer(PlayerId("cem"), second), "auch gleicher Spieler ist in anderer Phase stale")
            assertEquals(count, phone.match.eventCount)
        }
    }

    @Test
    fun retroactiveCorrectionStillWorksWithoutAnOpeningChoice() {
        val (_, phone, _) = cluster()
        phone.session.create(Presets.PADEL_GOLDEN_BO3.format(), doubles)
        repeat(4) { phone.session.recordPoint(Side.A) }
        assertTrue(phone.session.chooseServer(PlayerId("cem")))
        assertTrue(phone.match.canCorrectServerRetroactively, "nur zweite Wahl, keine übersteuernde Eröffnungswahl")
        assertTrue(phone.session.correctServer(PlayerId("max"), retroactive = true))
        assertEquals(PlayerId("max"), phone.match.lastPoint?.server)
        assertEquals(PlayerId("cem"), phone.score.serve?.player)
    }

    @Test
    fun choiceContextIsAbsentForSinglesAndOtherSports() {
        val (_, phone, _) = cluster()
        for (preset in listOf(
            Presets.TENNIS_SINGLES_BO3,
            Presets.PICKLEBALL_DOUBLES_11_SIDEOUT,
            Presets.BADMINTON_DOUBLES_BO3_21,
            Presets.TABLE_TENNIS_DOUBLES_BO3,
        )) {
            val format = preset.format()
            phone.session.create(format, if (format.discipline == Discipline.DOUBLES) doubles else SINGLES)
            assertNull(phone.match.serverChoiceContext, preset.id)
            assertTrue(phone.match.canCorrectServerRetroactively, preset.id)
        }
    }

    @Test
    fun serveInfoBarSetsAndOverwritesTheLastPoint() {
        val (cluster, phone, watch) = cluster()
        phone.session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        assertNull(phone.match.lastPoint)
        assertTrue(phone.match.serveInfoEnabled)
        phone.session.recordPoint(Side.A)
        val first = assertNotNull(phone.match.lastPoint)
        assertEquals(Side.A, first.servingSide)
        assertFalse(first.doubleFaultAllowed)
        assertFalse(phone.session.setServeInfo(first.id, ServeOutcome.DOUBLE_FAULT), "Punkt des Aufschlägers")
        assertTrue(phone.session.setServeInfo(first.id, ServeOutcome.FIRST_SERVE_IN))
        assertEquals(ServeOutcome.FIRST_SERVE_IN, phone.match.lastPoint?.serve)
        assertTrue(phone.session.setServeInfo(first.id, ServeOutcome.FIRST_SERVE_IN), "unverändert = No-op")
        phone.session.recordPoint(Side.B)
        val second = assertNotNull(phone.match.lastPoint)
        assertTrue(second.doubleFaultAllowed)
        assertTrue(phone.session.setServeInfo(second.id, ServeOutcome.DOUBLE_FAULT))
        assertTrue(phone.session.setServeInfo(second.id, ServeOutcome.SECOND_SERVE_IN))
        assertTrue(phone.session.setServeInfo(first.id, null), "nachträglich löschen")
        cluster.settle()
        assertEquals(ServeOutcome.SECOND_SERVE_IN, watch.match.lastPoint?.serve)
        assertFalse(watch.session.setServeInfo(second.id, ServeOutcome.FIRST_SERVE_IN), "nur der Zähler")
        val details =
            phone.data.events
                .scoreEvents(phone.match.id)
                .count { it.payload is PointDetailSet }
        assertEquals(4, details, "No-op und abgelehnte Eingaben schreiben nichts")
        assertEquals(emptyList(), phone.score.ignoredEvents)
        cluster.assertConverged()
    }

    @Test
    fun serveInfoOnlyForTennisPadelWithOption() {
        val (_, phone, _) = cluster()
        phone.session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES, MatchMeta(serveTracking = ServeTracking.OFF))
        phone.session.recordPoint(Side.A)
        assertFalse(phone.match.serveInfoEnabled)
        assertFalse(phone.session.setServeInfo(assertNotNull(phone.match.lastPoint).id, ServeOutcome.FIRST_SERVE_IN))
        phone.session.create(Presets.BADMINTON_SINGLES_BO3_21.format(), SINGLES)
        phone.session.recordPoint(Side.A)
        assertFalse(phone.match.serveInfoEnabled)
        assertFalse(phone.session.setServeInfo(assertNotNull(phone.match.lastPoint).id, ServeOutcome.FIRST_SERVE_IN))
    }

    private fun Device.serves() = ScoringEngine.pointHistory(data.events.scoreEvents(match.id)).map { it.serve }

    private fun Device.detailEvents() = data.events.scoreEvents(match.id).count { it.payload is PointDetailSet }

    @Test
    fun fullModeRecordsFirstServeWithoutInputOnNextPointAndTimeout() {
        val (cluster, phone, watch) = cluster()
        phone.session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        phone.session.recordPoint(Side.A)
        assertNull(phone.match.lastPoint?.serve, "solange die Leiste steht, noch offen")
        phone.session.recordPoint(Side.B) // nächster Punkt ohne Eingabe → 1. Aufschlag
        val second = assertNotNull(phone.match.lastPoint)
        assertTrue(phone.session.setServeInfo(second.id, ServeOutcome.SECOND_SERVE_IN))
        phone.session.recordPoint(Side.A) // Eingabe bleibt
        val third = assertNotNull(phone.match.lastPoint)
        assertTrue(phone.session.settleServeInfo(third.id), "Anzeigezeit abgelaufen")
        assertTrue(phone.session.settleServeInfo(third.id), "zweites Ende ist ein No-op")
        assertEquals(listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN, ServeOutcome.FIRST_SERVE_IN), phone.serves())
        assertEquals(3, phone.detailEvents(), "je Punkt genau ein PointDetailSet, nie doppelt")
        // Undo nimmt den Punkt zurück, nicht die Aufschlag-Info des vorigen
        assertTrue(phone.session.undo())
        assertEquals(second.id, phone.match.lastPoint?.id)
        assertEquals(ServeOutcome.SECOND_SERVE_IN, phone.match.lastPoint?.serve)
        assertTrue(phone.session.redo())
        assertEquals(ServeOutcome.FIRST_SERVE_IN, phone.match.lastPoint?.serve)
        assertFalse(watch.session.settleServeInfo(third.id), "nur der Zähler")
        cluster.settle()
        assertEquals(phone.serves(), watch.serves())
        cluster.assertConverged()
    }

    @Test
    fun fullModeSettlesTheMatchPointAndTheLastPointOnEarlyEnd() {
        val (_, phone, _) = cluster()
        phone.session.create(Presets.TENNIS_TIEBREAK_ONLY_7.format(), SINGLES)
        repeat(7) { phone.session.recordPoint(Side.A) }
        assertEquals(MatchStatus.FINISHED, phone.score.status)
        assertEquals(List(7) { ServeOutcome.FIRST_SERVE_IN }, phone.serves(), "auch der Punkt, der das Match beendet")
        phone.session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        phone.session.recordPoint(Side.B)
        assertTrue(phone.session.endMatch(EndReason.STOPPED))
        assertEquals(listOf(ServeOutcome.FIRST_SERVE_IN), phone.serves())
    }

    @Test
    fun doubleFaultsOnlyModeOffersDoubleFaultsAndSetsNothingAutomatically() {
        val (_, phone, _) = cluster()
        phone.session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES, MatchMeta(serveTracking = ServeTracking.DOUBLE_FAULTS_ONLY))
        assertTrue(phone.match.serveInfoEnabled)
        phone.session.recordPoint(Side.A)
        assertFalse(
            phone.session.setServeInfo(assertNotNull(phone.match.lastPoint).id, ServeOutcome.DOUBLE_FAULT),
            "Punkt des Aufschlägers",
        )
        phone.session.recordPoint(Side.B)
        val returned = assertNotNull(phone.match.lastPoint)
        assertTrue(returned.doubleFaultAllowed)
        assertTrue(phone.session.setServeInfo(returned.id, ServeOutcome.DOUBLE_FAULT))
        phone.session.recordPoint(Side.B)
        assertTrue(phone.session.settleServeInfo(assertNotNull(phone.match.lastPoint).id))
        assertTrue(phone.session.endMatch(EndReason.STOPPED))
        assertEquals(listOf(null, ServeOutcome.DOUBLE_FAULT, null), phone.serves())
        assertEquals(1, phone.detailEvents())
    }

    @Test
    fun setEndOffersTiebreakSetsAndKeepsOldWireForm() {
        val (cluster, phone, watch) = cluster()
        phone.session.create(Presets.TENNIS_OPEN.format(), SINGLES)
        assertEquals(emptyList(), phone.match.nextSetOptions)
        assertFalse(phone.session.startNextSet(NextSetKind.TIEBREAK_7), "kein Satzende-Dialog")
        repeat(24) { phone.session.recordPoint(Side.A) }
        cluster.settle()
        assertEquals(listOf(NextSetKind.REGULAR, NextSetKind.TIEBREAK_7, NextSetKind.MATCH_TIEBREAK_10), phone.match.nextSetOptions)
        assertFalse(watch.session.startNextSet(NextSetKind.TIEBREAK_7), "nur der Zähler")
        assertTrue(phone.session.startNextSet(NextSetKind.TIEBREAK_7))
        cluster.settle()
        assertEquals(SetKind.MATCH_TIEBREAK, watch.score.current?.setKind)
        repeat(7) { phone.session.recordPoint(Side.B) }
        // Champions-TB bis 10 = config.matchTiebreak (MTB10) → ältere Wire-Form MATCH_TIEBREAK
        assertTrue(phone.session.startNextSet(NextSetKind.MATCH_TIEBREAK_10))
        val decisions =
            phone.data.events
                .scoreEvents(phone.match.id)
                .mapNotNull { it.payload as? ContinueDecision }
        assertEquals(listOf(NextSetKind.TIEBREAK_7, NextSetKind.MATCH_TIEBREAK), decisions.map { it.nextSetKind })
        repeat(10) { phone.session.recordPoint(Side.A) }
        assertTrue(phone.session.endMatch())
        cluster.settle()
        assertEquals(MatchStatus.FINISHED, watch.score.status)
        assertEquals(SidePair(2, 1), watch.score.setsWon)
        cluster.assertConverged()
    }

    @Test
    fun startWithNewFormats() {
        val (cluster, phone, watch) = cluster()
        phone.session.create(Presets.TENNIS_TIEBREAK_ONLY_7.format(), SINGLES)
        repeat(7) { phone.session.recordPoint(Side.A) }
        cluster.settle()
        assertEquals(MatchStatus.FINISHED, watch.score.status)
        phone.session.create(Presets.withGamesPerSet(Presets.TENNIS_SINGLES_BO3.format(), 1), SINGLES)
        repeat(8) { phone.session.recordPoint(Side.A) }
        cluster.settle()
        assertEquals(1, watch.score.sets.size)
        assertFalse(phone.session.startNextSet(NextSetKind.TIEBREAK_7), "BEST_OF: kein Satzende-Dialog")
        cluster.assertConverged()
    }
}
