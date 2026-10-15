package eu.advantly.gate.check

import eu.advantly.core.model.DeviceId
import eu.advantly.core.scoring.EventLog
import eu.advantly.core.scoring.EventOrdering
import eu.advantly.core.scoring.LogAnalysis
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.data.ScoreSummary
import eu.advantly.data.StoredEvent
import eu.advantly.data.toScoreEventOrNull

/** Gesamturteil je Match. Reihenfolge = Schwere (für das Gesamturteil über alle Matches). */
enum class Verdict(
    val label: String,
) {
    PASSED("BESTANDEN"),
    DELETED("GELÖSCHT"),
    OPEN("OFFEN"),
    FAILED("NICHT BESTANDEN"),
}

/** Ein Befund; [verdict] ist das Urteil, das er mindestens erzwingt (`PASSED` = nur Hinweis). */
data class Finding(
    val verdict: Verdict,
    val text: String,
)

/**
 * Fold-Ergebnis eines Event-Satzes (02 §7.3/§8), zusammengefasst für den Bericht.
 *
 * @property accepted wirksam gezählte Ballwechsel (`pointsPlayed`)
 * @property pointEvents alle `PointWon` im Log (wirksam oder nicht)
 * @property undone durch `Undo` unwirksame Events
 * @property roleRejected Events eines Geräts, das an ihrer Position nicht Zähler war (verspätete Events des alten Zählers)
 * @property absorbed als Doppel-Tap zusammengeführte `PointWon`
 * @property conflictingCopies IDs mit abweichenden Kopien (keine Kopie zählt)
 */
data class FoldSummary(
    val state: MatchState,
    val events: Int,
    val pointEvents: Int,
    val accepted: Int,
    val undone: Int,
    val roleRejected: Int,
    val absorbed: Int,
    val conflictingCopies: Int,
    val invalidUndos: Int,
    val ignored: Int,
    val conflicts: Int,
    /** Zähler am Ende (im Prüfergebnis als Gerätename, siehe [DeviceNames]). */
    val scorer: String?,
) {
    val score: String get() = ScoreText.of(state)
}

/** Sicht eines Geräts auf ein Match. */
data class DeviceView(
    val label: String,
    val present: Boolean,
    val deleted: Boolean,
    val events: Int,
    val unconfirmed: Int,
    val missing: Int,
    val fold: FoldSummary?,
    val foldError: String?,
    val header: HeaderInfo?,
    val matchesUnion: Boolean,
)

/** Events eines erzeugenden Geräts (Autor) im Match, über alle Datenbanken vereinigt. */
data class AuthorView(
    val deviceId: String,
    val label: String,
    val events: Int,
    val maxSeq: Long,
    val gaps: List<LongRange>,
    val points: Int,
    val roleRejected: Int,
)

/** Zählerwechsel im vereinigten Log (in Ordnung). */
data class ScorerChange(
    val lamport: Long,
    val byLabel: String,
    val toLabel: String,
    val wallClockMs: Long,
)

/** Ergebnis der Integritätsprüfung eines Matches. */
data class MatchIntegrity(
    val matchId: String,
    val sport: String?,
    val createdAtMs: Long?,
    val verdict: Verdict,
    val findings: List<Finding>,
    val devices: List<DeviceView>,
    val authors: List<AuthorView>,
    val scorerChanges: List<ScorerChange>,
    val union: FoldSummary?,
)

/**
 * Integritätsprüfung „0 verlorene Punkte“ (PLAN Gate P1) über die Datenbanken mehrerer Geräte.
 *
 * Referenz ist die **erste** Datenbank (das Handy: dort landet jedes Match als Historie). Regeln je Match:
 * - **NICHT BESTANDEN:** Sequenzlücke eines Autors im vereinigten Log (ein Event existiert auf keinem Gerät mehr),
 *   ein Event fehlt in der Referenz, obwohl sein Halter es als bestätigt führt (Ack ohne Speicherung), abweichende Kopien
 *   derselben Event-ID, unlesbare Payload, fehlende Genesis oder unterschiedlicher Fold bei identischem Event-Satz.
 * - **OFFEN:** Der Referenz fehlen Events, die ihr Halter noch als unbestätigt führt (Sync ausstehend), oder ein
 *   beteiligtes Gerät hat nur eine Teilmenge (Anzeige nicht synchron) – nach dem Abgleich erneut ziehen.
 * - **GELÖSCHT:** Match auf der Referenz gelöscht (Löschmarkierung), kein Gate-Match – hat Vorrang vor allen Befunden.
 * - sonst **BESTANDEN**: alle beteiligten Geräte haben denselben Event-Satz und denselben Fold.
 *
 * Nicht erkennbar sind Events, die das erzeugende Gerät **als letzte** verloren hat (keine Lücke dahinter) und Tipps,
 * die nie zu einem Event wurden – dafür gibt es den Soll-Stand im Protokoll.
 */
