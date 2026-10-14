package eu.advantly.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun applicationIdIsSharedWithPhone() {
        // Phone und Wear MÜSSEN dieselbe applicationId haben (Wear Data Layer API).
        assertEquals("eu.advantly", BuildConfig.APPLICATION_ID)
    }
}
