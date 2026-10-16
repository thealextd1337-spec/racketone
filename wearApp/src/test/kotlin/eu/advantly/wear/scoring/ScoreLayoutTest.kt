package eu.advantly.wear.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreLayoutTest {
    @Test
    fun chordIsWidestInTheMiddleAndZeroAtTheEdge() {
        assertEquals(192f - 8f, ScoreLayout.chordWidth(192f, 96f), 0.01f)
        assertEquals(0f, ScoreLayout.chordWidth(192f, 0f), 0.01f)
        assertEquals(0f, ScoreLayout.chordWidth(192f, 192f), 0.01f)
        assertEquals("außerhalb wie am Rand", 0f, ScoreLayout.chordWidth(192f, -10f), 0.01f)
        // symmetrisch zur Mitte, schmaler zum Rand hin
        assertEquals(ScoreLayout.chordWidth(192f, 40f), ScoreLayout.chordWidth(192f, 152f), 0.01f)
        assertTrue(ScoreLayout.chordWidth(192f, 30f) < ScoreLayout.chordWidth(192f, 60f))
        // 30 dp unter dem oberen Rand: 2·√(96² − 66²) − 2·4
        assertEquals(2 * kotlin.math.sqrt(96f * 96f - 66f * 66f) - 8f, ScoreLayout.chordWidth(192f, 30f), 0.01f)
    }
}