object IntegrityCheck {
    fun check(
        snapshots: List<DeviceSnapshot>,
        filter: (matchId: String, createdAtMs: Long?) -> Boolean = { _, _ -> true },
    ): List<MatchIntegrity> {
        require(snapshots.isNotEmpty()) { "Mindestens eine Datenbank nötig" }
        val names = DeviceNames(snapshots)
        val ids = snapshots.flatMap { it.matches.keys + it.tombstones.keys }.toSortedSet()
        return ids
            .map { checkMatch(it, snapshots, names) }
            .filter { filter(it.matchId, it.createdAtMs) }
            .sortedWith(compareBy({ it.createdAtMs ?: Long.MAX_VALUE }, { it.matchId }))
    }

    /** Gesamturteil: das schwerste Einzelurteil; gelöschte Matches zählen nicht. */
    fun overall(results: List<MatchIntegrity>): Verdict =
        results.map { it.verdict }.filter { it != Verdict.DELETED }.maxOrNull() ?: Verdict.OPEN

    private fun checkMatch(
        matchId: String,
        snapshots: List<DeviceSnapshot>,
        names: DeviceNames,
    ): MatchIntegrity {
        val findings = mutableListOf<Finding>()
        val reference = snapshots.first()
        val holders = snapshots.filter { matchId in it.matches }

        // Vereinigung über alle Geräte; abweichende Kopien derselben ID sind ein harter Fehler
        val union = LinkedHashMap<String, StoredEvent>()
        holders.forEach { snap ->
            snap.matches.getValue(matchId).events.forEach { event ->
                val known = union[event.id]
                if (known == null) {
                    union[event.id] = event
                } else if (known.copy(confirmed = false) != event.copy(confirmed = false)) {
                    findings += Finding(Verdict.FAILED, "Event ${short(event.id)} liegt in abweichenden Kopien vor (${snap.label}).")
                }
            }
        }

        val deletedOn = snapshots.filter { matchId in it.tombstones }.map { it.label }
        if (deletedOn.isNotEmpty()) {
            findings += Finding(Verdict.PASSED, "Gelöscht auf: ${deletedOn.joinToString()}.")
        }

        // Unlesbare Payloads
        val parsed = union.values.associate { it.id to it.toScoreEventOrNull() }
        parsed.filterValues { it == null }.keys.forEach {
            findings += Finding(Verdict.FAILED, "Event ${short(it)} hat eine unlesbare Payload (Typ ${union.getValue(it).type}).")
        }
        val unionEvents = parsed.values.filterNotNull()

        // Fold über die Vereinigung; Zähler im Bericht als lesbarer Gerätename
        val unionFold = fold(unionEvents)?.let { it.copy(scorer = it.scorer?.let(names::of)) }
        val analysis = unionFold?.let { analysisOf(unionEvents) }
        // Sequenzlücken je Autor: lückenlose device_seq 1..max ist Pflicht (01 §4.3)
        val authors =
            union.values
                .groupBy { it.deviceId }
                .toSortedMap()
                .map { (device, events) ->
                    val seqs = events.map { it.deviceSeq }.toSortedSet()
                    val gaps = gapsOf(seqs)
                    val author = names.of(device)
                    gaps.forEach { gap ->
                        val text = "Sequenzlücke bei $author: device_seq ${rangeText(gap)} fehlt auf allen Geräten (Event verloren)."
                        findings += Finding(Verdict.FAILED, text)
                    }
                    val ids = events.map { it.id }.toSet()
                    AuthorView(
                        deviceId = device,
                        label = author,
                        events = events.size,
                        maxSeq = seqs.last(),
                        gaps = gaps,
                        points = events.count { it.type == "PointWon" },
                        roleRejected = analysis?.roleRejected?.count { it.value in ids } ?: 0,
                    )
                }

        if (union.isNotEmpty() && unionEvents.none { it.payload is MatchCreated }) {
            findings += Finding(Verdict.FAILED, "Keine Genesis (MatchCreated) auf irgendeinem Gerät – Match nicht rekonstruierbar.")
        }
        if (union.isEmpty() && holders.any { it.matches.getValue(matchId).header != null }) {
            findings += Finding(Verdict.FAILED, "Match-Kopf ohne ein einziges Event auf allen Geräten – Genesis verloren.")
        }

        // Referenz: jedes Event muss dort liegen (Handy = Historie)
        val referenceDeleted = matchId in reference.tombstones
        val referenceIds =
            reference.matches[matchId]
                ?.events
                ?.map { it.id }
                ?.toSet()
                .orEmpty()
        if (!referenceDeleted) {
            holders.filter { it !== reference }.forEach { snap ->
                val data = snap.matches.getValue(matchId)
                val missing = data.events.filter { it.id !in referenceIds }
                val (unconfirmed, confirmed) = missing.partition { it.id in data.unconfirmedIds }
                if (confirmed.isNotEmpty()) {
                    findings +=
                        Finding(
                            Verdict.FAILED,
                            "${confirmed.size} Event(s) auf ${snap.label} als bestätigt markiert, fehlen aber auf ${reference.label} " +
                                "(${byAuthor(confirmed, names)}).",
                        )
                }
                if (unconfirmed.isNotEmpty()) {
                    findings +=
                        Finding(
                            Verdict.OPEN,
                            "${unconfirmed.size} unbestätigte(s) Event(s) nur auf ${snap.label} (${byAuthor(unconfirmed, names)}) – " +
                                "Sync ausstehend: verbinden, abwarten, neu ziehen.",
                        )
                }
            }
        }

        // Sicht je Gerät inkl. Fold
        val devices =
            snapshots.map { snap ->
                val data = snap.matches[matchId]
                val deleted = matchId in snap.tombstones
                if (data == null) {
                    DeviceView(snap.label, false, deleted, 0, 0, 0, null, null, null, false)
                } else {
                    val events = data.events.mapNotNull { it.toScoreEventOrNull() }
                    val foldResult = runCatching { fold(events) }
                    val ids = data.events.map { it.id }.toSet()
                    DeviceView(
                        label = snap.label,
                        present = true,
                        deleted = deleted,
                        events = data.events.size,
                        unconfirmed = data.unconfirmedIds.size,
                        missing = union.keys.count { it !in ids },
                        fold = foldResult.getOrNull(),
                        foldError = foldResult.exceptionOrNull()?.message ?: if (foldResult.getOrNull() == null) "keine Genesis" else null,
                        header = data.header,
                        matchesUnion = ids == union.keys,
                    )
                }
            }

        devices.filter { it.present && !it.matchesUnion && it.label != reference.label }.forEach {
            findings +=
                Finding(
                    Verdict.OPEN,
                    "${it.label} hat nur ${it.events} von ${union.size} Events (${it.missing} fehlen) – Anzeige nicht synchron " +
                        "(unkritisch, wenn das Gerät dem Match bewusst nicht mehr folgt; dann im Protokoll vermerken).",
                )
        }
        val complete = devices.filter { it.present && it.matchesUnion }
        val states = complete.mapNotNull { it.fold?.state }.distinct()
        if (states.size > 1) {
            findings +=
                Finding(
                    Verdict.FAILED,
                    "Fold weicht bei identischem Event-Satz ab: ${complete.joinToString { "${it.label}=${it.fold?.score}" }}.",
                )
        }
        unionFold?.let { truth ->
            devices.filter { it.present && it.header != null && it.matchesUnion }.forEach { view ->
                val header = view.header!!
                if (header.status != truth.state.status.name ||
                    header.setsWonA != truth.state.setsWon.a ||
                    header.setsWonB != truth.state.setsWon.b
                ) {
                    findings +=
                        Finding(
                            Verdict.PASSED,
                            "Hinweis: Ergebnis-Cache (match_header) auf ${view.label} passt nicht zum Fold " +
                                "(${header.status} ${header.setsWonA}:${header.setsWonB} statt ${truth.state.status} " +
                                "${truth.state.setsWon.a}:${truth.state.setsWon.b}).",
                        )
                }
            }
            if (truth.roleRejected > 0) {
                findings +=
                    Finding(
                        Verdict.PASSED,
                        "${truth.roleRejected} Event(s) eines nicht (mehr) zählenden Geräts verworfen (Rollenprüfung 02 §7.5) – " +
                            "erwartet nach Übergabe mit verspäteten Events.",
                    )
            }
            if (truth.absorbed > 0) findings += Finding(Verdict.PASSED, "${truth.absorbed} Doppel-Tap(s) absorbiert.")
            if (truth.conflicts > 0) findings += Finding(Verdict.PASSED, "${truth.conflicts} aktive(r) Widerspruch/Widersprüche im Log.")
        }

        val scorerChanges =
            unionEvents
                .sortedWith(EventOrdering)
                .filter { it.payload is ScorerChanged }
                .map {
                    ScorerChange(
                        it.lamport,
                        names.of(it.deviceId.value),
                        names.of((it.payload as ScorerChanged).scorerDeviceId.value),
                        it.wallClockMs,
                    )
                }

        val header = holders.firstNotNullOfOrNull { it.matches.getValue(matchId).header }
        val genesis = unionEvents.firstOrNull { it.payload is MatchCreated }
        val sport = header?.sport ?: (genesis?.payload as? MatchCreated)?.config?.sport?.name
        val created = header?.createdAtMs ?: genesis?.wallClockMs

        val verdict =
            when {
                // Auf der Referenz gelöscht: Reste auf anderen Geräten sind kein Gate-Befund (Löschung läuft nach)
                referenceDeleted || (union.isEmpty() && deletedOn.isNotEmpty()) -> Verdict.DELETED

                findings.any { it.verdict == Verdict.FAILED } -> Verdict.FAILED

                findings.any { it.verdict == Verdict.OPEN } -> Verdict.OPEN

                else -> Verdict.PASSED
            }
        return MatchIntegrity(matchId, sport, created, verdict, findings, devices, authors, scorerChanges, unionFold)
    }

