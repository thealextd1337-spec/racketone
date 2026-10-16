package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.TelemetryPreference
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.live.LiveTelemetryStore
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** P2-J: Einstellung über den Session-Adapter; „Gar nicht“ leert den flüchtigen Eingang des Handys sofort. */
class TelemetrySettingsPresentationTest {
    private val match = MatchId("m")

    @Test
    fun settingIsPersistedThroughTheSessionAndOffDropsBufferedValues() {
        val data = createInMemoryJvmLocalData()
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })
        val buffer = LiveTelemetryStore(MonotonicClock { 0 })
        val store = SessionMatchStore(session, telemetryStore = buffer)
        assertEquals(TelemetryVisibility.PRIVATE, store.telemetryVisibility.value)
        buffer.receive(LiveTelemetry(1, match, 140, 10))
        store.setTelemetryVisibility(TelemetryVisibility.SHARE)
        assertEquals(TelemetryVisibility.SHARE, store.telemetryVisibility.value)
        assertNotNull(buffer.forMatch(match, true), "Teilen wirkt lokal wie „Nur für mich“")
        store.setTelemetryVisibility(TelemetryVisibility.OFF)
        assertEquals(TelemetryVisibility.OFF, store.telemetryVisibility.value)
        assertNull(buffer.state.value)
        assertNull(buffer.recent.value)
        // neuer Adapter über derselben Datenbank (App-Neustart)
        val restarted = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })
        assertEquals(TelemetryVisibility.OFF, SessionMatchStore(restarted, telemetryStore = null).telemetryVisibility.value)
    }

    @Test
    fun offFromTheWatchAlsoDropsBufferedValues() {
        val session =
            MatchSession(createInMemoryJvmLocalData(), null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })
        val buffer = LiveTelemetryStore(MonotonicClock { 0 })
        val store = SessionMatchStore(session, telemetryStore = buffer)
        buffer.receive(LiveTelemetry(1, match, 140, 10))
        // wie eine jüngere Änderung von der Uhr (gleicher Pfad wie `MatchMeta.telemetry`)
        session.mergeTelemetryPreference(TelemetryPreference(TelemetryVisibility.OFF, 99))
        assertEquals(TelemetryVisibility.OFF, store.telemetryVisibility.value)
        assertNull(buffer.state.value)
    }

    @Test
    fun inMemoryPortsKeepTheChoice() {
        val store = InMemoryMatchStore()
        assertEquals(TelemetryVisibility.PRIVATE, store.telemetryVisibility.value)
        store.setTelemetryVisibility(TelemetryVisibility.OFF)
        assertEquals(TelemetryVisibility.OFF, store.telemetryVisibility.value)
        val settings = InMemoryTelemetrySettings()
        settings.setTelemetryVisibility(TelemetryVisibility.SHARE)
        assertEquals(TelemetryVisibility.SHARE, settings.telemetryVisibility.value)
    }
}
