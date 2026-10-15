package eu.advantly.gate.check

/** Markdown-Bericht der Akku-/Laufzeitmessung einer Uhr. */
object BatteryReport {
    fun render(
        label: String,
        sources: List<String>,
        log: FieldLog,
        matches: List<BatterySection>,
        whole: BatterySection?,
        generatedAtMs: Long,
    ): String =
        buildString {
            appendLine("# P1-Gate – Akku/Laufzeit Uhr: $label")
            appendLine()
            appendLine("Erzeugt: ${IntegrityReport.time(generatedAtMs)} · Gerät: ${log.device ?: "unbekannt"}")
            appendLine()
            sources.forEach { appendLine("- Quelle: `$it`") }
            appendLine("- Messpunkte: ${log.samples.size}, verworfene Zeilen: ${log.skippedLines}")
            appendLine()
            val rated = matches.filter { it.verdict != BatteryVerdict.UNCLEAR }
            val overall =
                when {
                    rated.any { it.verdict == BatteryVerdict.FAILED } -> BatteryVerdict.FAILED
                    rated.isNotEmpty() -> BatteryVerdict.PASSED
                    else -> BatteryVerdict.UNCLEAR
                }
            appendLine("**Gesamt (Matches mit Urteil): ${overall.label}** – Schwelle < ${BatteryAnalysis.MAX_RATE_PER_HOUR.toInt()} %/h")
            appendLine()
            appendLine(
                "| Abschnitt | Sportart | Start | Dauer (min) | ohne Laden (min) | Akku Start → Ende | Verbrauch | %/h | " +
                    "mAh | Display an/Ambient/aus (%) | Workout (%) | Punkte | Sync gesendet/empfangen | Neustarts | max. Lücke (min) | Abschnitte | Urteil |",
            )
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            (matches + listOfNotNull(whole)).forEach { appendLine(row(it)) }
            appendLine()
            appendLine("Hinweise:")
            appendLine(
                "- %/h zählt nur Zeit ohne Laden; der Akkuwert hat 1 % Auflösung, unter ${BatteryAnalysis.MIN_DISCHARGE_MINUTES.toInt()} min ohne Laden → UNKLAR.",
            )
            appendLine(
                "- Je Match zählen nur zusammenhängende Messpunkte dieses Matches („Abschnitte“); Laden/Leerlauf dazwischen trennt. " +
                    "Akkuanstieg ohne erkanntes Laden → UNKLAR.",
            )
            appendLine("- Der Minutentakt kann im Tiefschlaf aussetzen („max. Lücke“); %/h nutzt echte Zeitstempel und bleibt gültig.")
            appendLine(
                "- Display-Anteile stammen vom Systemdisplay (an / Ambient = Doze / aus), unabhängig davon, welche App sichtbar ist.",
            )
            matches
                .filter { it.verdict != BatteryVerdict.PASSED }
                .forEach { appendLine("- ${it.title}: ${it.verdict.label} – ${it.reason}") }
        }

    private fun row(s: BatterySection): String {
        fun pct(v: Double?) = v?.let { "%.0f".format(it) } ?: "—"
        val battery = "${s.batteryStart ?: "—"} → ${s.batteryEnd ?: "—"}"
        val rate = s.ratePerHour?.let { "%.1f".format(it) } ?: "—"
        val mah = s.chargeDropMah?.let { "%.0f".format(it) } ?: "—"
        val screen = "${pct(s.screenOnPct)}/${pct(s.ambientPct)}/${pct(s.offPct)}"
        val charging = if (s.chargingMinutes > 0) " (lud %.0f min)".format(s.chargingMinutes) else ""
        val duration = "%.0f".format(s.durationMinutes)
        val discharge = "%.0f".format(s.dischargeMinutes) + charging
        val sync = "${s.sent}/${s.received}" + if (s.failed > 0) " (${s.failed} Fehler)" else ""
        val gap = "%.1f".format(s.maxGapMinutes)
        return "| ${s.title} | ${s.sport ?: "—"} | ${IntegrityReport.time(s.startMs)} | $duration | $discharge | $battery | " +
            "${s.dropPercent} % | $rate | $mah | $screen | ${pct(s.workoutPct)} | ${s.points ?: "—"} | $sync | ${s.restarts} | " +
            "$gap | ${s.segments} | **${s.verdict.label}** |"
    }
}
