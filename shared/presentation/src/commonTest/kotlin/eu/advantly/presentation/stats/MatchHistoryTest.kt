package eu.advantly.presentation.stats

import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.presentation.match.LocalDay
import eu.advantly.presentation.match.play
import eu.advantly.presentation.match.setupFor
import eu.advantly.presentation.match.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchHistoryTest {
    private val store = testStore()
    private val setup = store.setupFor(Presets.PADEL_GOLDEN_BO3.format())
    private val selfIds = setOf(store.self.value.id)

    private fun match(
        reason: ResultReason,
        winner: Side? = Side.A,
        date: Long = 1,
    ) = run {
        val id = store.start(setup)
        store.play("AB")
        store.endMatch(EndReason.STOPPED)
        store.record(id)!!.summary.copy(result = MatchResult(winner, reason), finishedAtMs = date)
    }

    @Test
    fun h2hSeparatesPartnerAndOpponentAndExcludesAbandonment() {
        val matches =
            listOf(
                match(ResultReason.COMPLETED),
                match(ResultReason.RETIRED, Side.B),
                match(ResultReason.STOPPED, null),
                match(ResultReason.ABANDONED, null),
            )
        val opponent = setup.lineup.b.first()
        val partner = setup.lineup.a.last()
        val against = headToHead(matches, opponent, HistoryRole.OPPONENT, selfIds)
        assertEquals(Triple(1, 1, 1), Triple(against.wins, against.losses, against.draws))
        assertEquals(3, against.recent.size)
        assertEquals(3, headToHead(matches, partner, HistoryRole.PARTNER, selfIds).total)
        assertEquals(0, headToHead(matches, opponent, HistoryRole.PARTNER, selfIds).total)
        assertEquals(0, headToHead(matches, store.self.value.id, HistoryRole.PARTNER, selfIds).total)
    }

    @Test
    fun filterCombinesSportNamePersonAndRole() {
        val padel = match(ResultReason.COMPLETED)
        val tennis = padel.copy(setup = padel.setup.copy(format = Presets.TENNIS_ONE_SET.format()))
        val matches = listOf(padel, tennis)
        assertEquals(listOf(padel), filterHistory(matches, HistoryFilter(sport = Sport.PADEL, query = "  jOnAs "), selfIds))
        assertEquals(
            emptyList(),
            filterHistory(matches, HistoryFilter(person = setup.lineup.b.first(), role = HistoryRole.PARTNER), selfIds),
        )
        assertEquals(2, filterHistory(matches, HistoryFilter(person = setup.lineup.a.last(), role = HistoryRole.PARTNER), selfIds).size)
        assertTrue(filterHistory(matches, HistoryFilter(query = "nobody"), selfIds).isEmpty())
    }

    @Test
    fun assignedPlaceholderUsesResolvedIdentityAndNameImmediately() {
        val original = match(ResultReason.COMPLETED)
        val slot = setup.lineup.b.first()
        val person = store.addGuest("New identity")
        val assigned =
            original.copy(
                setup =
                    setup.copy(
                        players = setup.players.map { if (it.id == slot) Player(slot, "", isPlaceholder = true) else it },
                        assignedPlayers = mapOf(slot to person),
                    ),
            )
        assertEquals(1, headToHead(listOf(assigned), person.id, HistoryRole.OPPONENT, selfIds).total)
        assertEquals(0, headToHead(listOf(assigned), slot, HistoryRole.OPPONENT, selfIds).total)
        assertTrue(filterHistory(listOf(assigned), HistoryFilter(query = "Jonas"), selfIds).isEmpty())
        assertEquals(listOf(assigned), filterHistory(listOf(assigned), HistoryFilter(query = "identity"), selfIds))
        val unassigned = assigned.copy(setup = assigned.setup.copy(assignedPlayers = emptyMap()))
        assertTrue(historyPeople(unassigned.setup, selfIds).none { it.player.id == slot })
    }

    @Test
    fun isoWeekAndMonthGroupsHandleYearAndLeapDayBoundaries() {
        val matches = listOf(1L, 2L, 3L).map { match(ResultReason.COMPLETED, date = it) }
        val dates = mapOf(1L to LocalDay(2020, 12, 31), 2L to LocalDay(2021, 1, 3), 3L to LocalDay(2021, 1, 4))
        val weeks = groupHistory(matches, HistoryGrouping.WEEK) { dates.getValue(it) }
        assertEquals(listOf(LocalDay(2021, 1, 4), LocalDay(2020, 12, 28)), weeks.map { it.start })
        assertEquals(listOf(1, 2), weeks.map { it.matches.size })
        assertEquals(listOf(2, 1), groupHistory(matches, HistoryGrouping.MONTH) { dates.getValue(it) }.map { it.matches.size })
        val leap = groupHistory(matches.take(1), HistoryGrouping.WEEK) { LocalDay(2024, 3, 1) }
        assertEquals(LocalDay(2024, 2, 26), leap.single().start)
    }

    @Test
    fun recentResultsAreNewestFiveAndStoppedTiesAreDraws() {
        val matches = (1L..8L).map { match(ResultReason.STOPPED, null, it) }
        assertEquals(
            listOf(8L, 7L, 6L, 5L, 4L),
            headToHead(matches, setup.lineup.b.first(), HistoryRole.OPPONENT, selfIds).recent.map {
                it.finishedAtMs
            },
        )
        assertEquals(HistoryOutcome.DRAW, matches.first().outcome())
        assertEquals(HistoryOutcome.ABANDONED, match(ResultReason.ABANDONED, Side.A).outcome())
    }
}
