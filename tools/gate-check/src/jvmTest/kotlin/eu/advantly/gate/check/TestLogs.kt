package eu.advantly.gate.check

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.data.StoredEvent
import eu.advantly.data.toStoredEvent

const val PHONE = "phone-device"
const val WATCH = "watch-device"
const val MATCH = "match-0001"

/**
 * Baut ein synthetisches Match-Log wie die Session: lückenlose `deviceSeq` je Gerät, steigende Lamport-Zeit,
 * `parentId` = vorheriges Event.
 */
class LogBuilder(
    private val matchId: String = MATCH,
    scorer: String = WATCH,
    firstServer: String = "A1",
) {
    private val seqs = HashMap<String, Long>()
    private var lamport = 0L
    private var parent: EventId? = null
    val events = mutableListOf<ScoreEvent>()

    init {
        add(
            PHONE,
            MatchCreated(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(PlayerId("A1")), listOf(PlayerId("B1")), PlayerId(firstServer)),
                scorerDeviceId = DeviceId(scorer),
            ),
        )
    }

    fun add(
        device: String,
        payload: EventPayload,
    ): ScoreEvent {
        val seq = (seqs[device] ?: 0) + 1
        seqs[device] = seq
        lamport++
        val event =
            ScoreEvent(
                EventId("e-$device-$seq"),
                MatchId(matchId),
                DeviceId(device),
                seq,
                lamport,
                1_000_000L + lamport * 30_000L,
                parent,
                payload,
            )
        parent = event.id
        events += event
        return event
    }

    fun points(
        device: String,
        vararg sides: Side,
    ) = sides.forEach { add(device, PointWon(it)) }

    fun handover(
        by: String,
        to: String,
    ) = add(by, ScorerChanged(DeviceId(to)))

    fun stored(confirmed: Boolean = true): List<StoredEvent> = events.map { it.toStoredEvent().copy(confirmed = confirmed) }
}

fun snapshot(
    label: String,
    own: String,
    vararg matches: Pair<String, List<StoredEvent>>,
    tombstones: Map<String, Long> = emptyMap(),
): DeviceSnapshot =
    DeviceSnapshot(
        label = label,
        ownDeviceId = own,
        knownKinds = mapOf(PHONE to "PHONE", WATCH to "WATCH"),
        matches =
            matches.associate { (id, events) ->
                id to MatchData(events, events.filterNot { it.confirmed }.map { it.id }.toSet(), null)
            },
        tombstones = tombstones,
    )
