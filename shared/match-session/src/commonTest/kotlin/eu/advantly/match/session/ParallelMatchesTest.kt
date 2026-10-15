package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.sync.protocol.Events
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.WatchAssignment
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mehrere Matches parallel am Handy (F22, 01 §4.9): Handy mit drei Matches, die Uhr zählt genau eines und folgt nur
 * ihrem Uhr-Match; Übergabe, Beenden/Abbrechen eines Nicht-Uhr-Matches, Prozessneustart, Migration.
 */
class ParallelMatchesTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val padel = Presets.PADEL_GOLDEN_BO3.format()
    private val doubles =
        Lineup(listOf(PlayerId("me"), PlayerId("ann")), listOf(PlayerId("bea"), PlayerId("cid")), PlayerId("me"))

    private val cluster = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
    private val phone get() = cluster["phone"].session
    private val watch get() = cluster["watch"].session

    private fun MatchSession.points(
        sequence: String,
        match: MatchId? = null,
    ) {
        match?.let { assertTrue(resume(it)) }
        sequence.forEach { assertTrue(recordPoint(if (it == 'A') Side.A else Side.B), "$it auf ${deviceId.value}") }
    }

    /** A: Uhr zählt; B und C: Handy als Director (Nachbarplätze). */
    private fun threeMatches(): Triple<MatchId, MatchId, MatchId> {
        val a = phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
        cluster.settle()
        val b = phone.create(padel, doubles)
        val c = phone.create(format, SINGLES)
        cluster.settle()
        return Triple(a, b, c)
    }

    @Test
    fun phoneHoldsThreeMatchesAndTheWatchOnlyItsOwn() {
        val (a, b, c) = threeMatches()
        val state = phone.state.value
        assertEquals(listOf(a, b, c), state.matches.map { it.id }, "alle offen, nach Erstellzeit")
        assertEquals(c, state.match?.id, "zuletzt angelegtes im Fokus")
        assertEquals(a, state.watchMatchId)
        assertEquals(DeviceId("watch"), state.match(a)?.scorerDeviceId)
        assertTrue(state.match(b)!!.isScorer && state.match(c)!!.isScorer, "übrige zählt das Handy als Director")

        // Die Uhr bleibt bei A; B und C erreichen sie gar nicht (Routing Handy → Uhr: nur das Uhr-Match)
        assertEquals(
            a,
            watch.state.value.match
                ?.id,
        )
        assertEquals(
            listOf(a),
            watch.state.value.matches
                .map { it.id },
        )
        assertTrue(
            watch.state.value.match!!
                .isScorer,
        )
        assertNull(cluster["watch"].data.matches.get(b), "Director-Match nicht auf der Uhr")
        assertNull(cluster["watch"].data.matches.get(c), "Director-Match nicht auf der Uhr")

        // Zählen auf allen drei Plätzen, pro Match genau ein Zähler
        watch.points("AAB")
        phone.points("BB", b)
        phone.points("A", c)
        assertFalse(phone.resume(a) && phone.recordPoint(Side.A), "A zählt nur die Uhr")
        cluster.settle()
        assertEquals(
            watch.state.value.match!!
                .state,
            phone.state.value
                .match(a)!!
                .state,
        )
        assertEquals(
            "30",
            phone.state.value
                .match(a)!!
                .state.current!!
                .display.a,
        )
        assertEquals(
            "30",
            phone.state.value
                .match(b)!!
                .state.current!!
                .display.b,
        )
        assertEquals(
            1,
            phone.state.value
                .match(c)!!
                .state.current!!
                .points.a,
        )
        assertEquals(
            a,
            watch.state.value.match
                ?.id,
            "Punkte der Nachbarplätze holen die Uhr nicht weg",
        )
        assertEquals(0, phone.state.value.sync.pendingToPeers, "Uhr-Match bestätigt, Director-Matches ohne Ziel nicht ausstehend")
        assertNull(cluster["watch"].data.matches.get(b), "auch nach Punkten und Abgleich nicht")
        assertTrue(cluster["phone"].data.events.unconfirmedCount() > 0, "Director-Events gelten nicht fälschlich als bestätigt")
    }

    @Test
    fun newWatchMatchHandsTheOldOneToThePhone() {
        val (a, b, _) = threeMatches()
        watch.points("AA")
        cluster.settle()
        // Uhr-Match wechseln: neues Match „Meine Uhr“ – das Handy übernimmt A als Director
        val d = phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
        cluster.settle()
        assertEquals(d, phone.state.value.watchMatchId)
        assertTrue(
            phone.state.value
                .match(a)!!
                .isScorer,
            "Handy zählt A jetzt selbst",
        )
        assertEquals(
            MatchStatus.IN_PROGRESS,
            phone.state.value
                .match(a)!!
                .state.status,
        )
        assertEquals(
            d,
            watch.state.value.match
                ?.id,
            "Uhr folgt ihrem neuen Match",
        )
        assertTrue(
            watch.state.value.match!!
                .isScorer,
        )
        assertTrue(watch.recordPoint(Side.B))
        phone.points("A", a)
        phone.points("B", b)
        cluster.settle()
        assertEquals(
            "40",
            phone.state.value
                .match(a)!!
                .state.current!!
                .display.a,
            "A läuft am Handy weiter",
        )
        assertEquals(
            1,
            phone.state.value
                .match(d)!!
                .state.current!!
                .points.b,
        )
        assertEquals(4, phone.state.value.matches.size)
    }

    @Test
    fun lateInputsOfTheWatchOnItsOldMatchAreRejected() {
        val (a, _, _) = threeMatches()
        watch.points("A") // Uhr hat A schon selbst gezählt …
        cluster.offline("watch")
        cluster.settle()
        assertEquals(a, phone.watchMatchNeedingTakeOver(), "… und ist nicht erreichbar: Wechsel nur mit Bestätigung")
        assertFailsWith<IllegalStateException> { phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch) }
        assertFalse(
            phone.assignToWatch(
                phone.state.value.matches[1]
                    .id,
            ),
        )
        // bestätigt: das Handy übernimmt A sofort
        val d = phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch, takeOverWatchMatch = true)
        watch.points("AAA") // Uhr zählt offline weiter auf A
        cluster.online("watch")
        cluster.settle()
        assertEquals(
            d,
            watch.state.value.match
                ?.id,
        )
        val onPhone = phone.state.value.match(a)!!
        assertEquals(3, onPhone.rejectedByRoleCount, "verspätete Uhr-Eingaben nach der Übernahme")
        assertEquals(
            "15",
            onPhone.state.current!!
                .display.a,
            "der Punkt vor dem Offline-Gehen bleibt",
        )
        // sichtbarer Hinweis auf der Uhr nach der Wiederverbindung
        assertEquals(DroppedInputs(a, 3), watch.state.value.droppedInputs)
    }

    /** Review R2 #4: Der Hinweis übersteht einen Neustart der Uhr, bis die UI ihn quittiert. */
    @Test
    fun droppedInputsNoticeSurvivesARestartUntilAcknowledged() {
        val (a, _, _) = threeMatches()
        watch.points("A")
        cluster.offline("watch")
        cluster.settle()
        phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch, takeOverWatchMatch = true)
        watch.points("AA")
        cluster.online("watch")
        cluster.settle()
        assertEquals(DroppedInputs(a, 2), watch.state.value.droppedInputs)

        fun restartedWatch() =
            MatchSession(cluster["watch"].data, null, cluster.scheduler, SessionConfig(DeviceId("watch"), PeerKind.WATCH), cluster.clock)
        assertEquals(DroppedInputs(a, 2), restartedWatch().state.value.droppedInputs, "nach Neustart noch da")
        watch.acknowledgeDroppedInputs()
        assertNull(watch.state.value.droppedInputs)
        assertNull(restartedWatch().state.value.droppedInputs, "quittiert: weg")
    }

    /** Review R2 #1a: Genesis zugestellt, Ack unterwegs, Uhr zählt – der Wechsel verwirft keinen Uhr-Punkt. */
    @Test
    fun switchWhileTheAckIsInFlightKeepsWatchPoints() {
        val a = phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
        cluster.settle(25) // Genesis bei der Uhr (20 ms), Ack noch unterwegs
        assertEquals(
            a,
            watch.state.value.match
                ?.id,
        )
        assertTrue(watch.recordPoint(Side.A))
        assertTrue(watch.recordPoint(Side.A))
        phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
        cluster.settle()
        val old = phone.state.value.match(a)!!
        assertEquals(0, old.rejectedByRoleCount, "nichts verworfen")
        assertEquals(
            "30",
            old.state.current!!
                .display.a,
        )
        assertTrue(old.isScorer, "danach zählt das Handy")
    }

    /** Review R2 #1b: Trennung kurz vor dem Ack – der Wechsel verlangt die Bestätigung. */
    @Test
    fun disconnectBeforeTheAckStillAsksBeforeTakingOver() {
        val a = phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
        cluster.settle(25)
        assertTrue(watch.recordPoint(Side.A))
        assertTrue(watch.recordPoint(Side.A))
        cluster.offline("watch")
        cluster.settle()
        assertEquals(a, phone.watchMatchNeedingTakeOver(), "Match wurde an die Uhr gesendet: Rückfrage")
        assertFailsWith<IllegalStateException> { phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch) }
    }

    /** Review R2 #2: ausdrücklicher Wechsel zu einem älteren Director-Match – die Uhr folgt und gibt ihr Match ab. */
    @Test
    fun explicitSwitchToAnOlderMatchWinsOverTheWatchStartedOne() {
        val a = phone.create(format, SINGLES) // Director, Uhr frei → zeigt A an
        cluster.settle(1_000)
        val w = watch.create(format, SINGLES) // Uhr startet selbst W (jünger)
        cluster.settle()
        assertEquals(w, phone.state.value.watchMatchId, "Rennregel: jüngeres Uhr-Match")
        assertTrue(watch.recordPoint(Side.B))
        assertTrue(phone.assignToWatch(a, watchScores = true))
        cluster.settle(65_000)
        assertEquals(a, phone.state.value.watchMatchId)
        assertEquals(
            a,
            watch.state.value.match
                ?.id,
        )
        assertTrue(
            watch.state.value.match!!
                .isScorer,
        )
        val watchScored =
            phone.state.value.matches
                .filter { it.state.status != MatchStatus.FINISHED && it.scorerDeviceId == DeviceId("watch") }
                .map { it.id }
        assertEquals(listOf(a), watchScored, "genau ein Match mit Zähler Uhr")
        val onPhone = phone.state.value.match(w)!!
        assertTrue(onPhone.isScorer, "W zählt jetzt das Handy")
        assertEquals(0, onPhone.rejectedByRoleCount)
        assertEquals(
            "15",
            onPhone.state.current!!
                .display.b,
            "Uhr-Punkt in W bleibt",
        )
    }

    /** Review R2 #3: Vor dem Hello gehen keine Matchdaten an eine unbekannte Gegenstelle. */
    @Test
    fun unidentifiedPeersGetNoMatchData() {
        for (kind in listOf(PeerKind.WATCH, PeerKind.PHONE)) {
            val node = WireNode("p-$kind", 21, identifyPeer = false)
            node.session.create(format, SINGLES, scorer = ScorerChoice.MyWatch) // Uhr-Match (Platzhalter)
            val id = node.session.create(format, SINGLES) // Director-Match auf dem Nachbarplatz
            node.points(Side.A, 2)
            val before = node.link.sent.toList()
            assertTrue(before.none { it is Events }, "keine Events vor dem Hello")
            assertTrue(before.filterIsInstance<MatchMetaMessage>().all { it.match == null }, "kein Match-Kopf vor dem Hello")
            node.link.deliver(Hello(DeviceId("other"), kind))
            val after = node.link.sent.drop(before.size)
            val events = after.filterIsInstance<Events>().filter { it.matchId == id }
            if (kind == PeerKind.WATCH) {
                assertTrue(events.isEmpty(), "Director-Match nie an eine Uhr")
            } else {
                assertEquals(3, events.flatMap { it.events }.size, "zweites Handy: Rückstand nach dem Hello")
            }
        }
    }

    /** Review #5: Online-Wechsel des Uhr-Matches ist verlustfrei – die Uhr übergibt nach ihrer letzten Eingabe. */
    @Test
    fun onlineSwitchKeepsEveryWatchPoint() {
        val (a, _, _) = threeMatches()
        watch.points("A")
        cluster.settle()
        assertNull(phone.watchMatchNeedingTakeOver(), "Uhr erreichbar: keine Rückfrage")
        val d = phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
        // die Uhr zählt weiter, bevor die neue Zuweisung ankommt
        assertTrue(watch.recordPoint(Side.A))
        assertTrue(watch.recordPoint(Side.A))
        cluster.settle()
        val onPhone = phone.state.value.match(a)!!
        assertEquals(0, onPhone.rejectedByRoleCount, "nichts verworfen")
        assertEquals(
            "40",
            onPhone.state.current!!
                .display.a,
            "alle drei Uhr-Punkte zählen",
        )
        assertTrue(onPhone.isScorer, "danach zählt das Handy A")
        assertEquals(
            d,
            watch.state.value.match
                ?.id,
        )
        assertTrue(
            watch.state.value.match!!
                .isScorer,
        )
        assertNull(watch.state.value.droppedInputs)
    }

    /** Review #1: Uhr offline, Handy weist B zu, Uhr startet W – danach zählt die Uhr genau ein Match. */
    @Test
    fun offlineRaceWatchStartedLater() = offlineRace(watchFirst = false)

    @Test
    fun offlineRaceWatchStartedFirst() = offlineRace(watchFirst = true)

    @Test
    fun offlineRaceSameMillisecond() = offlineRace(watchFirst = true, sameMs = true)

    private fun offlineRace(
        watchFirst: Boolean,
        sameMs: Boolean = false,
    ) {
        cluster.offline("watch")
        cluster.settle()
        val (b, w) =
            if (watchFirst) {
                val w = watch.create(format, SINGLES)
                if (!sameMs) cluster.settle(1_000)
                phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch) to w
            } else {
                val b = phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch)
                cluster.settle(1_000)
                b to watch.create(format, SINGLES)
            }
        watch.points("AB")
        cluster.online("watch")
        cluster.settle(5_000)
        val watchScored =
            phone.state.value.matches
                .filter { it.state.status != MatchStatus.FINISHED && it.scorerDeviceId == DeviceId("watch") }
                .map { it.id }
        assertEquals(1, watchScored.size, "genau ein laufendes Match mit Zähler Uhr: $watchScored")
        val expected = if (watchFirst) b else w
        assertEquals(expected, phone.state.value.watchMatchId)
        assertEquals(listOf(expected), watchScored)
        assertEquals(
            expected,
            watch.state.value.match
                ?.id,
        )
        assertTrue(
            watch.state.value.match!!
                .isScorer,
        )
        // keine Uhr-Eingabe ging verloren
        assertEquals(
            0,
            phone.state.value
                .match(w)!!
                .rejectedByRoleCount,
        )
        assertEquals(
            "15",
            phone.state.value
                .match(w)!!
                .state.current!!
                .display.a,
        )
        assertTrue(
            listOf(b, w).all {
                phone.state.value
                    .match(it)!!
                    .state.status == MatchStatus.IN_PROGRESS
            },
        )
    }

    /** Review #2: „Abbrechen“ im Satzende-Dialog bleibt ohne Wertung (kein ContinueDecision). */
    @Test
    fun abandonDuringTheSetEndQuestionIsNotRated() {
        val open = phone.create(Presets.TENNIS_OPEN.format(), SINGLES)
        phone.points("AAAA".repeat(6))
        assertEquals(
            MatchStatus.AWAITING_DECISION,
            phone.state.value
                .match(open)!!
                .state.status,
        )
        assertTrue(phone.endMatch(EndReason.ABANDONED))
        assertEquals(
            MatchResult(null, ResultReason.ABANDONED),
            phone.state.value
                .match(open)!!
                .state.result,
        )
        val other = phone.create(Presets.TENNIS_OPEN.format(), SINGLES)
        phone.points("AAAA".repeat(6))
        assertTrue(phone.endMatch(EndReason.STOPPED), "Beenden im Dialog = reguläres Ende")
        assertEquals(
            MatchResult(Side.A, ResultReason.COMPLETED),
            phone.state.value
                .match(other)!!
                .state.result,
        )
    }

    @Test
    fun stoppingAndAbandoningNonWatchMatchesLeavesTheWatchAlone() {
        val (a, b, c) = threeMatches()
        watch.points("A")
        phone.points("AAAA" + "AA", b) // Padel: 1:0, 30:0
        assertTrue(phone.endMatch(EndReason.STOPPED))
        phone.points("B", c)
        assertTrue(phone.endMatch(EndReason.ABANDONED))
        cluster.settle()
        val state = phone.state.value
        assertEquals(
            ResultReason.STOPPED,
            state
                .match(b)!!
                .state.result!!
                .reason,
        )
        assertEquals(
            Side.A,
            state
                .match(b)!!
                .state.result!!
                .winner,
        )
        assertEquals(
            ResultReason.ABANDONED,
            state
                .match(c)!!
                .state.result!!
                .reason,
        )
        assertEquals(c, state.match?.id, "beendetes bleibt im Fokus, bis es geschlossen wird")
        phone.closeMatch()
        assertEquals(
            a,
            phone.state.value.match
                ?.id,
            "laufendes Match rückt nach",
        )
        phone.closeMatch(b)
        assertEquals(
            listOf(a),
            phone.state.value.matches
                .map { it.id },
        )
        // Uhr unberührt: weiter bei A, sie zählt weiter
        assertEquals(
            a,
            watch.state.value.match
                ?.id,
        )
        assertTrue(watch.recordPoint(Side.A))
        cluster.settle()
        assertEquals(
            "30",
            phone.state.value
                .match(a)!!
                .state.current!!
                .display.a,
        )
        assertNull(
            cluster["watch"]
                .data.matches
                .get(c)
                ?.status,
            "Abbruch eines Director-Matches erreicht die Uhr nicht",
        )
    }

    @Test
    fun endingTheWatchMatchFromThePhoneAfterTakeOver() {
        val (a, _, _) = threeMatches()
        watch.points("AB")
        cluster.settle()
        assertTrue(phone.resume(a))
        assertFalse(phone.endMatch(EndReason.STOPPED), "Anzeige beendet nicht ohne Übernahme")
        assertTrue(phone.takeOverScoring())
        assertTrue(phone.endMatch(EndReason.STOPPED))
        cluster.settle()
        val onWatch = watch.state.value.match!!
        assertEquals(a, onWatch.id)
        assertEquals(MatchStatus.FINISHED, onWatch.state.status, "Uhr zeigt das Ende")
        assertEquals(ResultReason.STOPPED, onWatch.state.result!!.reason)
        assertNull(onWatch.state.result!!.winner, "15:15")
    }

    @Test
    fun watchStartedMatchBecomesTheWatchMatch() {
        val (a, b, _) = threeMatches()
        assertTrue(phone.resume(b))
        // Uhr-Nutzer beendet A und startet selbst ein neues Match
        watch.endMatch()
        val w = watch.create(format, SINGLES)
        cluster.settle()
        val state = phone.state.value
        assertEquals(w, state.watchMatchId)
        assertEquals(b, state.match?.id, "Fokus am Handy bleibt beim laufenden Director-Match")
        assertTrue(w in state.matches.map { it.id }, "Uhr-Match erscheint in der Übersicht")
        assertEquals(MatchStatus.FINISHED, state.match(a)!!.state.status)
        assertEquals(
            w,
            watch.state.value.match
                ?.id,
        )
    }

    @Test
    fun watchFollowsOnlyItsAssignmentAfterItsMatchEnds() {
        val (a, b, _) = threeMatches()
        watch.endMatch() // Uhr bricht A ab
        cluster.settle()
        watch.closeMatch()
        cluster.settle()
        phone.points("A", b) // Punkte auf B wecken die Uhr nicht für B
        cluster.settle()
        assertNull(watch.state.value.match, "Uhr folgt keinem fremden Director-Match")
        // Neues Director-Match bei freier Uhr: die Uhr zeigt es an (wie bisher)
        val e = phone.create(format, SINGLES)
        cluster.settle()
        assertEquals(e, phone.state.value.watchMatchId)
        assertEquals(
            e,
            watch.state.value.match
                ?.id,
        )
        assertFalse(
            watch.state.value.match!!
                .isScorer,
        )
        assertEquals(
            MatchStatus.FINISHED,
            phone.state.value
                .match(a)!!
                .state.status,
        )
    }

    @Test
    fun assignToWatchMovesTheWatchToAnotherRunningMatch() {
        val (a, b, _) = threeMatches()
        assertTrue(phone.assignToWatch(b, watchScores = true))
        cluster.settle()
        assertEquals(b, phone.state.value.watchMatchId)
        assertTrue(
            phone.state.value
                .match(a)!!
                .isScorer,
            "A geht an das Handy",
        )
        assertEquals(
            b,
            watch.state.value.match
                ?.id,
        )
        assertTrue(
            watch.state.value.match!!
                .isScorer,
            "Uhr zählt B",
        )
        assertFalse(phone.assignToWatch(MatchId("unbekannt")))
        assertFalse(watch.assignToWatch(b), "nur am Handy")
    }

    @Test
    fun processRestartKeepsTheWatchMatch() {
        val (a, b, c) = threeMatches()
        val device = cluster["phone"]
        device.session.close()
        val restarted =
            MatchSession(
                device.data,
                ReliableDeviceLink(cluster.network.transport("phone2"), cluster.scheduler),
                cluster.scheduler,
                SessionConfig(DeviceId("phone"), PeerKind.PHONE),
                cluster.clock,
                RandomIdGenerator(Random(99)),
            )
        restarted.start()
        assertEquals(a, restarted.state.value.watchMatchId, "Zuweisung aus den Settings")
        // so öffnet der Adapter nach dem Neustart (älteste zuerst, jüngstes im Fokus)
        listOf(a, b, c).forEach { assertTrue(restarted.resume(it)) }
        assertEquals(
            listOf(a, b, c),
            restarted.state.value.matches
                .map { it.id },
        )
        assertEquals(
            c,
            restarted.state.value.match
                ?.id,
        )
        assertTrue(
            restarted.state.value
                .match(b)!!
                .isScorer,
        )
        assertFalse(
            restarted.state.value
                .match(a)!!
                .isScorer,
        )
    }

    @Test
    fun migrationMakesTheLastRunningMatchTheWatchMatch() {
        val data = testLocalData()
        val old = MatchSession(data, null, cluster.scheduler, SessionConfig(DeviceId("p"), PeerKind.PHONE), cluster.clock)
        val id = old.create(format, SINGLES)
        // Stand vor F22: keine gespeicherte Zuweisung
        data.settings.remove("session.watchMatch")
        data.settings.remove("session.watchMatchAt")
        val migrated = MatchSession(data, null, cluster.scheduler, SessionConfig(DeviceId("p"), PeerKind.PHONE), cluster.clock)
        assertEquals(id, migrated.state.value.watchMatchId)
    }

    @Test
    fun deletingTheWatchMatchFreesTheWatch() {
        val (a, b, _) = threeMatches()
        assertTrue(phone.resume(a))
        assertTrue(phone.takeOverScoring())
        assertTrue(phone.endMatch(EndReason.ABANDONED))
        phone.deleteMatch(a)
        cluster.settle()
        assertNull(phone.state.value.watchMatchId)
        assertNull(watch.state.value.match, "gelöschtes Uhr-Match ist auch auf der Uhr weg")
        val e = phone.create(format, SINGLES)
        cluster.settle()
        assertEquals(e, phone.state.value.watchMatchId)
        assertEquals(
            e,
            watch.state.value.match
                ?.id,
        )
        assertTrue(
            b in
                phone.state.value.matches
                    .map { it.id },
        )
    }

    @Test
    fun placeholderGoesOnlyToTheAssignedMatch() {
        cluster.offline("watch")
        cluster.settle()
        val a = phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch) // bekannte Uhr wird direkt Zähler
        val b = phone.create(format, SINGLES)
        cluster.online("watch")
        cluster.settle()
        assertEquals(
            a,
            watch.state.value.match
                ?.id,
        )
        assertTrue(
            phone.state.value
                .match(b)!!
                .isScorer,
        )
    }

    @Test
    fun watchIgnoresAnOlderAssignment() {
        val node = WireNode("w", 3, PeerKind.WATCH)
        val source = WireNode("p", 4)
        val first = source.session.create(format, SINGLES)
        val second = source.session.create(format, SINGLES)
        listOf(first, second).forEach {
            node.link.deliver(
                eu.advantly.sync.protocol
                    .Events(it, source.data.events.scoreEvents(it)),
            )
        }
        node.link.deliver(MatchMetaMessage(watchMatch = WatchAssignment(second, assignedAtMs = 50)))
        assertEquals(second, node.match.id)
        node.link.deliver(MatchMetaMessage(watchMatch = WatchAssignment(first, assignedAtMs = 40)))
        assertEquals(second, node.match.id, "verspätete ältere Zuweisung")
        node.link.deliver(MatchMetaMessage(watchMatch = WatchAssignment(first, assignedAtMs = 60)))
        assertEquals(first, node.match.id)
        assertEquals(
            listOf(first),
            node.session.state.value.matches
                .map { it.id },
        )
    }
}
