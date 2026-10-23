package eu.advantly.ui.phone.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhonePullState
import eu.advantly.presentation.auth.PhoneSyncBlock
import eu.advantly.presentation.auth.PhoneSyncPort
import eu.advantly.presentation.auth.PhoneSyncState
import eu.advantly.presentation.auth.PhoneSyncStatus
import eu.advantly.presentation.auth.PhoneUploadPhase
import eu.advantly.presentation.auth.PhoneUploadPort
import eu.advantly.presentation.auth.PhoneUploadState
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CloudSyncSectionTest {
    private class Sync : PhoneSyncPort {
        override val state = MutableStateFlow(PhoneSyncState(available = true))
        var syncs = 0

        override fun syncNow() {
            syncs++
        }

        override fun onForeground() = Unit
    }

    private class Upload : PhoneUploadPort {
        override val state = MutableStateFlow(PhoneUploadState(available = true, busy = true, phase = PhoneUploadPhase.WAITING))

        override fun uploadNow() = Unit

        override fun refreshStatus() = Unit

        override fun close(onDrained: () -> Unit) = onDrained()
    }

    private class Pull : PhonePullPort {
        override val state = MutableStateFlow(PhonePullState(available = true))

        override fun pullNow() = Unit

        override fun close(onDrained: () -> Unit) = onDrained()
    }

    @Test fun backupStatusShowsLastBackupBlockedReasonAndRetryInBothLanguages() {
        val original = Locale.getDefault()
        try {
            for (language in listOf("de", "en")) {
                val de = language == "de"
                Locale.setDefault(Locale.forLanguageTag(language))
                runDesktopComposeUiTest {
                    val sync = Sync()
                    setContent { AdvantlyTheme { CloudSyncSection(sync) } }
                    onNodeWithTag("cloud_sync_last_backup").assertTextEquals(if (de) "Noch nicht gesichert" else "Not backed up yet")
                    onNodeWithTag("cloud_sync_status").assertDoesNotExist()
                    onNodeWithTag("cloud_sync_now")
                        .assertTextEquals(if (de) "Jetzt synchronisieren" else "Sync now")
                        .assertIsEnabled()
                        .performClick()
                    assertEquals(1, sync.syncs)

                    sync.state.value = PhoneSyncState(available = true, running = true, nextAttemptAtMs = 0)
                    waitForIdle()
                    onNodeWithTag("cloud_sync_now").assertIsNotEnabled()
                    onNodeWithTag("cloud_sync_status").assertTextEquals(if (de) "Wird synchronisiert…" else "Syncing…")
                    onNodeWithTag("cloud_sync_next_attempt").assertDoesNotExist()

                    sync.state.value =
                        PhoneSyncState(
                            available = true,
                            status = PhoneSyncStatus.BLOCKED,
                            blocked = PhoneSyncBlock.UPLOAD_WAITING,
                            lastBackupAtMs = 0,
                        )
                    waitForIdle()
                    onNodeWithTag("cloud_sync_last_backup").assertTextEquals(
                        if (de) "Zuletzt gesichert: 1970-01-01T00:00:00Z" else "Last backed up: 1970-01-01T00:00:00Z",
                    )
                    onNodeWithTag("cloud_sync_status").assertTextEquals(
                        if (de) "Die Sicherung pausiert, bis die Ursache behoben ist." else "Backup is paused until the cause is resolved.",
                    )
                    onNodeWithTag("cloud_sync_blocked").assertTextEquals(
                        if (de) {
                            "Einige Daten warten, bis der Cloud-Abgleich auf diesem Handy abgeschlossen ist."
                        } else {
                            "Some data is waiting until the cloud check on this phone is complete."
                        },
                    )
                    onNodeWithTag("cloud_sync_next_attempt").assertDoesNotExist()

                    sync.state.value =
                        PhoneSyncState(available = true, status = PhoneSyncStatus.CONNECTION_FAILED, nextAttemptAtMs = 60_000)
                    waitForIdle()
                    onNodeWithTag("cloud_sync_blocked").assertDoesNotExist()
                    onNodeWithTag("cloud_sync_status").assertTextEquals(
                        if (de) {
                            "Keine Verbindung. Der Abgleich wird automatisch wiederholt, sobald du online bist."
                        } else {
                            "No connection. Syncing retries automatically when you are online."
                        },
                    )
                    onNodeWithTag("cloud_sync_next_attempt").assertTextEquals(
                        if (de) "Nächster automatischer Versuch: 1970-01-01T00:01:00Z" else "Next automatic attempt: 1970-01-01T00:01:00Z",
                    )

                    sync.state.value = PhoneSyncState()
                    waitForIdle()
                    onNodeWithTag("cloud_sync_now").assertIsNotEnabled()
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    /** Mit „Jetzt synchronisieren“ bleibt nur die feste Reihenfolge Download → Upload; Warten auf die Sperre ist sichtbar. */
    @Test fun syncReplacesTheSeparateButtonsAndUploadShowsWaitingForTheDownload() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            runDesktopComposeUiTest {
                setContent {
                    AdvantlyTheme {
                        Column {
                            CloudUploadSection(Upload(), manual = false)
                            CloudPullSection(Pull(), manual = false)
                        }
                    }
                }
                onNodeWithTag("cloud_upload_now").assertDoesNotExist()
                onNodeWithTag("cloud_pull_now").assertDoesNotExist()
                onNodeWithTag("cloud_upload_refresh").assertExists()
                onNodeWithTag("cloud_upload_result").assertTextEquals("Waiting for the download to finish…")
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
