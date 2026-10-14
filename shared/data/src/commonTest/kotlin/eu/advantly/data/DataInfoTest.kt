package eu.advantly.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DataInfoTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("data", DataInfo.MODULE)
        assertEquals(listOf("core-scoring"), DataInfo.dependsOn)
    }
}
