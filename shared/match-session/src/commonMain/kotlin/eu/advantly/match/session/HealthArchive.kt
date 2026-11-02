package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.data.StoredHealthSeries
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthRecorder
import eu.advantly.health.HealthSeries
import eu.advantly.sync.protocol.HealthSeriesMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Grund, warum ein Import nichts übernommen hat. */
enum class HealthImportError {
    /** „Pulsverlauf aufzeichnen“ ist aus. */
    NO_CONSENT,

    /** Größer als [HealthArchive.MAX_BYTES]. */
    TOO_LARGE,

    /** Kein Advantly-Pulsexport, kaputt oder Werte außerhalb der erlaubten Bereiche. */
    INVALID,

    /** Formatversion neuer als [HealthArchive.VERSION]: zuerst die App aktualisieren. */
    NEWER_VERSION,
}

/**
 * Ergebnis eines Imports; zählt nur Serien, enthält keine Messwerte.
 *
 * @property unknownMatch Serien ohne passendes (lesbares) Match auf diesem Gerät
 * @property skipped Serien mit gleicher oder älterer Revision bzw. zu Matches mit Pulsverlauf „Gar nicht“
 */
data class HealthImportResult(
    val imported: Int = 0,
    val unknownMatch: Int = 0,
    val skipped: Int = 0,
    val error: HealthImportError? = null,
)

/** Was mit einer gültigen Serie aus der Datei geschehen ist. */
enum class HealthImportPlacement { IMPORTED, UNKNOWN_MATCH, SKIPPED }

/**
 * Datei „Pulsverläufe exportieren/importieren“ (Plan 0.5.1 §7b): alle lesbaren Serien aus `health_series` als JSON für den
 * Gerätewechsel ohne Cloud. Der Nutzer legt die Datei selbst ab; sie geht nie an Advantly-Server. Beim Import gilt sie als
 * fremd: Format, Version, Größe und Wertebereiche werden wie beim Empfang von der Uhr geprüft; eine ungültige Serie
 * verwirft die ganze Datei.
 */
object HealthArchive {
    const val FORMAT = "advantly-health"
    const val VERSION = 1
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_ID_LENGTH = 128

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** `advantly-puls-<JJJJ-MM-TT>.json`; Datum in UTC (shortcut: kurz nach Mitternacht ggf. der Vortag, egal für einen Dateinamen). */
    fun fileName(epochMs: Long): String = "advantly-puls-${isoDate(epochMs)}.json"

    fun encode(
        series: List<StoredHealthSeries>,
        exportedAtMs: Long,
    ): String =
        json.encodeToString(
            ArchiveFile.serializer(),
            ArchiveFile(
                FORMAT,
                VERSION,
                exportedAtMs,
                series.map { stored ->
                    with(stored.series) {
                        ArchiveSeries(
                            stored.matchId.value,
                            stored.sourceDeviceId.value,
                            stored.revision,
                            startMs,
                            intervalMs,
                            bpm,
                            steps,
                            clockOffsetMs,
                            quality.name,
                        )
                    }
                },
            ),
        )

    /**
     * Prüft [bytes] und reicht jede Serie an [place] (nur wenn die ganze Datei gültig ist). Ohne [consent] wird die Datei
     * gar nicht erst gelesen.
     */
    fun import(
        bytes: ByteArray,
        consent: Boolean,
        place: (StoredHealthSeries) -> HealthImportPlacement,
    ): HealthImportResult {
        if (!consent) return HealthImportResult(error = HealthImportError.NO_CONSENT)
        if (bytes.size > MAX_BYTES) return HealthImportResult(error = HealthImportError.TOO_LARGE)
        val series =
            try {
                decode(bytes)
            } catch (e: ArchiveException) {
                return HealthImportResult(error = e.error)
            }
        val placed = series.groupingBy(place).eachCount()
        return HealthImportResult(
            placed[HealthImportPlacement.IMPORTED] ?: 0,
            placed[HealthImportPlacement.UNKNOWN_MATCH] ?: 0,
            placed[HealthImportPlacement.SKIPPED] ?: 0,
        )
    }

    private fun decode(bytes: ByteArray): List<StoredHealthSeries> {
        val file =
            try {
                val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
                if (root["format"]?.jsonPrimitive?.content != FORMAT) invalid()
                val version = root["version"]?.jsonPrimitive?.intOrNull ?: invalid()
                if (version > VERSION) throw ArchiveException(HealthImportError.NEWER_VERSION)
                if (version < 1) invalid()
                json.decodeFromJsonElement(ArchiveFile.serializer(), root)
            } catch (e: IllegalArgumentException) {
                // auch SerializationException und kein Objekt an der Wurzel
                throw ArchiveException(HealthImportError.INVALID, e)
            } catch (e: CharacterCodingException) {
                throw ArchiveException(HealthImportError.INVALID, e)
            }
        return file.series.map { it.toStored() ?: invalid() }
    }

    /** Gleiche Grenzen wie `HealthSeriesMessage` und der Empfang in [HealthSync]; `null` = ungültig. */
    private fun ArchiveSeries.toStored(): StoredHealthSeries? {
        val ids = listOf(matchId, sourceDeviceId).all { it.isNotBlank() && it.length <= MAX_ID_LENGTH }
        val ranges =
            revision >= 0 &&
                intervalMs > 0 &&
                bpm.size == steps.size &&
                bpm.size <= HealthSeriesMessage.MAX_BUCKETS &&
                bpm.all { it == null || it in HealthRecorder.BPM_RANGE } &&
                steps.all { it == null || it >= 0 }
        if (!ids || !ranges) return null
        val quality = HealthQuality.entries.firstOrNull { it.name == quality } ?: HealthQuality.of(bpm, intervalMs)
        return StoredHealthSeries(
            MatchId(matchId),
            DeviceId(sourceDeviceId),
            revision,
            HealthSeries(startMs, intervalMs, bpm, steps, clockOffsetMs, quality),
        )
    }

    private fun invalid(): Nothing = throw ArchiveException(HealthImportError.INVALID)

    /** Tage seit 1970 → Kalenderdatum (H. Hinnant, `civil_from_days`). */
    private fun isoDate(epochMs: Long): String {
        val z = epochMs.floorDiv(86_400_000L) + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    private class ArchiveException(
        val error: HealthImportError,
        cause: Throwable? = null,
    ) : Exception(error.name, cause)

    @Serializable
    private data class ArchiveFile(
        val format: String,
        val version: Int,
        val exportedAtMs: Long = 0,
        val series: List<ArchiveSeries> = emptyList(),
    ) {
        override fun toString(): String = "ArchiveFile(<redacted>)"
    }

    /** Eine Serie wie in `health_series`; `null` in [bpm]/[steps] = keine Daten im Bucket. */
    @Serializable
    private data class ArchiveSeries(
        val matchId: String,
        val sourceDeviceId: String,
        val revision: Long,
        val startMs: Long,
        val intervalMs: Long,
        val bpm: List<Int?>,
        val steps: List<Long?>,
        val clockOffsetMs: Long = 0,
        val quality: String = "",
    ) {
        // Gesundheitsdaten nie in Logs (Plan Puls §3.4).
        override fun toString(): String = "ArchiveSeries(<redacted>)"
    }
}
