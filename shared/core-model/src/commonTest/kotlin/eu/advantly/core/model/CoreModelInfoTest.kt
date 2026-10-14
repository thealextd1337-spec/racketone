package eu.advantly.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreModelInfoTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("core-model", CoreModelInfo.MODULE)
        assertEquals(emptyList(), CoreModelInfo.dependsOn)
    }
}
