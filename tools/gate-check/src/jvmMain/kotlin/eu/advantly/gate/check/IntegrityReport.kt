package eu.advantly.gate.check

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Markdown-Berichte der Integritätsprüfung: eine Übersicht plus eine Datei je Match. */
object IntegrityReport {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())

    fun time(ms: Long?): String = ms?.takeIf { it > 0 }?.let { TIME.format(Instant.ofEpochMilli(it)) } ?: "—"

    /** Dateiname des Match-Berichts, z. B. `match-20261020-1830-tennis-1a2b3c4d.md`. */
    fun fileName(match: MatchIntegrity): String {
        val at = match.createdAtMs?.takeIf { it > 0 }?.let { FILE_TIME.format(Instant.ofEpochMilli(it)) } ?: "unbekannt"
        val sport = match.sport?.lowercase() ?: "sport"
        return "match-$at-$sport-${IntegrityCheck.short(match.matchId)}.md"
    }

    fun index(
        results: List<MatchIntegrity>,
        sources: List<Pair<String, String>>,
        generatedAtMs: Long,
    ): String =
        buildString {
            appendLine("# P1-Gate – Integritätsprüfung „0 verlorene Punkte“")
            appendLine()
            appendLine("Erzeugt: ${time(generatedAtMs)} · Referenz: **${sources.first().first}** (Handy-Historie)")
            appendLine()
            appendLine("| Datenbank | Datei |")
            appendLine("|---|---|")
            sources.forEach { (label, path) -> appendLine("| $label | `$path` |") }
            appendLine()
            appendLine("**Gesamt: ${IntegrityCheck.overall(results).label}** (${results.size} Matches)")
            appendLine()
            appendLine("| Match | Start | Sportart | Ergebnis (Fold) | Punkte wirksam | Rolle verworfen | Urteil |")
            appendLine("|---|---|---|---|---|---|---|")
            results.forEach { m ->
                appendLine(
                    "| [${IntegrityCheck.short(m.matchId)}](${fileName(m)}) | ${time(m.createdAtMs)} | ${m.sport ?: "—"} | " +
                        "${m.union?.score ?: "—"} | ${m.union?.accepted ?: "—"} | ${m.union?.roleRejected ?: "—"} | **${m.verdict.label}** |",
                )
            }
            appendLine()
            appendLine("Urteile: BESTANDEN = alle Geräte gleicher Event-Satz und Fold, keine Lücke; OFFEN = Sync ausstehend bzw.")
            appendLine("Teilmenge (nach Abgleich neu ziehen oder begründen); NICHT BESTANDEN = Event verloren/abweichend.")
            appendLine("Den Soll-Stand aus dem Protokollbogen mit „Ergebnis (Fold)“ vergleichen – Abweichung = verlorener/doppelter Punkt.")
        }

    fun match(m: MatchIntegrity): String =
        buildString {
            appendLine("# Match ${IntegrityCheck.short(m.matchId)} – ${m.verdict.label}")
            appendLine()
            appendLine("- Match-ID: `${m.matchId}`")
            appendLine("- Sportart: ${m.sport ?: "—"} · Start: ${time(m.createdAtMs)}")
            m.union?.let { u ->
                appendLine("- Ergebnis (Fold über alle Events): **${u.score}**")
                appendLine("- Zähler am Ende: ${u.scorer ?: "—"}")
            }
            appendLine()
            appendLine("## Befunde")
            appendLine()
            if (m.findings.isEmpty()) appendLine("- keine")
            m.findings.forEach { appendLine("- ${marker(it.verdict)} ${it.text}") }
            appendLine()
            appendLine("## Geräte")
            appendLine()
            appendLine("| Gerät | vorhanden | Events | unbestätigt | fehlen ggü. Vereinigung | Fold | wirksame Punkte | Kopf-Cache |")
            appendLine("|---|---|---|---|---|---|---|---|")
            m.devices.forEach { d ->
                val presence =
                    when {
                        d.deleted -> "gelöscht"
                        d.present -> "ja"
                        else -> "nein"
                    }
                val fold = d.fold?.score ?: d.foldError?.let { "— ($it)" } ?: "—"
                val header = d.header?.let { "${it.status} ${it.setsWonA}:${it.setsWonB}" } ?: "—"
                appendLine(
                    "| ${d.label} | $presence | ${d.events} | ${d.unconfirmed} | ${d.missing} | $fold | ${d.fold?.accepted ?: "—"} | $header |",
                )
            }
            appendLine()
            appendLine("## Events je erzeugendem Gerät (vereinigt)")
            appendLine()
            appendLine("| Autor | Events | max. device_seq | Lücken | PointWon | von Rollenprüfung verworfen |")
            appendLine("|---|---|---|---|---|---|")
            m.authors.forEach { a ->
                val gaps = if (a.gaps.isEmpty()) "keine" else a.gaps.joinToString { "${it.first}–${it.last}" }
                appendLine("| ${a.label} | ${a.events} | ${a.maxSeq} | $gaps | ${a.points} | ${a.roleRejected} |")
            }
            m.union?.let { u ->
                appendLine()
                appendLine("## Fold (akzeptiert/verworfen)")
                appendLine()
                appendLine("| Größe | Wert |")
                appendLine("|---|---|")
                appendLine("| Events (ohne Duplikate) | ${u.events} |")
                appendLine("| PointWon gesamt | ${u.pointEvents} |")
                appendLine("| wirksam gezählte Ballwechsel | ${u.accepted} |")
                appendLine("| per Undo unwirksam | ${u.undone} |")
                appendLine("| Rollenprüfung verworfen (nicht zählendes Gerät) | ${u.roleRejected} |")
                appendLine("| Doppel-Tap absorbiert | ${u.absorbed} |")
                appendLine("| abweichende Kopien | ${u.conflictingCopies} |")
                appendLine("| ungültige Undos | ${u.invalidUndos} |")
                appendLine("| aktive Widersprüche | ${u.conflicts} |")
                appendLine("| ignoredEvents (Engine) | ${u.ignored} |")
            }
            if (m.scorerChanges.isNotEmpty()) {
                appendLine()
                appendLine("## Zählerwechsel")
                appendLine()
                appendLine("| Lamport | Zeit (Gerät) | ausgelöst von | neuer Zähler |")
                appendLine("|---|---|---|---|")
                m.scorerChanges.forEach { appendLine("| ${it.lamport} | ${time(it.wallClockMs)} | ${it.byLabel} | ${it.toLabel} |") }
            }
        }

    private fun marker(verdict: Verdict): String =
        when (verdict) {
            Verdict.FAILED -> "**[FEHLER]**"
            Verdict.OPEN -> "**[OFFEN]**"
            else -> "[Info]"
        }
}
