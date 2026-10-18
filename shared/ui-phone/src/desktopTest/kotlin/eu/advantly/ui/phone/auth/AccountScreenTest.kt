package eu.advantly.ui.phone.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.cloud.sync.CloudAdoptionSource
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentDocument
import eu.advantly.cloud.sync.ConsentDocumentType
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.IdTokenProvider
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakePhoneAuth : CloudAuthPort {
    override val session = MutableStateFlow<CloudSession?>(null)
    val calls = mutableListOf<String>()
    var failure: String? = null
    var sources: List<CloudAdoptionSource> = emptyList()
    val adopted = mutableListOf<Set<String>>()
    var consentStatus = ConsentStatus.CURRENT
    var consentSnapshot: ConsentSnapshot? = null
    var duringConsent: (() -> Unit)? = null

    override suspend fun consent(language: String): ConsentSnapshot =
        consentSnapshot ?: ConsentSnapshot(session.value!!.profileId, consentStatus)

    override suspend fun decideConsent(
        snapshot: ConsentSnapshot,
        accept: Boolean,
    ): ConsentSnapshot {
        call("consent:$accept")
        duringConsent?.invoke()
        consentStatus = if (accept) ConsentStatus.CURRENT else ConsentStatus.DECLINED
        consentSnapshot = snapshot.copy(status = consentStatus)
        return consentSnapshot!!
    }

    override suspend fun exportData(): String {
        call("export")
        return "{\"consentHistory\":[]}"
    }

    override suspend fun requestDeletion() {
        call("delete")
        session.value = session.value!!.copy(deletionRequested = true)
    }

    override suspend fun cancelDeletion() {
        call("recover")
        session.value = session.value!!.copy(deletionRequested = false)
    }

    override fun adoptionSources(): List<CloudAdoptionSource> = sources

    override fun adoptSources(ids: Set<String>) {
        adopted += ids
    }

    private fun call(name: String) {
        calls += name
        failure?.let { throw CloudException(it, false) }
    }

    override suspend fun issueAgeTicket(
        country: String,
        birthDate: String,
    ): String {
        call("age:$country")
        return "ticket"
    }

    override suspend fun signInWithIdToken(
        provider: IdTokenProvider,
        idToken: String,
        rawNonce: String?,
        ticket: String,
    ) {
        call("google:$ticket")
        session.value = CloudSession("id", false)
    }

    override suspend fun cancelAgeTicket(ticket: String) {
        call("cancel:$ticket")
    }

    override suspend fun linkGoogle(
        idToken: String,
        rawNonce: String?,
    ) {
        call("link")
    }

    override suspend fun sendEmailCode(
        email: String,
        ticket: String,
    ) {
        call("send:$ticket")
    }

    override suspend fun verifyEmailCode(
        email: String,
        code: String,
    ) {
        call("verify")
        session.value = CloudSession("id", false)
    }

    override suspend fun completeOnboarding(
        alias: String,
        username: String?,
    ) {
        call("finish")
        session.value = CloudSession("id", true)
    }

    override suspend fun refreshProfile() {
        call("refresh")
    }

    override suspend fun signOut() {
        session.value = null
        call("logout")
    }
}

@OptIn(ExperimentalTestApi::class)
class AccountScreenTest {
    @Test fun consentNeedsBothChecksAndNewVersionResetsThemInBothLanguagesWithLargeFont() {
        val original = Locale.getDefault()
        try {
            for (language in listOf("de", "en")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                runDesktopComposeUiTest(width = 360, height = 640) {
                    val documents =
                        ConsentDocumentType.entries.map {
                            ConsentDocument(it, "v1", language, "https://advantly.eu/app-datenschutz.html", approved = true)
                        }
                    val auth =
                        FakePhoneAuth().also {
                            it.session.value = CloudSession("alice", true)
                            it.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED, documents)
                        }
                    val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                            AdvantlyTheme { AccountScreen(model, null) {} }
                        }
                    }
                    onNodeWithTag("consent_accept").performScrollTo().assertIsNotEnabled()
                    onNodeWithTag("consent_confirm_TERMS").performScrollTo().assertIsOff().performClick()
                    onNodeWithTag("consent_accept").performScrollTo().assertIsNotEnabled()
                    onNodeWithTag("consent_confirm_PRIVACY").performScrollTo().performClick()
                    onNodeWithTag("consent_confirm_TERMS").assertIsOn()
                    onNodeWithTag("consent_accept").performScrollTo().assertIsEnabled()
                    runOnIdle {
                        auth.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED, documents.map { it.copy(version = "v2") })
                        model.refresh()
                    }
                    onNodeWithTag("consent_accept").performScrollTo().assertIsNotEnabled()
                    onNodeWithTag("consent_confirm_TERMS").performScrollTo().assertIsOff().performClick()
                    onNodeWithTag("consent_confirm_PRIVACY").performScrollTo().performClick()
                    onNodeWithTag("consent_accept").performScrollTo().performClick()
                    assertTrue(auth.calls.contains("consent:true"))
                    onNodeWithTag("consent_accept").assertDoesNotExist()
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun unavailableAndDeclinedConsentKeepSupportExportDeletionRecoveryAndLocalExit() =
        runComposeUiTest {
            val auth =
                FakePhoneAuth().also {
                    it.session.value = CloudSession("alice", true)
                    it.consentStatus = ConsentStatus.UNAVAILABLE
                }
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
            var supportOpened = false
            var closed = false
            setContent { AdvantlyTheme { AccountScreen(model, null, onSupport = { supportOpened = true }) { closed = true } } }
            onNodeWithTag("consent_accept").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("consent_decline").performScrollTo().performClick()
            onNodeWithTag("account_support").performScrollTo().performClick()
            assertTrue(supportOpened)
            onNodeWithTag("account_export").performScrollTo().performClick()
            onNodeWithTag("account_export_data").assertExists()
            runOnIdle { model.dismissExport() }
            onNodeWithTag("account_delete").performScrollTo().performClick()
            onNodeWithTag("account_delete_confirm").performClick()
            onNodeWithTag("account_recover").performScrollTo().performClick()
            assertTrue(auth.calls.containsAll(listOf("consent:false", "export", "delete", "recover")))
            onNodeWithTag("auth_local").performScrollTo().performClick()
            assertTrue(closed)
        }

