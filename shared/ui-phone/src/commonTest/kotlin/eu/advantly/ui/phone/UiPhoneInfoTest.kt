package eu.advantly.ui.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class UiPhoneInfoTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("ui-phone", UiPhoneInfo.MODULE)
        assertEquals(listOf("presentation"), UiPhoneInfo.dependsOn)
    }
}
