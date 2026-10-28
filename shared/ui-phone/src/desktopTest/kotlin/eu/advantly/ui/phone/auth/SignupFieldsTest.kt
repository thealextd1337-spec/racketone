package eu.advantly.ui.phone.auth

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SignupFieldsTest {
    @Test fun birthDateDigitsBecomeIsoOnlyWhenComplete() {
        assertEquals("1999-12-31", birthDateIso("31121999"))
        assertNull(birthDateIso("3112199"))
        assertNull(birthDateIso("3112199x"))
    }

    @Test fun birthDateIsShownWithDashesAndCursorMapsBack() {
        val shown = BirthDateTransformation.filter(AnnotatedString("311219"))
        assertEquals("31-12-19", shown.text.text)
        assertEquals(8, shown.offsetMapping.originalToTransformed(6))
        assertEquals(6, shown.offsetMapping.transformedToOriginal(8))
        assertEquals(2, shown.offsetMapping.transformedToOriginal(3))
    }

    @Test fun countrySearchMatchesNameOrCodeAndSkipsEmbargo() {
        val names = mapOf("AT" to "Österreich", "US" to "Vereinigte Staaten", "DE" to "Deutschland")
        val name = { code: String -> names[code] ?: code }
        assertEquals(listOf("AT"), filterCountries("österr", name).map { it.first })
        assertEquals("US", filterCountries("us", name).first().first)
        assertEquals(SIGNUP_COUNTRIES.size, filterCountries("", name).size)
        assertFalse(SIGNUP_COUNTRIES.any { it in setOf("CU", "IR", "KP", "SY") })
    }
}
