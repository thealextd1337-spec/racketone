package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.sync.protocol.LiveRelation
import eu.advantly.sync.protocol.LiveView
import eu.advantly.sync.protocol.LiveViewSubscription
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.sync.ReceivedLiveViews
import eu.advantly.wear.workout.WorkoutPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Zuschauen auf der Uhr (plan-live-mitspieler PR 4): Startlisten-Eintrag, Liste, Detail ohne Zählflächen, Abo-Takt. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
class SpectateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val tennis =
        LiveView(
            MatchId("m1"),
            LiveRelation.PLAYER,
            Sport.TENNIS,
            "Maximilian / Konstantin",
            "Bartholomäus / Jonas",
            sets = listOf(listOf(6, 4), listOf(3, 6)),
            games = listOf(5, 4),
            points = listOf("40", "AD"),
            serving = Side.B,
            scorerConnected = true,
            ageMs = 3_000,
        )
    private val badminton =
        LiveView(
            MatchId("m2"),
            LiveRelation.VISITOR,
            Sport.BADMINTON,
            "Lena",
            "Sophie",
            sets = listOf(listOf(21, 17)),
            points = listOf("19", "21"),
            serving = Side.A,
        )
    private val padel =
        LiveView(MatchId("m3"), LiveRelation.SCORER, Sport.PADEL, "Tom", "Ben", games = listOf(1, 0), points = listOf("15", "0"))
    private val now = 100_000L

    private fun views(vararg matches: LiveView) = ReceivedLiveViews(matches.toList(), receivedAtMs = now - 4_000)

    private fun show(
        received: ReceivedLiveViews?,
        onClose: () -> Unit = {},
    ) {
        RuntimeEnvironment.setFontScale(1.3f)
        rule.setContent {
            WearTestFrame {
                SpectateScreen(
                    received,
                    isAmbient = false,
                    subscribe = {},
                    onClose = onClose,
                    nowMs = { now },
                )
            }
        }
    }

    @Test fun germanList() = list("de")

    @Test
    @Config(qualifiers = "en-w206dp-h206dp-round-watch-340dpi")
    fun englishList() = list("en")

    private fun list(language: String) {
        show(views(tennis, badminton, padel))
        rule.onNodeWithText(if (language == "de") "Zuschauen" else "Watching").assertIsDisplayed()
        rule.screenshot("spectate-list-$language-206dp-font130")
        // Lazy-Liste: nur sichtbare Einträge sind komponiert; der letzte ist per Krone/Scrollen erreichbar
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Tom – Ben"))
        rule.screenshot("spectate-list-end-$language-206dp-font130")
    }

    @Test fun germanDetail() = detail("de")

    @Test
    @Config(qualifiers = "en-w206dp-h206dp-round-watch-340dpi")
    fun englishDetail() = detail("en")

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-320dpi")
    fun smallWatchDetail() = detail("de", size = "192dp")

    private fun detail(
        language: String,
        size: String = "206dp",
    ) {
        show(views(tennis, badminton))
        rule.onAllNodesWithTag(SPECTATE_ITEM_TAG)[0].performClick()
        rule.onNodeWithTag(SPECTATE_BADGE_TAG).assertIsDisplayed()
        val score = rule.onNodeWithTag(SPECTATE_SCORE_TAG).assertIsDisplayed()
        assertEquals(
            "40 : AD",
            score
                .textLayout()
                .layoutInput.text.text,
        )
        assertFalse("großer Spielstand ungekürzt", score.textLayout().hasVisualOverflow)
        // Nur Anzeige: nichts zum Tippen (keine Zählflächen, kein Übernehmen/Beenden)
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
        rule.screenshot("spectate-detail-tennis-$language-$size-font130")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SPECTATE_SCORE_TAG))
        val serving = if (language == "de") "Aufschlag" else "serving"
        rule
            .onNode(
                androidx.compose.ui.test
                    .hasStateDescription(serving),
            ).assertExists()
    }

    @Test
    fun rallyDetailShowsPointsAndSets() {
        show(views(badminton))
        rule.onAllNodesWithTag(SPECTATE_ITEM_TAG)[0].performClick()
        assertEquals(
            "19 : 21",
            rule
                .onNodeWithTag(SPECTATE_SCORE_TAG)
                .textLayout()
                .layoutInput.text.text,
        )
        rule.onNodeWithText("21:17").assertExists()
        rule.screenshot("spectate-detail-badminton-de-206dp-font130")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Zähler offline"))
        rule.screenshot("spectate-detail-badminton-scrolled-de-206dp-font130")
    }

    @Test
    fun emptyAndWaitingStates() {
        var received by mutableStateOf<ReceivedLiveViews?>(null)
        RuntimeEnvironment.setFontScale(1.3f)
        rule.setContent { WearTestFrame { SpectateScreen(received, false, {}, {}, nowMs = { now }) } }
        rule.onNodeWithText("Frage Handy …").assertIsDisplayed()
        rule.screenshot("spectate-waiting-de-206dp-font130")
        received = views()
        rule.onNodeWithText("Gerade kein Match live").assertIsDisplayed()
        rule.screenshot("spectate-empty-de-206dp-font130")
    }

    @Test
    fun backGoesFromDetailToListAndThenCloses() {
        var closed = 0
        show(views(tennis)) { closed++ }
        rule.onAllNodesWithTag(SPECTATE_ITEM_TAG)[0].performClick()
        rule.onNodeWithTag(SPECTATE_BADGE_TAG).assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onAllNodesWithTag(SPECTATE_ITEM_TAG).assertCountEquals(1)
        assertEquals(0, closed)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun homeOffersLiveAndOpensTheSpectateView() {
        RuntimeEnvironment.setFontScale(1.3f)
        val home = WatchScreen.Home(null, null, emptyList(), workoutEnabled = false)
        var opened = 0
        rule.setContent {
            WearTestFrame {
                HomeScreen(
                    home,
                    WorkoutPermissions.ALL,
                    false,
                    recording<HomeActions>(),
                    onSpectate = { opened++ },
                )
            }
        }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SPECTATE_ENTRY_TAG))
        rule.onNodeWithTag(SPECTATE_ENTRY_TAG).assertIsDisplayed()
        rule.screenshot("home-live-entry-de-206dp-font130")
        rule.onNodeWithTag(SPECTATE_ENTRY_TAG).performClick()
        assertEquals(1, opened)
    }

    /** Abo nur sichtbar (RESUMED), nicht im Ambient, endet beim Verlassen; sofort beim Aktivwerden, dann alle 10 s. */
    @Test
    fun subscriptionFollowsVisibilityAmbientAndLeaving() {
        val owner =
            object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
                override val lifecycle: Lifecycle get() = registry
            }
        var ambient by mutableStateOf(false)
        var shown by mutableStateOf(true)
        var calls = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (shown) LiveViewSubscriptionEffect(ambient) { calls++ }
            }
        }

        fun advance(ms: Long) {
            rule.mainClock.advanceTimeBy(ms)
            rule.waitForIdle()
        }

        // Zustandswechsel: Recomposition im nächsten Frame, vor dem nächsten Abo-Takt
        fun change(block: () -> Unit) {
            rule.runOnUiThread(block)
            rule.waitForIdle()
            advance(16)
        }
        advance(16)
        assertEquals(1, calls)
        advance(LiveViewSubscription.INTERVAL_MS - 16)
        assertEquals(2, calls)

        change { ambient = true }
        advance(3 * LiveViewSubscription.INTERVAL_MS)
        assertEquals("kein Abo im Ambient", 2, calls)
        change { ambient = false }
        assertEquals(3, calls)

        change { owner.registry.currentState = Lifecycle.State.STARTED }
        advance(3 * LiveViewSubscription.INTERVAL_MS)
        assertEquals("kein Abo, wenn nicht sichtbar", 3, calls)
        change { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertEquals(4, calls)

        change { shown = false }
        advance(3 * LiveViewSubscription.INTERVAL_MS)
        assertEquals("Verlassen beendet das Abo", 4, calls)
    }
}
