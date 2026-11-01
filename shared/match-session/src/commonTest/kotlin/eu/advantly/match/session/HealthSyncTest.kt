package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.Presets
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.HealthAck
import eu.advantly.sync.protocol.HealthMessage
import eu.advantly.sync.protocol.HealthSeriesMessage
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan Puls H5/H6: Einwilligung, Gating, Übertragung Uhr → Handy (Protokoll 6), Ack, Löschung, Haltedauer und Offset. */
class HealthSyncTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val watchScores = ScorerChoice.Device(DeviceId("watch"))
    private val pulse = MatchMeta(pulseVisibility = TelemetryVisibility.PRIVATE)
    private val watchId = DeviceId("watch")
    private val lab = DeviceId("lab")

    private fun Device.consent() = session.state.value.healthConsent.enabled

    private fun Device.own() = data.health.forSource(watchId)

    /** Handy und Uhr mit Einwilligung und einem Match, das die Uhr zählt. */
    private fun recordingCluster(meta: MatchMeta = pulse): Pair<Cluster, MatchId> {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        c["phone"].session.setHealthConsent(true)
        val id = c["phone"].session.create(format, SINGLES, meta, scorer = watchScores)
        c.settle()
        return c to id
    }

    @Test
    fun consentDefaultsOffSyncsAndTheSeriesReachesThePhoneOnce() {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        assertFalse(c["phone"].consent())
        assertFalse(c["watch"].consent())
        c["phone"].session.setHealthConsent(true)
        c.settle()
        assertTrue(c["watch"].consent())
        val id = c["phone"].session.create(format, SINGLES, pulse, scorer = watchScores)
        c.settle()
        val watch = c["watch"].session
        val t = c.clock.nowMs()
        assertTrue(watch.recordSteps(id, t, 0))
        assertTrue(watch.recordHeartRate(id, t, 110))
        assertTrue(watch.recordHeartRate(id, t + 6_000, 120))
        assertTrue(watch.recordSteps(id, t + 6_000, 9))
        assertTrue(watch.isRecordingHealth(id))
        assertEquals(1, c["watch"].own().size, "erster Wert ist sofort gesichert")
        assertTrue(
            c["phone"]
                .data.health
                .forMatch(id)
                .isEmpty(),
            "während der Aufnahme geht nichts ans Handy",
        )
        watch.finishHealthRecording(id)
        c.settle()
        val stored =
            c["phone"]
                .data.health
                .forMatch(id)
                .single()
        assertEquals(watchId, stored.sourceDeviceId)
        assertEquals(listOf(110, 120), stored.series.bpm)
        assertEquals(listOf(0L, 9L), stored.series.steps)
        assertEquals(0, stored.series.clockOffsetMs, "Uhr zählt selbst")
        assertTrue(c["watch"].own().isEmpty(), "nach dem Ack gelöscht")
        assertFalse(watch.isRecordingHealth(id))
    }

    @Test
    fun revokingTheConsentDeletesEverythingOnBothDevices() {
        val (c, id) = recordingCluster()
        val watch = c["watch"].session
        watch.recordHeartRate(id, c.clock.nowMs(), 100)
        watch.finishHealthRecording(id)
        c.settle()
        assertEquals(
            1,
            c["phone"]
                .data.health
                .allSeries()
                .size,
        )
        // weitere Aufnahme, während die Uhr offline ist: bleibt dort unbestätigt liegen
        c.offline("watch")
        assertTrue(watch.recordHeartRate(id, c.clock.nowMs(), 130))
        watch.finishHealthRecording(id)
        c.settle()
        assertEquals(1, c["watch"].own().size)
        c["phone"].session.setHealthConsent(false)
        assertTrue(
            c["phone"]
                .data.health
                .allSeries()
                .isEmpty(),
        )
        c.online("watch")
        c.settle(5_000)
        assertFalse(c["watch"].consent())
        assertTrue(
            c["watch"]
                .data.health
                .allSeries()
                .isEmpty(),
        )
        assertTrue(
            c["phone"]
                .data.health
                .allSeries()
                .isEmpty(),
            "die alte Serie der Uhr kommt nicht zurück",
        )
    }

    @Test
    fun nothingIsRecordedWithoutConsentWithTelemetryOffOrForAMatchWithoutPulse() {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = c["phone"].session
        val watch = c["watch"].session
        val id = phone.create(format, SINGLES, pulse, scorer = watchScores)
        c.settle()
        assertEquals(TelemetryVisibility.OFF, phone.defaultPulseVisibility())
        assertFalse(watch.recordHeartRate(id, c.clock.nowMs(), 100), "ohne Einwilligung")
        phone.setHealthConsent(true)
        phone.setTelemetryVisibility(TelemetryVisibility.OFF)
        c.settle()
        assertEquals(TelemetryVisibility.OFF, phone.defaultPulseVisibility())
        assertFalse(watch.recordHeartRate(id, c.clock.nowMs(), 100), "F19 „Gar nicht“ sperrt")
        assertFalse(watch.isRecordingHealth(id))
        phone.setTelemetryVisibility(TelemetryVisibility.SHARE)
        c.settle()
        assertEquals(TelemetryVisibility.SHARE, phone.defaultPulseVisibility())
        assertTrue(
            c["watch"]
                .data.health
                .allSeries()
                .isEmpty(),
        )
        val off = phone.create(format, SINGLES, MatchMeta(), scorer = watchScores)
        c.settle()
        assertFalse(watch.recordHeartRate(off, c.clock.nowMs(), 100), "Stufe „Gar nicht“")
        assertTrue(
            c["watch"]
                .data.health
                .allSeries()
                .isEmpty(),
        )
    }

    @Test
    fun phoneDeletingTheMatchPulseDeletesAndStopsTheWatchRecording() {
        val (c, id) = recordingCluster()
        val watch = c["watch"].session
        watch.recordHeartRate(id, c.clock.nowMs(), 100)
        assertEquals(1, c["watch"].own().size)
        c["phone"].session.setPulseVisibility(id, TelemetryVisibility.OFF)
        c.settle()
        assertEquals(TelemetryVisibility.OFF, c["watch"].session.pulseVisibility(id))
        assertTrue(c["watch"].own().isEmpty())
        assertFalse(watch.isRecordingHealth(id))
        assertFalse(watch.recordHeartRate(id, c.clock.nowMs(), 100))
        watch.finishHealthRecording(id)
        c.settle()
        assertTrue(
            c["phone"]
                .data.health
                .allSeries()
                .isEmpty(),
        )
    }

    @Test
    fun watchDeletingTheMatchPulseDeletesThePhoneCopy() {
        val (c, id) = recordingCluster()
        val watch = c["watch"].session
        watch.recordHeartRate(id, c.clock.nowMs(), 100)
        watch.finishHealthRecording(id)
        c.settle()
        assertEquals(
            1,
            c["phone"]
                .data.health
                .forMatch(id)
                .size,
        )
        c["phone"].session.setPulseVisibility(id, TelemetryVisibility.SHARE)
        assertEquals(TelemetryVisibility.SHARE, c["phone"].session.pulseVisibility(id))
        watch.setPulseVisibility(id, TelemetryVisibility.OFF)
        c.settle()
        assertTrue(
            c["phone"]
                .data.health
                .forMatch(id)
                .isEmpty(),
        )
        assertEquals(TelemetryVisibility.OFF, c["phone"].session.pulseVisibility(id))
    }

    @Test
    fun phoneStoresIdempotentlyByRevisionAndRejectsForeignOrDeletedData() {
        val phone = WireNode("phone", 1)
        phone.session.setHealthConsent(true)
        val id = phone.session.create(format, SINGLES, pulse)
        phone.link.deliver(Hello(lab, PeerKind.WATCH, healthProtocol = 1))
        phone.link.sent.clear()
        val series = HealthSeriesMessage(id, lab, 5, phone.time, bpm = listOf(100, null), steps = listOf(0, 4), quality = "OK")
        repeat(2) { phone.link.deliver(series) }
        assertEquals(listOf(HealthAck(id, lab, 5), HealthAck(id, lab, 5)), phone.link.sent.filterIsInstance<HealthAck>())
        assertEquals(
            listOf(100, null),
            phone.data.health
                .forMatch(id)
                .single()
                .series.bpm,
        )
        phone.link.deliver(series.copy(revision = 4, bpm = listOf(150, 150)))
        assertEquals(
            5,
            phone.data.health
                .forMatch(id)
                .single()
                .revision,
            "ältere Revision ändert nichts",
        )
        phone.link.deliver(series.copy(revision = 6, bpm = listOf(90, 91)))
        assertEquals(
            listOf(90, 91),
            phone.data.health
                .forMatch(id)
                .single()
                .series.bpm,
        )
        phone.link.sent.clear()
        // fremde Quelle, unplausible Werte, unbekanntes Match: nichts speichern, nichts bestätigen
        phone.link.deliver(series.copy(sourceDeviceId = DeviceId("other"), revision = 9))
        phone.link.deliver(series.copy(revision = 10, bpm = listOf(300, null)))
        phone.link.deliver(series.copy(matchId = MatchId("unknown"), revision = 11))
        assertTrue(phone.link.sent.isEmpty())
        assertEquals(
            6,
            phone.data.health
                .forMatch(id)
                .single()
                .revision,
        )
        // gelöscht („Gar nicht“): eine verspätete Serie wird abgelehnt und die Uhr zum Löschen aufgefordert
        phone.session.setPulseVisibility(id, TelemetryVisibility.OFF)
        assertTrue(
            phone.data.health
                .forMatch(id)
                .isEmpty(),
        )
        phone.link.sent.clear()
        phone.link.deliver(series.copy(revision = 20))
        assertTrue(
            phone.data.health
                .forMatch(id)
                .isEmpty(),
        )
        assertTrue(
            phone.link.sent
                .filterIsInstance<HealthSeriesMessage>()
                .single()
                .deleted,
        )
    }

    @Test
    fun deleteAllRejectsSeriesThatStartedBefore() {
        val phone = WireNode("phone", 1)
        phone.session.setHealthConsent(true)
        val id = phone.session.create(format, SINGLES, pulse)
        phone.link.deliver(Hello(lab, PeerKind.WATCH, healthProtocol = 1))
        val series = HealthSeriesMessage(id, lab, 1, phone.time, bpm = listOf(100), steps = listOf(null), quality = "OK")
        phone.link.deliver(series)
        assertEquals(
            1,
            phone.data.health
                .allSeries()
                .size,
        )
        phone.time += 1_000
        phone.session.deleteAllHealthData()
        assertTrue(
            phone.data.health
                .allSeries()
                .isEmpty(),
        )
        phone.link.sent.clear()
        phone.link.deliver(series.copy(revision = 2))
        assertTrue(
            phone.data.health
                .allSeries()
                .isEmpty(),
        )
        assertTrue(
            phone.link.sent
                .filterIsInstance<HealthSeriesMessage>()
                .single()
                .deleted,
        )
    }

    /** Wire-Kompatibilität 5 ↔ 6: Eine Gegenstelle ohne Ankündigung bekommt keine Gesundheitsnachricht. */
    @Test
    fun olderPeersGetNothingAndAckOrSevenDaysEndTheWatchCopy() {
        val watch = WireNode("watch", 2, PeerKind.WATCH)
        watch.session.setHealthConsent(true)
        val id = watch.session.create(format, SINGLES, pulse)
        assertTrue(watch.session.recordHeartRate(id, watch.time, 100))
        watch.session.finishHealthRecording(id)
        watch.link.sent.clear()
        watch.link.deliver(Hello(DeviceId("phone"), PeerKind.PHONE, v = 5))
        assertTrue(watch.link.sent.none { it is HealthMessage }, "Handy vor Protokoll 6")
        watch.link.deliver(Hello(DeviceId("phone"), PeerKind.PHONE, healthProtocol = 1))
        val sent =
            watch.link.sent
                .filterIsInstance<HealthSeriesMessage>()
                .single()
        assertEquals(listOf(100), sent.bpm)
        watch.link.deliver(HealthAck(id, watchId, sent.revision - 1))
        assertEquals(
            1,
            watch.data.health
                .forSource(watchId)
                .size,
            "Ack einer älteren Revision löscht nicht",
        )
        watch.link.deliver(HealthAck(id, watchId, sent.revision))
        assertTrue(
            watch.data.health
                .forSource(watchId)
                .isEmpty(),
        )
        // ohne Ack: nach 7 Tagen weg, auch ohne Senden
        val next = watch.session.create(format, SINGLES, pulse)
        watch.session.recordHeartRate(next, watch.time, 100)
        watch.session.finishHealthRecording(next)
        watch.time += 7L * 24 * 60 * 60 * 1000 + 1
        watch.link.sent.clear()
        watch.link.deliver(Hello(DeviceId("phone"), PeerKind.PHONE, healthProtocol = 1))
        assertTrue(watch.link.sent.none { it is HealthMessage })
        assertTrue(
            watch.data.health
                .allSeries()
                .isEmpty(),
        )
    }

    /** Prozess-Tod mitten im Match: Die gesicherte Serie geht nicht halb raus und wird nach dem Neustart fortgesetzt. */
    @Test
    fun interruptedRecordingIsNotSentEarlyAndResumesAfterRestart() {
        val watch = WireNode("watch", 2, PeerKind.WATCH)
        watch.session.setHealthConsent(true)
        val id = watch.session.create(format, SINGLES, pulse)
        assertTrue(watch.session.recordHeartRate(id, watch.time, 100))
        val link = WireLink()
        val restarted =
            MatchSession(watch.data, link, ManualScheduler(), SessionConfig(watchId, PeerKind.WATCH), WallClock { watch.time + 10_000 })
        restarted.start()
        link.deliver(Hello(DeviceId("phone"), PeerKind.PHONE, healthProtocol = 1))
        assertTrue(link.sent.none { it is HealthMessage }, "laufendes Match: noch nicht senden")
        assertTrue(restarted.recordHeartRate(id, watch.time + 10_000, 140))
        restarted.finishHealthRecording(id)
        assertEquals(
            listOf(100, null, 140),
            link.sent
                .filterIsInstance<HealthSeriesMessage>()
                .single()
                .bpm,
        )
    }

    @Test
    fun aSessionBeforeProtocol6NeitherAnnouncesNorSends() {
        val link = WireLink()
        val session =
            MatchSession(
                testLocalData(),
                link,
                ManualScheduler(),
                SessionConfig(DeviceId("old"), PeerKind.WATCH, protocolVersions = 3..5),
                WallClock { 10_000 },
            )
        session.start()
        session.setHealthConsent(true)
        val id = session.create(format, SINGLES, pulse)
        session.recordHeartRate(id, 10_000, 100)
        session.finishHealthRecording(id)
        link.deliver(Hello(DeviceId("phone"), PeerKind.PHONE, healthProtocol = 1))
        assertNull(
            link.sent
                .filterIsInstance<Hello>()
                .last()
                .healthProtocol,
        )
        assertTrue(link.sent.none { it is HealthMessage })
    }

    /** Plan Puls §3.6: Zählt das Handy, ist der Offset das Minimum aus Empfang − Event-Zeit (Uhr geht 3 s vor). */
    @Test
    fun clockOffsetIsTheMinimumDelayOfPhoneEvents() {
        val phone = WireNode("phone", 1)
        val watch = WireNode("watch", 2, PeerKind.WATCH)
        phone.session.setHealthConsent(true)
        relay(phone, watch)
        val id = phone.session.create(format, SINGLES, pulse)
        watch.time = phone.time + 3_000
        relay(phone, watch)
        assertTrue(watch.session.recordHeartRate(id, watch.time, 100))
        for (latency in listOf(400L, 20L, 90L)) {
            phone.points(Side.A, 1)
            watch.time = phone.time - 1_000 + 3_000 + latency // points() stellt die Handyuhr nach dem Punkt 1 s weiter
            relay(phone, watch)
        }
        // nachgelieferter Rückstand (Minuten alt) ist keine Messung
        phone.points(Side.B, 1)
        watch.time = phone.time - 1_000 + 3_000 + 5 * 60_000
        relay(phone, watch)
        watch.session.finishHealthRecording(id)
        assertEquals(
            3_020,
            watch.data.health
                .forSource(watchId)
                .single()
                .series.clockOffsetMs,
        )
    }

    private fun relay(
        from: WireNode,
        to: WireNode,
    ) {
        val pending = from.link.sent.toList<SyncMessage>()
        from.link.sent.clear()
        pending.forEach(to.link::deliver)
    }
}
