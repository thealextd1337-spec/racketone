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

        assertTrue(alex.phone.handOverScoring(match, jonas.phone.deviceId))
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

        assertTrue(alex.phone.handOverScoring(match, jonas.watch.deviceId))
        settle()
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

        assertTrue(alex.phone.handOverScoring(match, jonas.phone.deviceId))
        settle()
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
