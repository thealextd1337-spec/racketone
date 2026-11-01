package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.data.db.HealthSeriesQueries
import eu.advantly.data.db.Health_series
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthSeries
import eu.advantly.health.maxBpm

/** Pulsserie einer Quelle ([sourceDeviceId]) zu einem Match; höhere [revision] ersetzt die gespeicherte. */
data class StoredHealthSeries(
    val matchId: MatchId,
    val sourceDeviceId: DeviceId,
    val revision: Long,
    val series: HealthSeries,
)

/** Rekorde über alle lesbaren Serien für die Startscreen-Kacheln; `toString` ohne Messwerte. */
data class HealthRecords(
    val maxBpm: Int?,
    val maxBpmMatchId: MatchId?,
) {
    override fun toString(): String = "HealthRecords(<redacted>)"
}

/**
 * Lokale Gesundheitsdaten (Plan Puls §3.4), getrennt von den Spieldaten: kein Weg in Outbox, Anti-Entropie, Cloud-Upload,
 * Export oder Logs. Gelesen wird nur, was zu einem für das aktuelle Konto lesbaren Match gehört; das Löschen eines
 * Match-Kopfs entfernt die Serien per Trigger.
 */
class HealthRepository internal constructor(
    private val queries: HealthSeriesQueries,
    private val headerExists: (String) -> Boolean,
) {
    /** Speichert [stored], wenn der Match-Kopf existiert und die Revision höher als die gespeicherte ist. `true` = übernommen. */
    fun upsert(stored: StoredHealthSeries): Boolean =
        queries.transactionWithResult {
            val match = stored.matchId.value
            val source = stored.sourceDeviceId.value
            // Ohne Kopf (unbekannt oder gelöscht) bliebe die Zeile verwaist, weil kein Lösch-Trigger sie je erreicht.
            if (!headerExists(match)) return@transactionWithResult false
            val current = queries.revision(match, source).executeAsOneOrNull()
            if (current != null && current >= stored.revision) return@transactionWithResult false
            with(stored.series) {
                queries.put(match, source, startMs, intervalMs, csv(bpm), csv(steps), clockOffsetMs, quality.name, stored.revision)
            }
            true
        }

    /** Alle Serien eines lesbaren Matches, nach Quelle sortiert. */
    fun forMatch(matchId: MatchId): List<StoredHealthSeries> = queries.forMatch(matchId.value).executeAsList().map { it.toStored() }

    /** Alle lesbaren Serien, z. B. für „meiste Schritte in einem Satz“ (Satzgrenzen rechnet `presentation`). */
    fun allSeries(): List<StoredHealthSeries> = queries.readable().executeAsList().map { it.toStored() }

    /** (Match, Quelle, Revision) aller lesbaren Serien ohne Messwerte: ändert sich, sobald eine Serie dazukommt, ersetzt oder gelöscht wird. */
    fun revisions(): List<Triple<MatchId, DeviceId, Long>> =
        queries.revisions().executeAsList().map { Triple(MatchId(it.match_id), DeviceId(it.source_device_id), it.revision) }

    /** Höchster Puls über alle lesbaren Serien. */
    fun records(): HealthRecords {
        val best =
            allSeries()
                .mapNotNull { stored -> maxBpm(stored.series)?.let { stored.matchId to it } }
                .maxByOrNull { it.second }
        return HealthRecords(best?.second, best?.first)
    }

    fun deleteForMatch(matchId: MatchId) = queries.deleteForMatch(matchId.value)

    /** „Alle Gesundheitsdaten löschen“ bzw. Widerruf der Einwilligung: alle Serien aller Konten auf diesem Gerät. */
    fun deleteAll() = queries.deleteAll()

    private fun Health_series.toStored(): StoredHealthSeries =
        StoredHealthSeries(
            MatchId(match_id),
            DeviceId(source_device_id),
            revision,
            HealthSeries(
                start_ms,
                interval_ms,
                values(bpm_csv) { it.toInt() },
                values(steps_csv) { it.toLong() },
                clock_offset_ms,
                HealthQuality.valueOf(quality),
            ),
        )

    private companion object {
        const val NO_DATA = "-"

        fun csv(values: List<Number?>): String = values.joinToString(",") { it?.toString() ?: NO_DATA }

        fun <T : Any> values(
            csv: String,
            parse: (String) -> T,
        ): List<T?> = if (csv.isEmpty()) emptyList() else csv.split(',').map { if (it == NO_DATA) null else parse(it) }
    }
}
