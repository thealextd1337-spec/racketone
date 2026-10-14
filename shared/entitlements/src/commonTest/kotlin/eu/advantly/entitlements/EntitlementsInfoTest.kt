package eu.advantly.entitlements

import kotlin.test.Test
import kotlin.test.assertEquals

class EntitlementsInfoTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("entitlements", EntitlementsInfo.MODULE)
        assertEquals(emptyList(), EntitlementsInfo.dependsOn)
    }
}