    @Test fun adoptionSelectionUsesLabelsAndCancelHasNoOwnershipEffect() =
        runComposeUiTest {
            val auth =
                FakePhoneAuth().also {
                    it.session.value = CloudSession("alice", true, generation = 4)
                    it.sources = listOf(CloudAdoptionSource("secret-source-id", "WATCH", 8))
                }
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
            setContent { AdvantlyTheme { AccountScreen(model, null) {} } }
            onNodeWithTag("auth_adoption_accept").assertIsNotEnabled()
            onNodeWithTag("auth_adoption_source_0").performScrollTo().performClick()
            onNodeWithTag("auth_adoption_cancel").performScrollTo().performClick()
            onNodeWithTag("auth_adoption_source_0").assertDoesNotExist()
            assertEquals(emptyList(), auth.adopted)
            runOnIdle { model.refresh() }
            onNodeWithTag("auth_adoption_source_0").performScrollTo().performClick()
            onNodeWithTag("auth_adoption_accept").performScrollTo().performClick()
            assertEquals(listOf(setOf("secret-source-id")), auth.adopted)
        }

    @Test fun ageGateHasNoProviderActionUntilServerAccepted() =
        runComposeUiTest {
            val auth = FakePhoneAuth()
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
            var credentialsOpened = false
            setContent {
                AdvantlyTheme {
                    AccountScreen(model, {
                        credentialsOpened = true
                        GoogleCredential("token", "nonce")
                    }) {}
                }
            }
            onNodeWithTag("auth_google").assertDoesNotExist()
            onNodeWithTag("auth_age_next").assertIsNotEnabled()
            onNodeWithTag("auth_birth").performTextInput("2000-01-01")
            onNodeWithTag("auth_age_next").performScrollTo().performClick()
            onNodeWithTag("auth_email").assertExists()
            onNodeWithTag("auth_google").assertExists()
            assertEquals(false, credentialsOpened)
            assertEquals(listOf("refresh", "age:AT"), auth.calls)
        }

    @Test fun unsupportedCountryNeverOpensProvider() =
        runComposeUiTest {
            val auth = FakePhoneAuth()
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
            setContent { AdvantlyTheme { AccountScreen(model, null) {} } }
            onNodeWithTag("auth_country").performTextReplacement("US")
            onNodeWithTag("auth_birth").performTextInput("2000-01-01")
            onNodeWithTag("auth_age_next").performScrollTo().performClick()
            onNodeWithTag("auth_error").assertExists()
            onNodeWithTag("auth_email").assertDoesNotExist()
            assertEquals(listOf("refresh"), auth.calls)
        }

    @Test fun rejectedAgeAndUnsupportedCountryKeepLocalExit() =
        runComposeUiTest {
            val auth = FakePhoneAuth()
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
            var closed = false
            setContent { AdvantlyTheme { AccountScreen(model, null) { closed = true } } }
            auth.failure = "age_below_minimum"
            onNodeWithTag("auth_birth").performTextInput("2020-01-01")
            onNodeWithTag("auth_age_next").performScrollTo().performClick()
            onNodeWithTag("auth_error").assertExists()
            onNodeWithTag("auth_birth").assertExists()
            onNodeWithTag("auth_local").performScrollTo().performClick()
            assertTrue(closed)
            assertEquals(listOf("refresh", "age:AT"), auth.calls)
        }
}
