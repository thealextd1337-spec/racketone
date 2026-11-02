package eu.advantly.presentation.auth

import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentDocument
import eu.advantly.cloud.sync.ConsentDocumentType
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan 0.5.1 §5.1: Schritt HEALTH nach CONSENT und vor ALIAS, nur nach einer Anmeldung und solange offen. */
class AuthHealthStepTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var open = true

    private fun model(auth: FakePhoneAuth) = AuthViewModel(auth, scope).also { it.healthDecisionOpen = { open } }

    @Test fun newAccountAsksAfterConsentAndBeforeAlias() {
        val auth = FakePhoneAuth()
        val model = model(auth)
        model.checkAge("AT", "2000-01-01")
        model.sendCode("a@b.at")
        auth.consentSnapshot = ConsentSnapshot("id", ConsentStatus.REQUIRED, documents())
        model.verifyCode("123456")
        assertEquals(AuthStep.CONSENT, model.state.value.step)
        model.decideConsent(true)
        assertEquals(AuthStep.HEALTH, model.state.value.step)
        assertTrue(model.state.value.accountRequired)
        model.continueAfterHealth()
        assertEquals(AuthStep.ALIAS, model.state.value.step)
        model.finish("Alias", "alias")
        assertEquals(AuthStep.ACCOUNT, model.state.value.step)
    }

    @Test fun existingAccountIsAskedToo() {
        val auth =
            object : FakePhoneAuth() {
                override suspend fun verifyEmailCode(
                    email: String,
                    code: String,
                ) {
                    session.value = CloudSession("id", true)
                }
            }
        val model = model(auth)
        model.signInExisting()
        model.sendCode("a@b.at")
        model.verifyCode("123456")
        assertEquals(AuthStep.HEALTH, model.state.value.step)
        assertTrue(model.state.value.accountRequired, "das Konto ist erst nach der Antwort nutzbar")
        model.continueAfterHealth()
        assertEquals(AuthStep.ACCOUNT, model.state.value.step)
        model.refresh()
        assertEquals(AuthStep.ACCOUNT, model.state.value.step, "„Später“ fragt nicht erneut")
    }

    @Test fun decidedDeviceOrAppStartSkipsTheStep() {
        open = false
        val auth = FakePhoneAuth()
        val model = model(auth)
        model.checkAge("AT", "2000-01-01")
        model.sendCode("a@b.at")
        model.verifyCode("123456")
        assertEquals(AuthStep.ALIAS, model.state.value.step)
        open = true
        model.refresh()
        assertEquals(AuthStep.ALIAS, model.state.value.step, "nur direkt nach der Anmeldung")
        // Gespeicherte Sitzung beim App-Start: kein Schritt HEALTH (dafür „Was ist neu?“)
        val restored = FakePhoneAuth().also { it.session.value = CloudSession("alice", true) }
        assertEquals(AuthStep.ACCOUNT, model(restored).state.value.step)
    }

    private fun documents() =
        ConsentDocumentType.entries.map { ConsentDocument(it, "v1", "en", "https://advantly.eu/document", approved = true) }
}
