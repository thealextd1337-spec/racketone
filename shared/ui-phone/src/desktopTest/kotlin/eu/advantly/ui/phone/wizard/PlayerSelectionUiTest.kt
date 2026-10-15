package eu.advantly.ui.phone.wizard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.PlayerPlace
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class PlayerSelectionUiTest {
    private fun ComposeUiTest.show(wizard: MatchWizard) {
        setContent { AdvantlyTheme(darkTheme = true) { WizardScreen(wizard, {}, {}) } }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun emptyPlaceCreatesAtThatPlaceAndTapActionsMoveSwapAndRemove() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val store = InMemoryMatchStore(selfName = "Alex")
            val wizard = MatchWizard(store).apply { selectSport(Sport.PADEL) }
            show(wizard)
            click("slot_A_1")
            click("player_create")
            onNodeWithTag("picker_name").performTextInput("Sam")
            click("picker_add")
            val sam = wizard.state.value.input.teamA[1]
            assertEquals("Sam", sam?.displayName)
            assertNull(wizard.state.value.input.teamB[0])
            click("slot_A_1")
            click("player_move")
            click("destination_B_1")
            assertEquals(sam, wizard.state.value.input.teamB[1])
            assertNull(wizard.state.value.input.teamA[1])
            wizard.addGuestAt("Robin", PlayerPlace(Side.A, 1))
            waitForIdle()
            click("slot_B_1")
            click("player_move")
            click("destination_A_1")
            assertEquals(sam, wizard.state.value.input.teamA[1])
            assertEquals(
                "Robin",
                wizard.state.value.input.teamB[1]
                    ?.displayName,
            )
            click("slot_A_1")
            click("player_remove")
            assertNull(wizard.state.value.input.teamA[1])
            click("slot_B_0")
            click("suggestion_${sam!!.id.value}")
            assertEquals(sam, wizard.state.value.input.teamB[0])
        }

    private fun ComposeUiTest.drag(
        from: String,
        to: String,
        cancelDrag: Boolean = false,
    ) {
        onNodeWithTag(from).performScrollTo()
        val source = onNodeWithTag(from).fetchSemanticsNode().boundsInRoot
        val target = onNodeWithTag(to).fetchSemanticsNode().boundsInRoot
        onNodeWithTag(from).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(Offset(target.center.x - source.left, target.center.y - source.top), delayMillis = 500)
            if (cancelDrag) cancel() else up()
        }
        waitForIdle()
    }

    @Test
    fun longPressDragMovesAndSwapsAcrossTeamsAndCancellationKeepsLineup() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val wizard =
                MatchWizard(InMemoryMatchStore(selfName = "Alex")).apply {
                    selectSport(Sport.PADEL)
                    addGuestAt("Sam", PlayerPlace(Side.A, 1))
                    addGuestAt("Robin", PlayerPlace(Side.B, 0))
                }
            show(wizard)
            val before = wizard.state.value.input
            drag("slot_A_1", "slot_B_1", cancelDrag = true)
            assertEquals(before, wizard.state.value.input)
            drag("slot_A_1", "slot_B_1")
            assertNull(wizard.state.value.input.teamA[1])
            assertEquals(
                "Sam",
                wizard.state.value.input.teamB[1]
                    ?.displayName,
            )
            drag("slot_B_1", "slot_B_0")
            assertEquals(
                "Sam",
                wizard.state.value.input.teamB[0]
                    ?.displayName,
            )
            assertEquals(
                "Robin",
                wizard.state.value.input.teamB[1]
                    ?.displayName,
            )
        }

    @Test
    fun selectionSheetScreenshotsInGermanAndEnglish() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.ENGLISH)) {
                Locale.setDefault(locale)
                runDesktopComposeUiTest(width = 412, height = 892) {
                    val wizard =
                        MatchWizard(InMemoryMatchStore(selfName = "Alex")).apply {
                            selectSport(Sport.PADEL)
                            addGuestAt("Sam", PlayerPlace(Side.A, 1))
                            clearSlot(Side.A, 1)
                        }
                    show(wizard)
                    click("slot_B_1")
                    onNodeWithTag("player_create").assertExists()
                    val directory = File("build/reports/screenshots").apply { mkdirs() }
                    ImageIO.write(
                        onNodeWithTag("player_sheet").captureToImage().toAwtImage(),
                        "png",
                        File(directory, "wizard-player-selection-${locale.language}.png"),
                    )
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
