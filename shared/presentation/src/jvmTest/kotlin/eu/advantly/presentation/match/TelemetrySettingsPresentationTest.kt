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
import eu.advantly.presentation.live.clearWhenTelemetryOff
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** P2-J: Einstellung über den Session-Adapter; „Gar nicht“ leert den flüchtigen Eingang des Handys prozessweit. */
class TelemetrySettingsPresentationTest {
    private val match = MatchId("m")

    private fun session(data: eu.advantly.data.LocalData = createInMemoryJvmLocalData()) =
        MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })

    /** Wie eine Änderung der Uhr, die die des Handys kannte (gleicher Pfad wie `MatchMeta.telemetry`). */
    private fun MatchSession.offFromWatch() = mergeTelemetryPreference(state.value.telemetry.changedBy("watch", TelemetryVisibility.OFF))

    @Test
    fun settingIsPersistedThroughTheSessionAndOffDropsBufferedValues() {
        val data = createInMemoryJvmLocalData()
        val session = session(data)
        val buffer = LiveTelemetryStore(MonotonicClock { 0 })
        buffer.clearWhenTelemetryOff(session)
        val store = SessionMatchStore(session)
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
        assertEquals(TelemetryVisibility.OFF, SessionMatchStore(session(data)).telemetryVisibility.value)
    }

    /** Review #47: Activity beendet (Adapter geschlossen), Prozess läuft für den Listener-Service weiter. */
    @Test
    fun remoteOffClearsTheBufferEvenWhenTheUiAdapterIsClosed() {
        val session = session()
        val buffer = LiveTelemetryStore(MonotonicClock { 0 })
        buffer.clearWhenTelemetryOff(session)
        SessionMatchStore(session).close()
        buffer.receive(LiveTelemetry(1, match, 140, 10))
        assertNotNull(buffer.state.value)
        session.offFromWatch()
        assertEquals(TelemetryVisibility.OFF, session.state.value.telemetryVisibility)
        assertNull(buffer.state.value)
        assertNull(buffer.recent.value)
    }

    @Test
    fun releasedCouplingNoLongerClears() {
        val session = session()
        val buffer = LiveTelemetryStore(MonotonicClock { 0 })
        val release = buffer.clearWhenTelemetryOff(session)
        release()
        buffer.receive(LiveTelemetry(1, match, 140, 10))
        session.mergeTelemetryPreference(TelemetryPreference(TelemetryVisibility.OFF, 1, "watch"))
        assertNotNull(buffer.state.value)
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
