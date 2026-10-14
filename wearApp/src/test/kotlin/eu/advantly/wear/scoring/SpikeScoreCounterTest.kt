package eu.advantly.wear.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeScoreCounterTest {
    @Test
    fun countsPointsPerSide() {
        val counter = SpikeScoreCounter()
        counter.addPoint(Side.US)
        counter.addPoint(Side.US)
        val score = counter.addPoint(Side.THEM)
        assertEquals(SpikeScore(us = 2, them = 1), score)
        assertEquals("2:1", score.format())
    }

    @Test
    fun undoRemovesLastPointRepeatedly() {
        val counter = SpikeScoreCounter()
        counter.addPoint(Side.US)
        counter.addPoint(Side.THEM)
        counter.addPoint(Side.THEM)

        assertEquals(Side.THEM, counter.undo())
        assertEquals(SpikeScore(1, 1), counter.score)
        assertEquals(Side.THEM, counter.undo())
        assertEquals(Side.US, counter.undo())
        assertEquals(SpikeScore(0, 0), counter.score)
    }

    @Test
    fun undoOnEmptyCounterIsNoOp() {
        val counter = SpikeScoreCounter()
        assertFalse(counter.canUndo)
        assertNull(counter.undo())
        assertEquals(SpikeScore(0, 0), counter.score)
    }

    @Test
    fun pointAfterUndoContinuesFromUndoneState() {
        val counter = SpikeScoreCounter()
        counter.addPoint(Side.US)
        counter.undo()
        counter.addPoint(Side.THEM)
        assertEquals(listOf(Side.THEM), counter.history)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val counter = SpikeScoreCounter(listOf(Side.US, Side.THEM, Side.US))
        val encoded = counter.encode()
        assertEquals("UTU", encoded)
        val restored = SpikeScoreCounter.decode(encoded)
        assertEquals(counter.history, restored.history)
        assertTrue(restored.canUndo)
    }

    @Test
    fun decodeIgnoresGarbageAndNull() {
        assertEquals(listOf(Side.US, Side.THEM), SpikeScoreCounter.decode("Ux?T").history)
        assertEquals(emptyList<Side>(), SpikeScoreCounter.decode(null).history)
    }

    @Test
    fun resetClearsEverything() {
        val counter = SpikeScoreCounter(listOf(Side.US, Side.US))
        counter.reset()
        assertEquals(SpikeScore(0, 0), counter.score)
        assertFalse(counter.canUndo)
    }
}
