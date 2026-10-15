package eu.advantly.wear.sync

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.sync.protocol.LiveTelemetryCodec
import eu.advantly.sync.protocol.LiveTelemetrySubscription
import eu.advantly.wear.WatchHarness
import eu.advantly.wear.workout.ExerciseSelection
import eu.advantly.wear.workout.WatchExerciseType
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutSport
import eu.advantly.wear.workout.WorkoutUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTelemetrySenderTest {
    private val harness =
        WatchHarness().apply {
            watch.create(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
            )
            settle()
        }
    private val match =
        harness.watch.state.value.match!!
            .id
    private var now = 0L
    private val subscriptions = LiveTelemetrySubscriptions()
    private val sent = mutableListOf<LiveTelemetry>()
    private val sender =
        LiveTelemetrySender(
            state = { harness.watch.state.value },
            messages =
                TelemetryMessageClient { node, path, payload ->
                    assertEquals("phone", node)
                    assertEquals(LiveTelemetry.MESSAGE_PATH, path)
                    sent += LiveTelemetryCodec.decode(payload)!!
                },
            subscriptions = subscriptions,
            nowMs = { now },
        )
    private val workout =
        WorkoutUiState(
            phase = WorkoutPhase.ACTIVE,
            selection = ExerciseSelection(WorkoutSport.TENNIS, WatchExerciseType.TENNIS, false, emptySet()),
            heartRateBpm = 140,
            steps = 50,
        )

    private fun subscribe(at: Long = now) =
        subscriptions.receive(LiveTelemetrySubscription(1, match), "phone", harness.watch.state.value, at)

    @Test
    fun threeUpdatesOverTenSecondsSendExactlyTwiceWithSingleThrottle() {
        subscribe()
        sender.onUpdate(workout, true)
        now = 4_999
        sender.onUpdate(workout.copy(steps = 51), true)
        now = 10_000
        sender.onUpdate(workout.copy(steps = 52), true)
        assertEquals(listOf(50L, 52L), sent.map { it.steps })
    }

    @Test
    fun subscriptionKeepaliveExpiryAndPauseGateSending() {
        sender.onUpdate(workout, true)
        assertEquals(0, sent.size)
        subscribe()
        sender.onUpdate(workout, false)
        assertEquals(0, sent.size)
        sender.onUpdate(workout, true)
        now = 20_000
        subscribe()
        now = 50_000
        sender.onUpdate(workout, true)
        now = 50_001
        sender.onUpdate(workout, true)
        now = 60_000
        sender.onUpdate(workout, true)
        assertEquals(2, sent.size)
    }

    @Test
    fun foreignWrongMatchDisabledAndOldPeerSubscriptionsNeverEnableSending() {
        val state = harness.watch.state.value
        val request = LiveTelemetrySubscription(1, match)
        subscriptions.receive(request, "foreign", state, now)
        subscriptions.receive(request.copy(matchId = MatchId("other")), "phone", state, now)
        subscriptions.receive(
            request,
            "phone",
            state.copy(match = state.match!!.copy(meta = state.match!!.meta.copy(watchWorkout = false))),
            now,
        )
        subscriptions.receive(
            request,
            "phone",
            state.copy(
                sync =
                    state.sync.copy(
                        peers =
                            state.sync.peers.map {
                                it.copy(compatibility = CompatibilityStatus.PEER_TOO_OLD)
                            },
                    ),
            ),
            now,
        )
        sender.onUpdate(workout, true)
        assertEquals(0, sent.size)
    }
}
