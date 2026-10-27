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
import eu.advantly.wear.BuildConfig
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.workout.WorkoutPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.util.Locale
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class BuildVersionUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun germanHomeAtLargeFont() = check("de")

    @Test
    @Config(qualifiers = "en-w206dp-h206dp-round-watch-340dpi")
    fun englishHomeAtLargeFont() = check("en")

    @Test
    fun datesUseLocalZoneAndReleaseHasNoDate() {
        val zone = ZoneId.of("Europe/Paris")
        assertEquals("16.10. 16:54", localBuildDate("2026-10-16T14:54:00Z", Locale.GERMANY, zone))
        assertEquals("Oct 17, 01:54", localBuildDate("2026-10-16T23:54:00Z", Locale.US, zone))
        assertEquals("", localBuildDate(""))
        assertEquals("", localBuildDate("unknown"))
    }

    private fun check(language: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        val home = WatchScreen.Home(null, null, emptyList(), workoutEnabled = false)
        rule.setContent { WearTestFrame { HomeScreen(home, WorkoutPermissions.ALL, false, recording<HomeActions>()) } }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(BUILD_VERSION_TAG))
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
                    .onNodeWithTag(BUILD_VERSION_TAG)
                    .fetchSemanticsNode()
                    .boundsInRoot.center.y - center
            rule.onNode(hasScrollToNodeAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, offset) }
            rule.waitForIdle()
        }
        rule.screenshot("home-version-$language-206dp-font130")
        val node = rule.onNodeWithTag(BUILD_VERSION_TAG).assertIsDisplayed()
        val layout = node.textLayout()
        assertTrue(
            layout.layoutInput.text.text
                .startsWith("v${BuildConfig.VERSION_NAME} · "),
        )
        assertFalse("Kein gekürzter Text", layout.hasVisualOverflow)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val radius = root.width / 2f
        for (x in listOf(bounds.left, bounds.right)) {
            for (y in listOf(bounds.top, bounds.bottom)) {
                assertTrue("Version vollständig im Kreis: $bounds", hypot(x - root.center.x, y - root.center.y) <= radius + 1f)
            }
        }
        rule.screenshot("home-version-$language-206dp-font130")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(PRIVACY_TAG))
        rule.onNodeWithTag(PRIVACY_TAG).assertIsDisplayed()
    }
}
