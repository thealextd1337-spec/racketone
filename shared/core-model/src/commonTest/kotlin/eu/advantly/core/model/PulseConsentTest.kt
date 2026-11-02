package eu.advantly.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Puls H5: Einwilligung „Pulsverlauf aufzeichnen“ und Standard der Stufe je Match. */
class PulseConsentTest {
    @Test
    fun consentDefaultsToOffAndMergesLikeTelemetry() {
        assertEquals(TelemetryVisibility.OFF, MatchMeta().pulseVisibility)
        val off = HealthConsent()
        assertFalse(off.enabled)
        assertTrue(off.isDefault)
        val on = off.changedBy("phone", true)
        assertEquals(HealthConsent(true, 1, "phone"), on)
        assertEquals(on, off.merge(on), "Standard verdrängt nie eine Änderung")
        assertEquals(on, on.merge(off))
        // nebenläufig mit gleichem Zähler: „aus“ gewinnt auf beiden Seiten
        val revoked = HealthConsent(false, 1, "watch")
        assertEquals(revoked, on.merge(revoked))
        assertEquals(revoked, revoked.merge(on))
        // kausal später gilt, auch wenn weniger restriktiv
        val again = revoked.changedBy("watch", true)
        assertEquals(again, revoked.merge(again))
        assertEquals(again, again.merge(on).merge(revoked))
    }
}
