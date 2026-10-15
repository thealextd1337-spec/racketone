package eu.advantly.wear.diagnostics

import eu.advantly.wear.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticLogGuardTest {
    @Test
    fun diagnosticLogIsDebugOnly() {
        // Das Diagnose-Log ist nur im Debug-Build aktiv. Der Release-Zweig greift, sobald Release-Unit-Tests aktiviert sind.
        assertEquals(BuildConfig.DEBUG, DiagnosticLog.ENABLED)
        if (!DiagnosticLog.ENABLED) {
            DiagnosticLog.log("TELEMETRY_SENT") // darf weder Datei noch Logcat anfassen
            DiagnosticLog.logAll(listOf("TELEMETRY_SENT" to emptyMap()))
            assertNull(DiagnosticLog.path)
        }
    }
}
