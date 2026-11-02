package eu.advantly.match.session

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.Presets
import eu.advantly.sync.protocol.HealthMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pulsverlauf mit Konto: Belege (Protokoll ≥ 4) und Aushandlung 5 ↔ 6. */
class HealthAccountTest {
    private var pair: LifecyclePair? = null

    @AfterTest
    fun tearDown() {
        pair?.close()
    }

    private fun recordOnWatch(
        phoneVersions: IntRange,
        watchVersions: IntRange,
    ): LifecyclePair {
        val pair = LifecyclePair(phoneVersions, watchVersions).also { pair = it }.apply { activate("alice") }
        val self =
            pair.phoneData.players
                .self()!!
                .id
        pair.phoneData.players.addGuest(GUEST, "Bea", 0)
        pair.phone.setHealthConsent(true)
        val match =
            pair.phone.create(
                Presets.BADMINTON_1GAME_15.format(Discipline.SINGLES),
                Lineup(listOf(self), listOf(GUEST), self),
                MatchMeta(pulseVisibility = TelemetryVisibility.PRIVATE),
                ScorerChoice.Device(pair.watch.deviceId),
            )
        pair.settle()
        assertTrue(pair.watch.state.value.healthConsent.enabled)
        assertTrue(pair.watch.recordHeartRate(match, 1_760_000_000_000 + pair.scheduler.nowMs(), 120))
        pair.watch.finishHealthRecording(match)
        pair.settle()
        return pair
    }

    @Test
    fun bothOnProtocol6TransferTheSeriesWithStamps() {
        val pair = recordOnWatch(3..6, 3..6)
        val stored =
            pair.phoneData.health
                .allSeries()
                .single()
        assertEquals(pair.watch.deviceId, stored.sourceDeviceId)
        assertEquals(listOf(120), stored.series.bpm)
        assertEquals(
            1,
            pair.watchData.health
                .allSeries()
                .size,
            "bestätigt, bleibt bis zum Matchende zum Fortsetzen",
        )
        assertTrue(pair.watchLink.decoded().any { it.message is HealthMessage && it.message.v == 6 && it.identity != null })
    }

    @Test
    fun aPhoneOnProtocol5GetsNothingAndTheWatchKeepsItsSeries() {
        val pair = recordOnWatch(3..5, 3..6)
        assertTrue(pair.watchLink.decoded().none { it.message is HealthMessage })
        assertTrue(
            pair.phoneData.health
                .allSeries()
                .isEmpty(),
        )
        assertEquals(
            1,
            pair.watchData.health
                .allSeries()
                .size,
        )
    }

    private companion object {
        val GUEST = PlayerId("guest-bea")
    }
}
