package eu.advantly.umbrella.ios

import kotlin.test.Test
import kotlin.test.assertEquals

class AdvantlyKitInfoTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("umbrella-ios", AdvantlyKitInfo.MODULE)
        assertEquals("AdvantlyKit", AdvantlyKitInfo.FRAMEWORK)
        assertEquals(listOf("ui-phone"), AdvantlyKitInfo.dependsOn)
    }
}
