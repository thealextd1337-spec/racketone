package eu.advantly.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun applicationIdIsSharedWithWear() {
        // Phone und Wear MÜSSEN dieselbe applicationId haben (Wear Data Layer API).
        assertEquals("eu.advantly", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun versionCodeEndsWithPhoneDigit() {
        // Play verlangt eindeutige versionCodes über alle Formfaktoren: Phone …1, Wear …2.
        assertEquals(1, BuildConfig.VERSION_CODE % 10)
    }
}
