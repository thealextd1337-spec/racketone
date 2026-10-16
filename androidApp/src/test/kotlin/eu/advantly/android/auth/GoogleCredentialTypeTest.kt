package eu.advantly.android.auth

import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import eu.advantly.cloud.sync.CloudException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GoogleCredentialTypeTest {
    @Test
    fun googleIdTokenTypeIsAccepted() {
        requireGoogleCredentialType(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
    }

    @Test
    fun otherCredentialTypesAreRejectedBeforeParsing() {
        for (type in listOf("", "android.credentials.TYPE_PASSWORD_CREDENTIAL", "custom")) {
            val error = assertThrows(CloudException::class.java) { requireGoogleCredentialType(type) }
            assertEquals("auth_failed", error.reason)
        }
    }
}
