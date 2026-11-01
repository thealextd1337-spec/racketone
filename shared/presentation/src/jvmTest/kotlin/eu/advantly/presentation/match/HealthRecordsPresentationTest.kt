package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import eu.advantly.match.session.WallClock as SessionClock

/** Plan Puls H8: Höchstwerte und Pulsverlauf aus `HealthRepository` über den Session-Adapter. */
class HealthRecordsPresentationTest {
    private var now = 1_760_000_000_000L
    private val data: LocalData = createInMemoryJvmLocalData()
    private val session =
        MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), SessionClock { now })
    private val store = SessionMatchStore(session, WallClock { now }, CountingIds())
    private val watch = DeviceId("watch")

    /** Badminton Bo3 bis 21: 42 Punkte für A im Abstand von 10 s; Satz 1 = Punkt 1…21, Satz 2 = Punkt 22…42. */
    private fun playMatch(abandonAfterFirstSet: Boolean = false): Pair<MatchId, Long> {
        val start = now
        val id = store.start(store.setupFor(Presets.BADMINTON_SINGLES_BO3_21.format()).copy(meta = NO_SERVE_TRACKING))
        repeat(if (abandonAfterFirstSet) 21 else 42) {
            now += 10_000
            store.play("A")
        }
        if (abandonAfterFirstSet) {
            now += 10_000
            store.abandonMatch()
        }
        store.dismiss()
        now += 60_000
        return id to start
    }

    /** Schritte je 5-s-Bucket ab Genesis: [perBucket] in Satz 1, [perBucketSet2] in Satz 2. */
    private fun steps(
        perBucket: Long,
        perBucketSet2: Long,
        gapAt: Int? = null,
    ): List<Long?> {
        var total = 0L
        return List(90) { i ->
            total += if (i <= 43) perBucket else perBucketSet2
            if (i == gapAt) null else total
        }
    }

    private fun put(
        match: MatchId,
        startMs: Long,
        bpm: Int,
        steps: List<Long?>,
        source: DeviceId = watch,
        revision: Long = 1,
        offsetMs: Long = 0,
    ) = data.health.upsert(
        StoredHealthSeries(
            match,
            source,
            revision,
            HealthSeries(startMs, 5_000, List(steps.size) { bpm }, steps, offsetMs, HealthQuality.OK),
        ),
    )

    /** Wie eine neue `dataRevision` der Session (z. B. Serie der Uhr empfangen). */
    private var fakeRevision = 1_000L

    private fun dataChanged() = store.onSessionState(session.state.value.copy(dataRevision = ++fakeRevision))

    @Test
    fun recordsCoverRatedMatchesSetsAndGaps() {
        assertEquals(HealthRecords(), store.records.value.copy(revision = 0))
        val (first, firstStart) = playMatch()
        val (second, secondStart) = playMatch()
        val (abandoned, abandonedStart) = playMatch(abandonAfterFirstSet = true)

        // Satz 1 von [first]: Buckets 2…41 → 40 × 10; Satz 2: Buckets 44…83, Lücke im letzten Bucket → 39 × 30
        put(first, firstStart, 150, steps(10, 30, gapAt = 83))
        put(second, secondStart, 171, steps(20, 5))
        // Abbruch ohne Wertung zählt nicht, auch mit Rekordwerten
        put(abandoned, abandonedStart, 200, steps(500, 500))
        dataChanged()
        assertEquals(HealthRecords(171, 1_170, store.records.value.revision), store.records.value)

        // Gelöschtes Match fällt heraus
        val before = store.records.value.revision
        assertTrue(store.delete(second))
        assertEquals(150, store.records.value.maxBpm)
        assertNotEquals(before, store.records.value.revision)
    }

    @Test
    fun unchangedSeriesAreNotRecomputed() {
        val (id, start) = playMatch()
        put(id, start, 140, steps(1, 1))
        dataChanged()
        val revision = store.records.value.revision
        dataChanged()
        assertEquals(revision, store.records.value.revision, "gleiche Revisionen und Matches → kein Neurechnen")
        put(id, start, 160, steps(1, 1), revision = 2)
        dataChanged()
        assertEquals(160, store.records.value.maxBpm)
        assertEquals(revision + 1, store.records.value.revision)
    }

    @Test
    fun pulseTrackUsesTheBestSourceInPhoneTime() {
        val (id, start) = playMatch()
        assertNull(store.pulseTrack(id))
        // Uhr 3 s vor: wird verschoben; zweite Quelle mit weniger Werten verliert
        put(id, start + 3_000, 130, steps(1, 1), offsetMs = 3_000)
        put(id, start, 120, steps(1, 1).take(10), source = DeviceId("other"))
        val track = assertNotNull(store.pulseTrack(id))
        assertEquals(start, track.startMs)
        assertEquals(130, track.bpm.first())
        assertEquals(HealthQuality.OK, track.quality)

        // Serie fast ganz nach dem Matchende → Hinweis „Lücken“
        put(id, start + 400_000, 130, steps(1, 1), revision = 2)
        assertEquals(HealthQuality.GAPS, store.pulseTrack(id)!!.quality)
    }
}
