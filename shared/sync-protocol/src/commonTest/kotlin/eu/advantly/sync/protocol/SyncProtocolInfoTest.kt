package eu.advantly.sync.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncProtocolInfoTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("sync-protocol", SyncProtocolInfo.MODULE)
        assertEquals(listOf("core-scoring"), SyncProtocolInfo.dependsOn)
    }
}
