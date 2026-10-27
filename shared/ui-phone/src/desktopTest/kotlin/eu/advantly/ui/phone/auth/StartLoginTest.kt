package eu.advantly.ui.phone.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.testModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StartLoginTest {
    private class FakeHost(
        decided: Boolean = false,
    ) : StartLoginHost {
        override val decided = MutableStateFlow(decided)

        override fun decide() {
            decided.value = true
        }
    }

    private fun show(
        available: Boolean = true,
        decided: Boolean = false,
        signedIn: Boolean = false,
        checking: Boolean = false,
        recoveryHold: Boolean = false,
    ) = shouldShowStartLogin(available, decided, signedIn, checking, recoveryHold)

    @Test fun firstStartShows() = assertTrue(show())

    @Test fun afterDecisionNotShown() = assertFalse(show(decided = true))

    @Test fun signedInNotShown() = assertFalse(show(signedIn = true))

    @Test fun withoutBackendNotShown() = assertFalse(show(available = false))

    @Test fun duringRecoveryHoldNotShown() = assertFalse(show(recoveryHold = true))

    @Test fun whileFirstAccountCheckRunsNotShown() = assertFalse(show(checking = true))

    private fun authModel(signedIn: Boolean = false) =
        AuthViewModel(
            FakePhoneAuth().also { if (signedIn) it.session.value = CloudSession("alice", true) },
            CoroutineScope(Dispatchers.Unconfined),
        )

    @Test fun germanAtLargeFontSkipStoresDecisionAndShowsHome() = skip(Locale.GERMANY)

    @Test fun englishAtLargeFontSkipStoresDecisionAndShowsHome() = skip(Locale.US)

    private fun skip(locale: Locale) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            val de = locale.language == "de"
            val host = FakeHost()
            // Kleines Handy (360 × 640 dp) bei doppelter Schrift: alles muss erreichbar und lesbar bleiben.
            runDesktopComposeUiTest(width = 720, height = 1280) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                        AdvantlyApp(model = testModel(), authModel = authModel(), startLogin = host)
                    }
                }
                onNodeWithTag("start_login").assertIsDisplayed()
                onNodeWithTag("home").assertDoesNotExist()
                listOf(
                    if (de) "Mit Konto werden deine Matches in der Cloud gesichert" else "With an account, your matches are backed up",
                    if (de) "später jederzeit im Profil" else "any time later under Profile",
                ).forEach { onNodeWithText(it, substring = true).performScrollTo().assertIsDisplayed() }
                listOf(
                    if (de) "Konto anlegen" else "Create account",
                    if (de) "Anmelden" else "Sign in",
                    if (de) "Ohne Konto weiter zählen" else "Continue scoring without an account",
                ).forEach { onNodeWithText(it).performScrollTo().assertIsDisplayed() }
                onNodeWithTag("start_login_skip").performScrollTo().performClick()
                assertTrue(host.decided.value)
                onNodeWithTag("start_login").assertDoesNotExist()
                onNodeWithTag("home").assertIsDisplayed()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun createAccountOpensTheExistingAccountFlow() {
        val host = FakeHost()
        val app = testModel()
        runDesktopComposeUiTest(width = 360, height = 640) {
            setContent { AdvantlyApp(model = app, authModel = authModel(), startLogin = host) }
            onNodeWithTag("start_login_create").performScrollTo().performClick()
            assertTrue(host.decided.value)
            assertEquals(AppTab.Profile, app.navigator.tab)
            onNodeWithTag("start_login").assertDoesNotExist()
            // Erster Schritt des bestehenden Konto-Flusses (Land/Geburtsdatum → Altersticket)
            onNodeWithTag("auth_country").assertIsDisplayed()
        }
    }

    @Test
    fun signInOpensTheSameAccountFlow() {
        val host = FakeHost()
        runDesktopComposeUiTest(width = 360, height = 640) {
            setContent { AdvantlyApp(model = testModel(), authModel = authModel(), startLogin = host) }
            onNodeWithTag("start_login_sign_in").performScrollTo().performClick()
            onNodeWithTag("auth_country").assertIsDisplayed()
        }
    }

    @Test
    fun signedInUserNeverSeesItAndCountsAsDecided() {
        val host = FakeHost()
        runDesktopComposeUiTest(width = 360, height = 640) {
            setContent { AdvantlyApp(model = testModel(), authModel = authModel(signedIn = true), startLogin = host) }
            onNodeWithTag("home").assertIsDisplayed()
            onNodeWithTag("start_login").assertDoesNotExist()
            runOnIdle { assertTrue(host.decided.value) }
        }
    }

    @Test
    fun decidedOrWithoutHostOrBackendNotShown() {
        val app = testModel()
        val auth = authModel()
        val decided = FakeHost(decided = true)
        val fresh = FakeHost()
        runDesktopComposeUiTest(width = 360, height = 640) {
            val variant = mutableStateOf(0)
            setContent {
                when (variant.value) {
                    0 -> AdvantlyApp(model = app, authModel = auth, startLogin = decided)
                    1 -> AdvantlyApp(model = app, authModel = auth)
                    else -> AdvantlyApp(model = app, startLogin = fresh)
                }
            }
            for (v in 0..2) {
                runOnIdle { variant.value = v }
                onNodeWithTag("home").assertIsDisplayed()
                onNodeWithTag("start_login").assertDoesNotExist()
            }
        }
    }

    @Test
    fun waitsForTheRecoveryHold() {
        val host = FakeHost()
        val app = testModel()
        val auth = authModel()
        runDesktopComposeUiTest(width = 360, height = 640) {
            val hold = mutableStateOf(true)
            setContent { AdvantlyApp(model = app, authModel = auth, startLogin = host, recoveryHold = hold.value) }
            onNodeWithTag("start_login").assertDoesNotExist()
            runOnIdle { hold.value = false }
            onNodeWithTag("start_login").assertIsDisplayed()
            assertFalse(host.decided.value)
        }
    }
}
