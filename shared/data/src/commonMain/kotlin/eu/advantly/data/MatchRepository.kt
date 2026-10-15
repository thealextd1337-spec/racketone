package eu.advantly.data

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.CurrentSet
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.SetKind
import eu.advantly.data.db.MatchHeaderQueries
import eu.advantly.data.db.MatchTombstoneQueries
import eu.advantly.data.db.Match_header
import kotlinx.serialization.Serializable

/**
 * Stand eines Satzes für Listen. Tennis/Padel-Satz: [a]/[b] = Spiele, [tiebreakA]/[tiebreakB] = Tiebreak-Punkte;
 * Match-Tiebreak ([matchTiebreak]) und Pickleball/Badminton/Tischtennis: [a]/[b] = Punkte.
 */
@Serializable
data class SetScore(
    val a: Int,
    val b: Int,
    val tiebreakA: Int? = null,
    val tiebreakB: Int? = null,
    val matchTiebreak: Boolean = false,
)

/**
 * Ergebnis-Zusammenfassung eines Matches für die Historie (01 §5.1 `match_summary`) – abgeleitet aus dem
 * Engine-Zustand, jederzeit neu berechenbar.
 *
 * @property current laufender Satz (Spiele bzw. Punkte); bei Aufgabe/Abbruch der eingefrorene Stand, `null` nach
 *   regulärem Ende bzw. im Satzende-Dialog
 */
@Serializable
data class ScoreSummary(
    val winner: Side? = null,
    val reason: ResultReason? = null,
    val setsWonA: Int = 0,
    val setsWonB: Int = 0,
    val sets: List<SetScore> = emptyList(),
    val current: SetScore? = null,
) {
    companion object {
        val EMPTY: ScoreSummary = ScoreSummary()

        private fun currentScore(c: CurrentSet): SetScore {
            val games = c.games
            return if (games !=
                null
            ) {
                SetScore(games.a, games.b)
            } else {
                SetScore(c.points.a, c.points.b, matchTiebreak = c.setKind == SetKind.MATCH_TIEBREAK)
            }
        }

        /** Zusammenfassung aus dem kanonischen Zustand (02 §8). */
        fun of(state: MatchState): ScoreSummary =
            ScoreSummary(
                winner = state.result?.winner,
                reason = state.result?.reason,
                setsWonA = state.setsWon.a,
                setsWonB = state.setsWon.b,
                sets =
                    state.sets.map { set ->
                        val games = set.games
                        when {
                            set.kind == SetKind.REGULAR && games != null -> {
                                SetScore(games.a, games.b, set.tiebreak?.a, set.tiebreak?.b)
                            }

                            set.kind == SetKind.MATCH_TIEBREAK -> {
                                val points = set.tiebreak ?: set.points
                                SetScore(points?.a ?: 0, points?.b ?: 0, matchTiebreak = true)
                            }

                            else -> {
                                SetScore(set.points?.a ?: 0, set.points?.b ?: 0)
                            }
                        }
                    },
                // Engine liefert current = null bei regulärem Ende; bei Aufgabe/Abbruch bleibt der Satz eingefroren
                current = state.current?.let { currentScore(it) },
            )
    }
}

/**
 * Match-Kopf mit Ergebnis-Cache (01 §5.1 `match` + `match_summary`).
 *
 * @property status Engine-Status; `FINISHED` = beendet (regulär, Aufgabe, abgebrochen, „Noch ein Satz?“ = Nein)
 * @property previousMatchId Vorgänger bei einem verknüpften Folge-Match (02 §5.2)
 */
data class MatchRecord(
    val id: MatchId,
    val format: MatchFormat,
    val lineup: Lineup,
    val meta: MatchMeta,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val status: MatchStatus,
    val score: ScoreSummary,
    val previousMatchId: MatchId?,
) {
    val sport: Sport get() = format.sport
    val discipline: Discipline get() = format.discipline
    val isFinished: Boolean get() = status == MatchStatus.FINISHED
}

/** Dauerhafte Löschmarkierung eines Matches. */
data class MatchTombstone(
    val matchId: MatchId,
    val deletedAtMs: Long,
)

