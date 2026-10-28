package eu.advantly.ui.phone.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.PhoneSyncBlock
import eu.advantly.presentation.auth.PhoneSyncPort
import eu.advantly.presentation.auth.PhoneSyncState
import eu.advantly.presentation.auth.PhoneSyncStatus
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Profil (0.3.0): eine Statuszeile zur Sicherung, bei Problem genau ein Knopf; keine manuellen Upload-/Download-Knöpfe. */
@OptIn(ExperimentalTestApi::class)
class BackupStatusLineTest {
    private class Sync : PhoneSyncPort {
        override val state = MutableStateFlow(PhoneSyncState(available = true))
        var syncs = 0

        override fun syncNow() {
            syncs++
        }

        override fun onForeground() = Unit
    }

    @Test fun backedUpRunningAndProblemsInBothLanguages() {
        val original = Locale.getDefault()
        val originalZone = TimeZone.getDefault()
        try {
            // Lokale Zeit statt ISO-Instant: 0 ms ist 01:00 in Paris.
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            for (language in listOf("de", "en")) {
                val de = language == "de"
                Locale.setDefault(Locale.forLanguageTag(language))
                runDesktopComposeUiTest {
                    val sync = Sync()
                    val auth = FakePhoneAuth().also { it.session.value = CloudSession("alice", true) }
                    val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined), sync = sync)
                    setContent { AdvantlyTheme { BackupStatusLine(model) } }
                    val text = onNodeWithTag("backup_status_text")
                    // Noch kein Lauf in diesem Prozess: der Koordinator startet ihn selbst, kein Knopf.
                    text.assertTextEquals(if (de) "Wird gesichert…" else "Backing up…")
                    onNodeWithTag("backup_action").assertDoesNotExist()

                    sync.state.value = PhoneSyncState(available = true, status = PhoneSyncStatus.BACKED_UP, lastBackupAtMs = 0)
                    waitForIdle()
                    text.assertTextEquals(if (de) "Alles gesichert ✓ (zuletzt 01.01. 01:00)" else "All backed up ✓ (last Jan 1, 01:00)")
                    onNodeWithTag("backup_action").assertDoesNotExist()

                    sync.state.value = PhoneSyncState(available = true, running = true, lastBackupAtMs = 0)
                    waitForIdle()
                    text.assertTextEquals(if (de) "Wird gesichert…" else "Backing up…")

                    sync.state.value = PhoneSyncState(available = true, status = PhoneSyncStatus.CONNECTION_FAILED)
                    waitForIdle()
                    text.assertTextEquals(
                        if (de) {
                            "Keine Verbindung. Deine Matches werden automatisch gesichert, sobald du online bist."
                        } else {
                            "No connection. Your matches are backed up automatically as soon as you are online."
                        },
                    )
                    onNodeWithTag("backup_action").assertTextEquals(if (de) "Erneut versuchen" else "Try again").performClick()
                    assertEquals(1, sync.syncs)

                    sync.state.value =
                        PhoneSyncState(available = true, status = PhoneSyncStatus.BLOCKED, blocked = PhoneSyncBlock.PERMISSION)
                    waitForIdle()
                    text.assertTextEquals(
                        if (de) {
                            "Dieses Handy hat keinen Cloud-Zugriff mehr. Melde dich ab und wieder an oder wende dich an den Support."
                        } else {
                            "This phone no longer has cloud access. Sign out and sign in again, or contact support."
                        },
                    )
                    onNodeWithTag("backup_action").performClick()
                    assertEquals(2, sync.syncs)

                    // Ohne Cloud-Kontext prüft „Erneut versuchen“ das Konto; danach startet der Koordinator selbst.
                    sync.state.value = PhoneSyncState()
                    waitForIdle()
                    text.assertTextEquals(if (de) "Die Sicherung ist gerade nicht verfügbar." else "Backup is not available right now.")
                    val refreshes = auth.calls.count { it == "refresh" }
                    onNodeWithTag("backup_action").performClick()
                    assertEquals(refreshes + 1, auth.calls.count { it == "refresh" })
                    assertEquals(2, sync.syncs)
                }
            }
        } finally {
            Locale.setDefault(original)
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun outdatedConsentShowsConfirmLegalTextsWhichRechecksTheAccount() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMAN)
            runDesktopComposeUiTest {
                val auth =
                    FakePhoneAuth().also {
                        it.session.value = CloudSession("alice", true)
                        it.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED)
                    }
                val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined), sync = Sync())
                setContent { AdvantlyTheme { BackupStatusLine(model) } }
                onNodeWithTag("backup_status_text")
                    .assertTextEquals("Die Sicherung ist pausiert, bis du die aktuellen Rechtstexte bestätigst.")
                onNodeWithTag("backup_action").assertTextEquals("Rechtstexte bestätigen").performClick()
                assertEquals(2, auth.calls.count { it == "refresh" })
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
