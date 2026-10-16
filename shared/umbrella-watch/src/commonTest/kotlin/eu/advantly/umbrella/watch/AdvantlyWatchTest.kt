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
    fun operationTimingsMatchWearOs() {
        assertEquals(5_000L, AdvantlyWatch.serveBarVisibleMillis())
        assertEquals(1_500L, AdvantlyWatch.serveBarAfterChoiceMillis())
        assertEquals(1_000L, AdvantlyWatch.holdToRemoveMillis())
    }

    @Test
    fun engineModuleIsCoreScoring() {
        assertEquals("core-scoring", AdvantlyWatch.engineModule())
    }
}
