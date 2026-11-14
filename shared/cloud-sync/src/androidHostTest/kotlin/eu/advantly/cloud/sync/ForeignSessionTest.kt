package eu.advantly.cloud.sync

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Backend-Wechsel (Staging → Produktion): eine Sitzung des alten Backends darf nicht weiterverwendet werden. */
class ForeignSessionTest {
    private val prod = "https://prod.supabase.co"

    private fun token(payload: String) = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"

    @Test
    fun storedBackendDecides() {
        assertFalse(foreignSession(prod, token("""{"iss":"https://staging.supabase.co/auth/v1"}"""), prod))
        assertTrue(foreignSession("https://staging.supabase.co", token("""{"iss":"$prod/auth/v1"}"""), prod))
    }

    @Test
    fun legacySessionIsJudgedByIssuer() {
        assertTrue(foreignSession(null, token("""{"iss":"https://staging.supabase.co/auth/v1"}"""), prod))
        assertFalse(foreignSession(null, token("""{"iss":"$prod/auth/v1"}"""), "$prod/"))
    }

    @Test
    fun unreadableIssuerKeepsTheSession() {
        assertFalse(foreignSession(null, "opaque", prod))
        assertFalse(foreignSession(null, token("""{"sub":"x"}"""), prod))
    }
}
