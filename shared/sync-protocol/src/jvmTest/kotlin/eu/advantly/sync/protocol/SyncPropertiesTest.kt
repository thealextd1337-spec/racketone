package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchEnded
import eu.advantly.core.scoring.Paused
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Undo
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Property-Tests für Anti-Entropie und Codec (fester Seed, siehe CLAUDE.md „Tests“). */
@OptIn(ExperimentalKotest::class)
class SyncPropertiesTest {
    private val config = PropTestConfig(seed = 2026L)
    private val devices = listOf("phone", "watch", "watch2").map { DeviceId(it) }

    /** Zufällige Teilmenge der Sequenzen 1..30 je Gerät. */
    private fun arbSeqs() = Arb.list(Arb.set(Arb.long(1L..30L), 0..30), 3..3)

    private fun toSeqs(sets: List<Set<Long>>): Set<DeviceSeq> = sets.flatMapIndexed { i, s -> s.map { DeviceSeq(devices[i], it) } }.toSet()

    @Test
    fun missingRangesAreExactlyTheUncoveredLocalSequences() {
        runBlocking {
            checkAll(config, arbSeqs(), arbSeqs()) { localSets, remoteSets ->
                val local = toSeqs(localSets)
                val remote = VersionVector.fromSequences(toSeqs(remoteSets))
                val ranges = AntiEntropy.missingRanges(local, remote)
                val expanded = ranges.flatMap { r -> (r.first..r.last).map { DeviceSeq(r.deviceId, it) } }
                assertEquals(expanded.size, expanded.toSet().size, "Bereiche überlappen")
                assertEquals(local.filter { it.seq > remote[it.deviceId] }.toSet(), expanded.toSet())
                // sortiert und maximal zusammengefasst
                ranges.zipWithNext().forEach { (a, b) ->
                    assertTrue(a.deviceId < b.deviceId || (a.deviceId == b.deviceId && a.last + 1 < b.first), "$a / $b")
                }
            }
        }
    }

    /** Zwei Geräte: ein Abgleich nach Protokoll (Summary → fehlende Events → ggf. Summary(reply) → Rest) reicht. */
    @Test
    fun oneExchangeMakesTwoDevicesEqual() {
        runBlocking {
            checkAll(config, arbSeqs(), arbSeqs()) { a, b ->
                val x = toSeqs(a).toMutableSet()
                val y = toSeqs(b).toMutableSet()
                exchange(x, y)
                assertEquals(x, y)
                assertEquals(VersionVector.fromSequences(x), VersionVector.fromSequences(y))
            }
        }
    }

    /** Stern (Handy = Hub): Uhr 1 ↔ Handy, Uhr 2 ↔ Handy, Uhr 1 ↔ Handy → alle drei gleich. */
    @Test
    fun hubForwardingConvergesThreeDevices() {
        runBlocking {
            checkAll(config, arbSeqs(), arbSeqs(), arbSeqs()) { a, b, c ->
                val phone = toSeqs(a).toMutableSet()
                val watch1 = toSeqs(b).toMutableSet()
                val watch2 = toSeqs(c).toMutableSet()
                exchange(watch1, phone)
                exchange(watch2, phone)
                exchange(watch1, phone)
                assertEquals(phone, watch1)
                assertEquals(phone, watch2)
            }
        }
    }

    /** Ein Abgleich zwischen [x] und [y] wie in [AntiEntropy] beschrieben; nur Summary-Vektoren gehen über den Draht. */
    private fun exchange(
        x: MutableSet<DeviceSeq>,
        y: MutableSet<DeviceSeq>,
    ) {
        val sx = VersionVector.fromSequences(x)
        val sy = VersionVector.fromSequences(y)
        // beide senden Summary; jeder schickt, was dem anderen fehlt
        val toY = expand(AntiEntropy.missingRanges(x, sy))
        val toX = expand(AntiEntropy.missingRanges(y, sx))
        y += toY
        x += toX
        // wer noch hinten liegt, antwortet einmal mit Summary(reply)
        if (AntiEntropy.isBehind(VersionVector.fromSequences(x), VersionVector.fromSequences(y))) {
            x += expand(AntiEntropy.missingRanges(y, VersionVector.fromSequences(x)))
        }
        if (AntiEntropy.isBehind(VersionVector.fromSequences(y), VersionVector.fromSequences(x))) {
            y += expand(AntiEntropy.missingRanges(x, VersionVector.fromSequences(y)))
        }
    }

    private fun expand(ranges: List<SeqRange>) = ranges.flatMap { r -> (r.first..r.last).map { DeviceSeq(r.deviceId, it) } }

    @Test
    fun codecRoundTripsRandomEventBatches() {
        runBlocking {
            checkAll(config, Arb.int(0..10_000), Arb.int(0..120)) { seed, count ->
                val random = Random(seed)
                val events =
                    (1..count).map { i ->
                        val parent = if (i == 1) null else "e${i - 1}"
                        ev("e$i", devices[random.nextInt(3)].value, i.toLong(), i.toLong(), parent, randomPayload(random, i))
                    }
                val message = Events(MATCH, events, more = random.nextBoolean())
                assertEquals(DecodeResult.Ok(message), SyncCodec.decode(SyncCodec.encode(message)))
            }
        }
    }

    private fun randomPayload(
        random: Random,
        i: Int,
    ): EventPayload =
        when (random.nextInt(7)) {
            0 -> Undo(EventId("e${random.nextInt(1, i + 1)}"))
            1 -> ContinueDecision(random.nextBoolean())
            2 -> Correction.server(PlayerId("B1"), serverNumber = random.nextInt(1, 3))
            3 -> MatchEnded(EndReason.RETIRED, Side.B)
            4 -> Paused
            else -> PointWon(if (random.nextBoolean()) Side.A else Side.B)
        }
}
