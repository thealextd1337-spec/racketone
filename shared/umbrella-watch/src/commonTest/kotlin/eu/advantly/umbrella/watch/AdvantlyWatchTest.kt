package eu.advantly.umbrella.watch

import kotlin.test.Test
import kotlin.test.assertEquals

class AdvantlyWatchTest {
    @Test
    fun moduleNameAndDependencies() {
        assertEquals("umbrella-watch", AdvantlyWatch.MODULE)
        assertEquals("AdvantlyWatchKit", AdvantlyWatch.FRAMEWORK)
        assertEquals(listOf("data", "device-link"), AdvantlyWatch.dependsOn)
    }

    @Test
    fun engineModuleIsCoreScoring() {
        assertEquals("core-scoring", AdvantlyWatch.engineModule())
    }
}
