package eu.advantly.ui.phone.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import eu.advantly.core.scoring.Presets
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ScoreTextsTest {
    @Test
    fun historyMonthUsesLocalizedMonthNames() {
        val previous = Locale.getDefault()
        try {
            for ((locale, expected) in listOf(Locale.GERMANY to "Oktober 2026", Locale.US to "October 2026")) {
                Locale.setDefault(locale)
                assertEquals(
                    expected,
                    eu.advantly.ui.phone.platform
                        .formatMonth(2026, 10),
                )
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun regularSetFormatHasAnIntactLocalizedTiebreakSeparator() {
        val previous = Locale.getDefault()
        try {
            for ((locale, expected) in listOf(Locale.GERMANY to "Tiebreak bei 6:6 (bis 7)", Locale.US to "Tiebreak at 6–6 (to 7)")) {
                Locale.setDefault(locale)
                runComposeUiTest {
                    setContent {
                        AdvantlyTheme(darkTheme = true) {
                            Text(formatDescription(Presets.TENNIS_SINGLES_BO3.format()), Modifier.testTag("set_format"))
                        }
                    }
                    onNodeWithTag("set_format").assertTextContains(expected, substring = true)
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun customTiebreakDescriptionOnlyDescribesThePlayedFormat() {
        val previous = Locale.getDefault()
        val descriptions =
            listOf(
                Locale.GERMANY to "Best of 1 · Match-Tiebreak bis 10 · 2 Punkte Vorsprung",
                Locale.US to "Best of 1 · Match tiebreak to 10 · Win by 2",
            )
        try {
            for ((locale, expected) in descriptions) {
                Locale.setDefault(locale)
                runComposeUiTest {
                    setContent {
                        AdvantlyTheme(darkTheme = true) {
                            Text(
                                formatDescription(Presets.TENNIS_TIEBREAK_ONLY_10.format().copy(presetId = null)),
                                Modifier.testTag("custom_tb"),
                            )
                        }
                    }
                    onNodeWithTag("custom_tb").assertTextEquals(expected)
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun regularSetTiebreakKeepsGamesAndTiebreakPointsInBothLanguages() {
        val previous = Locale.getDefault()
        try {
            for ((locale, expected) in listOf(Locale.GERMANY to "7:6 (7:5)", Locale.US to "7–6 (7–5)")) {
                Locale.setDefault(locale)
                runComposeUiTest {
                    val model =
                        testModel().apply {
                            startWith(Presets.TENNIS_ONE_SET.format(), "AAAABBBB".repeat(6) + "AB".repeat(5) + "AA")
                        }
                    val state =
                        model.live.active.value!!
                            .state
                    setContent {
                        AdvantlyTheme(darkTheme = true) {
                            Text(resultLine(state.sets, state.current, state.result, state.setsWon), Modifier.testTag("regular_tb"))
                        }
                    }
                    onNodeWithTag("regular_tb").assertTextEquals(expected)
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun completedTiebreakHasBracketsInResultAndState() {
        val previous = Locale.getDefault()
        try {
            for ((locale, expected) in listOf(Locale.GERMANY to "[7:5]", Locale.US to "[7–5]")) {
                Locale.setDefault(locale)
                runComposeUiTest {
                    val model = testModel().apply { startWith(Presets.TENNIS_TIEBREAK_ONLY_7.format(), "AB".repeat(5) + "AA") }
                    val state =
                        model.live.active.value!!
                            .state
                    setContent {
                        AdvantlyTheme(darkTheme = true) {
                            Column {
                                Text(resultLine(state.sets, state.current, state.result, state.setsWon), Modifier.testTag("result_line"))
                                Text(
                                    stateScore(
                                        state,
                                        model.live.active.value!!
                                            .setup.sport,
                                    ),
                                    Modifier.testTag("state_score"),
                                )
                            }
                        }
                    }
                    onNodeWithTag("result_line").assertTextEquals(expected)
                    onNodeWithTag("state_score").assertTextEquals(expected)
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun tiebreakOnlyFormatsHaveLocalizedLabels() {
        val previous = Locale.getDefault()
        val presets =
            listOf(
                Presets.TENNIS_TIEBREAK_ONLY_7,
                Presets.TENNIS_TIEBREAK_ONLY_10,
                Presets.PADEL_TIEBREAK_ONLY_7,
                Presets.PADEL_TIEBREAK_ONLY_10,
            )
        val german =
            listOf("Nur Tiebreak (bis 7)", "Nur Champions-Tiebreak (bis 10)", "Nur Tiebreak (bis 7)", "Nur Super-Tiebreak (bis 10)")
        val english =
            listOf("Tiebreak only (to 7)", "Champions tiebreak only (to 10)", "Tiebreak only (to 7)", "Super tiebreak only (to 10)")
        try {
            for ((locale, labels) in listOf(Locale.GERMANY to german, Locale.US to english)) {
                Locale.setDefault(locale)
                runComposeUiTest {
                    setContent {
                        AdvantlyTheme(darkTheme = true) {
                            Column {
                                presets.forEachIndexed {
                                    index,
                                    preset,
                                    ->
                                    Text(formatLabel(preset.format()), Modifier.testTag("format_$index"))
                                }
                            }
                        }
                    }
                    labels.forEachIndexed { index, label -> onNodeWithTag("format_$index").assertTextEquals(label) }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
