package eu.advantly.wear.live

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateThrottleTest {
    @Test
    fun interactiveShowsEveryValue() {
        val t = HeartRateThrottle()
        assertEquals(120, t.display(120, 0, ambient = false))
        assertEquals(121, t.display(121, 1_000, ambient = false))
    }

    @Test
    fun ambientUpdatesAtMostOncePerMinute() {
        val t = HeartRateThrottle()
        assertEquals(120, t.display(120, 0, ambient = true))
        assertEquals(120, t.display(130, 30_000, ambient = true))
        assertEquals(120, t.display(140, 59_999, ambient = true))
        assertEquals(141, t.display(141, 60_000, ambient = true))
        // zurück im interaktiven Modus sofort aktuell
        assertEquals(150, t.display(150, 61_000, ambient = false))
    }
}