    /** Fold über ein vollständiges Log mit Genesis; `null` ohne Genesis. */
    fun fold(events: List<ScoreEvent>): FoldSummary? {
        if (events.none { it.payload is MatchCreated }) return null
        val engine = ScoringEngine.replay(events)
        val analysis = analysisOf(events)
        return FoldSummary(
            state = engine.state,
            events = events.size,
            pointEvents = events.count { it.payload is PointWon },
            accepted = engine.pointsPlayed,
            undone = analysis.undone.size,
            roleRejected = analysis.roleRejected.size,
            absorbed = analysis.absorbed.size,
            conflictingCopies = analysis.rejected.size,
            invalidUndos = analysis.invalidUndos.size,
            ignored = engine.state.ignoredEvents.size,
            conflicts = engine.conflicts.size,
            scorer = engine.state.scorerDeviceId?.value,
        )
    }

    /** Dieselbe Log-Analyse wie `ScoringEngine.replay` (Genesis heraus, Startzähler aus der Genesis). */
    private fun analysisOf(events: List<ScoreEvent>): LogAnalysis {
        val ordered = EventLog.distinctOrdered(events)
        val genesis = ordered.first { it.payload is MatchCreated }
        val created = genesis.payload as MatchCreated
        val scorer: DeviceId = created.scorerDeviceId ?: genesis.deviceId
        return EventLog.analyze(ordered.filter { it != genesis }, scorer)
    }

