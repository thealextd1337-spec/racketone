package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EventOrdering
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.LocalData
import eu.advantly.data.scoreEvents
import eu.advantly.data.toStoredEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Zählen an ein anderes Konto weitergeben (plan-live-mitspieler §3.4, PR 10; p2-plan §11 G4/G5): Alex (Konto A) und
 * Jonas (Konto B) haben je Handy und Uhr. Die Cloud ist hier der Pull-Seam: Metadaten über `commitMetadata`, Events über
 * `storeDownloaded`, Readiness über `markReady`, danach der Host-Hook `eventsCommitted` – wie der Pull-Runner.
 */
class CrossAccountHandoverTest {
    private val alex = LifecyclePair().also { it.activate("A") }
    private val jonas = LifecyclePair(seedBase = 100).also { it.activate("B") }

    @AfterTest
    fun tearDown() {
        alex.close()
        jonas.close()
    }

    private fun settle() {
        alex.settle()
        jonas.settle()
    }

    private fun start(scorer: ScorerChoice): MatchId {
        val me =
            alex.phoneData.players
                .self()!!
                .id
        return alex.phone.create(
            Presets.TENNIS_SINGLES_BO3.format(),
            Lineup(listOf(me), listOf(PlayerId("jonas-bei-alex")), me),
            MatchMeta(),
            scorer,
        )
    }

    /** Ein Pull von [profile]: Kopf (Ersteller A), alle fehlenden Events, Readiness ([ready]) und der Session-Hook. */
    private fun pull(
        to: LifecyclePair,
        profile: String,
        from: LocalData,
        match: MatchId,
        ready: Boolean = true,
    ) {
        val data = to.phoneData
        val run = data.cloudPulls.beginRun(profile) {}.runSerial
        val page =
            CloudPullMetadataPage(
                listOf(CloudPullMatch(match.value, 1, """{"createdBy":"A"}""", "member", 1, 0, false, from.matches.get(match)!!)),
                emptyList(),
                emptyList(),
                1,
                false,
                1,
            )
        assertTrue(data.cloudPulls.commitMetadata(profile, run, data.cloudPulls.progress(profile).cursor, page) {})
        val known =
            data.events
                .scoreEvents(match)
                .map { it.id }
                .toSet()
        data.cloud.storeDownloaded(
            from.events
                .scoreEvents(match)
                .filter { it.id !in known }
                .map { it.toStoredEvent() },
        ) {}
        if (ready) data.replicas.markReady(data.replicas.captureContext(), match.value, 1, 0) {}
        to.phone.eventsCommitted(match.value)
        settle()
    }

    private fun toJonas(
        match: MatchId,
        ready: Boolean = true,
    ) = pull(jonas, "B", alex.phoneData, match, ready)

    private fun toAlex(match: MatchId) = pull(alex, "A", jonas.phoneData, match)

    private fun points(
        session: MatchSession,
        match: MatchId,
    ) = session.state.value
        .match(match)!!
        .pointsPlayed

    private fun scorerChanges(
        data: LocalData,
        match: MatchId,
    ) = data.events.scoreEvents(match).mapNotNull { (it.payload as? ScorerChanged)?.scorerDeviceId }

    /** Zählt Alex' Uhr, gibt sie selbst ab: erst Auftrag (`null`), nach dem Abgleich liefert das Handy die Abgabe der Uhr. */
    private fun watchHandOver(
        match: MatchId,
        target: DeviceId,
    ): ScorerHandover {
        assertNull(alex.phone.handOverScoring(match, target), "die Uhr erzeugt das ScorerChanged")
        assertTrue(alex.phone.watchHandOverPending(match))
        settle()
        assertFalse(alex.phone.watchHandOverPending(match))
        val handover = assertNotNull(alex.phone.handOverScoring(match, target))
        assertEquals(alex.watch.deviceId, handover.from)
        assertEquals(handover, alex.phone.handOverScoring(match, target), "wiederholbar")
        return handover
    }