/** Match-Köpfe und Historie-Abfragen. Synchron, ein Thread (siehe [EventStore]). */
class MatchRepository internal constructor(
    private val queries: MatchHeaderQueries,
    private val tombstones: MatchTombstoneQueries,
    private val assignments: (MatchId) -> Map<PlayerId, PlayerId>,
) {
    /**
     * Legt den Kopf an, falls [id] noch unbekannt ist (idempotent – Genesis und `MatchMeta` können beide zuerst
     * ankommen). Liefert `true`, wenn neu angelegt. Gelöschte Matches (Tombstone) werden nie wieder angelegt.
     */
    @Suppress("LongParameterList")
    fun insertIfAbsent(
        id: MatchId,
        format: MatchFormat,
        lineup: Lineup,
        meta: MatchMeta,
        createdAtMs: Long,
        previousMatchId: MatchId? = null,
    ): Boolean =
        queries.transactionWithResult {
            if (isDeleted(id)) return@transactionWithResult false
            if (queries.countById(id.value).executeAsOne() > 0) {
                previousMatchId?.let { queries.updatePrevious(it.value, id.value) }
                return@transactionWithResult false
            }
            queries.insertOrIgnore(
                id = id.value,
                sport = format.sport.name,
                discipline = format.discipline.name,
                format_json = DataJson.encodeToString(MatchFormat.serializer(), format),
                lineup_json = DataJson.encodeToString(Lineup.serializer(), lineup),
                meta_json = DataJson.encodeToString(MatchMeta.serializer(), meta),
                surface = meta.surface?.name,
                environment = meta.environment?.name,
                created_at_ms = createdAtMs,
                updated_at_ms = createdAtMs,
                status = MatchStatus.IN_PROGRESS.name,
                previous_match_id = previousMatchId?.value,
            )
            true
        }

    /** Aktualisiert Status und Ergebnis-Cache (nach jedem Event des Matches). */
    fun updateProgress(
        id: MatchId,
        status: MatchStatus,
        score: ScoreSummary,
        updatedAtMs: Long,
    ) {
        queries.updateProgress(
            status = status.name,
            winner = score.winner?.name,
            result_reason = score.reason?.name,
            sets_won_a = score.setsWonA.toLong(),
            sets_won_b = score.setsWonB.toLong(),
            score_json = DataJson.encodeToString(ScoreSummary.serializer(), score),
            updated_at_ms = updatedAtMs,
            id = id.value,
        )
    }

    fun get(id: MatchId): MatchRecord? = queries.selectById(id.value).executeAsOneOrNull()?.toRecord()

    /**
     * Historie, neueste zuerst: optional nur [sport], nur Matches mit `createdAt` in [fromMs] bis ausschließlich [toMs].
     */
    fun history(
        sport: Sport? = null,
        fromMs: Long = 0,
        toMs: Long = Long.MAX_VALUE,
        limit: Long = 50,
        offset: Long = 0,
    ): List<MatchRecord> = queries.history(sport?.name, fromMs, toMs, limit, offset).executeAsList().map { it.toRecord() }

    /** Neueste Besetzungen für Mitspieler-Vorschläge, ohne Format, Metadaten und Ergebnis zu dekodieren. */
    fun recentLineups(
        sport: Sport? = null,
        limit: Long = 100,
    ): List<Lineup> =
        queries.recentLineups(sport?.name, limit).executeAsList().map { row ->
            val lineup = DataJson.decodeFromString(Lineup.serializer(), row.lineup_json)
            val assigned = assignments(MatchId(row.id))

            fun person(id: PlayerId) = assigned[id] ?: id
            lineup.copy(
                a = lineup.a.map(::person),
                b = lineup.b.map(::person),
                firstServer = person(lineup.firstServer),
                receiveRight = lineup.receiveRight?.let { it.copy(a = it.a?.let(::person), b = it.b?.let(::person)) },
            )
        }

    /** Nicht beendete Matches, zuletzt geändertes zuerst (Resume-Karte). */
    fun active(): List<MatchRecord> = queries.active().executeAsList().map { it.toRecord() }

    /** Zuletzt geänderte Matches (für die Anti-Entropie). */
    fun recent(limit: Long): List<MatchRecord> = queries.recent(limit).executeAsList().map { it.toRecord() }

    /** Folge-Matches von [id] (02 §5.2), älteste zuerst. */
    fun followUps(id: MatchId): List<MatchRecord> = queries.followUps(id.value).executeAsList().map { it.toRecord() }

    /** „Wie letztes Mal“: Besetzung des jüngsten Matches dieser Sportart (optional Disziplin). */
    fun lastLineup(
        sport: Sport,
        discipline: Discipline? = null,
    ): Lineup? =
        queries
            .lastOfSport(sport.name, discipline?.name)
            .executeAsOneOrNull()
            ?.toRecord()
            ?.let { record ->
                val assigned = assignments(record.id)

                fun person(id: PlayerId) = assigned[id] ?: id
                record.lineup.let { lineup ->
                    lineup.copy(
                        a = lineup.a.map(::person),
                        b = lineup.b.map(::person),
                        firstServer = person(lineup.firstServer),
                        receiveRight = lineup.receiveRight?.let { it.copy(a = it.a?.let(::person), b = it.b?.let(::person)) },
                    )
                }
            }

    /** `true`, wenn [id] gelöscht wurde (dauerhafte Löschmarkierung). */
    fun isDeleted(id: MatchId): Boolean = tombstones.countById(id.value).executeAsOne() > 0

    /** Alle Löschmarkierungen (für die Replikation an gekoppelte Geräte), älteste zuerst. */
    fun deleted(): List<MatchTombstone> = tombstones.selectAll { id, at -> MatchTombstone(MatchId(id), at) }.executeAsList()

    internal fun markDeleted(
        id: MatchId,
        deletedAtMs: Long,
    ) {
        tombstones.insertOrIgnore(id.value, deletedAtMs)
    }

    /** Löscht nur den Kopf; Events löscht [LocalData.deleteMatch] mit. */
    fun delete(id: MatchId) {
        queries.deleteById(id.value)
    }

    private fun Match_header.toRecord(): MatchRecord =
        MatchRecord(
            id = MatchId(id),
            format = DataJson.decodeFromString(MatchFormat.serializer(), format_json),
            lineup = DataJson.decodeFromString(Lineup.serializer(), lineup_json),
            meta = DataJson.decodeFromString(MatchMeta.serializer(), meta_json),
            createdAtMs = created_at_ms,
            updatedAtMs = updated_at_ms,
            status = MatchStatus.valueOf(status),
            score = scoreOf(score_json),
            previousMatchId = previous_match_id?.let { MatchId(it) },
        )

    private fun scoreOf(json: String): ScoreSummary =
        try {
            DataJson.decodeFromString(ScoreSummary.serializer(), json)
        } catch (e: IllegalArgumentException) {
            ScoreSummary.EMPTY
        }
}
