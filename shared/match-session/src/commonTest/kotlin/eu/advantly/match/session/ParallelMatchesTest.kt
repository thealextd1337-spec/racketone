package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.WatchAssignment
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

        // Die Uhr bleibt bei A, obwohl B und C (als Backup) bei ihr ankommen
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
        assertTrue(cluster["watch"].data.matches.get(b) != null && cluster["watch"].data.matches.get(c) != null)

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
        assertEquals(0, phone.state.value.sync.pendingToPeers, "Sofort-Backup: alles bestätigt")
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
        cluster.offline("watch")
        cluster.settle()
        val d = phone.create(format, SINGLES, scorer = ScorerChoice.MyWatch) // Uhr offline: Handy übernimmt A
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
            "0",
            onPhone.state.current!!
                .display.a,
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
        assertEquals(
            MatchStatus.FINISHED,
            cluster["watch"]
                .data.matches
                .get(c)
                ?.status,
            "Backup des Abbruchs auf der Uhr",
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
