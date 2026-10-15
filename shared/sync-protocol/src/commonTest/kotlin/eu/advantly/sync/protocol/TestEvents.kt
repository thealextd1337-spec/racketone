package eu.advantly.sync.protocol

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

val MATCH = MatchId("m1")
val FORMAT = Presets.TENNIS_SINGLES_BO3.format()
val LINEUP = Lineup(listOf(PlayerId("A1")), listOf(PlayerId("B1")), PlayerId("A1"))

/** Synthetisches Event für Tests (IDs frei wählbar, Ordnung über lamport/deviceId/id). */
fun ev(
    id: String,
    device: String,
    seq: Long,
    lamport: Long,
    parent: String?,
    payload: EventPayload,
    wallClockMs: Long = lamport * 10_000L,
): ScoreEvent = ScoreEvent(EventId(id), MATCH, DeviceId(device), seq, lamport, wallClockMs, parent?.let { EventId(it) }, payload)

fun genesis(): ScoreEvent = ev("g", "phone", 1, 1, null, MatchCreated(FORMAT, LINEUP), 0)

fun point(
    id: String,
    device: String,
    seq: Long,
    lamport: Long,
    parent: String?,
    side: Side,
    wallClockMs: Long = lamport * 10_000L,
): ScoreEvent = ev(id, device, seq, lamport, parent, PointWon(side), wallClockMs)
