package eu.advantly.ui.phone.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import eu.advantly.cloud.sync.CloudAdoptionSource
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.IdTokenProvider
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakePhoneAuth : CloudAuthPort {
    override val session = MutableStateFlow<CloudSession?>(null)
    val calls = mutableListOf<String>()
    var failure: String? = null
    var sources: List<CloudAdoptionSource> = emptyList()
    val adopted = mutableListOf<Set<String>>()

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
