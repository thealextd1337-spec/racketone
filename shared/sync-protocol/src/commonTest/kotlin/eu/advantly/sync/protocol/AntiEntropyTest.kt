package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AntiEntropyTest {
    private val w = DeviceId("watch")
    private val p = DeviceId("phone")

    private fun seqs(
        device: DeviceId,
        vararg values: Long,
    ) = values.map { DeviceSeq(device, it) }

    @Test
    fun watermarkIsContiguousPrefix() {
        val vector = VersionVector.fromSequences(seqs(w, 1, 2, 3, 5, 6) + seqs(p, 2, 3))
        assertEquals(3, vector[w])
        assertEquals(0, vector[p])
        assertEquals(VersionVector.of(mapOf(w to 3L)), vector)
        assertEquals(0, VersionVector.EMPTY[w])
    }

    @Test
    fun missingRangesCoverEverythingAboveRemoteWatermark() {
        val local = seqs(w, 1, 2, 3, 5, 6, 7) + seqs(p, 1, 2)
        val remote = VersionVector.of(mapOf(w to 2L, p to 2L))
        assertEquals(listOf(SeqRange(w, 3, 3), SeqRange(w, 5, 7)), AntiEntropy.missingRanges(local, remote))
        assertEquals(
            listOf(SeqRange(p, 1, 2), SeqRange(w, 1, 3), SeqRange(w, 5, 7)),
            AntiEntropy.missingRanges(local, VersionVector.EMPTY),
        )
        assertEquals(emptyList(), AntiEntropy.missingRanges(local, VersionVector.of(mapOf(w to 9L, p to 9L))))
    }

    @Test
    fun behindAndGaps() {
        val local = VersionVector.of(mapOf(w to 3L))
        assertTrue(AntiEntropy.isBehind(local, VersionVector.of(mapOf(w to 4L))))
        assertTrue(AntiEntropy.isBehind(local, VersionVector.of(mapOf(p to 1L))))
        assertFalse(AntiEntropy.isBehind(local, VersionVector.of(mapOf(w to 3L))))
        assertEquals(
            listOf(SeqRange(p, 1, 1), SeqRange(w, 4, 4), SeqRange(w, 6, 8)),
            AntiEntropy.gaps(seqs(w, 1, 2, 3, 5, 9) + seqs(p, 2)),
        )
    }

    @Test
    fun mergeIsPointwiseMax() {
        val a = VersionVector.of(mapOf(w to 3L, p to 1L))
        val b = VersionVector.of(mapOf(w to 2L, p to 4L, DeviceId("x") to 1L))
        val merged = a.merge(b)
        assertEquals(VersionVector.of(mapOf(w to 3L, p to 4L, DeviceId("x") to 1L)), merged)
        assertTrue(merged.dominates(a) && merged.dominates(b))
    }

    @Test
    fun selectAndBatchKeepEventOrder() {
        val events =
            listOf(
                point("c", "watch", 2, 3, "b", Side.A),
                point("a", "phone", 1, 1, null, Side.A),
                point("b", "watch", 1, 2, "a", Side.B),
            )
        assertEquals(listOf("b", "c"), AntiEntropy.select(events, listOf(SeqRange(w, 1, 5))).map { it.id.value })
        val all = AntiEntropy.select(events, AntiEntropy.compress(events.map { DeviceSeq(it.deviceId, it.deviceSeq) }))
        assertEquals(listOf(listOf("a", "b"), listOf("c")), AntiEntropy.batches(all, 2).map { batch -> batch.map { it.id.value } })
    }

    @Test
    fun plausibility() {
        val ok = point("a", "phone", 1, 1, null, Side.A)
        assertTrue(AntiEntropy.isPlausible(ok, MATCH))
        assertFalse(AntiEntropy.isPlausible(ok, MatchId("other")))
        assertFalse(AntiEntropy.isPlausible(ok.copy(deviceSeq = 0), MATCH))
        assertFalse(AntiEntropy.isPlausible(ok.copy(lamport = 0), MATCH))
    }
}