    /** Fehlende Bereiche in 1..max. */
    fun gapsOf(seqs: Collection<Long>): List<LongRange> {
        val sorted = seqs.toSortedSet()
        val gaps = mutableListOf<LongRange>()
        var expected = 1L
        for (seq in sorted) {
            if (seq > expected) gaps += expected until seq
            expected = seq + 1
        }
        return gaps
    }

    private fun rangeText(range: LongRange) = if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}"

    private fun byAuthor(
        events: List<StoredEvent>,
        names: DeviceNames,
    ): String =
        events
            .groupBy { it.deviceId }
            .entries
            .joinToString { (device, list) ->
                "${list.size}× von ${names.of(device)}: ${list.map { it.type }.distinct().joinToString("/")}"
            }

    fun short(id: String): String = id.takeLast(8)
}

/** Lesbare Namen für Geräte-IDs: Label der Datenbank, deren eigene ID es ist, sonst bekannte Art + Kurz-ID. */
class DeviceNames(
    snapshots: List<DeviceSnapshot>,
) {
    private val own = snapshots.mapNotNull { s -> s.ownDeviceId?.let { it to s.label } }.toMap()
    private val kinds = snapshots.flatMap { it.knownKinds.entries }.associate { it.key to it.value }

    fun of(deviceId: String): String =
        own[deviceId] ?: kinds[deviceId]?.let { "${it.lowercase()}-${IntegrityCheck.short(deviceId)}" }
            ?: "gerät-${IntegrityCheck.short(deviceId)}"
}

/** Spielstand als kurzer Text: Sätze, laufender Satz, Status. */
object ScoreText {
    fun of(state: MatchState): String {
        val summary = ScoreSummary.of(state)
        val sets =
            summary.sets.joinToString(" ") { set ->
                val tb = if (set.tiebreakA != null && set.tiebreakB != null) "(${set.tiebreakA}:${set.tiebreakB})" else ""
                "${set.a}:${set.b}$tb"
            }
        val current =
            state.current?.let { c ->
                val points = "${c.display.a}:${c.display.b}"
                "laufend " + (c.games?.let { "${it.a}:${it.b} ($points)" } ?: points)
            }
        val result = state.result?.let { r -> " → ${r.reason}${r.winner?.let { " Sieger $it" }.orEmpty()}" }.orEmpty()
        return listOf(sets, current.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "0:0" } + " (${state.status}$result)"
    }
}
