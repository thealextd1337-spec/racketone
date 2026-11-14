package eu.advantly.ui.phone.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
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
import kotlinx.coroutines.channels.Channel
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
                    text.assertTextContains(if (de) "wende dich an den Support" else "contact support", substring = true)
                    text.assertTextContains(if (de) "Lösche keine App-Daten" else "Do not clear the app data", substring = true)
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

    /** 401 ohne Grund, solange [expired] gilt (Access-Token vor seiner Erneuerung). */
    private class ExpiringAuth(
        val inner: FakePhoneAuth,
    ) : CloudAuthPort by inner {
        var expired = true

        override suspend fun refreshProfile() {
            inner.refreshProfile()
            if (expired) throw CloudException("cloud_connection_failed", true)
        }
    }

    @Test fun unconfirmedAccountShowsCheckingInsteadOfNoConnectionAndRetryRechecks() {
        val original = Locale.getDefault()
        try {
            for (language in listOf("de", "en")) {
                val de = language == "de"
                Locale.setDefault(Locale.forLanguageTag(language))
                runDesktopComposeUiTest {
                    val sync = Sync().also { it.state.value = PhoneSyncState(available = true, status = PhoneSyncStatus.BACKED_UP) }
                    val fake = FakePhoneAuth().also { it.session.value = CloudSession("alice", true) }
                    val auth = ExpiringAuth(fake)
                    val renewal = Channel<Unit>(Channel.UNLIMITED)
                    val model =
                        AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined), sync = sync, awaitTokenRenewal = { renewal.receive() })
                    setContent { AdvantlyTheme { BackupStatusLine(model) } }
                    val text = onNodeWithTag("backup_status_text")
                    // Token vor der Erneuerung: kein „Keine Verbindung“, sondern Prüfung läuft.
                    text.assertTextEquals(if (de) "Konto wird geprüft…" else "Checking your account…")
                    runOnIdle {
                        auth.expired = false
                        renewal.trySend(Unit)
                    }
                    text.assertTextEquals(if (de) "Alles gesichert ✓" else "All backed up ✓")

                    // Echter Netzfehler bleibt „Keine Verbindung“; „Erneut versuchen“ prüft das Konto und startet den Abgleich.
                    runOnIdle {
                        fake.failure = "connection_failed"
                        model.refresh()
                    }
                    text.assertTextContains(if (de) "Keine Verbindung" else "No connection", substring = true)
                    val refreshes = fake.calls.count { it == "refresh" }
                    fake.failure = null
                    onNodeWithTag("backup_action").performClick()
                    assertEquals(refreshes + 1, fake.calls.count { it == "refresh" })
                    assertEquals(1, sync.syncs)
                    text.assertTextEquals(if (de) "Alles gesichert ✓" else "All backed up ✓")
                }
            }
        } finally {
            Locale.setDefault(original)
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
                // Kein freigegebener Katalog: nur die Sicherung pausiert, die App sperrt nicht.
                runOnIdle {
                    auth.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.UNAVAILABLE)
                    model.refresh()
                }
                onNodeWithTag("backup_status_text")
                    .assertTextEquals(
                        "Die Sicherung pausiert, solange die Rechtstexte aktualisiert werden. Bitte versuche es später erneut.",
                    )
                onNodeWithTag("backup_action").assertTextEquals("Erneut versuchen")
                assertEquals(false, model.state.value.accountRequired)
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
