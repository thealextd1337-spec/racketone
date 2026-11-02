package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.data.StoredHealthSeries
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthSeries
import eu.advantly.match.session.HealthArchive
import eu.advantly.match.session.HealthArchiveContent
import eu.advantly.match.session.HealthImportError
import eu.advantly.match.session.HealthImportResult
import eu.advantly.presentation.match.InMemoryTelemetrySettings
import eu.advantly.presentation.match.TelemetrySettingsPort
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.platform.DocumentFiles
import eu.advantly.ui.phone.platform.FileTooLargeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan 0.5.1 §7b: „Pulsverläufe exportieren/importieren“ unter „Puls & Schritte“ (Hinweis, Einwilligung, Meldungen, DE/EN). */
@OptIn(ExperimentalTestApi::class)
class HealthArchiveUiTest {
    private val clock = WallClock { 1_793_577_600_000 } // 02.11.2026

    /** Merkt sich Aufträge; [opened] liefert die gewählte Datei (`null` = abgebrochen, Fehlschlag = Lesefehler). */
    private class FakeFiles(
        val opened: Result<ByteArray>? = null,
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
            onDone: (Result<ByteArray>?) -> Unit,
        ) {
            opens++
            onDone(opened)
        }
    }

    private class FilePort(
        private val inner: InMemoryTelemetrySettings,
        private val series: List<StoredHealthSeries>,
        private val result: HealthImportResult,
    ) : TelemetrySettingsPort by inner {
        val imported = mutableListOf<HealthArchiveContent>()

        override fun exportHealthSeries(): List<StoredHealthSeries> = series

        override fun importHealth(content: HealthArchiveContent): HealthImportResult = result.also { imported += content }
    }

    /** Arbeitsthread zum Mitschalten: Aufträge warten, bis der Test [runAll] aufruft. */
    private class ManualWorker : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        val pending: Boolean get() = queue.isNotEmpty()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue += block
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private val stored =
        StoredHealthSeries(MatchId("m1"), DeviceId("watch"), 1, HealthSeries(0, 5_000, listOf(120), listOf(0L), 0, HealthQuality.OK))

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
                val port = FilePort(InMemoryTelemetrySettings(consent = true), listOf(stored), HealthImportResult())
                val files = FakeFiles()
                setContent { AdvantlyTheme { Column { HealthArchiveSection(port, locked = false, files, clock, Dispatchers.Unconfined) } } }
                onNodeWithTag("health_export").assertTextContains("Pulsverläufe exportieren").performClick()
                onNodeWithText("Die Datei enthält Gesundheitsdaten (Puls, Schritte)", substring = true).assertExists()
                runOnIdle { assertTrue(files.saves.isEmpty(), "erst nach dem Hinweis") }
                onNodeWithTag("health_archive_confirm").assertTextContains("Weiter").performClick()
                runOnIdle {
                    assertEquals(
                        listOf("advantly-puls-2026-11-02.json" to HealthArchive.encode(listOf(stored), clock.nowMs())),
                        files.saves,
                    )
                }
                onNodeWithTag("health_archive_message").assertTextContains("Datei gespeichert.")
            }
        }

    /** Review M5: Ein Export über der Import-Grenze geht in mehrere Dateien; vor jeder weiteren fragt ein Dialog. */
    @Test
    fun aLargeExportIsSavedAsSeveralFiles() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest(width = 412, height = 892) {
                val series = List(3) { stored.copy(matchId = MatchId("m$it")) }
                val port = FilePort(InMemoryTelemetrySettings(consent = true), series, HealthImportResult())
                val files = FakeFiles()
                val limit = HealthArchive.encode(series.take(1), clock.nowMs()).length
                setContent {
                    AdvantlyTheme { Column { HealthArchiveSection(port, false, files, clock, Dispatchers.Unconfined, maxBytes = limit) } }
                }
                onNodeWithTag("health_export").performClick()
                onNodeWithTag("health_archive_confirm").performClick()
                onNodeWithText("Datei 1 von 3 gespeichert").assertExists()
                onNodeWithTag("health_export_next").assertTextContains("Nächste Datei speichern").performClick()
                onNodeWithText("Datei 2 von 3 gespeichert").assertExists()
                onNodeWithTag("health_export_next").performClick()
                onNodeWithTag("health_archive_message").assertTextContains("3 Dateien gespeichert.")
                runOnIdle {
                    assertEquals(
                        (1..3).map { "advantly-puls-2026-11-02-$it.json" },
                        files.saves.map { it.first },
                    )
                    assertEquals(series.map { HealthArchive.encode(listOf(it), clock.nowMs()) }, files.saves.map { it.second })
                }
            }
        }

    @Test
    fun cancellingALaterFileSaysHowManyWereSaved() =
        withLocale(Locale.US) {
            runDesktopComposeUiTest(width = 412, height = 892) {
                val series = List(2) { stored.copy(matchId = MatchId("m$it")) }
                val port = FilePort(InMemoryTelemetrySettings(consent = true), series, HealthImportResult())
                val files = FakeFiles()
                val limit = HealthArchive.encode(series.take(1), clock.nowMs()).length
                setContent {
                    AdvantlyTheme { Column { HealthArchiveSection(port, false, files, clock, Dispatchers.Unconfined, maxBytes = limit) } }
                }
                onNodeWithTag("health_export").performClick()
                onNodeWithTag("health_archive_confirm").performClick()
                onNodeWithText("File 1 of 2 saved").assertExists()
                onNodeWithText("Cancel").performClick()
                onNodeWithTag("health_archive_message").assertTextContains("Only 1 of 2 files saved.", substring = true)
                runOnIdle { assertEquals(1, files.saves.size) }
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
                val port = FilePort(inner, emptyList(), HealthImportResult(imported = 12, unknownMatch = 2))
                val files = FakeFiles(opened = Result.success(byteArrayOf(1, 2)))
                setContent { AdvantlyTheme { Column { HealthArchiveSection(port, locked = false, files, clock, Dispatchers.Unconfined) } } }
                onNodeWithTag("health_import").assertTextContains("Pulsverläufe importieren").performClick()
                onNodeWithText("Pulsverlauf aufzeichnen?").assertExists()
                runOnIdle { assertEquals(0, files.opens, "Dateiauswahl erst nach der Einwilligung") }
                onNodeWithTag("health_archive_confirm").assertTextContains("Einwilligen").performClick()
                runOnIdle {
                    assertTrue(inner.healthConsent.value)
                    assertEquals(1, files.opens)
                    assertEquals(listOf(HealthImportError.INVALID), port.imported.map { it.error }, "geprüft, bevor die Session sie sieht")
                }
                onNodeWithTag("health_archive_message")
                    .assertTextContains("12 übernommen, 2 ohne passendes Match, 0 schon vorhanden oder abgewählt.")
            }
        }

    @Test
    fun brokenFilesAndCancelledPickersEndWithoutCrash() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest {
                val files = FakeFiles(opened = Result.success("kein json".encodeToByteArray()))
                setContent {
                    AdvantlyTheme {
                        Column {
                            HealthArchiveSection(
                                InMemoryTelemetrySettings(consent = true),
                                locked = false,
                                files,
                                clock,
                                Dispatchers.Unconfined,
                            )
                        }
                    }
                }
                onNodeWithTag("health_import").performClick()
                onNodeWithTag("health_archive_message").assertTextContains("kein Advantly-Pulsexport", substring = true)
            }
        }

    /** Review M1: Prüfen/Bauen der Datei läuft auf dem Arbeitsthread, nur der Datenbankteil auf dem UI-Thread. */
    @Test
    fun largeFilesAreProcessedOffTheUiThreadWithProgressAndLockedButtons() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest(width = 412, height = 892) {
                val worker = ManualWorker()
                val port = FilePort(InMemoryTelemetrySettings(consent = true), listOf(stored), HealthImportResult(imported = 1))
                val files = FakeFiles(opened = Result.success(HealthArchive.encode(listOf(stored), 0).encodeToByteArray()))
                setContent { AdvantlyTheme { Column { HealthArchiveSection(port, locked = false, files, clock, worker) } } }

                onNodeWithTag("health_import").performClick()
                onNodeWithTag("health_archive_busy").assertExists()
                onNodeWithText("Datei wird verarbeitet", substring = true).assertExists()
                onNodeWithTag("health_import").assertIsNotEnabled()
                onNodeWithTag("health_export").assertIsNotEnabled()
                runOnIdle {
                    assertTrue(worker.pending, "Prüfung wartet auf dem Arbeitsthread")
                    assertTrue(port.imported.isEmpty(), "Session erst nach der Prüfung")
                    worker.runAll()
                }
                onNodeWithTag("health_archive_message").assertTextContains("1 übernommen", substring = true)
                onNodeWithTag("health_archive_busy").assertDoesNotExist()
                onNodeWithTag("health_import").assertIsEnabled()
                runOnIdle { assertEquals(listOf(stored), port.imported.single().series) }

                onNodeWithTag("health_export").performClick()
                onNodeWithTag("health_archive_confirm").performClick()
                onNodeWithTag("health_archive_busy").assertExists()
                runOnIdle {
                    assertTrue(files.saves.isEmpty(), "Datei entsteht auf dem Arbeitsthread")
                    worker.runAll()
                }
                onNodeWithTag("health_archive_message").assertTextContains("Datei gespeichert.")
                runOnIdle { assertEquals(1, files.saves.size) }
            }
        }

    /** Review N3: Ein Lese-/Berechtigungsfehler ist kein Urteil über den Inhalt. */
    @Test
    fun readErrorsAreNotReportedAsForeignFiles() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest {
                val port = FilePort(InMemoryTelemetrySettings(consent = true), emptyList(), HealthImportResult())
                val files = FakeFiles(opened = Result.failure(SecurityException("keine Berechtigung")))
                setContent { AdvantlyTheme { Column { HealthArchiveSection(port, locked = false, files, clock, Dispatchers.Unconfined) } } }
                onNodeWithTag("health_import").performClick()
                onNodeWithTag("health_archive_message").assertTextContains("Die Datei konnte nicht gelesen werden.")
                runOnIdle { assertTrue(port.imported.isEmpty()) }
            }
        }

    /** Review M3: Meldet die Plattform die Größe schon vor dem Lesen als zu groß, heißt es „zu groß“, nicht „unlesbar“. */
    @Test
    fun aFileKnownToBeTooLargeIsReportedAsSuch() =
        withLocale(Locale.GERMANY) {
            runDesktopComposeUiTest {
                val port = FilePort(InMemoryTelemetrySettings(consent = true), emptyList(), HealthImportResult())
                val files = FakeFiles(opened = Result.failure(FileTooLargeException()))
                setContent { AdvantlyTheme { Column { HealthArchiveSection(port, locked = false, files, clock, Dispatchers.Unconfined) } } }
                onNodeWithTag("health_import").performClick()
                onNodeWithTag("health_archive_message").assertTextContains("Die Datei ist zu groß (höchstens 20 MB).")
                runOnIdle { assertTrue(port.imported.isEmpty()) }
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
