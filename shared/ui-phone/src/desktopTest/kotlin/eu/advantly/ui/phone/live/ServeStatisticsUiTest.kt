package eu.advantly.ui.phone.live

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.stats.MatchStatistics
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.result.ServeStatistics
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
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
            val stats = MatchStatistics.compute(model.history.record(active.matchId)!!)
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
}
