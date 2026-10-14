package eu.advantly.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class PresentationInfoTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("presentation", PresentationInfo.MODULE)
        assertEquals(listOf("data", "device-link", "entitlements"), PresentationInfo.dependsOn)
    }
}
