package eu.advantly.ui.phone.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.presentation.match.InMemoryTelemetrySettings
import eu.advantly.presentation.match.PulseSetting
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.WatchStatus
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.result.PulseVisibilitySection
import eu.advantly.ui.phone.testModel
import eu.advantly.ui.phone.wizard.ScorerChooser
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan Puls H5: Einwilligung im Profil, Stufe im Assistenten und nachträglich im Match-Detail (de). */
@OptIn(ExperimentalTestApi::class)
class PulseSettingsUiTest {
    private val match = MatchId("m")

    private fun <T> german(block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun consentIsOffByDefaultRevokingAsksAndTelemetryOffLocksIt() =
        german {
            runDesktopComposeUiTest(width = 412, height = 892) {
                val model = testModel()
                model.navigator.tab = AppTab.Profile
                setContent { AdvantlyApp(model = model, darkTheme = false) }
                onNodeWithTag("health_record_toggle").performScrollTo().assertTextContains("Pulsverlauf aufzeichnen", substring = true)
                runOnIdle { assertFalse(model.telemetry.healthConsent.value) }
                onNodeWithTag("health_record_toggle").performClick()
                runOnIdle { assertTrue(model.telemetry.healthConsent.value) }
                onNodeWithTag("health_record_toggle").performClick()
                onNodeWithTag("health_confirm").assertTextContains("Beenden und löschen").performClick()
                runOnIdle { assertFalse(model.telemetry.healthConsent.value) }
                onNodeWithTag("telemetry_off").performScrollTo().performClick()
                onNodeWithTag("health_record_toggle").performScrollTo().assertIsNotEnabled()
            }
        }

    @Test
    fun matchDetailGreysOutLevelsWithoutRecordingAndDeletesAfterConfirmation() =
        german {
            runDesktopComposeUiTest(width = 412, height = 892) {
                val port = InMemoryTelemetrySettings(consent = true)
                port.setPulse(match, PulseSetting(TelemetryVisibility.PRIVATE, recorded = true))
                setContent { AdvantlyTheme { PulseVisibilitySection(port, match) } }
                onNodeWithTag("pulse_level_private").assertIsSelected()
                onNodeWithTag("pulse_level_share").assertIsEnabled().performClick()
                runOnIdle { assertEquals(TelemetryVisibility.SHARE, port.pulseSetting(match).visibility) }
                onNodeWithTag("pulse_level_off").performClick()
                onNodeWithTag("pulse_delete_confirm").assertTextContains("Pulsverlauf löschen").performClick()
                runOnIdle { assertEquals(PulseSetting(TelemetryVisibility.OFF, recorded = false), port.pulseSetting(match)) }
                // nichts aufgezeichnet, Stufe gewählt: nur „Gar nicht“ wählbar
                port.setPulse(match, PulseSetting(TelemetryVisibility.PRIVATE, recorded = false))
                onNodeWithTag("pulse_hint").assertTextContains("Kein Pulsverlauf aufgezeichnet")
                onNodeWithTag("pulse_level_share").assertIsNotEnabled()
                onNodeWithTag("pulse_level_off").assertIsEnabled()
            }
        }

    @Test
    fun wizardPrefillsFromTelemetryOnlyWithConsent() =
        german {
            runDesktopComposeUiTest(width = 412, height = 892) {
                var consent by mutableStateOf(false)
                var chosen: TelemetryVisibility? = null
                setContent {
                    CompositionLocalProvider(LocalHealthConsent provides consent) {
                        AdvantlyTheme {
                            ScorerChooser(
                                ScorerOption.THIS_PHONE,
                                WatchStatus.AVAILABLE,
                                watchWorkout = true,
                                onSelect = {},
                                onWatchWorkout = {},
                                pulseVisibility = null,
                                onPulseVisibility = { chosen = it },
                            )
                        }
                    }
                }
                onNodeWithTag("pulse_unavailable").assertTextContains("Pulsverlauf aufzeichnen", substring = true)
                consent = true
                onNodeWithTag("pulse_1").assertIsSelected().assertTextContains("Nur für mich")
                onNodeWithTag("pulse_2").performClick()
                runOnIdle { assertEquals(TelemetryVisibility.SHARE, chosen) }
            }
        }
}
