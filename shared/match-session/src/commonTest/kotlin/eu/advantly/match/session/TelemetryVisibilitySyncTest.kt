package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.TelemetryPreference
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.TelemetrySetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2-J: Telemetrie-Sichtbarkeit – Standard, Persistenz, Abgleich Handy ↔ Uhr und Konfliktregel. */
class TelemetryVisibilitySyncTest {
    private fun Device.visibility() = session.state.value.telemetryVisibility

    @Test
    fun defaultIsPrivateAndNothingIsSentForIt() {
        val phone = WireNode("phone", 1)
        assertEquals(TelemetryVisibility.PRIVATE, phone.session.state.value.telemetryVisibility)
        assertEquals(TelemetryPreference(), phone.session.state.value.telemetry)
        phone.link.deliver(Hello(DeviceId("lab"), PeerKind.WATCH))
        assertTrue(
            phone.link.sent
                .filterIsInstance<MatchMetaMessage>()
                .all { it.telemetry == null },
        )
    }

    @Test
    fun phoneChangeReachesWatchAndSurvivesRestart() {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        c["phone"].session.setTelemetryVisibility(TelemetryVisibility.OFF)
        c.settle()
        assertEquals(TelemetryVisibility.OFF, c["phone"].visibility())
        assertEquals(TelemetryVisibility.OFF, c["watch"].visibility())
        assertEquals(
            c["phone"]
                .session.state.value.telemetry,
            c["watch"]
                .session.state.value.telemetry,
        )
        // Neustart der Uhr (gleiche Datenbank, ohne Link): Wert kommt aus den Settings
        val restarted = MatchSession(c["watch"].data, null, ManualScheduler(), SessionConfig(DeviceId("watch"), PeerKind.WATCH))
        assertEquals(
            c["watch"]
                .session.state.value.telemetry,
            restarted.state.value.telemetry,
        )
    }

    @Test
    fun watchMayChangeItTooAndChangesWhileOfflineConvergeByLatestChange() {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        c.offline("watch")
        c["phone"].session.setTelemetryVisibility(TelemetryVisibility.OFF)
        c.settle(1_000)
        // später auf der Uhr geändert → gewinnt nach dem Wiederverbinden auf beiden Geräten
        c["watch"].session.setTelemetryVisibility(TelemetryVisibility.SHARE)
        c.online("watch")
        c.settle(5_000)
        assertEquals(TelemetryVisibility.SHARE, c["phone"].visibility())
        assertEquals(TelemetryVisibility.SHARE, c["watch"].visibility())
        // und zurück: die Uhr stellt „Gar nicht“ ein, das Handy übernimmt sofort
        c["watch"].session.setTelemetryVisibility(TelemetryVisibility.OFF)
        c.settle()
        assertEquals(TelemetryVisibility.OFF, c["phone"].visibility())
    }

    @Test
    fun olderMessageWithoutFieldOrOlderChangeKeepsTheLocalValue() {
        val watch = WireNode("watch", 2, PeerKind.WATCH)
        watch.link.deliver(Hello(DeviceId("lab"), PeerKind.PHONE))
        watch.time = 50_000
        watch.session.setTelemetryVisibility(TelemetryVisibility.OFF)
        val sent =
            watch.link.sent
                .filterIsInstance<MatchMetaMessage>()
                .last()
        assertEquals(TelemetrySetting("off", 50_000), sent.telemetry)
        // Handy ohne P2-J (kein Feld) und eine ältere Änderung ändern nichts
        watch.link.deliver(MatchMetaMessage())
        watch.link.deliver(MatchMetaMessage(telemetry = TelemetrySetting("share", 40_000)))
        assertEquals(TelemetryVisibility.OFF, watch.session.state.value.telemetryVisibility)
        // unbekannter Wert einer künftigen Version ebenfalls nicht
        watch.link.deliver(MatchMetaMessage(telemetry = TelemetrySetting("friends", 90_000)))
        assertEquals(TelemetryVisibility.OFF, watch.session.state.value.telemetryVisibility)
        // jüngere Änderung vom Handy gewinnt
        watch.link.deliver(MatchMetaMessage(telemetry = TelemetrySetting("private", 60_000)))
        assertEquals(TelemetryPreference(TelemetryVisibility.PRIVATE, 60_000), watch.session.state.value.telemetry)
    }

    @Test
    fun sameTimestampResolvesToTheMoreRestrictiveValueOnBothSides() {
        val phone = WireNode("phone", 1)
        phone.time = 70_000
        phone.session.setTelemetryVisibility(TelemetryVisibility.SHARE)
        phone.link.deliver(MatchMetaMessage(telemetry = TelemetrySetting("off", 70_000)))
        assertEquals(TelemetryVisibility.OFF, phone.session.state.value.telemetryVisibility)
    }

    @Test
    fun profileMergeHookFollowsTheSameRuleAndBroadcastsChanges() {
        val phone = WireNode("phone", 1)
        phone.time = 20_000
        phone.session.setTelemetryVisibility(TelemetryVisibility.OFF)
        phone.link.sent.clear()
        assertFalse(phone.session.mergeTelemetryPreference(TelemetryPreference(TelemetryVisibility.SHARE, 10_000)))
        assertTrue(phone.link.sent.isEmpty())
        assertTrue(phone.session.mergeTelemetryPreference(TelemetryPreference(TelemetryVisibility.SHARE, 30_000)))
        assertEquals(TelemetryVisibility.SHARE, phone.session.state.value.telemetryVisibility)
        assertEquals(
            TelemetrySetting("share", 30_000),
            phone.link.sent
                .filterIsInstance<MatchMetaMessage>()
                .single()
                .telemetry,
        )
        // Setzen auf den aktuellen Wert ist ein No-op ohne Nachricht
        phone.link.sent.clear()
        phone.session.setTelemetryVisibility(TelemetryVisibility.SHARE)
        assertTrue(phone.link.sent.isEmpty())
        assertNull(MatchMetaMessage().telemetry)
    }
}
