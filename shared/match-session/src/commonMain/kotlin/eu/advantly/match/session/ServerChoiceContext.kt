package eu.advantly.match.session

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EngineState
import eu.advantly.core.scoring.EventLog
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.ServerChosen

/** Wahlphase, kein tatsächlicher Game-Index (im Tiebreak: vor Punkt 1 bzw. Punkt 2). */
enum class ServerChoicePhase {
    FIRST,
    SECOND,
}

/**
 * Plattformfreies, lokales Wahlfenster (F30). Identität: [matchId], [setIndex] (nullbasiert), [phase].
 * [players] folgt der Engine-Reihenfolge, [dueSide] ist das aktuell fällige Team. [defaultServer] schlägt bei „Später“
 * tatsächlich auf. Texte in der UI anhand [gameKind] lokalisieren; keine Übersetzungen oder Dismiss-Merker im Modell.
 * [chosen] ist die noch wirksame Wahl. Nach Undo auf null Ballwechsel auch eine markierte Wahl überprüfen lassen.
 * Dieser Kontext wird nicht serialisiert und ist kein Event-/Wire-Feld.
 */
data class ServerChoiceContext(
    val matchId: MatchId,
    val setIndex: Int,
    val phase: ServerChoicePhase,
    val players: List<PlayerId>,
    val dueSide: Side,
    val defaultServer: PlayerId,
    val chosen: PlayerId?,
    val gameKind: GameKind,
) {
    /**
     * Prüfung unmittelbar vor Eventerzeugung: Fensterwechsel und geänderte Auswahl/Korrektur verwerfen.
     * Die gleiche wirksame Wahl bleibt mit dem ursprünglichen Kontext idempotent, auch bei umsortierten Spielern
     * oder gewechseltem eröffnendem Team. Namen/Personenauflösung sind für die Berechtigung unerheblich.
     */
    fun accepts(
        expected: ServerChoiceContext,
        player: PlayerId,
    ): Boolean =
        matchId == expected.matchId && setIndex == expected.setIndex && phase == expected.phase &&
            gameKind == expected.gameKind && players.toSet() == expected.players.toSet() && player in players &&
            (chosen == player || (dueSide == expected.dueSide && defaultServer == expected.defaultServer && chosen == expected.chosen))
}

/** Ableitung ausschließlich aus der Engine; `null` bei geschlossenem oder für diesen Sport unzulässigem Fenster. */
fun serverChoiceContext(
    matchId: MatchId,
    state: MatchState,
): ServerChoiceContext? {
    val choice = state.serverChoice ?: return null
    val current = state.current ?: return null
    val server = state.serve?.player ?: return null
    return ServerChoiceContext(
        matchId,
        current.setIndex,
        if (choice.game == 1) ServerChoicePhase.FIRST else ServerChoicePhase.SECOND,
        choice.players.toList(),
        choice.side,
        server,
        choice.chosen,
        current.gameKind,
    )
}

/**
 * Eine wirksame eröffnende Wahl in Satz 0 übersteuert INITIAL_SERVER beim Replay (Plan §3.5).
 * In diesem Fall rückwirkende Korrektur nicht anbieten/erzeugen; neu wählen oder SERVER „ab jetzt“ verwenden.
 * Auch die nach Undo auf null Ballwechsel umgedeutete zweite Wahl zählt. Historische Logs bleiben unverändert.
 */
fun canCorrectServerRetroactively(engine: EngineState): Boolean {
    val excluded = EventLog.analyze(engine.events, engine.initialScorer).undone + engine.state.ignoredEvents
    return engine.events.takeWhile { engine.point(it.id) == null }.none {
        val choice = it.payload as? ServerChosen
        choice?.set == 0 && it.id !in excluded
    }
}
