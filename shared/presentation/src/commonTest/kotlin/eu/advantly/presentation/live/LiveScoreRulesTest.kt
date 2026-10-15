package eu.advantly.presentation.live

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.play
import eu.advantly.presentation.match.setupFor
import eu.advantly.presentation.match.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveScoreRulesTest {
    private fun started(
        preset: eu.advantly.core.scoring.Preset,
        firstServer: Side = Side.A,
    ): InMemoryMatchStore =
        testStore().apply {
            start(setupFor(preset.format(), firstServer))
        }

    private val InMemoryMatchStore.hint get() = LiveScoreRules.hint(active.value!!)

    @Test
    fun tennisBreakPointSetPointMatchPointAndDeuce() {
        val store = started(Presets.TENNIS_SINGLES_BO3, firstServer = Side.B)
        store.play("AAA")
        assertEquals(ScoreHint(ScoreHint.Kind.BreakPoint, Side.A, 3), store.hint)
        store.play("BBB")
        assertEquals(ScoreHint(ScoreHint.Kind.Deuce), store.hint)
        store.play("A")
        assertEquals(PointLabel.Advantage, LiveScoreRules.pointLabel(store.active.value!!.state, Side.A))
        store.play("A")
        assertEquals(ScoreHint(ScoreHint.Kind.ChangeEnds), store.hint, "Seitenwechsel nach ungerader Spielsumme")
        // 1:0 Spiele, A schlägt auf: Spielball (kein Break) bei 40:0
        store.play("AAA")
        assertEquals(ScoreHint(ScoreHint.Kind.GamePoint, Side.A, 3), store.hint)
        store.play("A")
        repeat(3) { store.play("AAAA") }
        // 5:0, 40:0 → Satzball
        store.play("AAA")
        assertEquals(ScoreHint(ScoreHint.Kind.SetPoint, Side.A, 3), store.hint)
        store.play("A")
        repeat(5) { store.play("AAAA") }
        store.play("AAA")
        assertEquals(ScoreHint(ScoreHint.Kind.MatchPoint, Side.A, 3), store.hint)
        store.play("A")
        assertEquals(ScoreHint(ScoreHint.Kind.MatchWon, Side.A), store.hint)
        assertEquals(PointLabel.Number("2"), LiveScoreRules.pointLabel(store.active.value!!.state, Side.A))
    }

    @Test
    fun padelGoldenPointAndStarPoint() {
        val golden = started(Presets.PADEL_GOLDEN_BO3)
        golden.play("AAABBB")
        assertEquals(ScoreHint(ScoreHint.Kind.DecidingPoint), golden.hint)

        val star = started(Presets.PADEL_PREMIER_2026)
        star.play("AAABBB" + "AB" + "AB")
        assertEquals(ScoreHint(ScoreHint.Kind.StarPoint), star.hint)
    }

    @Test
    fun tiebreakIsShownWhenNothingMoreImportantApplies() {
        val store = started(Presets.TENNIS_SINGLES_BO3)
        // 6:6 durch abwechselnde Spielgewinne (jeder hält seinen Aufschlag)
        repeat(6) {
            store.play("AAAA")
            store.play("BBBB")
        }
        store.play("A")
        assertEquals(ScoreHint(ScoreHint.Kind.Tiebreak), store.hint)
        val columns = LiveScoreRules.setColumns(store.active.value!!.state)
        assertEquals(1, columns.size)
        assertEquals(6, columns.single().values.a)
    }

    @Test
    fun badmintonIntervalAndDecidingPointAtTheCap() {
        val store = started(Presets.BADMINTON_1GAME_21)
        store.play("A".repeat(11))
        assertEquals(ScoreHint(ScoreHint.Kind.ChangeEnds), store.hint, "Best of 1: Wechsel bei 11 im einzigen Satz")
        val bo3 = started(Presets.BADMINTON_SINGLES_BO3_21)
        bo3.play("A".repeat(11))
        assertEquals(ScoreHint(ScoreHint.Kind.Interval), bo3.hint, "Kein Seitenwechsel im 1. von 3 Sätzen")
        bo3.play("B".repeat(9) + "AB".repeat(9) + "BB")
        // 20:20 → Verlängerung
        assertEquals(ScoreHint(ScoreHint.Kind.Deuce), bo3.hint)
        bo3.play("AB".repeat(9))
        assertEquals(ScoreHint(ScoreHint.Kind.DecidingPoint), bo3.hint, "29:29")
    }

    @Test
    fun pickleballSideOutAndSecondServer() {
        val store = started(Presets.PICKLEBALL_DOUBLES_11_SIDEOUT)
        // 0-0-2: Der erste Aufschläger verliert → Side-Out
        store.play("B")
        assertEquals(ScoreHint(ScoreHint.Kind.SideOut), store.hint)
        store.play("A")
        assertEquals(ScoreHint(ScoreHint.Kind.SecondServer), store.hint)
    }

    @Test
    fun awaitingDecisionHasNoHintAndSetColumnsShowRallyPoints() {
        val store = started(Presets.TABLE_TENNIS_OPEN)
        store.play("A".repeat(11))
        assertNull(store.hint)
        val columns = LiveScoreRules.setColumns(store.active.value!!.state)
        assertEquals(listOf(11), columns.map { it.values.a })
        assertEquals(Side.A, columns.single().winner)
    }

    @Test
    fun cuesFollowTheStrongestEffect() {
        val store = started(Presets.TENNIS_SINGLES_BO3)
        store.play("A")
        assertEquals(HapticCue.PointUs, LiveScoreRules.cueFor(store.active.value!!.state, Side.A))
        store.play("AAA")
        // 1:0 nach Spielgewinn A → Seitenwechsel ist stärker als Spiel
        assertEquals(HapticCue.ChangeEnds, LiveScoreRules.cueFor(store.active.value!!.state, Side.A))
        store.play("BBBB")
        assertEquals(HapticCue.GameLost, LiveScoreRules.cueFor(store.active.value!!.state, Side.B))
    }
}
