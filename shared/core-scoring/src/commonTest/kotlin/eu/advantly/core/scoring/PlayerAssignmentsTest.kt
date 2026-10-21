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
    fun assignmentSourcesFollowOnlyValidWinnersAndClearWithUnassignment() {
        val genesis = event(0, MatchCreated(format, lineup, createdBy = owner))
        val first = event(1, PlayerAssigned(slot, person, owner))
        val invalid = event(2, PlayerAssigned(slot, person, PlayerId("outsider")))
        assertEquals(mapOf(slot to first.id), PlayerAssignments.assignmentEventIds(listOf(invalid, first, genesis)))
        val next = event(3, PlayerAssigned.reference(slot, person.id, owner))
        assertEquals(mapOf(slot to next.id), PlayerAssignments.assignmentEventIds(listOf(next, invalid, genesis, first)))
        val cleared = event(4, PlayerAssigned(slot, null, owner))
        assertTrue(PlayerAssignments.assignmentEventIds(listOf(cleared, next, first, genesis)).isEmpty())
    }

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

    @Test
    fun referencesPreserveScoreUndoAndIdentityWithoutReusingLegacyNames() {
        val base =
            listOf(event(0, MatchCreated(format, lineup, createdBy = owner))) +
                (1L..15L).map { event(it, PointWon(Side.A)) }
        val before = ScoringEngine.replay(base)
        val legacy = event(16, PlayerAssigned(slot, person, owner), "phone")
        val reference = event(17, PlayerAssigned.reference(slot, person.id, owner), "phone")
        val log = base + legacy + reference
        val after = ScoringEngine.replay(log)
        assertEquals(before.state, after.state)
        assertEquals(before.undoTarget, after.undoTarget)
        assertEquals(before.points.toList(), after.points.toList())
        assertFalse(EventLog.isScorerOnly(reference.payload))
        assertEquals(mapOf(slot to person.id), PlayerAssignments.projectIds(log).peopleBySlot)
        assertTrue(PlayerAssignments.resolve(log).isEmpty())
        val undo = event(18, Undo(reference.id))
        assertTrue(undo.id in EventLog.analyze(log + undo).invalidUndos)
        assertEquals(PlayerAssignments.projectIds(log), PlayerAssignments.projectIds((log + undo).reversed() + reference))
        val pointUndo = event(18, Undo(base.last().id))
        assertEquals(ScoringEngine.replay(base + pointUndo).state, ScoringEngine.replay(log + pointUndo).state)
    }

    @Test
    fun mixedAssignmentsShareDuplicateChecksAndReleasePeopleOnCorrectionAndNull() {
        val partner = Placeholders.id("partner")
        val genesis =
            event(
                0,
                MatchCreated(
                    format.copy(discipline = Discipline.DOUBLES),
                    lineup.copy(a = listOf(owner, partner), b = listOf(slot, Placeholders.id("b2"))),
                    createdBy = owner,
                ),
            )
        val max = Player(PlayerId("max"), "Max")
        val first = listOf(genesis, event(1, PlayerAssigned.reference(slot, person.id, owner)))
        val duplicateLegacy = first + event(2, PlayerAssigned(partner, person, owner))
        assertEquals(mapOf(slot to person.id), PlayerAssignments.projectIds(duplicateLegacy).peopleBySlot)
        assertTrue(PlayerAssignments.resolve(duplicateLegacy).isEmpty())
        val corrected =
            duplicateLegacy + event(3, PlayerAssigned(slot, max, owner)) +
                event(4, PlayerAssigned.reference(partner, person.id, owner))
        assertEquals(mapOf(slot to max.id, partner to person.id), PlayerAssignments.projectIds(corrected).peopleBySlot)
        assertEquals(mapOf(slot to max), PlayerAssignments.resolve(corrected))
        val cleared =
            corrected + event(5, PlayerAssigned.reference(slot, null, owner)) +
                event(6, PlayerAssigned.reference(partner, max.id, owner))
        assertEquals(mapOf(partner to max.id), PlayerAssignments.projectIds(cleared).peopleBySlot)
        assertTrue(PlayerAssignments.resolve(cleared).isEmpty())
        val namedAgain = cleared + event(7, PlayerAssigned(partner, max, owner))
        assertEquals(mapOf(partner to max), PlayerAssignments.resolve(namedAgain))
        assertEquals(PlayerAssignments.projectIds(namedAgain), PlayerAssignments.projectIds(namedAgain.reversed() + namedAgain))
    }

    @Test
    fun idReferencesUseOwnerFallbackCreatorAliasAndLegacySlotEvidence() {
        val legacy = PlayerId("old-slot")
        val genesis = event(0, MatchCreated(format, lineup.copy(b = listOf(legacy))))
        val assignment = event(1, PlayerAssigned.reference(legacy, person.id, PlayerId("phone-self"), creatorAlias = owner))
        assertTrue(PlayerAssignments.projectIds(listOf(genesis, assignment)).peopleBySlot.isEmpty())
        assertEquals(
            PlayerAssignments.IdentityProjection(setOf(legacy), mapOf(legacy to person.id)),
            PlayerAssignments.projectIds(listOf(genesis, assignment), setOf(legacy)),
        )
        val proof = event(2, PlayerAssigned.reference(legacy, person.id, owner, legacySlot = true))
        assertEquals(
            PlayerAssignments.projectIds(listOf(genesis, assignment), setOf(legacy)),
            PlayerAssignments.projectIds(listOf(proof, assignment, genesis)),
        )
        assertEquals(setOf(legacy), PlayerAssignments.project(listOf(genesis, proof)).slots)
    }

    @Test
    fun referencesRejectWrongActorsSlotsMatchOrderAndConflictingEventCopies() {
        val genesis = event(0, MatchCreated(format, lineup, createdBy = owner))
        val valid = event(2, PlayerAssigned.reference(slot, person.id, owner))
        val conflict = valid.copy(payload = PlayerAssigned.reference(slot, PlayerId("max"), owner))
        val invalid =
            listOf(
                event(-1, PlayerAssigned.reference(slot, person.id, owner)),
                event(1, PlayerAssigned.reference(slot, person.id, PlayerId("stranger"))),
                valid,
                conflict,
                event(3, PlayerAssigned.reference(owner, person.id, owner)),
                event(4, PlayerAssigned.reference(PlayerId("foreign-slot"), person.id, owner, legacySlot = true)),
                event(5, PlayerAssigned.reference(slot, person.id, owner)).copy(matchId = MatchId("other")),
                event(6, PlayerAssigned.reference(slot, person.id, PlayerId("stranger"), creatorAlias = PlayerId("other"))),
            )
        assertTrue(PlayerAssignments.projectIds(listOf(genesis) + invalid).peopleBySlot.isEmpty())
        assertEquals(
            PlayerAssignments.projectIds(listOf(genesis) + invalid),
            PlayerAssignments.projectIds((listOf(genesis) + invalid).reversed()),
        )
        assertTrue(PlayerAssignments.projectIds(listOf(valid)).slots.isEmpty())
        val rejectedGenesis = genesis.copy(payload = (genesis.payload as MatchCreated).copy(createdBy = PlayerId("other")))
        assertTrue(PlayerAssignments.projectIds(listOf(genesis, rejectedGenesis, valid)).slots.isEmpty())
    }

    @Test
    fun invalidReferencesAndLegacyNamesCannotDisplaceValidIdentity() {
        val genesis = event(0, MatchCreated(format, lineup, createdBy = owner))
        val assigned = event(1, PlayerAssigned(slot, person, owner))
        val invalidPayloads =
            listOf(
                PlayerAssigned.reference(slot, owner, owner),
                PlayerAssigned.reference(slot, slot, owner),
                PlayerAssigned.reference(slot, PlayerId(" "), owner),
                PlayerAssigned.reference(slot, Placeholders.id("foreign"), owner),
                PlayerAssigned(slot, person.copy(displayName = " "), owner),
                PlayerAssigned(slot, person.copy(isPlaceholder = true), owner),
                PlayerAssigned(slot, person.copy(id = PlayerId("")), owner),
                PlayerAssigned(slot, person.copy(id = Placeholders.id("foreign")), owner),
                PlayerAssigned(slot, null, owner, assignmentVersion = 3),
                PlayerAssigned(slot, null, owner, playerId = PlayerId("max")),
                PlayerAssigned.reference(slot, PlayerId("max"), owner).copy(player = person),
            )
        for ((index, payload) in invalidPayloads.withIndex()) {
            val log = listOf(genesis, assigned, event(index + 2L, payload))
            assertEquals(mapOf(slot to person.id), PlayerAssignments.projectIds(log).peopleBySlot, payload.toString())
            assertEquals(mapOf(slot to person), PlayerAssignments.resolve(log), payload.toString())
        }
    }

    @Test
    fun legacyPlaceholdersCannotBecomePeopleAndInvalidEvidenceCannotUnlockSlots() {
        val legacy = PlayerId("old-slot")
        val genesis = event(0, MatchCreated(format, lineup.copy(b = listOf(legacy)), createdBy = owner))
        val invalidProofs =
            listOf(
                event(1, PlayerAssigned.reference(legacy, person.id, PlayerId("stranger"), legacySlot = true)),
                event(2, PlayerAssigned.reference(legacy, person.id, owner, legacySlot = true)).copy(matchId = MatchId("foreign")),
                event(-1, PlayerAssigned.reference(legacy, person.id, owner, legacySlot = true)),
                event(3, PlayerAssigned(legacy, null, owner, legacySlot = true, assignmentVersion = 99)),
            )
        val assignment = event(4, PlayerAssigned.reference(legacy, person.id, owner))
        assertTrue(PlayerAssignments.projectIds(listOf(genesis, assignment) + invalidProofs).slots.isEmpty())
        val conflict = event(5, PlayerAssigned.reference(legacy, person.id, owner, legacySlot = true))
        val conflictingProofs = listOf(conflict, conflict.copy(payload = PlayerAssigned.reference(legacy, null, owner, legacySlot = true)))
        assertTrue(PlayerAssignments.projectIds(listOf(genesis, assignment) + conflictingProofs).slots.isEmpty())
        val placeholderGenesis = event(0, MatchCreated(format, lineup, createdBy = owner))
        val fakePerson = event(1, PlayerAssigned.reference(slot, legacy, owner))
        assertTrue(PlayerAssignments.projectIds(listOf(placeholderGenesis, fakePerson), setOf(legacy)).peopleBySlot.isEmpty())
        assertTrue(
            PlayerAssignments
                .projectIds(
                    listOf(placeholderGenesis, event(1, PlayerAssigned(slot, Player(legacy, "Old"), owner))),
                    setOf(legacy),
                ).peopleBySlot
                .isEmpty(),
        )
    }
}
