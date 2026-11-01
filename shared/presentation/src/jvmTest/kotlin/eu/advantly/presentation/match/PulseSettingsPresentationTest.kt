package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.Presets
import eu.advantly.data.StoredHealthSeries
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthSeries
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan Puls H5 über den Session-Adapter: Einwilligung, Vorbelegung beim Start und Stufe im Match-Detail. */
class PulseSettingsPresentationTest {
    private val data = createInMemoryJvmLocalData()
    private val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })
    private val store = SessionMatchStore(session)
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    private fun start(pulse: TelemetryVisibility?) =
        store.start(store.setupFor(format), StartOptions(ScorerOption.THIS_PHONE, pulseVisibility = pulse))

    @Test
    fun withoutConsentEveryMatchStartsWithoutPulse() {
        assertFalse(store.healthConsent.value)
        val id = start(TelemetryVisibility.SHARE)
        assertEquals(PulseSetting(TelemetryVisibility.OFF, recorded = false), store.pulseSetting(id))
    }

    @Test
    fun consentPrefillsFromTelemetryAndTheChoiceWins() {
        store.setHealthConsent(true)
        assertTrue(store.healthConsent.value)
        assertEquals(TelemetryVisibility.PRIVATE, store.pulseSetting(start(null)).visibility, "Vorbelegung = Telemetrie")
        assertEquals(TelemetryVisibility.SHARE, store.pulseSetting(start(TelemetryVisibility.SHARE)).visibility)
        store.setTelemetryVisibility(TelemetryVisibility.OFF)
        assertEquals(TelemetryVisibility.OFF, store.pulseSetting(start(TelemetryVisibility.SHARE)).visibility, "F19 sperrt")
    }

    @Test
    fun laterChangeAndDeletionInTheMatchDetail() {
        store.setHealthConsent(true)
        val id = start(TelemetryVisibility.PRIVATE)
        val series = HealthSeries(10, 5_000, listOf(100), listOf(null), 0, HealthQuality.OK)
        data.health.upsert(StoredHealthSeries(id, DeviceId("watch"), 1, series))
        assertEquals(PulseSetting(TelemetryVisibility.PRIVATE, recorded = true), store.pulseSetting(id))
        val revision = store.healthRevision.value
        store.setPulseVisibility(id, TelemetryVisibility.SHARE)
        assertEquals(PulseSetting(TelemetryVisibility.SHARE, recorded = true), store.pulseSetting(id))
        assertTrue(store.healthRevision.value != revision)
        store.setPulseVisibility(id, TelemetryVisibility.OFF)
        assertEquals(PulseSetting(TelemetryVisibility.OFF, recorded = false), store.pulseSetting(id))
        // Widerruf löscht alles
        val other = start(TelemetryVisibility.PRIVATE)
        data.health.upsert(StoredHealthSeries(other, DeviceId("watch"), 1, series))
        store.setHealthConsent(false)
        assertFalse(store.pulseSetting(other).recorded)
    }
}
