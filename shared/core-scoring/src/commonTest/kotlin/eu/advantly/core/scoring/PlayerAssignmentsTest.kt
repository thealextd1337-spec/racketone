package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerAssignmentsTest {
    private val owner = PlayerId("owner")
    private val slot = Placeholders.id("opponent")
    private val person = Player(PlayerId("bea"), "Bea")
    private val format = Presets.BADMINTON_1GAME_15.format()
    private val lineup = Lineup(listOf(owner), listOf(slot), owner)

    private fun event(
        n: Long,
        payload: EventPayload,
        device: String = "watch",
    ) = ScoreEvent(
        EventId("e$n$device"),
        MatchId("m"),
        DeviceId(device),
        n + 1,
        n,
        n * 10_000,
        if (n == 0L) null else EventId("e${n - 1}watch"),
        payload,
    )

    @Test
    fun assignmentFromDisplayAfterFinishPreservesEveryScoreFieldAndUndoTarget() {
        val base =
            listOf(event(0, MatchCreated(format, lineup, createdBy = owner))) +
                (1L..15L).map { event(it, PointWon(Side.A)) }
        val before = ScoringEngine.replay(base)
        val assigned = event(16, PlayerAssigned(slot, person, owner), "phone")
        val after = ScoringEngine.apply(before, assigned)
        assertEquals(before.state, after.state)
        assertEquals(before.undoTarget, after.undoTarget)
        assertEquals(before.points.toList(), after.points.toList())
        assertFalse(EventLog.isScorerOnly(assigned.payload))
        assertEquals(mapOf(slot to person), PlayerAssignments.resolve(base + assigned))
        assertEquals(after.state, ScoringEngine.replay((base + assigned + assigned).reversed()).state)
    }

    @Test
    fun competingAssignmentsResolveInEventOrderAndCannotBeUndone() {
        val genesis = event(0, MatchCreated(format, lineup, createdBy = owner))
        val first = event(1, PlayerAssigned(slot, person, owner), "phone")
        val second = event(2, PlayerAssigned(slot, Player(PlayerId("max"), "Max"), owner))
        val undo = event(3, Undo(first.id))
        val log = listOf(genesis, first, second, undo)
        assertEquals(mapOf(slot to Player(PlayerId("max"), "Max")), PlayerAssignments.resolve(log.reversed() + first))
        assertTrue(undo.id in EventLog.analyze(log).invalidUndos)
    }

    @Test
    fun correctionReleasesPreviousPersonAndNullReleasesSlot() {
        val secondSlot = Placeholders.id("partner")
        val genesis =
            event(
                0,
                MatchCreated(
                    format.copy(discipline = Discipline.DOUBLES),
                    lineup.copy(a = listOf(owner, secondSlot), b = listOf(slot, Placeholders.id("b2"))),
                    createdBy = owner,
                ),
            )
        val max = Player(PlayerId("max"), "Max")
        val log =
            listOf(
                genesis,
                event(1, PlayerAssigned(slot, person, owner)),
                event(2, PlayerAssigned(slot, max, owner)),
                event(3, PlayerAssigned(secondSlot, person, owner)),
            )
        assertEquals(mapOf(slot to max, secondSlot to person), PlayerAssignments.resolve(log.reversed()))
        assertEquals(mapOf(secondSlot to person), PlayerAssignments.resolve(log + event(4, PlayerAssigned(slot, null, owner))))
    }

    @Test
    fun legacyFlagMakesEventOnlyDeliveryIndependentOfLocalRegistry() {
        val legacy = PlayerId("legacy")
        val genesis = event(0, MatchCreated(format, lineup.copy(b = listOf(legacy)), createdBy = owner))
        assertEquals(
            mapOf(legacy to person),
            PlayerAssignments.resolve(
                listOf(
                    genesis,
                    event(1, PlayerAssigned(legacy, person, owner, legacySlot = true)),
                ),
            ),
        )
    }

    @Test
    fun wrongCreatorRealSlotsAndDuplicatePeopleAreRejectedAndLegacyIdsNeedEvidence() {
        val legacy = PlayerId("legacy-opponent")
        val genesis = event(0, MatchCreated(format, lineup.copy(b = listOf(legacy)), createdBy = owner))
        val assignment = event(2, PlayerAssigned(legacy, person, owner))
        assertTrue(PlayerAssignments.resolve(listOf(genesis, assignment)).isEmpty())
        assertEquals(mapOf(legacy to person), PlayerAssignments.resolve(listOf(genesis, assignment), setOf(legacy)))
        val invalid =
            listOf(
                event(1, PlayerAssigned(legacy, person, PlayerId("stranger"))),
                event(2, PlayerAssigned(owner, person, owner)),
                event(3, PlayerAssigned(legacy, Player(owner, "Owner"), owner)),
                event(4, PlayerAssigned(legacy, Player(slot, "Unknown"), owner)),
            )
        assertTrue(PlayerAssignments.resolve(listOf(genesis) + invalid, setOf(legacy)).isEmpty())
    }
}
