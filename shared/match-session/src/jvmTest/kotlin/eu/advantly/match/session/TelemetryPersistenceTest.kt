package eu.advantly.match.session

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review #47 (Blocker): Sichtbarkeit und Versionsangabe werden in **einem** Schreibvorgang gespeichert; der RAM-Zustand
 * folgt erst nach dem Schreiben. Ein Fehler (z. B. Prozessabbruch, volle Platte) hinterlässt nie einen halben Stand.
 */
class TelemetryPersistenceTest {
    /** Treiber, dessen Settings-Schreibvorgänge auf Wunsch scheitern (simuliert Abbruch beim Speichern). */
    private class FailingSettingsDriver(
        private val delegate: SqlDriver = createInMemoryJvmDriver(),
    ) : SqlDriver by delegate {
        var failSettingWrites = false
        var settingWrites = 0

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (sql.contains("INTO setting")) {
                settingWrites++
                if (failSettingWrites) error("Abbruch beim Speichern")
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private val driver = FailingSettingsDriver()
    private val data = LocalData(driver)

    private fun node(): Pair<MatchSession, WireLink> {
        val link = WireLink()
        val session =
            MatchSession(
                data,
                link,
                ManualScheduler(),
                SessionConfig(DeviceId("phone"), PeerKind.PHONE),
                { 10_000 },
                RandomIdGenerator(Random(1)),
            )
        session.start()
        link.deliver(Hello(DeviceId("lab"), PeerKind.WATCH))
        link.sent.clear()
        return session to link
    }

    @Test
    fun failedWriteKeepsTheOldStateAndSendsNothing() {
        val (session, link) = node()
        driver.failSettingWrites = true
        assertFails { session.setTelemetryVisibility(TelemetryVisibility.OFF) }
        assertEquals(TelemetryVisibility.PRIVATE, session.state.value.telemetryVisibility)
        assertTrue(link.sent.none { it is MatchMetaMessage && it.telemetry != null })
        // Neustart: konsistenter Standard, kein „OFF ohne Versionsangabe“
        driver.failSettingWrites = false
        val (restarted, _) = node()
        assertEquals(session.state.value.telemetry, restarted.state.value.telemetry)
    }

    @Test
    fun oneWriteCarriesValueAndVersionAndSurvivesRestartIncludingTheHello() {
        val (session, _) = node()
        val before = driver.settingWrites
        session.setTelemetryVisibility(TelemetryVisibility.OFF)
        assertEquals(1, driver.settingWrites - before, "Wert und Versionsangabe in einem Schreibvorgang")
        val (restarted, link) = node()
        assertEquals(session.state.value.telemetry, restarted.state.value.telemetry)
        // nach dem Neustart geht die Einstellung beim Hello wieder an die Gegenstelle
        link.deliver(Hello(DeviceId("watch"), PeerKind.WATCH))
        val sent = assertNotNull(link.sent.filterIsInstance<MatchMetaMessage>().lastOrNull { it.telemetry != null })
        assertEquals("off", sent.telemetry?.visibility)
    }

    @Test
    fun unreadableStoredValueFallsBackToTheDefault() {
        data.settings.put("session.telemetry", "{kaputt")
        val (session, _) = node()
        assertEquals(TelemetryVisibility.PRIVATE, session.state.value.telemetryVisibility)
    }
}
