package eu.advantly.wear.glance

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class GlanceTextTest {
    private val en = ResourceStrings("values", Locale.ENGLISH)
    private val de = ResourceStrings("values-de", Locale.GERMAN)
    private val players = listOf(GlancePlayer(null, true, Side.A, 0), GlancePlayer("Bea", false, Side.B, 0))
    private val last = GlanceSetup(Presets.TENNIS_SINGLES_BO3.format(), players)
    private val live = GlanceMatch("1:0", "3:2", "30:15", players[1], false, MatchStatus.IN_PROGRESS)

    @Test
    fun firstInstallOffersNewMatchAndShortAppLauncher() {
        val copy = GlanceText.render(GlanceState(), en)
        assertEquals("New match", copy.title)
        assertEquals(GlanceAction.NEW, copy.primary)
        assertEquals("Open", copy.shortText)
        assertEquals("Open app", copy.longText)
        val german = GlanceText.render(GlanceState(), de)
        assertEquals("Öffnen", german.shortText)
        assertEquals("App öffnen", german.longText)
        assertTrue(copy.lines.isEmpty())
    }

    @Test
    fun repeatShowsSportFormatAndActualLineupInBothLanguages() {
        val english = GlanceText.render(GlanceState(last = last), en)
        val german = GlanceText.render(GlanceState(last = last), de)
        assertEquals("Same as last time", english.title)
        assertEquals("Wie letztes Mal", german.title)
        assertEquals(listOf("Tennis · Singles", "Best of 3 · 6 games/set", "You – Bea"), english.lines)
        assertEquals(listOf("Tennis · Einzel", "Best of 3 · 6 Games/Satz", "Du – Bea"), german.lines)
        assertEquals(GlanceAction.REPEAT, german.primary)
    }

    @Test
    fun liveHasLocalizedRoleScoreAndConcreteServer() {
        val copy = GlanceText.render(GlanceState(live, last), de)
        assertEquals("Anzeige", copy.title)
        assertEquals(listOf("Sätze 1:0", "Games 3:2", "Punkte 30:15", "Aufschlag: Bea"), copy.lines)
        assertEquals("30:15", copy.shortText)
        assertEquals("30:15", copy.score)
        assertEquals("Sätze 1:0 · Games 3:2 · Punkte 30:15", copy.longText)
        assertTrue(copy.description.contains("Anzeige"))
        assertTrue(copy.description.contains("Aufschlag: Bea"))
        assertTrue(copy.description.endsWith("Live-Spielstand öffnen"))
        assertEquals(GlanceAction.OPEN, copy.primary)
    }

    @Test
    fun smallRoundTileKeepsSetsAndGamesOnSeparateLinesInBothLanguages() {
        listOf(en, de).forEach { strings ->
            val copy = GlanceText.render(GlanceState(live = live), strings)
            listOf(1f, 1.3f).forEach { scale ->
                val metrics = TileLayoutMetrics(192, 192, true, scale)
                val visible = copy.tileLines.take(metrics.liveLines)
                assertEquals(3, visible.size)
                assertEquals(strings.get(GlanceLabel.SETS, "1:0"), visible[0])
                assertEquals(strings.get(GlanceLabel.GAMES, "3:2"), visible[1])
                assertEquals(strings.get(GlanceLabel.SERVE, "Bea"), visible[2])
                assertTrue(copy.description.contains(strings.get(GlanceLabel.POINTS, "30:15")))
            }
        }
    }

    @Test
    fun tileStatusIsExplicitAndTakesPriorityOverScoreDetails() {
        listOf(en, de).forEach { strings ->
            listOf(MatchStatus.PAUSED, MatchStatus.AWAITING_DECISION).forEach { status ->
                val copy = GlanceText.render(GlanceState(live = live.copy(status = status)), strings)
                val label = if (status == MatchStatus.PAUSED) GlanceLabel.PAUSED else GlanceLabel.DECISION
                assertEquals(strings.get(label), copy.status)
                val visible = (listOfNotNull(copy.status) + copy.tileLines).distinct().take(3)
                assertEquals(copy.status, visible.first())
                assertEquals(strings.get(GlanceLabel.SETS, "1:0"), visible[1])
                assertEquals(strings.get(GlanceLabel.GAMES, "3:2"), visible[2])
            }
        }
    }

    @Test
    fun selfAndNeutralDoublesPlayersAreLocalized() {
        val setup =
            GlanceSetup(
                Presets.PADEL_GOLDEN_BO3.format(),
                listOf(
                    players[0],
                    GlancePlayer(null, false, Side.A, 1),
                    GlancePlayer(null, false, Side.B, 0),
                    GlancePlayer(null, false, Side.B, 1),
                ),
            )
        val copy = GlanceText.render(GlanceState(last = setup), de)
        assertEquals("Padel · Doppel", copy.lines.first())
        assertEquals("Du/Partner – Gegner 1/Gegner 2", copy.lines.last())
    }

    @Test
    fun openAndRallyFormatsShowTheirRealTargets() {
        val open = GlanceText.render(GlanceState(last = last.copy(format = Presets.TENNIS_OPEN.format())), de)
        assertTrue(open.lines[1].contains(de.get(GlanceLabel.OPEN_FORMAT)))
        val rally = GlanceText.render(GlanceState(last = last.copy(format = Presets.BADMINTON_SINGLES_BO3_21.format())), en)
        assertTrue(rally.lines[1].contains("21"))
        assertFalse(rally.lines[1].contains("games/set"))
    }

    @Test
    fun pausedDecisionAndScorerAreVisibleWithoutInventedPoints() {
        val paused = GlanceText.render(GlanceState(live = live.copy(status = MatchStatus.PAUSED, scorer = true)), en)
        assertEquals(en.get(GlanceLabel.SCORER), paused.title)
        assertTrue(paused.lines.contains("Paused"))
        assertEquals("Paused", paused.shortTitle)
        assertTrue(paused.longText.startsWith("Paused · "))
        val germanPaused = GlanceText.render(GlanceState(live = live.copy(status = MatchStatus.PAUSED)), de)
        assertEquals("Pause", germanPaused.shortTitle)
        assertTrue(germanPaused.longText.startsWith("Pausiert · "))
        val decision =
            GlanceText.render(
                GlanceState(
                    live =
                        live.copy(
                            games = null,
                            points = null,
                            server = null,
                            status = MatchStatus.AWAITING_DECISION,
                        ),
                ),
                de,
            )
        assertEquals(listOf("Sätze 1:0", "Satz beendet"), decision.lines)
        assertEquals("1:0", decision.shortText)
        assertEquals("Satz", decision.shortTitle)
        assertTrue(decision.longText.startsWith("Satz beendet · "))
    }

    @Test
    fun shortTextFallsBackAtSevenCharactersAndAccessibilityKeepsFullNames() {
        val name = "Ein sehr langer vollständiger Spielername"
        val copy = GlanceText.render(GlanceState(live = live.copy(points = "1000:1000", server = players[1].copy(name = name))), de)
        assertEquals("3:2", copy.shortText)
        assertTrue(copy.description.contains(name))
    }

    @Test
    fun glanceResourcesHaveIdenticalKeysAndFormatArgumentsInBothLanguages() {
        val english = resources("values", "glance_strings.xml")
        val german = resources("values-de", "glance_strings.xml")
        assertEquals(english.keys, german.keys)
        val placeholders = Regex("%[0-9]+\\$[sd]")
        english.forEach { (key, value) ->
            assertEquals(
                key,
                placeholders.findAll(value).map { it.value }.toList(),
                placeholders.findAll(german.getValue(key)).map { it.value }.toList(),
            )
        }
        GlanceLabel.entries.forEach { label ->
            val args =
                if (label in listOf(GlanceLabel.BEST_OF, GlanceLabel.GAMES_TO, GlanceLabel.POINTS_TO)) {
                    arrayOf<Any>(3)
                } else if (label in listOf(GlanceLabel.SETS, GlanceLabel.GAMES, GlanceLabel.POINTS, GlanceLabel.SERVE)) {
                    arrayOf<Any>("1:0")
                } else {
                    emptyArray()
                }
            assertTrue(en.get(label, *args).isNotBlank())
            assertTrue(de.get(label, *args).isNotBlank())
        }
    }

    private class ResourceStrings(
        directory: String,
        private val locale: Locale,
    ) : GlanceStrings {
        private val values =
            resources("values", "strings.xml") + resources(directory, "strings.xml") +
                resources(directory, "glance_strings.xml")

        override fun get(
            label: GlanceLabel,
            vararg args: Any,
        ): String {
            val key =
                when (label) {
                    GlanceLabel.APP -> {
                        "app_name"
                    }

                    GlanceLabel.REPEAT -> {
                        "home_repeat_last"
                    }

                    GlanceLabel.NEW -> {
                        "home_new_match"
                    }

                    GlanceLabel.SCORER -> {
                        "role_scorer_short"
                    }

                    GlanceLabel.BEST_OF -> {
                        "format_best_of"
                    }

                    GlanceLabel.OPEN_FORMAT -> {
                        "format_open_sets"
                    }

                    GlanceLabel.SINGLES, GlanceLabel.DOUBLES -> {
                        "discipline_${label.name.lowercase()}"
                    }

                    GlanceLabel.TENNIS, GlanceLabel.PADEL, GlanceLabel.PICKLEBALL,
                    GlanceLabel.BADMINTON, GlanceLabel.TABLE_TENNIS,
                    -> {
                        "sport_${label.name.lowercase()}"
                    }

                    GlanceLabel.YOU, GlanceLabel.PARTNER, GlanceLabel.OPPONENT_1, GlanceLabel.OPPONENT_2 -> {
                        "player_${label.name.lowercase()}"
                    }

                    else -> {
                        "glance_${label.name.lowercase()}"
                    }
                }
            return String.format(locale, values.getValue(key), *args)
        }
    }

    companion object {
        private fun resources(
            directory: String,
            filename: String,
        ): Map<String, String> {
            val path = File("src/main/res/$directory/$filename")
            val file = if (path.exists()) path else File("wearApp", path.path)
            val nodes =
                DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(file)
                    .getElementsByTagName("string")
            return (0 until nodes.length).associate { i ->
                val node = nodes.item(i)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }
    }
}
