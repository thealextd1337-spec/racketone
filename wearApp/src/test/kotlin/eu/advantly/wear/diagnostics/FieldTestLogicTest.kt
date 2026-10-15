package eu.advantly.wear.diagnostics

import android.view.Display
import eu.advantly.wear.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTestLogicTest {
    private val session =
        SessionReading(
            matchId = "m-1",
            sport = "TENNIS",
            scorer = true,
            points = 12,
            events = 15,
            pending = 0,
            peers = 1,
            link = "CONNECTED",
            sent = 30,
            received = 28,
            acked = 29,
            retries = 1,
            failed = 0,
        )

    private val tick =
        FieldTestSample(
            kind = FieldTestLogic.KIND_TICK,
            wallMs = 1_760_000_000_000L,
            elapsedMs = 123_456L,
            pid = 4242,
            battery = BatteryReading(87, charging = false, chargeUah = 250_000L, powerSave = false),
            display = DisplayTimes(DisplayMode.AMBIENT, 1_000, 50_000, 9_000),
            workout = WorkoutReading(active = true, phase = "ACTIVE", countOnly = false),
            session = session,
        )

    @Test
    fun encodesGoldenLineReadByGateCheck() {
        // Identisch mit BatteryAnalysisTest.goldenTick in tools/gate-check (Leser-Seite)
        assertEquals(GOLDEN_TICK, tick.toJsonLine())
    }

    @Test
    fun containsNoHealthData() {
        val line = tick.copy(device = DeviceInfo("Pixel Watch 3", "16", "1.0"), reason = "enabled").toJsonLine()
        listOf("heart", "hr", "bpm", "steps", "kcal", "calories").forEach { key ->
            assertFalse("Schlüssel $key darf nicht vorkommen: $line", Regex("\"$key\"", RegexOption.IGNORE_CASE).containsMatchIn(line))
        }
        assertTrue(line.endsWith(""""model":"Pixel Watch 3","os":"16","app":"1.0","reason":"enabled"}"""))
    }

    @Test
    fun omitsNullsAndEscapesStrings() {
        val line =
            tick
                .copy(
                    battery = BatteryReading(null, null, null, null),
                    session = session.copy(matchId = null, sport = null, scorer = null, points = null, events = null),
                    device = DeviceInfo("Watch \"X\"\\1", "16", "1.0"),
                ).toJsonLine()
        assertFalse(line.contains("\"battery\""))
        assertFalse(line.contains("\"matchId\""))
        assertTrue(line, line.contains(""""model":"Watch \"X\"\\1""""))
    }

    @Test
    fun mapsDisplayStates() {
        assertEquals(DisplayMode.ON, FieldTestLogic.displayMode(Display.STATE_ON))
        assertEquals(DisplayMode.AMBIENT, FieldTestLogic.displayMode(Display.STATE_DOZE))
        assertEquals(DisplayMode.AMBIENT, FieldTestLogic.displayMode(Display.STATE_DOZE_SUSPEND))
        assertEquals(DisplayMode.AMBIENT, FieldTestLogic.displayMode(Display.STATE_ON_SUSPEND))
        assertEquals(DisplayMode.OFF, FieldTestLogic.displayMode(Display.STATE_OFF))
        assertEquals(DisplayMode.UNKNOWN, FieldTestLogic.displayMode(Display.STATE_UNKNOWN))
    }

    @Test
    fun accumulatesDisplayTimeBetweenSamples() {
        val acc = DisplayTimeAccumulator(DisplayMode.ON, 0)
        acc.onChange(DisplayMode.AMBIENT, 5_000)
        acc.onChange(DisplayMode.OFF, 45_000)
        assertEquals(DisplayTimes(DisplayMode.OFF, 5_000, 40_000, 15_000), acc.drain(60_000))
        acc.onChange(DisplayMode.ON, 70_000)
        assertEquals(DisplayTimes(DisplayMode.ON, 50_000, 0, 10_000), acc.drain(120_000))
        // Uhrzeit rückwärts (sollte bei elapsedRealtime nicht vorkommen) zählt nicht negativ
        assertEquals(DisplayTimes(DisplayMode.ON, 0, 0, 0), acc.drain(100_000))
    }

    @Test
    fun matchTransitionsCarryLastStateOfOldMatch() {
        val idle = session.copy(matchId = null, sport = null, points = null)
        val next = session.copy(matchId = "m-2", points = 0)

        assertEquals(emptyList<Pair<String, SessionReading>>(), FieldTestLogic.transitions(session, session.copy(points = 13)))
        assertEquals(listOf(FieldTestLogic.KIND_MATCH_START to session), FieldTestLogic.transitions(null, session))
        assertEquals(listOf(FieldTestLogic.KIND_MATCH_END to session), FieldTestLogic.transitions(session, idle))
        assertEquals(
            listOf(FieldTestLogic.KIND_MATCH_END to session, FieldTestLogic.KIND_MATCH_START to next),
            FieldTestLogic.transitions(session, next),
        )
    }

    @Test
    fun fieldTestModeIsDebugOnly() {
        assertEquals(BuildConfig.DEBUG, FieldTestRecorder.AVAILABLE)
        if (!FieldTestRecorder.AVAILABLE) assertNull(FieldTestRecorder.path)
    }

    companion object {
        const val GOLDEN_TICK =
            """{"v":1,"kind":"tick","wallMs":1760000000000,"elapsedMs":123456,"pid":4242,"battery":87,"charging":false,""" +
                """"chargeUah":250000,"display":"ambient","onMs":1000,"ambientMs":50000,"offMs":9000,"powerSave":false,""" +
                """"workout":true,"workoutPhase":"ACTIVE","countOnly":false,"matchId":"m-1","sport":"TENNIS","scorer":true,""" +
                """"points":12,"events":15,"pending":0,"peers":1,"link":"CONNECTED","sent":30,"received":28,"acked":29,""" +
                """"retries":1,"failed":0}"""
    }
}
