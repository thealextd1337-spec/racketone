package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import eu.advantly.match.session.DeviceLight
import eu.advantly.presentation.match.UpdateRequired
import eu.advantly.wear.R
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.workout.WorkoutPermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class PhoneStatusUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun germanPhoneStatusAtLargeFont() = check("de")

    @Test
    @Config(qualifiers = "en-w206dp-h206dp-round-watch-340dpi")
    fun englishPhoneStatusAtLargeFont() = check("en")

    @Test fun greenPhoneStatusAtLargeFont() = check("de", DeviceLight.GREEN)

    @Test fun redPhoneStatusAtLargeFont() = check("de", DeviceLight.RED)

    @Test fun grayPhoneStatusAtLargeFont() = check("de", DeviceLight.GRAY)

    @Test fun olderPhoneShowsUpdateHint() = check("de", DeviceLight.RED, UpdateRequired.OTHER_DEVICE)

    @Test fun newerPhoneShowsUpdateHint() = check("de", DeviceLight.RED, UpdateRequired.THIS_DEVICE)

    @Test fun dirtyBuildShowsDevelopmentHint() = check("de", DeviceLight.YELLOW, developmentBuild = true)

    private fun check(
        language: String,
        light: DeviceLight = DeviceLight.YELLOW,
        update: UpdateRequired? = null,
        developmentBuild: Boolean = false,
    ) {
        RuntimeEnvironment.setFontScale(1.3f)
        val home =
            WatchScreen.Home(
                null,
                null,
                emptyList(),
                workoutEnabled = false,
                phoneLight = light,
                updateRequired = update,
                developmentBuild = developmentBuild,
            )
        rule.setContent { WearTestFrame { HomeScreen(home, WorkoutPermissions.ALL, false, recording<HomeActions>()) } }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(PHONE_STATUS_TAG))
        // ScrollToNode bringt den Eintrag zunächst an den Rand. Wie mit Krone/Rotary in die Kreismitte scrollen;
        // ein zweiter Schritt gleicht die geänderte Skalierung der ScalingLazyColumn aus.
        repeat(2) {
            val center =
                rule
                    .onRoot()
                    .fetchSemanticsNode()
                    .boundsInRoot.center.y
            val offset =
                rule
                    .onNodeWithTag(PHONE_STATUS_TAG)
                    .fetchSemanticsNode()
                    .boundsInRoot.center.y - center
            rule.onNode(hasScrollToNodeAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, offset) }
            rule.waitForIdle()
        }
        val shot = "home-phone-status-${light.name.lowercase()}-$language-${update ?: "none"}-$developmentBuild-206dp-font130"
        rule.screenshot(shot)
        val node = rule.onNodeWithTag(PHONE_STATUS_TAG).assertIsDisplayed()
        val layout = node.textLayout()
        val badge =
            when (light) {
                DeviceLight.GREEN -> "✓"
                DeviceLight.GRAY -> "–"
                DeviceLight.RED -> "×"
                else -> "!"
            }
        assertTrue(
            layout.layoutInput.text.text
                .startsWith("$badge "),
        )
        if (update != null) {
            val resource =
                if (update ==
                    UpdateRequired.THIS_DEVICE
                ) {
                    R.string.update_this_device
                } else {
                    R.string.update_other_device
                }
            assertTrue(
                layout.layoutInput.text.text
                    .contains(rule.activity.getString(resource)),
            )
        } else if (developmentBuild) {
            assertTrue(
                layout.layoutInput.text.text
                    .contains("Entwicklungsbuild"),
            )
        } else if (light == DeviceLight.YELLOW) {
            assertTrue(
                layout.layoutInput.text.text
                    .contains(if (language == "de") "Update empfohlen" else "update recommended"),
            )
        }
        assertFalse("Kein gekürzter Text", layout.hasVisualOverflow)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val radius = root.width / 2f
        for (x in listOf(bounds.left, bounds.right)) {
            for (y in listOf(bounds.top, bounds.bottom)) {
                assertTrue("Handy-Status vollständig im Kreis: $bounds", hypot(x - root.center.x, y - root.center.y) <= radius + 1f)
            }
        }
        rule.screenshot(shot)
    }
}