    @Test
    fun alexPhoneHandsOverToJonasPhoneWithoutLosingAPoint() {
        val match = start(ScorerChoice.ThisDevice)
        assertTrue(alex.phone.recordPoint(Side.A))
        assertTrue(alex.phone.recordPoint(Side.B))
        toJonas(match)
        assertFalse(jonas.phone.scoresHere(match), "vor der Abgabe nur Anzeige")
        assertNull(
            jonas.phone.state.value
                .match(match),
        )

        assertNotNull(alex.phone.handOverScoring(match, jonas.phone.deviceId))
        assertFalse(alex.phone.recordPoint(Side.A), "Alex zählt nicht mehr")
        assertFalse(alex.phone.scoresHere(match))
        toJonas(match)

        assertTrue(jonas.phone.scoresHere(match))
        assertTrue(jonas.phone.resume(match))
        assertTrue(jonas.phone.recordPoint(Side.B))
        toAlex(match)

        assertEquals(3, points(jonas.phone, match))
        assertEquals(3, points(alex.phone, match))
        assertEquals(
            0,
            alex.phone.state.value
                .match(match)!!
                .rejectedByRoleCount,
        )
        assertEquals(listOf(jonas.phone.deviceId), scorerChanges(jonas.phoneData, match))
    }

    @Test
    fun alexWatchHandsOverToJonasWatch() {
        val match = start(ScorerChoice.MyWatch)
        settle()
        assertTrue(alex.watch.recordPoint(Side.A))
        assertTrue(alex.watch.recordPoint(Side.A))
        settle()

        watchHandOver(match, jonas.watch.deviceId)
        assertFalse(alex.watch.recordPoint(Side.B), "Alex' Uhr wechselt sofort in die Anzeige")
        assertEquals(
            jonas.watch.deviceId,
            alex.watch.state.value.match!!
                .scorerDeviceId,
        )
        toJonas(match)

        assertEquals(match, jonas.phone.state.value.watchMatchId, "Jonas' Handy gibt das Match an seine Uhr")
        assertEquals(
            match,
            jonas.watch.state.value.match
                ?.id,
        )
        assertTrue(jonas.watch.recordPoint(Side.B))
        settle()
        toAlex(match)

        assertEquals(3, points(alex.phone, match))
        assertEquals(3, points(jonas.phone, match))
        assertEquals(
            3,
            alex.watch.state.value.match!!
                .pointsPlayed,
            "Alex' Uhr zeigt den Stand von Jonas",
        )
        assertEquals(
            0,
            alex.phone.state.value
                .match(match)!!
                .rejectedByRoleCount,
        )
    }

    @Test
    fun alexWatchHandsOverToJonasPhone() {
        val match = start(ScorerChoice.MyWatch)
        settle()
        assertTrue(alex.watch.recordPoint(Side.A))
        settle()

        watchHandOver(match, jonas.phone.deviceId)
        assertFalse(alex.watch.recordPoint(Side.A))
        toJonas(match)

        assertTrue(jonas.phone.scoresHere(match))
        assertNull(jonas.phone.state.value.watchMatchId, "Jonas' Uhr bleibt frei")
        assertTrue(jonas.phone.resume(match))
        assertTrue(jonas.phone.recordPoint(Side.B))
        toAlex(match)
        settle()

        assertEquals(2, points(alex.phone, match))
        assertEquals(
            2,
            alex.watch.state.value.match!!
                .pointsPlayed,
            "gezogene Events gehen sofort an Alex' Uhr",
        )
    }

    /** M3: lehnt der Server die Abgabe ab, zählt Alex' Uhr wieder – sonst zählte niemand. */
    @Test
    fun rejectedHandoverHandsScoringBackToAlexWatch() {
        val match = start(ScorerChoice.MyWatch)
        settle()
        assertTrue(alex.watch.recordPoint(Side.A))
        settle()
        val handover = watchHandOver(match, jonas.phone.deviceId)
        assertEquals(jonas.phone.deviceId, handover.to)
        assertFalse(alex.watch.recordPoint(Side.A))

        assertTrue(alex.phone.handBackScoring(match, handover))
        settle()
        assertTrue(alex.watch.recordPoint(Side.B), "Alex' Uhr zählt wieder")
        settle()
        assertEquals(2, points(alex.phone, match))
        assertEquals(listOf(jonas.phone.deviceId, alex.watch.deviceId), scorerChanges(alex.phoneData, match).takeLast(2))
        assertFalse(alex.phone.handBackScoring(match, handover), "nur solange das abgelehnte Ziel zählt")
    }

