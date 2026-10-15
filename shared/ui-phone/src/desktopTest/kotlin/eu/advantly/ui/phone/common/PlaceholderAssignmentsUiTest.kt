package eu.advantly.ui.phone.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.SessionRig
import eu.advantly.ui.phone.app.Overlay
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlaceholderAssignmentsUiTest {
    @Test
    fun placeholderLabelsFollowOwnerAliasOnSideB() {
        runDesktopComposeUiTest {
            val owner =
                PlayerId("canonical-self")
            val alias =
                PlayerId("old-watch-self")
            val partner = Placeholders.id("partner-b")
            val opponents = listOf(Placeholders.id("a1"), Placeholders.id("a2"))
            val setup =
                MatchSetup(
                    Presets.TENNIS_BO3_MTB10.format().copy(discipline = Discipline.DOUBLES),
                    Lineup(opponents, listOf(alias, partner), alias),
                    players =
                        (opponents + partner).map {
                            Player(it, "", isPlaceholder = true)
                        },
                )
            setContent {
                CompositionLocalProvider(LocalSelfId provides owner, LocalSelfAliases provides setOf(alias)) {
                    Column {
                        Text(playerName(setup, partner))
                        Text(playerName(setup, opponents.first()))
                    }
                }
            }
            // Ressourcen werden aus der aktiven Locale gelesen; beide Seiten müssen verschieden beschriftet sein.
            val german = Locale.getDefault().language == "de"
            onNodeWithText("Partner").assertIsDisplayed()
            onNodeWithText(if (german) "Gegner 1" else "Opponent 1").assertIsDisplayed()
        }
    }

    @Test
    fun localizedPlaceholderCanBeAssignedDuringWatchScoringAndAfterFinish() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.US)) {
                Locale.setDefault(locale)
                runDesktopComposeUiTest(width = 824, height = 1784) {
                    val rig = SessionRig()
                    val slot = Placeholders.id("opponent")
                    val me = rig.store.self.value.id
                    rig.watch.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(slot), me))
                    rig.settle()
                    rig.model.navigator.push(Overlay.Live)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(2f)) {
                            Box(Modifier.size(412.dp, 892.dp)) { AdvantlyApp(model = rig.model, darkTheme = true) }
                        }
                    }
                    onNodeWithTag("assign_${slot.value}").performScrollTo().performClick()
                    onNodeWithText(if (locale.language == "de") "Gegner zuweisen" else "Assign Opponent").assertIsDisplayed()
                    onNodeWithTag("assign_name").performTextInput("Bea")
                    val out = File("build/reports/screenshots").apply { mkdirs() }
                    val roots = onAllNodes(isRoot())
                    ImageIO.write(
                        roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().toAwtImage(),
                        "png",
                        File(out, "assign-${locale.language}.png"),
                    )
                    onNodeWithTag("assign_create").performClick()
                    runOnIdle {
                        assertEquals(
                            "Bea",
                            rig.store.active.value!!
                                .setup
                                .player(slot)
                                ?.displayName,
                        )
                        assertFalse(
                            rig.store.active.value!!
                                .isScorer,
                        )
                        assertEquals(
                            SidePair(0, 0),
                            rig.store.active.value!!
                                .state.current!!
                                .points,
                        )
                        rig.settle()
                    }
                    onNodeWithTag("assign_${slot.value}").performScrollTo().performClick()
                    onNodeWithText(if (locale.language == "de") "Bea ändern" else "Change Bea").assertIsDisplayed()
                    onNodeWithTag("assign_remove").performClick()
                    runOnIdle {
                        assertTrue(
                            rig.store.active.value!!
                                .setup
                                .player(slot)!!
                                .isPlaceholder,
                        )
                    }
                    onNodeWithTag("assign_${slot.value}").performScrollTo().performClick()
                    val bea =
                        rig.store.guests.value
                            .single()
                    onNodeWithTag("assign_person_${bea.id.value}").performClick()
                    // Ein zweites Match unbesetzt beenden und aus dem Ergebnis einem bestehenden Gast zuweisen.
                    runOnIdle {
                        rig.watch.endMatch(EndReason.ABANDONED)
                        rig.settle()
                        rig.watch.closeMatch()
                        val next = rig.watch.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(slot), me))
                        repeat(15) { rig.watch.recordPoint(Side.A) }
                        rig.settle()
                        rig.model.openResult(next)
                    }
                    onNodeWithTag("assign_${slot.value}").performScrollTo().performClick()
                    val person =
                        rig.store.guests.value
                            .single()
                    onNodeWithTag("assign_person_${person.id.value}").performClick()
                    runOnIdle {
                        assertEquals(
                            "Bea",
                            rig.store.matches.value
                                .first()
                                .setup
                                .player(slot)
                                ?.displayName,
                        )
                    }
                    // Die Platzhalterbeschriftung ist unabhängig von gespeicherten Namen.
                    runOnIdle { rig.model.navigator.pop() }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
