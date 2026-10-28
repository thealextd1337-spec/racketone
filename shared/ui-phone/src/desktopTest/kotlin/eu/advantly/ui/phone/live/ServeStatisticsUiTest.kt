package eu.advantly.ui.phone.live

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.ServeTracking
import eu.advantly.core.scoring.PointDetailSet
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.stats.MatchStatistics
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.result_stat_serve_points
import eu.advantly.ui.phone.resources.stats_double_faults
import eu.advantly.ui.phone.resources.stats_first_serve_pct
import eu.advantly.ui.phone.resources.stats_first_serve_won
import eu.advantly.ui.phone.resources.stats_second_serve_won
import eu.advantly.ui.phone.resources.stats_service_games
import eu.advantly.ui.phone.result.ServeStatistics
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ServeStatisticsUiTest {
    @Test
    fun unrecordedServeDataUsesDashesForTeamsAndServers() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.PADEL_GOLDEN_BO3.format(), "AAAAB") }
            val active = model.live.active.value!!
            // wie Daten von App 0.2.0 (Modus „Ja“, aber ohne automatische 1. Aufschläge): keine Aufschlag-Info
            val record = model.history.record(active.matchId)!!
            val stats = MatchStatistics.compute(record.copy(events = record.events.filter { it.payload !is PointDetailSet }))
            setContent {
                AdvantlyTheme(darkTheme = true) {
                    Column { ServeStatistics(active.setup, stats) }
                }
            }
            onNodeWithTag("serve_team_A").assertExists()
            onNodeWithTag("serve_team_B").assertExists()
            assertEquals(2, stats.servers.size)
            stats.servers.forEach { onNodeWithTag("serve_player_${it.player.value}").assertExists() }
            assertTrue(onAllNodes(hasText("—"), useUnmergedTree = true).fetchSemanticsNodes().size >= 16)
            assertEquals(null, stats.serve.a.firstServeIn.percent)
        }

    /** „Nur Doppelfehler“: keine 1./2.-Aufschlag-Quoten (unbekannt), nur Doppelfehler; „Nein“: keine Aufschlagwerte. */
    @Test
    fun doubleFaultsOnlyShowsOnlyDoubleFaults() = checkServeRows(ServeTracking.DOUBLE_FAULTS_ONLY, quotas = false, doubleFaults = true)

    @Test
    fun noServeTrackingShowsNoServeValues() = checkServeRows(ServeTracking.OFF, quotas = false, doubleFaults = false)

    @Test
    fun fullServeTrackingShowsQuotasAndDoubleFaults() = checkServeRows(ServeTracking.FULL, quotas = true, doubleFaults = true)

    private fun checkServeRows(
        tracking: ServeTracking,
        quotas: Boolean,
        doubleFaults: Boolean,
    ) = runComposeUiTest {
        val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "AAB", meta = MatchMeta(serveTracking = tracking)) }
        val active = model.live.active.value!!
        val stats = MatchStatistics.compute(model.history.record(active.matchId)!!)
        setContent {
            AdvantlyTheme(darkTheme = true) {
                Column { ServeStatistics(active.setup, stats) }
            }
        }
        val rows = listOf(Res.string.stats_first_serve_pct, Res.string.stats_first_serve_won, Res.string.stats_second_serve_won)
        rows.forEach { onAllNodes(hasText(getString(it)), useUnmergedTree = true).assertCountEquals(if (quotas) 2 else 0) }
        onAllNodes(hasText(getString(Res.string.stats_double_faults)), useUnmergedTree = true).assertCountEquals(if (doubleFaults) 2 else 0)
        onAllNodes(hasText(getString(Res.string.stats_service_games)), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun singlesServeGroupDoesNotRepeatServicePointsComparison() =
        runComposeUiTest {
            val model = testModel().apply { startWith(Presets.TENNIS_SINGLES_BO3.format(), "AB") }
            val active = model.live.active.value!!
            val stats = MatchStatistics.compute(model.history.record(active.matchId)!!)
            setContent {
                AdvantlyTheme(darkTheme = true) {
                    Column { ServeStatistics(active.setup, stats) }
                }
            }
            onNodeWithTag("serve_team_A").assertExists()
            onNodeWithTag("serve_team_B").assertExists()
            onAllNodes(hasText(getString(Res.string.result_stat_serve_points)), useUnmergedTree = true).assertCountEquals(0)
            stats.servers.forEach { onNodeWithTag("serve_player_${it.player.value}").assertDoesNotExist() }
        }
}
