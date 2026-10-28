package eu.advantly.ui.phone.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
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
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

private const val DE_COUNTRY = "Konten sind in deinem Land noch nicht verfügbar."
private const val EN_COUNTRY = "Accounts are not yet available in your country."

/** Wie im Profil: die Kontorechte liegen in einer scrollbaren Spalte. */
@Composable
internal fun ScrollingRights(
    model: AuthViewModel,
    onShareExport: (() -> Unit)? = null,
) = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { AccountRights(model, null, onShareExport) }

@OptIn(ExperimentalTestApi::class)
class AccountScreenTest {
    @Test fun accountCopyKeepsThemeContrastWithoutAParentSurfaceInBothLanguagesAtLargeFont() {
        val original = Locale.getDefault()
        try {
            for (language in listOf("de", "en")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                for (dark in listOf(true, false)) {
                    runDesktopComposeUiTest(width = 360, height = 640) {
                        val model = AuthViewModel(FakePhoneAuth(), CoroutineScope(Dispatchers.Unconfined))
                        var background = Color.Unspecified
                        setContent {
                            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalContentColor provides Color.Black) {
                                AdvantlyTheme(darkTheme = dark) {
                                    background = MaterialTheme.colorScheme.background
                                    Box(Modifier.fillMaxSize().background(background)) { AccountScreen(model, null) }
                                }
                            }
                        }
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNodeWithText(if (language == "de") "Konto" else "Account")
                            .assertIsDisplayed()
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        val foreground =
                            layouts
                                .single()
                                .layoutInput.style.color
                        val ratio =
                            (maxOf(foreground.luminance(), background.luminance()) + 0.05f) /
                                (minOf(foreground.luminance(), background.luminance()) + 0.05f)
                        val directory = File("build/reports/account-contrast").also { it.mkdirs() }
                        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "account-$language-$dark-200.png"))
                        assertTrue(ratio >= 4.5f, "Account foreground contrast: $ratio (dark=$dark)")
                        onNodeWithTag("auth_birth").performScrollTo().assertIsEnabled()
                        onNodeWithTag("auth_local").assertDoesNotExist()
                    }
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

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
                            AdvantlyTheme { AccountScreen(model, null) }
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

    @Test fun unavailableAndDeclinedConsentOfferSupportAndSignOutButNoLocalExit() =
        inBothLanguages { german ->
            runComposeUiTest {
                val auth =
                    FakePhoneAuth().also {
                        it.session.value = CloudSession("alice", true)
                        it.consentStatus = ConsentStatus.UNAVAILABLE
                    }
                val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
                var supportOpened = false
                setContent { AdvantlyTheme { AccountScreen(model, null, onSupport = { supportOpened = true }) } }
                onNodeWithTag("consent_accept").performScrollTo().assertIsNotEnabled()
                onNodeWithTag("consent_decline").performScrollTo().performClick()
                onNodeWithTag("consent_status")
                    .performScrollTo()
                    .assertTextContains(if (german) "Bestätige sie oder melde dich ab" else "Confirm them or sign out", substring = true)
                onNodeWithTag("account_support").performScrollTo().performClick()
                assertTrue(supportOpened)
                // Kontorechte hängen nicht an der Zustimmung.
                onNodeWithTag("account_export").performScrollTo().performClick()
                onNodeWithTag("account_export_data").assertExists()
                runOnIdle { model.dismissExport() }
                onNodeWithTag("account_delete").performScrollTo().assertIsEnabled()
                onNodeWithTag("auth_local").assertDoesNotExist()
                onNodeWithTag("auth_sign_out").performScrollTo().performClick()
                assertTrue(auth.calls.containsAll(listOf("consent:false", "export", "logout")))
            }
        }

    @Test fun deletionRequestOffersRecoveryExportAndSignOut() =
        runComposeUiTest {
            val auth = FakePhoneAuth().also { it.session.value = CloudSession("alice", true, deletionRequested = true) }
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
            setContent { AdvantlyTheme { AccountScreen(model, null) } }
            onNodeWithTag("account_export").performScrollTo().assertIsEnabled()
            onNodeWithTag("account_delete").assertDoesNotExist()
            onNodeWithTag("auth_sign_out").performScrollTo().assertIsEnabled()
            onNodeWithTag("account_recover").performScrollTo().performClick()
            assertTrue("recover" in auth.calls)
            assertFalse(model.state.value.accountRequired)
        }

    @Test fun suspendedAccountExplainsTheBlockAndKeepsExport() =
        runComposeUiTest {
            val auth = FakePhoneAuth().also { it.session.value = CloudSession("alice", true, suspended = true) }
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
            setContent { AdvantlyTheme { ScrollingRights(model) } }
            onNodeWithTag("account_suspended").performScrollTo().assertIsDisplayed()
            onNodeWithTag("account_export").performScrollTo().assertIsEnabled()
            onNodeWithTag("account_devices_show").assertDoesNotExist()
        }

    private fun inBothLanguages(block: (german: Boolean) -> Unit) {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ENGLISH, Locale.GERMAN)) {
                Locale.setDefault(locale)
                block(locale.language == "de")
            }
        } finally {
            Locale.setDefault(previous)
        }
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
                    })
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

    @Test fun malformedCountryNeverOpensProvider() =
        runComposeUiTest {
            val auth = FakePhoneAuth()
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
            setContent { AdvantlyTheme { AccountScreen(model, null) } }
            onNodeWithTag("auth_country").performTextReplacement("U1")
            onNodeWithTag("auth_birth").performTextInput("2000-01-01")
            onNodeWithTag("auth_age_next").performScrollTo().performClick()
            onNodeWithTag("auth_error").assertExists()
            onNodeWithTag("auth_email").assertDoesNotExist()
            assertEquals(listOf("refresh"), auth.calls)
        }

    @Test fun unsupportedCountryExplainsWithoutPromisingLocalUse() =
        inBothLanguages { german ->
            runComposeUiTest {
                val auth = FakePhoneAuth()
                val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
                setContent { AdvantlyTheme { AccountScreen(model, null) } }
                auth.failure = "country_not_supported"
                onNodeWithTag("auth_country").performTextReplacement("KP")
                onNodeWithTag("auth_birth").performTextInput("2000-01-01")
                onNodeWithTag("auth_age_next").performScrollTo().performClick()
                onNodeWithTag("auth_error")
                    .assertTextEquals(if (german) DE_COUNTRY else EN_COUNTRY)
                onNodeWithTag("auth_local").assertDoesNotExist()
                onNodeWithTag("auth_email").assertDoesNotExist()
                assertTrue(model.state.value.accountRequired)
            }
        }

    @Test fun rejectedAgeKeepsTheAccountFlowWithoutLocalExit() =
        runComposeUiTest {
            val auth = FakePhoneAuth()
            val model = AuthViewModel(auth, CoroutineScope(Dispatchers.Unconfined))
            setContent { AdvantlyTheme { AccountScreen(model, null) } }
            auth.failure = "age_below_minimum"
            onNodeWithTag("auth_birth").performTextInput("2020-01-01")
            onNodeWithTag("auth_age_next").performScrollTo().performClick()
            onNodeWithTag("auth_error").assertExists()
            onNodeWithTag("auth_birth").assertExists()
            onNodeWithTag("auth_local").assertDoesNotExist()
            assertTrue(model.state.value.accountRequired)
            assertEquals(listOf("refresh", "age:AT"), auth.calls)
        }
}
