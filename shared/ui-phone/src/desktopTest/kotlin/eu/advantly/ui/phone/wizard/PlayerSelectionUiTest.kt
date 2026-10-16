package eu.advantly.ui.phone.wizard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.PlayerPlace
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlayerSelectionUiTest {
    private fun ComposeUiTest.show(wizard: MatchWizard) {
        setContent { AdvantlyTheme(darkTheme = true) { WizardScreen(wizard, {}, {}) } }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun padelDoublesHasNoSetupServerPicker() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val wizard =
                MatchWizard(InMemoryMatchStore(selfName = "Alex")).apply {
                    selectSport(Sport.PADEL)
                    addGuest("Sam")
                    addGuest("Robin")
                    addGuest("Kim")
                }
            show(wizard)
            assertTrue(wizard.state.value.playersComplete)
            onNodeWithTag("coin_toss").assertDoesNotExist()
            wizard.state.value.input.players
                .forEach { player -> onNodeWithTag("server_${player.id.value}").assertDoesNotExist() }
        }

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
    fun switchingFromSinglesToDoublesEnablesSelfDragAndBackDisablesIt() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val store = InMemoryMatchStore(selfName = "Alex")
            val wizard =
                MatchWizard(store).apply {
                    selectSport(Sport.TENNIS)
                    selectSurface(Surface.CLAY)
                }
            show(wizard)
            assertFalse(onNodeWithTag("slot_A_0").fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
            click("discipline_1") // DOUBLES
            drag("slot_A_0", "slot_A_1")
            assertEquals(store.self.value, wizard.state.value.input.teamA[1])
            assertNull(wizard.state.value.input.teamA[0])
            click("discipline_0") // SINGLES
            assertEquals(
                store.self.value,
                wizard.state.value.input.teamA
                    .single(),
            )
            assertFalse(onNodeWithTag("slot_A_0").fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        }

    @Test
    fun singlesOpponentHasRemovalButNoDeadEndMoveLongClickHintOrDragGhost() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val wizard =
                MatchWizard(InMemoryMatchStore(selfName = "Alex")).apply {
                    selectSport(Sport.TENNIS)
                    selectSurface(Surface.CLAY)
                    addGuestAt("Sam", PlayerPlace(Side.B, 0))
                }
            show(wizard)
            onNodeWithText("Hold a player to drag. Tap to swap or remove.").assertDoesNotExist()
            onNodeWithText("Spieler zum Ziehen gedrückt halten. Antippen zum Tauschen oder Entfernen.").assertDoesNotExist()
            val before = wizard.state.value.input
            click("slot_B_0")
            onNodeWithTag("player_move").assertDoesNotExist()
            onNodeWithTag("player_remove").assertIsDisplayed()
            click("player_remove")
            assertNull(wizard.state.value.input.teamB[0])
            runOnIdle { wizard.assignPlayer(PlayerPlace(Side.B, 0), before.teamB.single()!!) }
            val source = onNodeWithTag("slot_B_0").performScrollTo()
            assertFalse(source.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
            val bounds = source.fetchSemanticsNode().boundsInRoot
            source.performTouchInput {
                down(center)
                advanceEventTime(700)
                moveTo(center + Offset(60f, 0f), delayMillis = 500)
            }
            waitForIdle()
            assertEquals(bounds, source.fetchSemanticsNode().boundsInRoot, "Ohne Ziele wird kein Drag-Ghost angezeigt")
            source.performTouchInput { up() }
            assertEquals(before, wizard.state.value.input)
        }

    @Test
    fun droppingOpponentOnSelfKeepsLineup() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val wizard =
                MatchWizard(InMemoryMatchStore(selfName = "Alex")).apply {
                    selectSport(Sport.PADEL)
                    addGuestAt("Sam", PlayerPlace(Side.B, 0))
                }
            show(wizard)
            val before = wizard.state.value.input
            drag("slot_B_0", "slot_A_0")
            assertEquals(before, wizard.state.value.input)
        }

    @Test
    fun selectionSheetScreenshotsInGermanAndEnglish() {
        val previous = Locale.getDefault()
        val screenshots = mutableListOf<BufferedImage>()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.ENGLISH)) {
                Locale.setDefault(locale)
                runDesktopComposeUiTest(width = 412, height = 892) {
                    val store = InMemoryMatchStore(selfName = "Alex").apply { addGuest("Sam") }
                    val wizard = MatchWizard(store).apply { selectSport(Sport.PADEL) }
                    show(wizard)
                    click("slot_B_1")
                    onNodeWithTag("player_create").assertIsDisplayed()
                    onNodeWithText(if (locale.language == "de") "SPIELER AUSWÄHLEN" else "CHOOSE PLAYER").assertIsDisplayed()
                    onNodeWithTag("suggestion_${wizard.playerSuggestions().single().id.value}").assertIsDisplayed()
                    val screenshot = onNodeWithTag("player_sheet").captureToImage().toAwtImage()
                    assertTrue(screenshot.width >= 350 && screenshot.height >= 150, "Das Sheet muss in Handygröße gerendert sein")
                    val pixels = screenshot.getRGB(0, 0, screenshot.width, screenshot.height, null, 0, screenshot.width)
                    assertTrue(pixels.toSet().size > 100, "Der Screenshot muss Text und Steuerelemente enthalten")
                    screenshots += screenshot
                    val directory = File("build/reports/screenshots").apply { mkdirs() }
                    ImageIO.write(
                        screenshot,
                        "png",
                        File(directory, "wizard-player-selection-${locale.language}.png"),
                    )
                }
            }
            val (german, english) = screenshots
            val width = minOf(german.width, english.width)
            val height = minOf(german.height, english.height)
            val changedPixels = (0 until height).sumOf { y -> (0 until width).count { x -> german.getRGB(x, y) != english.getRGB(x, y) } }
            assertTrue(changedPixels > 100, "Die Übersetzung muss auch im gerenderten Screenshot sichtbar sein")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
