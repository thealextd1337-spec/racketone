package eu.advantly.presentation.stats

import eu.advantly.core.model.EventId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.CorrectedSet
import eu.advantly.core.scoring.CorrectionCheck
import eu.advantly.core.scoring.EngineState
import eu.advantly.core.scoring.GameServer
import eu.advantly.core.scoring.ResultCorrected
import eu.advantly.core.scoring.ResultCorrection
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.ServiceGame
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SetRecord
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.MatchRecord

/**
 * „Ergebnis bearbeiten“ (0.3.0): Ausgangswerte, Prüfung und Vorschau für den Editor – rein, ohne Port. Gespeichert wird
 * über `MatchHistoryPort.correctResult` (Sätze und Aufschläger in einem Vorgang).
 */
class ResultEditing(
    private val record: MatchRecord,
) {
    private val engine: EngineState = ScoringEngine.replay(record.events)

    /** Wählbare Satzarten der Sportart (Tennis/Padel: Satz und Match-Tiebreak). */
    val setKinds: List<SetKind> = ResultCorrection.setKinds(engine.format)

    /** Aufschläger je Spiel gibt es nur bei Tennis/Padel (sonst folgt der Aufschlag aus der Punktfolge). */
    val serversEditable: Boolean = engine.format.sport.isTennisLike

    /**
     * Aktuelle (ggf. schon korrigierte) Sätze als Startwerte; bei vorzeitigem Ende (Aufgabe, Abbruch, „Beenden“) zuletzt
     * der eingefrorene laufende Satz als unvollständiger Satz.
     */
    val initialSets: List<CorrectedSet> =
        record.finalState.sets.map(::corrected) +
            listOfNotNull(
                record.finalState.current?.let { CorrectedSet(it.setKind, ResultCorrection.unitsOf(it), incomplete = true) },
            )

    /** Bisher wirksame Aufschläger-Korrekturen. */
    val initialServers: Map<Pair<Int, Int>, PlayerId> = engine.gameServers.associate { (it.set to it.game) to it.player }

    /** Plausibilität und abgeleitetes Ergebnis wie im Fold. */
    fun check(sets: List<CorrectedSet>): CorrectionCheck = engine.checkCorrection(sets)

    /**
     * Standardspiele mit Aufschläger und Sieger, wie sie nach dem Speichern von [sets] wären (Vorschau über ein
     * angehängtes, nicht gespeichertes `ResultCorrected`); bei unveränderten Sätzen der gespeicherte Stand.
     */
    fun serviceGames(sets: List<CorrectedSet>): List<ServiceGame> {
        if (!serversEditable) return emptyList()
        if (sets == initialSets) return ScoringEngine.serviceGames(record.events)
        val genesis = record.events.first()
        val last = record.events.maxByOrNull { it.lamport } ?: genesis
        val preview =
            ScoreEvent(
                EventId("result-preview"),
                genesis.matchId,
                record.finalState.scorerDeviceId ?: genesis.deviceId,
                0,
                last.lamport + 1,
                last.wallClockMs,
                null,
                ResultCorrected(sets),
            )
        return ScoringEngine.serviceGames(record.events + preview)
    }

    /** Vollständige Aufschläger-Liste fürs Speichern: nur Spiele, die es in [games] gibt, sortiert nach Satz und Spiel. */
    fun servers(
        chosen: Map<Pair<Int, Int>, PlayerId>,
        games: List<ServiceGame>,
    ): List<GameServer> {
        val existing = games.mapTo(HashSet()) { it.set to it.game }
        return chosen
            .filterKeys { it in existing }
            .mapNotNull { (key, player) -> engine.lineup.sideOf(player)?.let { GameServer(key.first, key.second, player, it) } }
            .sortedWith(compareBy({ it.set }, { it.game }))
    }

    private fun corrected(set: SetRecord): CorrectedSet =
        when (set.kind) {
            SetKind.REGULAR -> CorrectedSet(set.kind, set.games ?: ZERO, set.tiebreak)
            SetKind.MATCH_TIEBREAK -> CorrectedSet(set.kind, set.tiebreak ?: set.points ?: ZERO)
            else -> CorrectedSet(set.kind, set.points ?: ZERO)
        }

    private companion object {
        val ZERO = SidePair(0, 0)
    }
}
