package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GuestDeletionUiTest {
    @Test
    fun archivedPersonDisappearsFromHistoryChoicesAndHeadToHeadLinksButNameStaysInTheResult() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val model = testModel().apply { startWith(Presets.BADMINTON_1GAME_15.format(), "A".repeat(15)) }
            val guest =
                model.players.guests.value
                    .single()
            val match =
                model.history.matches.value
                    .single()
                    .matchId
            model.navigator.tab = AppTab.History
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) { AdvantlyApp(model = model, darkTheme = true) }
            }
            onNodeWithTag("person_${guest.id.value}").assertExists()
            runOnIdle { assertTrue(model.players.deleteGuest(guest.id)) }
            onNodeWithTag("person_${guest.id.value}").assertDoesNotExist()
            runOnIdle { model.openResult(match) }
            onNodeWithTag("h2h_person_${guest.id.value}").assertDoesNotExist()
            onNodeWithText(guest.displayName).assertExists()
            assertTrue(
                model.history
                    .record(match)!!
                    .summary.setup.players
                    .any { it.displayName == guest.displayName },
            )
        }

    @Test
    fun deletingRequiresConfirmationAndCancellationKeepsTheGuestInBothLanguagesAtDoubleFontSize() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ENGLISH, Locale.GERMAN)) {
                Locale.setDefault(locale)
                runDesktopComposeUiTest(width = 412, height = 892) {
                    val store = InMemoryMatchStore(selfName = "Alex")
                    val guest = store.addGuest("Sam mit langem Gastnamen")
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                            AdvantlyTheme(darkTheme = true) {
                                Column(Modifier.verticalScroll(rememberScrollState())) { GuestList(store) }
                            }
                        }
                    }
                    onNodeWithTag("guest_actions_${guest.id.value}").performScrollTo().performClick()
                    onNodeWithTag("delete_${guest.id.value}").performClick()
                    val message =
                        if (locale.language ==
                            "de"
                        ) {
                            "Aus Auswahl entfernen – vergangene Matches behalten den Namen."
                        } else {
                            "Remove from selection — past matches keep the name."
                        }
                    onNodeWithText(message).assertIsDisplayed()
                    assertTrue(store.guests.value.any { it.id == guest.id })
                    val screenshot = onNodeWithTag("delete_dialog").captureToImage().toAwtImage()
                    val directory = File(System.getenv("ADVANTLY_SCREENSHOT_DIR") ?: "build/reports/screenshots").apply { mkdirs() }
                    ImageIO.write(screenshot, "png", File(directory, "delete-${locale.language}-200.png"))
                    onNodeWithTag("delete_cancel").performClick()
                    assertTrue(store.guests.value.any { it.id == guest.id })
                    onNodeWithTag("guest_actions_${guest.id.value}").performScrollTo().performClick()
                    onNodeWithTag("delete_${guest.id.value}").performClick()
                    onNodeWithTag("delete_confirm").performClick()
                    onNodeWithTag("guest_row_${guest.id.value}").assertDoesNotExist()
                    assertTrue(guest.id in store.archivedPlayerIds.value)
                    assertFalse(store.deleteGuest(store.self.value.id))
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
