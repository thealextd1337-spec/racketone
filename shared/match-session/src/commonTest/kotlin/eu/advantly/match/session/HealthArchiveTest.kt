package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.Presets
import eu.advantly.data.StoredHealthSeries
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan 0.5.1 §7b: Pulsverläufe als Datei exportieren und importieren (Codec, Prüfung, Zuordnung). */
class HealthArchiveTest {
    private val watch = DeviceId("watch")
    private val series =
        StoredHealthSeries(
            MatchId("m1"),
            watch,
            7,
            HealthSeries(1_000, 5_000, listOf(110, null, 130), listOf(0L, 12L, null), -250, HealthQuality.OK),
        )

    private fun importAll(
        text: String,
        consent: Boolean = true,
    ): Pair<HealthImportResult, List<StoredHealthSeries>> {
        val seen = mutableListOf<StoredHealthSeries>()
        val result =
            HealthArchive.import(HealthArchive.decode(text.encodeToByteArray()), consent) {
                seen += it
                HealthImportPlacement.IMPORTED
            }
        return result to seen
    }

    private fun file(seriesJson: String) = """{"format":"advantly-health","version":1,"series":[$seriesJson]}"""

    private fun seriesJson(
        bpm: String = "[110]",
        steps: String = "[0]",
        extra: String = "",
    ) = """{"matchId":"m1","sourceDeviceId":"watch","revision":1,"startMs":0,"intervalMs":5000,"bpm":$bpm,"steps":$steps$extra}"""

    @Test
    fun roundTripKeepsEverySeriesUnchanged() {
        val other =
            series.copy(
                matchId = MatchId("m2"),
                series = series.series.copy(bpm = listOf(null, null, null), quality = HealthQuality.NO_HR),
            )
        val text = HealthArchive.encode(listOf(series, other), exportedAtMs = 42)
        assertTrue(text.contains("\"format\":\"advantly-health\"") && text.contains("\"version\":1"))
        val (result, seen) = importAll(text)
        assertEquals(HealthImportResult(imported = 2), result)
        assertEquals(listOf(series, other), seen)
    }

    /** Review M5: Jede Exportdatei bleibt unter der Import-Grenze; zusammen enthalten sie jede Serie genau einmal. */
    @Test
    fun aLargeExportIsSplitIntoFilesTheImportAccepts() {
        val many = List(40) { series.copy(matchId = MatchId("m$it")) }
        val single = HealthArchive.encodeParts(many, 0)
        assertEquals(listOf(HealthArchive.encode(many, 0)), single, "passt in eine Datei")
        val limit = HealthArchive.encode(many.take(5), 0).length
        val parts = HealthArchive.encodeParts(many, 0, maxBytes = limit)
        assertTrue(parts.size >= 8, "${parts.size} Dateien")
        assertTrue(parts.all { it.encodeToByteArray().size <= limit })
        assertEquals(many, parts.flatMap { importAll(it).second })
        assertEquals(listOf(HealthArchive.encode(emptyList(), 0)), HealthArchive.encodeParts(emptyList(), 0))
        assertEquals("advantly-puls-1970-01-01-2.json", HealthArchive.fileName(0, part = 2, parts = 3))
    }

    @Test
    fun placementsAreCounted() {
        val text = HealthArchive.encode(List(4) { series.copy(matchId = MatchId("m$it")) }, 0)
        val result =
            HealthArchive.import(HealthArchive.decode(text.encodeToByteArray()), consent = true) {
                when (it.matchId.value) {
                    "m0", "m1" -> HealthImportPlacement.IMPORTED
                    "m2" -> HealthImportPlacement.UNKNOWN_MATCH
                    else -> HealthImportPlacement.SKIPPED
                }
            }
        assertEquals(HealthImportResult(imported = 2, unknownMatch = 1, skipped = 1), result)
    }

