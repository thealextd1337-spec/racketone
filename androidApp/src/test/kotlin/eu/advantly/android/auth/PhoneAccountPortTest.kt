package eu.advantly.android.auth

import androidx.lifecycle.ViewModelStore
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.IdTokenProvider
import eu.advantly.presentation.auth.AuthViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Konto-ViewModels über dem prozessweiten Konto-Host: Abbau nur der eigenen Arbeit, Datensparsamkeit bei Logout/Löschantrag. */
class PhoneAccountPortTest {
    private class Auth : CloudAuthPort {
        override val session = MutableStateFlow<CloudSession?>(CloudSession("alice", onboarded = true))
        var refreshes = 0
        var failSignOut = false

        /** Bleibt hängen wie eine Profilprüfung ohne Netz: Auch gestartete Aktionen müssen mit dem ViewModel enden. */
        override suspend fun refreshProfile() {
            refreshes++
            awaitCancellation()
        }

        override suspend fun signOut() {
            session.value = null
            if (failSignOut) throw CloudException("connection_failed", true)
        }

        override suspend fun requestDeletion() {
            session.value = session.value?.copy(deletionRequested = true)
        }

        override suspend fun issueAgeTicket(
            country: String,
            birthDate: String,
        ) = error("unused")

        override suspend fun cancelAgeTicket(ticket: String) = error("unused")

        override suspend fun signInWithIdToken(
            provider: IdTokenProvider,
            idToken: String,
            rawNonce: String?,
            ticket: String?,
        ) = error("unused")

        override suspend fun linkGoogle(
            idToken: String,
            rawNonce: String?,
        ) = error("unused")

        override suspend fun sendEmailCode(
            email: String,
            ticket: String?,
        ) = error("unused")

        override suspend fun verifyEmailCode(
            email: String,
            code: String,
        ) = error("unused")

        override suspend fun completeOnboarding(
            alias: String,
            username: String?,
        ) = error("unused")
    }

    private class Record : PhoneSyncRecord {
        val forgotten = mutableListOf<String>()

        override fun lastBackupAtMs(profile: String): Long? = null

        override fun failures(profile: String) = 0

        override fun save(
            profile: String,
            lastBackupAtMs: Long?,
            failures: Int,
        ) = Unit

        override fun forget(profile: String) {
            forgotten += profile
        }
    }

    /** Review-Probe P2-1: Vorher blieben je geräumtem ViewModel Collector und Aktion im Prozess-Scope (expected 0, was 3). */
    @Test fun clearedViewModelsLeaveNoCoroutineInTheProcessScopeAndKeepTheHost() {
        val process = Job()
        val processScope = CoroutineScope(Dispatchers.Unconfined + process)
        val auth = Auth()
        var released = 0
        repeat(3) {
            val port = PhoneAccountPort(auth, processScope, Record()) { released++ }
            val model = AuthViewModel(port, workScope = port.scope)
            ViewModelStore().apply {
                put("account", model)
                clear()
            }
        }
        assertEquals(3, auth.refreshes)
        assertEquals("aktive Coroutinen geräumter ViewModels im Prozess-Scope", 0, process.children.count())
        assertEquals(3, released)
        assertTrue("der prozessweite Konto-Host bleibt", process.isActive)
        process.cancel()
    }

    /** P3-7: Logout (auch mit Netzfehler) und Löschantrag entfernen den Sicherungsstand des Kontos. */
    @Test fun signOutAndDeletionForgetTheBackupRecordOfTheAccount() =
        runBlocking {
            val record = Record()
            val auth = Auth()
            val port = PhoneAccountPort(auth, this, record) {}
            port.requestDeletion()
            assertEquals(listOf("alice"), record.forgotten)
            auth.failSignOut = true
            runCatching { port.signOut() }
            assertEquals(listOf("alice", "alice"), record.forgotten)
            // Ohne Konto gibt es nichts zu entfernen.
            auth.failSignOut = false
            port.signOut()
            assertEquals(2, record.forgotten.size)
            port.close()
        }
}
