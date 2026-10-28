package eu.advantly.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import eu.advantly.core.model.Side
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.presentation.live.SetColumn
import eu.advantly.wear.R
import eu.advantly.wear.live.CourtLetter
import eu.advantly.wear.live.LiveRole
import eu.advantly.wear.live.LiveUi
import eu.advantly.wear.live.SyncUi
import eu.advantly.wear.live.WatchTelemetryPolicy
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.scoring.TapZones
import eu.advantly.wear.workout.WorkoutUiState
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
import kotlin.math.hypot

/**
 * Feldtest 2026-10-16: Puls und Schritte verdeckten unten die Zeile „Gegner“. Jetzt stehen sie seitlich auf Höhe der
 * Mitte (links Puls, rechts Schritte) und Statuszeile/Hinweise haben eigenen Platz in ihrer Hälfte. Geprüft wird über die
 * echten Layout-Grenzen: Telemetrie, Statuszeile, Team-/Namenszeilen, Aufschlag-Indikator, Ziffern, Satzanzeige,
 * Hinweis-Pill und Anzeige-Hinweise überlappen sich auf keiner Displaygröße – Einzel/Doppel, lange Namen, große Schrift,
 * Ambient – und bleiben im runden Display.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScoreLayoutUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun pixelWatch41mmSinglesWithLongNameAndHint() {
        check(singles(), "score-singles-de-192dp")
    }

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun pixelWatch41mmDoublesWithServeArrow() {
        check(liveUi(doubles = true).copy(hint = ScoreHint(ScoreHint.Kind.BreakPoint)), "score-doubles-de-192dp")
    }

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun pixelWatch41mmLargestSystemFont() {
        RuntimeEnvironment.setFontScale(1.24f)
        check(singles(), "score-singles-de-192dp-font124")
    }

    @Test
    @Config(qualifiers = "de-w228dp-h228dp-round-watch-xhdpi")
    fun pixelWatch45mm() {
        check(singles(), "score-singles-de-228dp")
    }

    @Test
    @Config(qualifiers = "de-w228dp-h228dp-round-watch-xhdpi")
    fun pixelWatch45mmDoubles() {
        check(liveUi(doubles = true), "score-doubles-de-228dp")
    }

    @Test
    @Config(qualifiers = "de-w180dp-h180dp-round-watch-hdpi")
    fun smallRoundWatch() {
        check(singles(), "score-singles-de-180dp")
    }

    @Test
    @Config(qualifiers = "de-w180dp-h180dp-round-watch-hdpi")
    fun smallRoundWatchDoubles() {
        check(liveUi(doubles = true), "score-doubles-de-180dp")
    }

    // Galaxy Watch8 40 mm (SM-L320, Zielgerät): 438 px bei 340 dpi ≈ 206 dp – Pflichtgröße, Schrift 1,0 und 1,3

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun galaxyWatch8Singles() {
        check(singles(), "score-singles-de-galaxy8")
    }

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun galaxyWatch8SinglesLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        check(singles(), "score-singles-de-galaxy8-font130")
    }

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun galaxyWatch8DoublesLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        check(liveUi(doubles = true).copy(hint = ScoreHint(ScoreHint.Kind.BreakPoint)), "score-doubles-de-galaxy8-font130")
    }

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun galaxyWatch8DisplayRole() {
        val display = singles().copy(role = LiveRole.DISPLAY, sync = SyncUi.Stale(45), takeOverOffered = true)
        check(display, "score-display-de-galaxy8", bottomLine = true)
    }

    // Samsung Galaxy Watch (rund, xhdpi): 396 px (40 mm), 432 px (Watch 6/7 40 mm), 480 px (44 mm/Ultra)

    @Test
    @Config(qualifiers = "de-w198dp-h198dp-round-watch-xhdpi")
    fun galaxyWatch396px() {
        check(singles(), "score-singles-de-galaxy-396px")
    }

    @Test
    @Config(qualifiers = "de-w216dp-h216dp-round-watch-xhdpi")
    fun galaxyWatch432pxDoubles() {
        check(liveUi(doubles = true).copy(hint = ScoreHint(ScoreHint.Kind.BreakPoint)), "score-doubles-de-galaxy-432px")
    }

    @Test
    @Config(qualifiers = "de-w240dp-h240dp-round-watch-xhdpi")
    fun galaxyWatch480px() {
        check(singles(), "score-singles-de-galaxy-480px")
    }

    @Test
    @Config(qualifiers = "en-w192dp-h192dp-round-watch-xhdpi")
    fun ambientAndEnglish() {
        check(singles(), "score-ambient-en-192dp", isAmbient = true)
    }

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun displayRoleWithTakeOverOfferAtTheBottom() {
        val display = singles().copy(role = LiveRole.DISPLAY, sync = SyncUi.Stale(45), takeOverOffered = true)
        check(display, "score-display-de-192dp", bottomLine = true)
    }

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun withoutWorkoutNoTelemetry() {
        render(singles(), WorkoutUiState())
        assertTrue(rule.onAllNodesWithTag(LiveTags.HEART_RATE, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertNoOverlaps(bottomLine = false, telemetry = false)
    }

    /**
     * Telemetrie „Gar nicht“ (P2-J) bei laufendem Workout auf der Pflichtgröße (Galaxy Watch8, 206 dp, Schrift 1,3): keine
     * Puls-/Schritte-Spalten, das Mittelband und die Namen bleiben sauber und überlappungsfrei.
     */
    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun telemetryOffHidesHeartRateAndStepsOnGalaxyWatch8() {
        RuntimeEnvironment.setFontScale(1.3f)
        val off = WatchTelemetryPolicy.apply(activeWorkout(), TelemetryVisibility.OFF)
        render(singles(), off)
        for (tag in listOf(LiveTags.HEART_RATE, LiveTags.STEPS)) {
            assertTrue("$tag ausgeblendet", rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        assertNoOverlaps(bottomLine = false, telemetry = false)
        rule.screenshot("score-telemetry-off-de-galaxy8-font130")
    }

    /** Toast im Mittelband (zwischen Puls und Schritten) bleibt im Kreis und überdeckt die Telemetrie nicht. */
    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun toastStaysInsideOnGalaxyWatch8() {
        RuntimeEnvironment.setFontScale(1.3f)
        render(singles(), activeWorkout(), toast = WatchToast.Undone("30:15", "15:15"))
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val toast = bounds(LiveTags.TOAST)
        assertInsideCircle("Toast", toast, root)
        for (tag in listOf(LiveTags.HEART_RATE, LiveTags.STEPS)) assertFalse("Toast überlappt $tag", toast.overlaps(bounds(tag)))
        rule.screenshot("score-toast-de-galaxy8-font130")
    }

    /** Solange die Aufschlag-Leiste steht, pausiert die Telemetrie am Rand (die Leiste reicht fast bis zum Rand). */
    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun telemetryGivesWayToTheServeBar() {
        render(singles().copy(serveBar = serveBar()), activeWorkout())
        assertTrue(rule.onAllNodesWithTag(LiveTags.HEART_RATE, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag(LiveTags.STEPS, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    // 0.3.0: Die Aufschlag-Leiste verdeckt den Spielstand nicht – Sätze/Games stehen links in der Leiste, Punkte frei

    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun scoreStaysVisibleWithTheServeBarOnPixelWatch41mm() = checkScoreVisibleWithServeBar()

    @Test
    @Config(qualifiers = "de-w180dp-h180dp-round-watch-hdpi")
    fun scoreStaysVisibleWithTheServeBarOnSmallRoundWatch() = checkScoreVisibleWithServeBar()

    @Test
    @Config(qualifiers = "de-w206dp-h206dp-round-watch-340dpi")
    fun scoreStaysVisibleWithTheServeBarOnGalaxyWatch8LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        checkScoreVisibleWithServeBar()
    }

    private fun checkScoreVisibleWithServeBar() {
        render(singles().copy(serveBar = serveBar()), activeWorkout())
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val sets = rule.onNodeWithTag(LiveTags.SETS, useUnmergedTree = true)
        assertEquals("6:4 6:7", sets.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text })
        assertFalse("Satzstand abgeschnitten", sets.textLayout().hasVisualOverflow)
        val setsBox = bounds(LiveTags.SETS)
        assertInsideCircle("Satzstand", setsBox, root)
        val app = RuntimeEnvironment.getApplication()
        val segments =
            listOf(R.string.serve_second_in, R.string.serve_double_fault).map {
                rule.onNodeWithText(app.getString(it)).fetchSemanticsNode().boundsInRoot
            }
        segments.forEach { assertFalse("Satzstand unter einem Segment ($it)", setsBox.overlaps(it)) }
        for (side in Side.entries) {
            val digits = bounds(LiveTags.points(side))
            segments.forEach { assertFalse("Punkte $side verdeckt ($digits / $it)", digits.overlaps(it)) }
            assertFalse("Punkte $side unter dem Satzstand", digits.overlaps(setsBox))
        }
        rule.screenshot("score-serve-bar-${root.width.toInt()}px")
    }

    /** Telemetrie ist nicht klickbar: ein Tap darauf zählt nicht (Mittelband), die Hälften zählen wie bisher. */
    @Test
    @Config(qualifiers = "de-w192dp-h192dp-round-watch-xhdpi")
    fun telemetryDoesNotDisturbTheTapZones() {
        val recorder = render(singles(), activeWorkout())
        for (tag in listOf(LiveTags.HEART_RATE, LiveTags.STEPS)) {
            val r = bounds(tag)
            rule.onRoot().performTouchInput { click(Offset(r.center.x, r.center.y)) }
        }
        assertFalse(recorder.calls.toString(), recorder.calls.any { it.startsWith("point") })
        val b = bounds(LiveTags.points(Side.B))
        rule.onRoot().performTouchInput { click(Offset(b.center.x, b.center.y)) }
        assertEquals(listOf("point(B)"), recorder.calls.filter { it.startsWith("point") })
    }

    private fun singles(): LiveUi =
        liveUi().copy(
            hint = ScoreHint(ScoreHint.Kind.MatchTiebreak),
            sets =
                listOf(
                    SetColumn(SidePair(6, 4), tiebreakLoserPoints = null, winner = Side.A, isCurrent = false),
                    SetColumn(SidePair(6, 7), tiebreakLoserPoints = 5, winner = Side.B, isCurrent = false),
                ),
            serve = liveUi().serve!!.copy(court = CourtLetter.R),
        )

    private fun render(
        live: LiveUi,
        workout: WorkoutUiState,
        isAmbient: Boolean = false,
        toast: WatchToast? = null,
    ): RecordingActions {
        val recorder = RecordingActions()
        rule.setContent {
            WearTestFrame { LiveScreen(live, workout, heartRate = null, isAmbient = isAmbient, toast = toast, actions = recorder.actions) }
        }
        rule.waitForIdle()
        return recorder
    }

    private fun check(
        live: LiveUi,
        screenshot: String,
        isAmbient: Boolean = false,
        bottomLine: Boolean = false,
    ) {
        render(live, activeWorkout(), isAmbient)
        assertNoOverlaps(bottomLine = bottomLine, telemetry = true, doubles = live.doubles)
        rule.screenshot(screenshot)
    }

    private fun assertNoOverlaps(
        bottomLine: Boolean,
        telemetry: Boolean,
        doubles: Boolean = false,
    ) {
        val tags =
            buildList {
                add(LiveTags.label(Side.A))
                add(LiveTags.label(Side.B))
                add(LiveTags.points(Side.A))
                add(LiveTags.points(Side.B))
                add(LiveTags.SETS)
                if (exists(LiveTags.HINT)) add(LiveTags.HINT)
                if (doubles) add(LiveTags.SERVE_ARROW)
                if (telemetry) addAll(listOf(LiveTags.HEART_RATE, LiveTags.STEPS))
            }
        val rects = tags.associateWith { bounds(it) }.toMutableMap()
        // Aufschlag-Indikator steht in der Namenszeile „Wir“ (eigenes Element, aber Teil der Zeile)
        val marker = bounds(LiveTags.SERVE_MARKER)
        assertTrue("Aufschlag-Indikator in seiner Zeile", rects.getValue(LiveTags.label(Side.A)).contains(marker.center))
        if (bottomLine) rects["bottom"] = bottomLineBounds()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        // Uhrzeit mit Status steht gebogen oben in der Totzone: „Wir“ beginnt darunter
        assertTrue("Uhrzeit/Status frei", rects.getValue(LiveTags.label(Side.A)).top >= root.height * TapZones.TOP_DEAD_ZONE - 0.5f)
        val entries = rects.entries.toList()
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                val (a, ra) = entries[i]
                val (b, rb) = entries[j]
                assertFalse("$a $ra überlappt $b $rb", ra.overlaps(rb))
            }
        }
        // Telemetrie, Namen und Satzanzeige liegen im runden Display (nichts am Rand abgeschnitten)
        // Safe-Inset rund: jedes Element vollständig im Kreis (alle Ecken ≤ Radius) – auch Ziffern, Aufschlag-Indikator,
        // Doppel-Pfeil und der Anzeige-Hinweis unten (Feldbefund Galaxy Watch8: „unten abgeschnitten“)
        rects[LiveTags.SERVE_MARKER] = marker
        for ((tag, r) in rects) assertInsideCircle(tag, r, root)
        // Kein Text wird in seinem Knoten abgeschnitten (Bounds allein reichen nicht: geclippter Text läge „innen“).
        // Telemetrie, Satzstände und Hinweis müssen vollständig sichtbar sein (ohne „…“); Namen dürfen mit „…“ enden.
        val complete =
            listOfNotNull(LiveTags.SETS, LiveTags.HINT.takeIf(::exists)) +
                (if (telemetry) TelemetryKind.entries.map(LiveTags::telemetryValue) else emptyList())
        for (tag in complete) {
            val layout = rule.onNodeWithTag(tag, useUnmergedTree = true).textLayout()
            val text = layout.layoutInput.text.text
            assertFalse("$tag „$text“ abgeschnitten", layout.hasVisualOverflow)
            assertFalse("$tag „$text“ gekürzt", layout.isLineEllipsized(0))
            assertEquals("$tag „$text“ vollständig", text.length, layout.getLineEnd(0, visibleEnd = true))
        }
        if (telemetry) {
            val heart = rects.getValue(LiveTags.HEART_RATE)
            val steps = rects.getValue(LiveTags.STEPS)
            assertTrue("Puls links", heart.right < root.center.x)
            assertTrue("Schritte rechts", steps.left > root.center.x)
            assertEquals("auf Höhe der Mitte", root.center.y, heart.center.y, root.height * 0.03f)
            assertEquals("auf Höhe der Mitte", root.center.y, steps.center.y, root.height * 0.03f)
        }
    }

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(tag: String): Rect {
        val density =
            RuntimeEnvironment
                .getApplication()
                .resources.displayMetrics.density
        val r = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return Rect(r.left.value * density, r.top.value * density, r.right.value * density, r.bottom.value * density)
    }

    /** Schaltfläche „Zählen übernehmen“ unten. */
    private fun bottomLineBounds(): Rect {
        val text = RuntimeEnvironment.getApplication().getString(eu.advantly.wear.R.string.take_over_offer)
        val density =
            RuntimeEnvironment
                .getApplication()
                .resources.displayMetrics.density
        val r = rule.onNode(hasText(text) and hasClickAction()).getUnclippedBoundsInRoot()
        return Rect(r.left.value * density, r.top.value * density, r.right.value * density, r.bottom.value * density)
    }

    private fun assertInsideCircle(
        what: String,
        r: Rect,
        root: Rect,
    ) {
        val radius = root.width / 2
        for (corner in listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)) {
            val distance = hypot(corner.x - root.center.x, corner.y - root.center.y)
            assertTrue("$what: Ecke $corner außerhalb des Kreises", distance <= radius + 1f)
        }
    }
}
