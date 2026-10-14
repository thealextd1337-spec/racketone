package eu.advantly.device.link

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceLinkInfoTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("device-link", DeviceLinkInfo.MODULE)
        assertEquals(listOf("sync-protocol"), DeviceLinkInfo.dependsOn)
    }
}
