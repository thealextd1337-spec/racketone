package eu.advantly.ui.phone.history

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.play
import eu.advantly.ui.phone.setupFor
import eu.advantly.ui.phone.testModel
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertNotEquals

/** Bilanz mit Freunden: dieselbe Person unter zwei Gast-IDs, verknüpft mit einem Konto, ergibt eine Bilanz. */
@OptIn(ExperimentalTestApi::class)
class FriendRecordUiTest {
    @Test
    fun matchDetailOpensTheJoinedFriendRecordInGerman() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            runDesktopComposeUiTest(width = 720, height = 1600) {
                val model = testModel()
                val format = Presets.TENNIS_ONE_SET.format()

                fun played(): eu.advantly.core.model.MatchId {
                    val base = model.setupFor(format)
                    val opponent = base.lineup.b.single()
                    val id = model.live.start(base.copy(linkedProfiles = mapOf(opponent to "anna")))
                    model.live.play("AAAA")
                    model.live.endMatch(EndReason.STOPPED)
                    return id
                }
                val first = played()
                val second = played()
                model.openResult(second)
                setContent { AdvantlyApp(model = model, darkTheme = true) }
                waitForIdle()

                fun opponentOf(id: eu.advantly.core.model.MatchId) =
                    model.history.matches.value
                        .first { it.matchId == id }
                        .setup.lineup.b
                        .single()
                val opponent = opponentOf(second)
                assertNotEquals(opponentOf(first), opponent, "zwei verschiedene Gast-IDs")
                onNodeWithTag("h2h_person_${opponent.value}").performScrollTo().performClick()
                waitForIdle()
                onNodeWithTag("h2h").assertExists()
                // Beide Matches, obwohl die Gegnerin dort unter verschiedenen Gast-IDs steht.
                onNodeWithTag("h2h_match_${first.value}").performScrollTo().assertExists()
                onNodeWithTag("h2h_match_${second.value}").performScrollTo().assertExists()
                onNodeWithTag("h2h_record_PARTNER").assertTextEquals("0 Siege · 0 Niederlagen · 0 Unentschieden")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
