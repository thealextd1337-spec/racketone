package eu.advantly.wear.diagnostics

import eu.advantly.wear.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticLogGuardTest {
    @Test
    fun diagnosticLogIsDebugOnly() {
        // Das Log enthält Pulswerte: aktiv nur im Debug-Build. Der Release-Zweig greift, sobald Release-Unit-Tests aktiviert sind.
        assertEquals(BuildConfig.DEBUG, DiagnosticLog.ENABLED)
        if (!DiagnosticLog.ENABLED) {
            DiagnosticLog.log("HR", "bpm" to 120) // darf weder Datei noch Logcat anfassen
            DiagnosticLog.logAll(listOf("HR" to mapOf("bpm" to 121)))
            assertNull(DiagnosticLog.path)
        }
    }
}