    @Test
    fun withoutConsentNothingIsRead() {
        val (result, seen) = importAll(HealthArchive.encode(listOf(series), 0), consent = false)
        assertEquals(HealthImportError.NO_CONSENT, result.error)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun tooLargeFilesAreRejectedBeforeParsing() {
        val bytes = ByteArray(HealthArchive.MAX_BYTES + 1) { ' '.code.toByte() }
        assertEquals(HealthImportError.TOO_LARGE, HealthArchive.decode(bytes).error)
    }

    @Test
    fun newerFormatVersionAsksForAnUpdate() {
        val (result, seen) = importAll("""{"format":"advantly-health","version":2,"series":"anders"}""")
        assertEquals(HealthImportError.NEWER_VERSION, result.error)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun brokenOrForeignFilesAreRejectedWithoutCrash() {
        val broken =
            listOf(
                "",
                "kein json",
                "[]",
                "{\"format\":\"advantly-health\"",
                """{"format":"other","version":1,"series":[]}""",
                """{"format":"advantly-health","series":[]}""",
                """{"format":"advantly-health","version":0,"series":[]}""",
                """{"format":["advantly-health"],"version":1}""",
                file(seriesJson(bpm = "[251]")),
                file(seriesJson(bpm = "[24]")),
                file(seriesJson(bpm = "[3000000000]")),
                file(seriesJson(steps = "[-1]")),
                file(seriesJson(steps = "[0, 1]")),
                file(seriesJson(bpm = "[\"x\"]")),
                file(seriesJson().replace("\"intervalMs\":5000", "\"intervalMs\":0")),
                file(seriesJson().replace("\"revision\":1", "\"revision\":-1")),
                file(seriesJson().replace("\"matchId\":\"m1\"", "\"matchId\":\" \"")),
                file(seriesJson().replace("\"matchId\":\"m1\"", "\"matchId\":\"${"x".repeat(129)}\"")),
                file(seriesJson(bpm = List(5_041) { "null" }.toString(), steps = List(5_041) { "null" }.toString())),
                // eine gültige und eine kaputte Serie: nichts wird übernommen
                file(seriesJson() + "," + seriesJson(bpm = "[999]")),
            )
        for (text in broken) {
            val (result, seen) = importAll(text)
            assertEquals(HealthImportError.INVALID, result.error, text.take(80))
            assertTrue(seen.isEmpty(), text.take(80))
        }
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        assertEquals(HealthImportError.INVALID, HealthArchive.decode(invalidUtf8).error)
    }

    @Test
    fun unknownQualityIsRecomputedAndUnknownKeysAreIgnored() {
        val (result, seen) = importAll(file(seriesJson(extra = ""","quality":"BRAND_NEW","future":true""")))
        assertNull(result.error)
        assertEquals(HealthQuality.of(listOf(110), 5_000), seen.single().series.quality)
    }

    @Test
    fun noHealthValuesInStrings() {
        val content = HealthArchive.decode(HealthArchive.encode(listOf(series), 0).encodeToByteArray())
        assertFalse(content.toString().contains("110"))
        assertFalse(HealthArchive.import(content, true) { HealthImportPlacement.IMPORTED }.toString().contains("110"))
    }

    @Test
    fun fileNameCarriesTheUtcDate() {
        assertEquals("advantly-puls-1970-01-01.json", HealthArchive.fileName(0))
        assertEquals("advantly-puls-2026-11-02.json", HealthArchive.fileName(1_793_577_600_000))
        assertEquals("advantly-puls-2024-02-29.json", HealthArchive.fileName(1_709_164_800_000 + 86_399_999))
    }

    // --- über die Session (Datenbank, Revision, Einwilligung) ---------------------------------------------------------

    /** Wie die App: Datei bauen bzw. prüfen abseits des Session-Threads, nur der Datenbankteil in der Session. */
    private fun MatchSession.exportHealthText(): String? =
        exportHealthSeries().takeIf { it.isNotEmpty() }?.let { HealthArchive.encode(it, 0) }

    private fun MatchSession.importBytes(bytes: ByteArray): HealthImportResult = importHealth(HealthArchive.decode(bytes))

    private val format = Presets.TENNIS_SINGLES_BO3.format()

    @Test
    fun sessionRoundTripAfterDeletingEverything() {
        val phone = WireNode("phone", 1)
        phone.session.setHealthConsent(true)
        val id = phone.session.create(format, SINGLES, MatchMeta(pulseVisibility = TelemetryVisibility.PRIVATE))
        assertNull(phone.session.exportHealthText(), "ohne Serien keine Datei")
        val stored = series.copy(matchId = id)
        assertTrue(phone.data.health.upsert(stored))
        val text = assertNotNull(phone.session.exportHealthText())
        val withUnknown = HealthArchive.encode(listOf(stored, series.copy(matchId = MatchId("fremd"))), 0)
        phone.session.deleteAllHealthData()
        val before = phone.session.state.value.dataRevision

        val result = phone.session.importBytes(withUnknown.encodeToByteArray())

        assertEquals(HealthImportResult(imported = 1, unknownMatch = 1), result)
        assertEquals(listOf(stored), phone.data.health.forMatch(id))
        assertTrue(phone.session.state.value.dataRevision > before, "Statistik und Zeitleiste lesen neu")
        assertEquals(HealthImportResult(skipped = 1), phone.session.importBytes(text.encodeToByteArray()), "gleiche Revision")
    }

    @Test
    fun onlyAHigherRevisionReplacesAndTurnedOffMatchesStayEmpty() {
        val phone = WireNode("phone", 1)
        phone.session.setHealthConsent(true)
        val id = phone.session.create(format, SINGLES, MatchMeta(pulseVisibility = TelemetryVisibility.PRIVATE))
        val stored = series.copy(matchId = id)
        phone.data.health.upsert(stored)
        val older = stored.copy(revision = 6, series = stored.series.copy(bpm = listOf(90, 90, 90)))
        assertEquals(
            HealthImportResult(skipped = 1),
            phone.session.importBytes(HealthArchive.encode(listOf(older), 0).encodeToByteArray()),
        )
        assertEquals(listOf(stored), phone.data.health.forMatch(id))
        val newer = older.copy(revision = 8)
        assertEquals(
            HealthImportResult(imported = 1),
            phone.session.importBytes(HealthArchive.encode(listOf(newer), 0).encodeToByteArray()),
        )
        assertEquals(listOf(newer), phone.data.health.forMatch(id))

        phone.session.setPulseVisibility(id, TelemetryVisibility.OFF)
        assertEquals(
            HealthImportResult(skipped = 1),
            phone.session.importBytes(HealthArchive.encode(listOf(newer), 0).encodeToByteArray()),
        )
        assertTrue(
            phone.data.health
                .forMatch(id)
                .isEmpty(),
            "„Gar nicht“ bleibt",
        )
    }

    @Test
    fun sessionImportNeedsConsent() {
        val phone = WireNode("phone", 1)
        val id = phone.session.create(format, SINGLES, MatchMeta())
        val result = phone.session.importBytes(HealthArchive.encode(listOf(series.copy(matchId = id)), 0).encodeToByteArray())
        assertEquals(HealthImportError.NO_CONSENT, result.error)
        assertTrue(
            phone.data.health
                .forMatch(id)
                .isEmpty(),
        )
    }
}
