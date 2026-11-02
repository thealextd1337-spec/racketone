package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.match.session.HealthImportResult
import eu.advantly.presentation.match.InMemoryTelemetrySettings
import eu.advantly.presentation.match.TelemetrySettingsPort
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.platform.DocumentFiles
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan 0.5.1 §7b: „Pulsverläufe exportieren/importieren“ unter „Puls & Schritte“ (Hinweis, Einwilligung, Meldungen, DE/EN). */
@OptIn(ExperimentalTestApi::class)
class HealthArchiveUiTest {
    private val clock = WallClock { 1_793_577_600_000 } // 02.11.2026

    /** Merkt sich Aufträge; [opened] liefert die gewählte Datei (`null` = abgebrochen). */
    private class FakeFiles(
        val opened: ByteArray? = null,
        val saved: Boolean? = true,
    ) : DocumentFiles {
        val saves = mutableListOf<Pair<String, String>>()
        var opens = 0

        override fun save(
            fileName: String,
            content: String,
            onDone: (Boolean?) -> Unit,
        ) {
            saves += fileName to content
            onDone(saved)
        }

        override fun open(
            maxBytes: Int,
            onDone: (ByteArray?) -> Unit,
        ) {
            opens++
            onDone(opened)
        }
    }

    private class FilePort(
        private val inner: InMemoryTelemetrySettings,
        private val file: String?,
        private val result: HealthImportResult,
    ) : TelemetrySettingsPort by inner {
        val imported = mutableListOf<ByteArray>()

        override fun exportHealth(): String? = file

        override fun importHealth(bytes: ByteArray): HealthImportResult = result.also { imported += bytes }
    }

    private fun <T> withLocale(
        locale: Locale,
        block: () -> T,
    ): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun exportWarnsAboutHealthDataAndSavesUnderTheDatedName() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest(width = 412, height = 892) {
                val port = FilePort(InMemoryTelemetrySettings(consent = true), "{\"format\":\"advantly-health\"}", HealthImportResult())
                val files = FakeFiles()
                setContent { AdvantlyTheme { Column { HealthArchiveSection(port, locked = false, files, clock) } } }
                onNodeWithTag("health_export").assertTextContains("Pulsverläufe exportieren").performClick()
                onNodeWithText("Die Datei enthält Gesundheitsdaten (Puls, Schritte)", substring = true).assertExists()
                runOnIdle { assertTrue(files.saves.isEmpty(), "erst nach dem Hinweis") }
                onNodeWithTag("health_archive_confirm").assertTextContains("Weiter").performClick()
                runOnIdle { assertEquals(listOf("advantly-puls-2026-11-02.json" to "{\"format\":\"advantly-health\"}"), files.saves) }
                onNodeWithTag("health_archive_message").assertTextContains("Datei gespeichert.")
            }
        }

    @Test
    fun exportWithoutSeriesSaysSoAndOpensNoPicker() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest {
                val files = FakeFiles()
                setContent { AdvantlyTheme { Column { HealthArchiveSection(InMemoryTelemetrySettings(), locked = false, files, clock) } } }
                onNodeWithTag("health_export").performClick()
                onNodeWithTag("health_archive_confirm").performClick()
                onNodeWithTag("health_archive_message").assertTextContains("noch keine Pulsverläufe", substring = true)
                runOnIdle { assertTrue(files.saves.isEmpty()) }
            }
        }

    @Test
    fun importWithoutConsentAsksForItFirstAndReportsTheCounts() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest(width = 412, height = 892) {
                val inner = InMemoryTelemetrySettings(consent = false)
                val port = FilePort(inner, null, HealthImportResult(imported = 12, unknownMatch = 2))
                val files = FakeFiles(opened = byteArrayOf(1, 2))
                setContent { AdvantlyTheme { Column { HealthArchiveSection(port, locked = false, files, clock) } } }
                onNodeWithTag("health_import").assertTextContains("Pulsverläufe importieren").performClick()
                onNodeWithText("Pulsverlauf aufzeichnen?").assertExists()
                runOnIdle { assertEquals(0, files.opens, "Dateiauswahl erst nach der Einwilligung") }
                onNodeWithTag("health_archive_confirm").assertTextContains("Einwilligen").performClick()
                runOnIdle {
                    assertTrue(inner.healthConsent.value)
                    assertEquals(1, files.opens)
                    assertEquals(listOf(listOf<Byte>(1, 2)), port.imported.map { it.toList() })
                }
                onNodeWithTag("health_archive_message")
                    .assertTextContains("12 übernommen, 2 ohne passendes Match, 0 schon vorhanden oder abgewählt.")
            }
        }

    @Test
    fun brokenFilesAndCancelledPickersEndWithoutCrash() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest {
                val files = FakeFiles(opened = "kein json".encodeToByteArray())
                setContent {
                    AdvantlyTheme {
                        Column {
                            HealthArchiveSection(
                                InMemoryTelemetrySettings(consent = true),
                                locked = false,
                                files,
                                clock,
                            )
                        }
                    }
                }
                onNodeWithTag("health_import").performClick()
                onNodeWithTag("health_archive_message").assertTextContains("kein Advantly-Pulsexport", substring = true)
            }
        }

    @Test
    fun englishTextsAndImportLockedWhileTelemetryIsOff() =
        withLocale(Locale.US) {
            runDesktopComposeUiTest {
                val files = FakeFiles(opened = null)
                setContent {
                    AdvantlyTheme {
                        Column {
                            HealthArchiveSection(
                                InMemoryTelemetrySettings(consent = true),
                                locked = true,
                                files,
                                clock,
                            )
                        }
                    }
                }
                onNodeWithTag("health_import").assertTextContains("Import heart rate history").assertIsNotEnabled()
                onNodeWithTag("health_export").assertTextContains("Export heart rate history").performClick()
                onNodeWithText("Keep it safe; Advantly has no access to it.", substring = true).assertExists()
            }
        }

    @Test
    fun withoutPlatformPickerTheEntriesAreHidden() =
        runDesktopComposeUiTest {
            setContent { AdvantlyTheme { Column { HealthArchiveSection(InMemoryTelemetrySettings(), locked = false, files = null) } } }
            onNodeWithTag("health_export").assertDoesNotExist()
            onNodeWithTag("health_import").assertDoesNotExist()
        }
}
