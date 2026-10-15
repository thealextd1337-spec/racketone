package eu.advantly.gate.check

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryAnalysisTest {
    /**
     * Golden-Zeile des Formats v1 – identisch mit `FieldTestLogicTest.GOLDEN_TICK` in `wearApp` (Writer-Seite).
     * Ändert sich das Format, beide Stellen und `FieldLogParser.VERSION` gemeinsam anpassen.
     */
    private val goldenTick =
        """{"v":1,"kind":"tick","wallMs":1760000000000,"elapsedMs":123456,"pid":4242,"battery":87,"charging":false,""" +
            """"chargeUah":250000,"display":"ambient","onMs":1000,"ambientMs":50000,"offMs":9000,"powerSave":false,""" +
            """"workout":true,"workoutPhase":"ACTIVE","countOnly":false,"matchId":"m-1","sport":"TENNIS","scorer":true,""" +
            """"points":12,"events":15,"pending":0,"peers":1,"link":"CONNECTED","sent":30,"received":28,"acked":29,""" +
            """"retries":1,"failed":0}"""

    private fun sample(
        minute: Int,
        battery: Int?,
        match: String? = "m-1",
        charging: Boolean = false,
        pid: Int = 1,
        sent: Int = minute,
        received: Int = minute * 2,
        on: Long = 6_000,
        ambient: Long = 30_000,
        off: Long = 24_000,
        workout: Boolean = true,
        points: Int? = minute,
        chargeUah: Long? = null,
    ) = FieldSample(
        kind = "tick",
        wallMs = 1_000_000_000L + minute * 60_000L,
        elapsedMs = minute * 60_000L,
        pid = pid,
        battery = battery,
        charging = charging,
        chargeUah = chargeUah,
        display = "off",
        onMs = on,
        ambientMs = ambient,
        offMs = off,
        powerSave = false,
        workout = workout,
        workoutPhase = if (workout) "ACTIVE" else "IDLE",
        countOnly = false,
        matchId = match,
        sport = match?.let { "BADMINTON" },
        scorer = true,
        points = points,
        events = points,
        pending = 0,
        peers = 1,
        link = "CONNECTED",
        sent = sent,
        received = received,
        acked = sent,
        retries = 0,
        failed = 0,
        model = null,
        os = null,
        app = null,
    )

    @Test
    fun parsesGoldenLineOfWatchWriter() {
        val log = FieldLogParser.parseLines(listOf(goldenTick))

        val s = log.samples.single()
        assertEquals(0, log.skippedLines)
        assertEquals(87, s.battery)
        assertEquals("ambient", s.display)
        assertEquals(50_000L, s.ambientMs)
        assertEquals("m-1", s.matchId)
        assertEquals(12, s.points)
        assertEquals(30, s.sent)
        assertEquals(250_000L, s.chargeUah)
        assertTrue(s.workout)
    }

    @Test
    fun skipsMalformedAndNewerVersions() {
        val log =
            FieldLogParser.parseLines(
                listOf("", "kaputt", """{"v":2,"kind":"tick","wallMs":1}""", """{"v":1,"wallMs":1}""", goldenTick),
            )
        assertEquals(1, log.samples.size)
        assertEquals(3, log.skippedLines)
    }

    @Test
    fun readsUtf16FilesFromPowerShellRedirection() {
        val file = Files.createTempFile("fieldtest", ".jsonl").toFile()
        try {
            file.writeText(goldenTick + "\n", Charsets.UTF_16)
            assertEquals(1, FieldLogParser.parse(listOf(file)).samples.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun ratePerHourOverOneHourMatch() {
        // 61 Messpunkte, 90 % → 80 % in 60 min
        val samples = (0..60).map { sample(it, 90 - it / 6) }
        val section = BatteryAnalysis.perMatch(FieldLog(samples, 0)).single()

        assertEquals(60.0, section.dischargeMinutes, 1e-9)
        assertEquals(10, section.dropPercent)
        assertEquals(10.0, section.ratePerHour!!, 1e-9)
        assertEquals(BatteryVerdict.PASSED, section.verdict)
        assertEquals("BADMINTON", section.sport)
        assertEquals(60, section.points)
        assertEquals(60, section.sent)
        assertEquals(120, section.received)
        assertEquals(0, section.restarts)
        assertEquals(10.0, section.screenOnPct!!, 1e-9)
        assertEquals(50.0, section.ambientPct!!, 1e-9)
        assertEquals(40.0, section.offPct!!, 1e-9)
        assertEquals(100.0, section.workoutPct!!, 1e-9)
    }

    @Test
    fun rateAboveThresholdFails() {
        val samples = (0..30).map { sample(it, 100 - it / 4) } // 7 % in 30 min = 14 %/h
        val heavy = (0..30).map { sample(it, 100 - it / 2) } // 15 % in 30 min = 30 %/h
        assertEquals(BatteryVerdict.PASSED, BatteryAnalysis.section("a", "m", null, samples).verdict)
        val failed = BatteryAnalysis.section("b", "m", null, heavy)
        assertEquals(BatteryVerdict.FAILED, failed.verdict)
        assertEquals(30.0, failed.ratePerHour!!, 1e-9)
    }

    @Test
    fun chargingPhasesAreExcluded() {
        val samples =
            (0..30).map { sample(it, 90 - it / 6) } +
                (31..40).map { sample(it, 85 + (it - 30), charging = true) } +
                (41..71).map { sample(it, 95 - (it - 41) / 6) }
        val section = BatteryAnalysis.section("m", "m-1", null, samples)

        assertEquals(60.0, section.dischargeMinutes, 1e-9)
        assertEquals(11.0, section.chargingMinutes, 1e-9)
        assertEquals(10, section.dropPercent)
        assertEquals(BatteryVerdict.PASSED, section.verdict)
    }

    @Test
    fun shortWindowsAreUnclear() {
        val section = BatteryAnalysis.section("m", "m-1", null, (0..10).map { sample(it, 90) })
        assertEquals(BatteryVerdict.UNCLEAR, section.verdict)
        val none = BatteryAnalysis.section("m", "m-1", null, (0..40).map { sample(it, null) })
        assertNull(none.ratePerHour)
        assertEquals(BatteryVerdict.UNCLEAR, none.verdict)
    }

    @Test
    fun processRestartResetsCountersAndIsCounted() {
        val samples =
            listOf(
                sample(0, 90, sent = 10, received = 10),
                sample(1, 90, sent = 15, received = 12),
                sample(2, 90, pid = 2, sent = 3, received = 1, on = 999_999), // neuer Prozess: Zähler ab 0
                sample(3, 89, pid = 2, sent = 4, received = 1),
            )
        val section = BatteryAnalysis.section("m", "m-1", null, samples)

        assertEquals(1, section.restarts)
        assertEquals(5 + 3 + 1, section.sent)
        assertEquals(2 + 1 + 0, section.received)
        // Bildschirmzeit der ersten Zeile nach dem Neustart ist nicht zuordenbar und zählt nicht
        assertEquals(12_000.0 / (12_000 + 60_000 + 48_000) * 100, section.screenOnPct!!, 1e-9)
    }

    @Test
    fun matchesAreSeparatedAndIdleTimeIgnored() {
        val samples =
            (0..25).map { sample(it, 90 - it / 5) } +
                (26..40).map { sample(it, 85, match = null, workout = false) } +
                (41..70).map { sample(it, 85 - (it - 41) / 3, match = "m-2") }
        val log = FieldLog(samples, 0)

        val sections = BatteryAnalysis.perMatch(log)
        assertEquals(listOf("m-1", "m-2"), sections.map { it.matchId })
        assertEquals(25.0, sections[0].dischargeMinutes, 1e-9)
        assertEquals(29.0, sections[1].dischargeMinutes, 1e-9)
        val whole = assertNotNull(BatteryAnalysis.whole(log))
        assertEquals(70.0, whole.dischargeMinutes, 1e-9)
    }

    @Test
    fun chargeCounterIsReportedInMah() {
        val samples = (0..30).map { sample(it, 90, chargeUah = 300_000L - it * 1_000L) }
        assertEquals(30.0, BatteryAnalysis.section("m", "m", null, samples).chargeDropMah!!, 1e-9)
    }

    @Test
    fun batteryCommandWritesReport() {
        val dir = Files.createTempDirectory("gate-battery").toFile()
        try {
            val log = File(dir, "fieldtest.jsonl")
            log.writeText(
                goldenTick + "\n" + goldenTick.replace("1760000000000", "1760003600000").replace("\"battery\":87", "\"battery\":80"),
            )
            val code = run(listOf("battery", "--log", log.path, "--out", dir.path, "--label", "pixel"))

            assertEquals(0, code)
            val report = File(dir, "akku-pixel.md").readText()
            assertTrue("Akku/Laufzeit Uhr: pixel" in report, report)
            assertTrue("**BESTANDEN**" in report, report)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun separateSessionsWithChargingInBetweenAreNotBridged() {
        // Review: 2× 10 min Spiel (90→87, Laden, 100→97) – früher 81 min/−7 %/BESTANDEN, korrekt 20 min/6 %/18 %/h
        val first = (0..10).map { sample(it, 90 - it * 3 / 10) }
        val charge = (11..40).map { sample(it, minOf(100, 87 + (it - 10) / 2), charging = true) }
        val second = (41..51).map { sample(it, 100 - (it - 41) * 3 / 10) }

        val section = BatteryAnalysis.perMatch(FieldLog(first + charge + second, 0)).single()

        assertEquals(20.0, section.dischargeMinutes, 1e-9)
        assertEquals(6, section.dropPercent)
        assertEquals(18.0, section.ratePerHour!!, 1e-9)
        assertEquals(BatteryVerdict.FAILED, section.verdict)
        assertEquals(0, section.rises)
    }

    @Test
    fun idleChargingRowsOfAnotherStateSplitTheMatch() {
        val first = (0..10).map { sample(it, 90 - it * 3 / 10) }
        val idle = (11..40).map { sample(it, 95, match = null, charging = true, workout = false) }
        val second = (41..51).map { sample(it, 100 - (it - 41) * 3 / 10) }

        val section = BatteryAnalysis.perMatch(FieldLog(first + idle + second, 0)).single()

        assertEquals(2, section.segments)
        assertEquals(20.0, section.dischargeMinutes, 1e-9)
        assertEquals(BatteryVerdict.FAILED, section.verdict)
    }

    @Test
    fun unnoticedBatteryRiseIsNeverAPass() {
        // Laden ohne erkanntes Ladesignal (z. B. Messpunkte fehlten): 85 → 100 ohne charging=true
        val samples = (0..30).map { sample(it, 90 - it / 6) } + (31..70).map { sample(it, 100 - (it - 31) / 10) }

        val section = BatteryAnalysis.perMatch(FieldLog(samples, 0)).single()

        assertEquals(1, section.rises)
        assertEquals(BatteryVerdict.UNCLEAR, section.verdict)
        assertTrue("Akkuanstieg" in section.reason)
    }

    @Test
    fun unknownChargingStateDoesNotCountAsDischarge() {
        val samples = (0..40).map { sample(it, 90 - it / 6).copy(charging = null) }
        val section = BatteryAnalysis.section("m", "m", null, samples)
        assertNull(section.ratePerHour)
        assertEquals(BatteryVerdict.UNCLEAR, section.verdict)
    }
}
