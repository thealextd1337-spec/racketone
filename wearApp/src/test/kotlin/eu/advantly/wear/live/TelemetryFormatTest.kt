package eu.advantly.wear.live

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TelemetryFormatTest {
    @Test
    fun stepsStayShortForTheNarrowSideColumn() {
        assertEquals("0", TelemetryFormat.steps(0, Locale.GERMANY))
        assertEquals("9999", TelemetryFormat.steps(9_999, Locale.GERMANY))
        assertEquals("10,0k", TelemetryFormat.steps(10_000, Locale.GERMANY))
        assertEquals("12,3k", TelemetryFormat.steps(12_345, Locale.GERMANY))
        assertEquals("12.3k", TelemetryFormat.steps(12_399, Locale.US))
        assertEquals("abgerundet", "99,9k", TelemetryFormat.steps(99_999, Locale.GERMANY))
        assertEquals("123k", TelemetryFormat.steps(123_456, Locale.GERMANY))
    }
}