    /** H2: Die Uhr zählt im Funkloch weiter; die Abgabe kommt erst nach ihrer letzten Eingabe – kein Uhr-Punkt geht verloren. */
    @Test
    fun watchCountingOfflineKeepsItsPointsThroughTheHandover() {
        val match = start(ScorerChoice.MyWatch)
        settle()
        assertTrue(alex.watch.recordPoint(Side.A))
        settle()
        alex.network.setConnected("watch", false)
        assertTrue(alex.watch.recordPoint(Side.A))
        assertTrue(alex.watch.recordPoint(Side.B))
        assertTrue(alex.watch.recordPoint(Side.B))
        settle()
        assertEquals(1, points(alex.phone, match), "das Handy kennt die Offline-Punkte noch nicht")

        assertNull(alex.phone.handOverScoring(match, jonas.phone.deviceId))
        assertTrue(alex.phone.watchHandOverPending(match), "ohne Uhr keine Abgabe am Handy")
        assertTrue(alex.phone.scoresHere(match))
        assertTrue(alex.watch.recordPoint(Side.A), "die Uhr zählt bis zum Auftrag weiter")
        alex.network.setConnected("watch", true)
        settle()

        val handover = assertNotNull(alex.phone.handOverScoring(match, jonas.phone.deviceId))
        assertEquals(alex.watch.deviceId, handover.from)
        assertFalse(alex.watch.recordPoint(Side.A))
        toJonas(match)
        assertTrue(jonas.phone.resume(match))
        assertTrue(jonas.phone.recordPoint(Side.B))
        toAlex(match)
        settle()

        assertEquals(6, points(alex.phone, match))
        assertEquals(6, points(jonas.phone, match))
        assertEquals(
            0,
            alex.phone.state.value
                .match(match)!!
                .rejectedByRoleCount,
            "kein Uhr-Punkt verworfen",
        )
    }

    /** Zurückgenommener Auftrag (Einladung beendet): Die Uhr führt ihn nach der Funkpause nicht mehr aus. */
    @Test
    fun cancelledWatchHandOverIsNotExecutedLater() {
        val match = start(ScorerChoice.MyWatch)
        settle()
        alex.network.setConnected("watch", false)
        assertNull(alex.phone.handOverScoring(match, jonas.phone.deviceId))
        alex.phone.cancelWatchHandOver(match)
        assertFalse(alex.phone.watchHandOverPending(match))
        alex.network.setConnected("watch", true)
        settle()

        assertTrue(alex.watch.recordPoint(Side.A), "die Uhr zählt weiter")
        settle()
        assertEquals(emptyList(), scorerChanges(alex.phoneData, match).filter { it == jonas.phone.deviceId })
        assertTrue(alex.phone.scoresHere(match))
    }

    /** Review H4 (a): Jonas' Rolle ist entzogen, sein Handy zählt noch – Alex holt das Zählen auf sein Handy zurück. */
    @Test
    fun creatorReclaimsScoringAndLaterForeignPointsAreDropped() {
        val match = start(ScorerChoice.ThisDevice)
        assertTrue(alex.phone.recordPoint(Side.A))
        toJonas(match)
        assertNotNull(alex.phone.handOverScoring(match, jonas.phone.deviceId))
        toJonas(match)
        assertTrue(jonas.phone.resume(match))
        assertTrue(jonas.phone.recordPoint(Side.B))
        toAlex(match)

        assertTrue(alex.phone.reclaimScoring(match))
        assertFalse(alex.phone.reclaimScoring(match), "nur einmal")
        assertTrue(alex.phone.scoresHere(match))
        assertTrue(jonas.phone.recordPoint(Side.B), "Jonas' Handy weiß es noch nicht")
        toAlex(match)
        assertEquals(2, points(alex.phone, match), "Jonas' Punkt danach zählt nicht")
        assertTrue(
            alex.phone.state.value
                .match(match)!!
                .rejectedByRoleCount >= 1,
            "„N Eingaben nicht übernommen“",
        )
        assertTrue(alex.phone.recordPoint(Side.A))
    }

    /** Review H4 (a): zählte vorher Alex' Uhr und ist es noch ihr Match, geht das Zählen an die Uhr zurück. */
    @Test
    fun creatorReclaimsScoringToHisWatch() {
        val match = start(ScorerChoice.MyWatch)
        settle()
        assertTrue(alex.watch.recordPoint(Side.A))
        settle()
        watchHandOver(match, jonas.phone.deviceId)
        assertTrue(alex.phone.reclaimScoring(match))
        settle()
        assertTrue(alex.watch.recordPoint(Side.A), "Alex' Uhr zählt wieder")
        settle()
        assertEquals(2, points(alex.phone, match))
    }

