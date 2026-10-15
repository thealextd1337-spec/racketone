package eu.advantly.wear.glance

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Preset
import eu.advantly.core.scoring.Presets
import eu.advantly.match.session.ScorerChoice
import eu.advantly.wear.WatchHarness
import eu.advantly.wear.match.WatchSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceModelTest {
    private val h = WatchHarness()
    private val me = PlayerId("me")
    private val other = PlayerId("other")
    private val singles = Lineup(listOf(me), listOf(other), me)

    private fun derive(): GlanceState =
        GlanceModel.derive(
            h.watch.state.value,
            h.directory.lastSetup(),
            { if (it == other) "Bea" else null },
            { it == me },
        )

    private fun start(preset: Preset = Presets.TENNIS_SINGLES_BO3) {
        h.watch.create(preset.format(Discipline.SINGLES), singles, MatchMeta())
    }

    @Test
    fun emptyWatchHasNoLiveScoreOrRepeatSetup() {
        assertEquals(GlanceState(), derive())
    }

    @Test
    fun storedSnapshotPreservesSetupScoreServerNamesAndRole() {
        start()
        h.watch.recordPoint(Side.B)
        val snapshot = derive()
        assertEquals(snapshot, GlanceStateStorage.decode(GlanceStateStorage.encode(snapshot)))
        assertEquals(GlanceState(), GlanceStateStorage.decode(GlanceStateStorage.encode(GlanceState())))
    }

    @Test
    fun scoreUndoAndServerFollowSession() {
        start()
        h.watch.recordPoint(Side.A)
        assertEquals("15:0", derive().live?.points)
        assertEquals("0:0", derive().live?.sets)
        assertEquals("0:0", derive().live?.games)
        assertTrue(derive().live!!.server!!.isSelf)
        h.watch.undo()
        assertEquals("0:0", derive().live?.points)
        repeat(4) { h.watch.recordPoint(Side.A) }
        assertEquals("1:0", derive().live?.games)
        assertEquals("Bea", derive().live?.server?.name)
    }

    @Test
    fun advantageAndCompletedSetKeepCanonicalScore() {
        start()
        repeat(3) {
            h.watch.recordPoint(Side.A)
            h.watch.recordPoint(Side.B)
        }
        h.watch.recordPoint(Side.A)
        assertEquals("AD:40", derive().live?.points)
        h.watch.recordPoint(Side.A)
        repeat(20) { h.watch.recordPoint(Side.A) }
        assertEquals("1:0", derive().live?.sets)
        assertEquals("0:0", derive().live?.games)
    }

    @Test
    fun rallySportsHavePointsWithoutInventedGames() {
        listOf(
            Presets.PICKLEBALL_SINGLES_11_SIDEOUT,
            Presets.BADMINTON_SINGLES_BO3_21,
            Presets.TABLE_TENNIS_SINGLES_BO5,
        ).forEach { preset ->
            h.watch.closeMatch()
            start(preset)
            h.watch.recordPoint(Side.A)
            assertEquals("1:0", derive().live?.points)
            assertNull(derive().live?.games)
        }
    }

    @Test
    fun phoneScorerIsDisplayAndRoleChangeIsReflected() {
        h.phone.create(Presets.TENNIS_SINGLES_BO3.format(), singles, MatchMeta(), ScorerChoice.ThisDevice)
        h.settle()
        assertFalse(derive().live!!.scorer)
        h.phone.recordPoint(Side.B)
        h.settle()
        assertEquals("0:15", derive().live?.points)
        assertTrue(h.watch.takeOverScoring())
        assertTrue(derive().live!!.scorer)
    }

    @Test
    fun pausedAndAwaitingDecisionRemainLiveButFinishedDoesNot() {
        start(Presets.TENNIS_OPEN)
        h.watch.pause()
        assertEquals(MatchStatus.PAUSED, derive().live?.status)
        h.watch.resumePlay()
        repeat(24) { h.watch.recordPoint(Side.A) }
        assertEquals(MatchStatus.AWAITING_DECISION, derive().live?.status)
        assertNull(derive().live?.points)
        h.watch.endMatch(EndReason.STOPPED)
        assertNull(derive().live)
        assertNotNull(derive().last)
    }

    @Test
    fun unrelatedOpenMatchesAreNeverSelected() {
        start()
        val state = h.watch.state.value
        assertNull(GlanceModel.derive(state.copy(match = null), null, { null }, { false }).live)
    }

    @Test
    fun quickStartAfterFinishedResultCreatesOneMatchAndPreservesResult() {
        start()
        h.watch.recordPoint(Side.A)
        h.watch.endMatch(EndReason.STOPPED)
        val previous =
            h.watch.state.value.match!!
                .id
        val result = h.watchData.matches.get(previous)
        assertEquals(
            GlanceAction.REPEAT,
            GlanceLaunch.resolve(
                "REPEAT",
                h.watch.state.value.match!!
                    .state.status,
            ),
        )
        h.controller.close()
        assertTrue(h.controller.repeatLast())
        assertTrue(
            previous !=
                h.watch.state.value.match!!
                    .id,
        )
        assertEquals(result, h.watchData.matches.get(previous))
        assertEquals(
            2,
            h.watchData.matches
                .recent(10)
                .size,
        )
    }

    @Test
    fun repeatPreservesDoublesFormatNamesAndNeutralSlots() {
        val partner = PlayerId("partner")
        val opponent2 = PlayerId("opponent2")
        val setup =
            WatchSetup(
                Presets.PADEL_GOLDEN_BO3.format(),
                Lineup(listOf(me, partner), listOf(other, opponent2), me),
                MatchMeta(),
            )
        val result = GlanceModel.derive(h.watch.state.value, setup, { if (it == other) "Bea" else " " }, { it == me })
        assertEquals(setup.format, result.last?.format)
        assertEquals(4, result.last?.players?.size)
        assertNull(
            result.last
                ?.players
                ?.get(1)
                ?.name,
        )
        assertEquals(
            "Bea",
            result.last
                ?.players
                ?.get(2)
                ?.name,
        )
    }
}
