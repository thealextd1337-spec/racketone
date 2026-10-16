package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.TelemetryPreference
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncMessage
import eu.advantly.sync.protocol.TelemetrySetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2-J: Telemetrie-Sichtbarkeit – Standard, Persistenz, Abgleich Handy ↔ Uhr und kausale Konfliktregel. */
class TelemetryVisibilitySyncTest {
    private fun Device.visibility() = session.state.value.telemetryVisibility

    private fun WireNode.visibility() = session.state.value.telemetryVisibility

    private fun WireNode.lastTelemetry(): MatchMetaMessage = link.sent.filterIsInstance<MatchMetaMessage>().last { it.telemetry != null }

    /** Alles, was [from] gesendet hat, an [to] zustellen (Link ohne Netz). */
    private fun relay(
        from: WireNode,
        to: WireNode,
    ) {
        val pending = from.link.sent.toList<SyncMessage>()
        from.link.sent.clear()
        pending.forEach(to.link::deliver)
    }

    @Test
    fun defaultIsPrivateAndNothingIsSentForIt() {
        val phone = WireNode("phone", 1)
        assertEquals(TelemetryVisibility.PRIVATE, phone.visibility())
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

    /** Beide ändern, während die Uhr offline ist (nebenläufig): „Gar nicht“ gewinnt auf beiden Geräten. */
    @Test
    fun concurrentOfflineChangesResolveRestrictivelyAndLaterDecisionsStillCount() {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        c.offline("watch")
        c["phone"].session.setTelemetryVisibility(TelemetryVisibility.OFF)
        c.settle(1_000)
        c["watch"].session.setTelemetryVisibility(TelemetryVisibility.SHARE)
        c.online("watch")
        c.settle(5_000)
        assertEquals(TelemetryVisibility.OFF, c["phone"].visibility())
        assertEquals(TelemetryVisibility.OFF, c["watch"].visibility())
        assertEquals(
            c["phone"]
                .session.state.value.telemetry,
            c["watch"]
                .session.state.value.telemetry,
        )
        // bewusste Änderung nach dem Abgleich gilt (auch weniger restriktiv) …
        c["watch"].session.setTelemetryVisibility(TelemetryVisibility.SHARE)
        c.settle()
        assertEquals(TelemetryVisibility.SHARE, c["phone"].visibility())
        // … und zurück: die Uhr stellt „Gar nicht“ ein, das Handy übernimmt sofort
        c["watch"].session.setTelemetryVisibility(TelemetryVisibility.OFF)
        c.settle()
        assertEquals(TelemetryVisibility.OFF, c["phone"].visibility())
    }

    /**
     * Review #47: Die Offline-Uhr geht eine Stunde vor und setzt „Teilen“ (ihre Uhrzeit 13:01), das Handy setzt real später
     * „Gar nicht“ (12:02). Die Wanduhr entscheidet nicht – beide Änderungen sind nebenläufig, „Gar nicht“ gewinnt überall.
     */
    @Test
    fun clockSkewCannotOverrideALaterPrivacyDecision() {
        val phone = WireNode("phone", 1)
        val watch = WireNode("watch", 2, PeerKind.WATCH)
        phone.time = 12 * HOUR
        watch.time = 13 * HOUR + MINUTE
        watch.session.setTelemetryVisibility(TelemetryVisibility.SHARE)
        phone.time = 12 * HOUR + 2 * MINUTE
        phone.session.setTelemetryVisibility(TelemetryVisibility.OFF)
        // Wiederverbinden: beide schicken ihren Stand (Reihenfolge egal)
        relay(watch, phone)
        relay(phone, watch)
        relay(watch, phone)
        assertEquals(TelemetryVisibility.OFF, phone.visibility())
        assertEquals(TelemetryVisibility.OFF, watch.visibility())
        assertEquals(phone.session.state.value.telemetry, watch.session.state.value.telemetry)
        // Spätere bewusste Änderung auf der vorgehenden Uhr gilt dagegen (sie kannte „Gar nicht“)
        watch.session.setTelemetryVisibility(TelemetryVisibility.PRIVATE)
        relay(watch, phone)
        assertEquals(TelemetryVisibility.PRIVATE, phone.visibility())
    }

    @Test
    fun olderMessageWithoutFieldStaleOrUnknownValuesKeepTheLocalValue() {
        val phone = WireNode("phone", 1)
        val watch = WireNode("watch", 2, PeerKind.WATCH)
        phone.session.setTelemetryVisibility(TelemetryVisibility.SHARE)
        val stale = phone.lastTelemetry()
        relay(phone, watch)
        watch.session.setTelemetryVisibility(TelemetryVisibility.OFF)
        assertEquals(TelemetrySetting("off", mapOf("phone" to 1, "watch" to 1)), watch.lastTelemetry().telemetry)
        // Handy ohne P2-J (kein Feld), der überholte Stand und ein unbekannter Wert ändern nichts
        watch.link.deliver(MatchMetaMessage())
        watch.link.deliver(stale)
        watch.link.deliver(MatchMetaMessage(telemetry = TelemetrySetting("friends", mapOf("phone" to 9))))
        assertEquals(TelemetryVisibility.OFF, watch.visibility())
        // eine Änderung des Handys, die „Gar nicht“ kannte, gilt
        relay(watch, phone)
        phone.session.setTelemetryVisibility(TelemetryVisibility.PRIVATE)
        relay(phone, watch)
        assertEquals(TelemetryVisibility.PRIVATE, watch.visibility())
    }

    @Test
    fun profileMergeHookFollowsTheSameRuleAndBroadcastsChanges() {
        val phone = WireNode("phone", 1)
        phone.session.setTelemetryVisibility(TelemetryVisibility.OFF)
        phone.link.sent.clear()
        // gleicher Stand mit weniger restriktivem Wert: keine Änderung, keine Nachricht
        assertFalse(phone.session.mergeTelemetryPreference(TelemetryPreference(TelemetryVisibility.SHARE, mapOf("phone" to 1))))
        assertTrue(phone.link.sent.isEmpty())
        // nebenläufiger Server-Stand ohne Kenntnis von „Gar nicht“: Vektor wird zusammengeführt, „Gar nicht“ bleibt
        phone.session.mergeTelemetryPreference(TelemetryPreference(TelemetryVisibility.SHARE, mapOf("server" to 1)))
        assertEquals(TelemetryVisibility.OFF, phone.visibility())
        // Server-Stand, der „Gar nicht“ kannte, gilt
        val later =
            phone.session.state.value.telemetry
                .changedBy("server", TelemetryVisibility.SHARE)
        assertTrue(phone.session.mergeTelemetryPreference(later))
        assertEquals(TelemetryVisibility.SHARE, phone.visibility())
        assertEquals(TelemetrySetting.of(later), phone.lastTelemetry().telemetry)
        // Setzen auf den aktuellen Wert ist ein No-op ohne Nachricht
        phone.link.sent.clear()
        phone.session.setTelemetryVisibility(TelemetryVisibility.SHARE)
        assertTrue(phone.link.sent.isEmpty())
        assertNull(MatchMetaMessage().telemetry)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
