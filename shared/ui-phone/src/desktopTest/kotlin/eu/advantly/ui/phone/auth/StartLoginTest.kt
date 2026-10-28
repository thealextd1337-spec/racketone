package eu.advantly.ui.phone.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentDocument
import eu.advantly.cloud.sync.ConsentDocumentType
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.startWith
import eu.advantly.ui.phone.testModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pflichtkonto (0.3.0): ohne nutzbares Konto zeigt die Handy-App nur den Konto-Fluss, ohne Überspringen. */
@OptIn(ExperimentalTestApi::class)
class StartLoginTest {
    private fun authModel(
        auth: FakePhoneAuth = FakePhoneAuth(),
        signedIn: Boolean = false,
        failure: String? = null,
    ) = AuthViewModel(
        auth.also {
            if (signedIn) it.session.value = CloudSession("alice", true)
            it.failure = failure
        },
        CoroutineScope(Dispatchers.Unconfined),
    )

    @Test fun germanAtLargeFontFirstStartHasNoSkip() = firstStart(Locale.GERMANY)

    @Test fun englishAtLargeFontFirstStartHasNoSkip() = firstStart(Locale.US)

    private fun firstStart(locale: Locale) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            val de = locale.language == "de"
            // Kleines Handy (360 × 640 dp) bei doppelter Schrift: alles muss erreichbar und lesbar bleiben.
            runDesktopComposeUiTest(width = 720, height = 1280) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                        AdvantlyApp(model = testModel(), authModel = authModel())
                    }
                }
                onNodeWithTag("start_login").assertIsDisplayed()
                onNodeWithTag("home").assertDoesNotExist()
                onNodeWithTag("tab_home").assertDoesNotExist()
                onNodeWithText(if (de) "Zählen auf Handy und Uhr geht auch offline" else "also works offline", substring = true)
                    .performScrollTo()
                    .assertIsDisplayed()
                listOf(if (de) "Konto anlegen" else "Create account", if (de) "Anmelden" else "Sign in")
                    .forEach { onNodeWithText(it).performScrollTo().assertIsDisplayed() }
                onNodeWithTag("start_login_skip").assertDoesNotExist()
                onNodeWithText(if (de) "ohne Konto" else "without an account", substring = true).assertDoesNotExist()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun legalTextsAreReachableBeforeAnyInput() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            runDesktopComposeUiTest(width = 360, height = 640) {
                val opened = mutableListOf<String>()
                setContent {
                    CompositionLocalProvider(
                        LocalUriHandler provides
                            object : UriHandler {
                                override fun openUri(uri: String) {
                                    opened += uri
                                }
                            },
                    ) { AdvantlyApp(model = testModel(), authModel = authModel()) }
                }
                for (tag in listOf("legal_privacy", "legal_terms", "legal_imprint")) onNodeWithTag(tag).performScrollTo().performClick()
                // Auch im Alters-/Anmeldeschritt, bevor Land, Geburtsdatum oder E-Mail eingegeben sind.
                onNodeWithTag("start_login_create").performScrollTo().performClick()
                onNodeWithTag("auth_country").assertIsDisplayed()
                onNodeWithTag("legal_privacy").performScrollTo().performClick()
                assertEquals(
                    listOf(
                        "https://advantly.eu/app-datenschutz.html",
                        "https://advantly.eu/nutzungsbedingungen.html",
                        "https://advantly.eu/impressum.html",
                        "https://advantly.eu/app-datenschutz.html",
                    ),
                    opened,
                )
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun createAccountAndSignInOpenTheAccountFlowWithoutTabs() {
        for (button in listOf("start_login_create", "start_login_sign_in")) {
            runDesktopComposeUiTest(width = 360, height = 640) {
                setContent { AdvantlyApp(model = testModel(), authModel = authModel()) }
                onNodeWithTag(button).performScrollTo().performClick()
                onNodeWithTag("start_login").assertDoesNotExist()
                // Erster Schritt des Konto-Flusses (Land/Geburtsdatum → Altersticket)
                onNodeWithTag("auth_country").assertIsDisplayed()
                onNodeWithTag("auth_local").assertDoesNotExist()
                onNodeWithTag("tab_home").assertDoesNotExist()
            }
        }
    }

    @Test
    fun signedInUserSeesTheAppAndSignOutShowsTheStartAgain() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            signOutAfterConfirmation()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun signOutAfterConfirmation() {
        runDesktopComposeUiTest(width = 360, height = 640) {
            val app = testModel()
            setContent { AdvantlyApp(model = app, authModel = authModel(signedIn = true)) }
            onNodeWithTag("home").assertIsDisplayed()
            onNodeWithTag("start_login").assertDoesNotExist()
            runOnIdle { app.navigator.tab = AppTab.Profile }
            onNodeWithTag("auth_sign_out").performScrollTo().performClick()
            // Rückfrage: ohne Netz kein erneutes Anmelden; Ungesichertes erst nach Anmeldung mit demselben Konto.
            onNodeWithText("same account", substring = true).assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            onNodeWithTag("auth_sign_out_dialog").assertDoesNotExist()
            onNodeWithTag("profile").assertExists()
            onNodeWithTag("auth_sign_out").performScrollTo().performClick()
            onNodeWithTag("auth_sign_out_confirm").performClick()
            onNodeWithTag("start_login").assertIsDisplayed()
            onNodeWithTag("tab_profile").assertDoesNotExist()
        }
    }

    @Test
    fun storedSessionOfflineKeepsCounting() {
        // Gespeicherte Sitzung, my_auth_profile scheitert an der Verbindung: weiterzählen statt Anmeldung erzwingen.
        runDesktopComposeUiTest(width = 360, height = 640) {
            setContent { AdvantlyApp(model = testModel(), authModel = authModel(signedIn = true, failure = "connection_failed")) }
            onNodeWithTag("home").assertIsDisplayed()
            onNodeWithTag("start_login").assertDoesNotExist()
        }
    }

    @Test
    fun newLegalTextsBlockTheAppUntilConfirmed() {
        val auth =
            FakePhoneAuth().also {
                it.consentSnapshot =
                    ConsentSnapshot(
                        "alice",
                        ConsentStatus.REQUIRED,
                        ConsentDocumentType.entries.map { type ->
                            ConsentDocument(type, "5", "en", "https://advantly.eu/en/app-privacy.html", approved = true)
                        },
                    )
            }
        runDesktopComposeUiTest(width = 360, height = 640) {
            setContent { AdvantlyApp(model = testModel(), authModel = authModel(auth, signedIn = true)) }
            onNodeWithTag("home").assertDoesNotExist()
            onNodeWithTag("tab_home").assertDoesNotExist()
            onNodeWithTag("consent_confirm_TERMS").performScrollTo().performClick()
            onNodeWithTag("consent_confirm_PRIVACY").performScrollTo().performClick()
            onNodeWithTag("consent_accept").performScrollTo().performClick()
            assertTrue("consent:true" in auth.calls)
            onNodeWithTag("home").assertIsDisplayed()
        }
    }

    @Test
    fun aRunningMatchIsNotInterruptedAndTheConfirmationFollowsAfterwards() {
        val auth = FakePhoneAuth()
        val account = authModel(auth, signedIn = true)
        runDesktopComposeUiTest(width = 360, height = 640) {
            val app = testModel().apply { startWith(Presets.TENNIS_OPEN.format(), "AAAA") }
            runOnIdle { app.navigator.push(Overlay.Live) }
            setContent { AdvantlyApp(model = app, authModel = account) }
            runOnIdle {
                auth.consentSnapshot = ConsentSnapshot("alice", ConsentStatus.REQUIRED)
                account.refresh()
            }
            onNodeWithTag("live_minimize").assertIsDisplayed()
            onNodeWithTag("live_minimize").performClick()
            onNodeWithTag("consent_status").assertIsDisplayed()
            onNodeWithTag("mini_scorebar").assertDoesNotExist()
        }
    }

    @Test
    fun withoutBackendTheAppCountsWithoutAccount() {
        runDesktopComposeUiTest(width = 360, height = 640) {
            setContent { AdvantlyApp(model = testModel()) }
            onNodeWithTag("home").assertIsDisplayed()
            onNodeWithTag("start_login").assertDoesNotExist()
        }
    }

    @Test
    fun waitsForTheRecoveryHold() {
        val app = testModel()
        val auth = authModel()
        runDesktopComposeUiTest(width = 360, height = 640) {
            val hold = mutableStateOf(true)
            setContent { AdvantlyApp(model = app, authModel = auth, recoveryHold = hold.value) }
            onNodeWithTag("start_login").assertDoesNotExist()
            runOnIdle { hold.value = false }
            onNodeWithTag("start_login").assertIsDisplayed()
        }
    }
}