    /** Review H4 (b): Jonas' Rolle ist entzogen – seine Uhr wechselt in die Anzeige, das Match wird nur noch angezeigt. */
    @Test
    fun revokedScorerStopsScoringOnPhoneAndWatch() {
        val match = start(ScorerChoice.ThisDevice)
        assertTrue(alex.phone.recordPoint(Side.A))
        toJonas(match)
        assertNotNull(alex.phone.handOverScoring(match, jonas.watch.deviceId))
        toJonas(match)
        assertEquals(
            match,
            jonas.watch.state.value.match
                ?.id,
        )
        assertTrue(jonas.watch.recordPoint(Side.B))
        settle()
        assertTrue(jonas.phone.scoresHere(match))
        assertFalse(alex.phone.stopScoring(match, 0), "eigenes Match: kein Rechteverlust")

        assertTrue(jonas.phone.stopScoring(match, 2))
        settle()
        assertFalse(jonas.phone.scoresHere(match))
        assertFalse(jonas.phone.resume(match), "nur noch Anzeige")
        assertFalse(jonas.watch.recordPoint(Side.B), "Uhr in der Anzeige")
        assertEquals(DroppedInputs(match, 2), jonas.phone.state.value.droppedInputs)
        assertFalse(jonas.phone.stopScoring(match, 0), "einmalig")
        jonas.restartPhone()
        assertFalse(jonas.phone.resume(match), "übersteht den Neustart")

        // erneute Abgabe (neuer Zählerwechsel) hebt die Sperre auf
        toAlex(match)
        assertTrue(alex.phone.reclaimScoring(match))
        assertNotNull(alex.phone.handOverScoring(match, jonas.phone.deviceId))
        toJonas(match)
        assertTrue(jonas.phone.scoresHere(match))
    }

    /** N4: Die Antwort kommt ohne Replay aus dem Cache, bis neue Events ankommen (hier: Alex beendet das Match). */
    @Test
    fun theTakeOverAnswerFollowsNewlyPulledEvents() {
        val match = start(ScorerChoice.ThisDevice)
        assertTrue(alex.phone.recordPoint(Side.A))
        toJonas(match)
        assertTrue(jonas.phone.canTakeOverAsRole(match))
        assertTrue(jonas.phone.canTakeOverAsRole(match), "unverändert")
        assertTrue(alex.phone.endMatch())
        toJonas(match)
        assertFalse(jonas.phone.canTakeOverAsRole(match), "neue Events: neu berechnet, das Match ist beendet")
    }

    @Test
    fun emergencyTakeOverNeedsCompleteStateAndDropsLateAlexPoints() {
        val match = start(ScorerChoice.ThisDevice)
        assertTrue(alex.phone.recordPoint(Side.A))
        toJonas(match, ready = false)
        assertFalse(jonas.phone.canTakeOverAsRole(match), "ohne vollständigen Stand keine Übernahme")
        assertFalse(jonas.phone.takeOverAsRole(match))
        toJonas(match)
        assertTrue(jonas.phone.canTakeOverAsRole(match))
        assertFalse(alex.phone.canTakeOverAsRole(match), "eigenes Match: keine Rollen-Übernahme")

        // Alex' Handy ist offline und zählt weiter, Jonas übernimmt
        assertTrue(jonas.phone.takeOverAsRole(match))
        assertTrue(jonas.phone.scoresHere(match))
        assertFalse(jonas.phone.canTakeOverAsRole(match))
        assertTrue(jonas.phone.resume(match))
        assertTrue(jonas.phone.recordPoint(Side.B))
        val before =
            alex.phoneData.events
                .scoreEvents(match)
                .map { it.id }
                .toSet()
        assertTrue(alex.phone.recordPoint(Side.A))
        assertTrue(alex.phone.recordPoint(Side.A))
        // Punkte samt Aufschlag-Angaben, die Alex' Handy nach der Übernahme noch erzeugt
        val late =
            alex.phoneData.events
                .scoreEvents(match)
                .filter { it.id !in before }

        toAlex(match)
        toJonas(match)
        val takeOver =
            jonas.phoneData.events
                .scoreEvents(match)
                .single { it.payload is ScorerChanged }
        val dropped = late.filter { EventOrdering.compare(it, takeOver) > 0 }
        assertTrue(dropped.any { it.payload is PointWon }, "mindestens ein verspäteter Punkt")
        assertEquals(
            dropped.size,
            alex.phone.state.value
                .match(match)!!
                .rejectedByRoleCount,
            "„N Eingaben nicht übernommen“",
        )
        assertEquals(points(alex.phone, match), points(jonas.phone, match))
        assertEquals(1 + 1 + late.count { it.payload is PointWon && it !in dropped }, points(jonas.phone, match))
        assertEquals(
            DeviceId(jonas.phone.deviceId.value),
            alex.phone.state.value
                .match(match)!!
                .scorerDeviceId,
        )
    }
}
