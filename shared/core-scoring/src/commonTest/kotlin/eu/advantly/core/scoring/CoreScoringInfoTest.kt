package eu.advantly.core.scoring

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreScoringInfoTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("core-scoring", CoreScoringInfo.MODULE)
        assertEquals(listOf("core-model"), CoreScoringInfo.dependsOn)
    }
}
