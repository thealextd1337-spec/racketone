package eu.advantly.wear.live

import eu.advantly.core.model.EventId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.match.session.ActiveMatch
import eu.advantly.match.session.SyncStatus
import eu.advantly.presentation.match.UpdateRequired
import eu.advantly.sync.protocol.CompatibilityStatus

// Bedienung W2 (Feldtest 2026-10-15, docs/w2-ui-vertrag.md): Aufschlag-Leiste, Aufschlägerwahl im Doppel,
// Aktionsleiste im Mittelband und Versionshinweis – reine Modelle und Regeln ohne Android (JVM-getestet).

/**
 * Aufschlag-Leiste nach dem Punkt (W2-Vertrag §2, 02 §7.2): „1. · 2. · DF“ für [pointId] (= `lastPoint`).
 *
 * @property options wählbare Werte; `DOUBLE_FAULT` nur, wenn die Rückschlagseite den Punkt gewann
 * @property selected aktuell erfasster Wert (markieren) oder `null`
 */
data class ServeBarUi(
    val pointId: EventId,
    val options: List<ServeOutcome>,
    val selected: ServeOutcome?,
)

/**
 * Offene Aufschlägerwahl im Doppel (02 §6.5.5, W2-Vertrag §2 `serverChoice`), nur am Zähler.
 *
 * @property game 1 = Satzbeginn (alle vier wählbar), 2 = erstes Aufschlagspiel des anderen Teams
 * @property side Team, das nach Regel aufschlägt (Game 1: Vorschlag, dessen Spieler stehen vorn)
 * @property players wählbare Spieler in der Reihenfolge der Engine
 * @property chosen in diesem Fenster bereits gewählter Spieler, sonst `null`
 * @property prompt Hinweis „Aufschlag wählen“ im Mittelband zeigen: Wahl offen und nicht schon beim Start getroffen
 *   (Game 1 des ersten Satzes legt der Start-Assistent über den Erstaufschläger fest)
 */
data class ServerChoiceUi(
    val game: Int,
    val side: Side,
    val players: List<PlayerUi>,
    val chosen: PlayerId?,
    val prompt: Boolean,
)

/**
 * Sichtbarkeit der Aufschlag-Leiste: erscheint nach jedem auf dieser Uhr gezählten Punkt für [VISIBLE_MILLIS], nach einer
 * Auswahl noch [AFTER_CHOICE_MILLIS] (Markierung sichtbar, Korrektur möglich), dann blendet sie sich aus. Ein neuer
 * Punkt ersetzt sie (neuer `lastPoint`); Undo, „Punkt abziehen“ und Aufwachen aus Ambient blenden sie aus.
 * Zeiten monoton (ms), als Parameter.
 */
class ServeBarTimer(
    private val visibleMillis: Long = VISIBLE_MILLIS,
    private val afterChoiceMillis: Long = AFTER_CHOICE_MILLIS,
) {
    private var pointId: EventId? = null
    private var hideAt: Long = Long.MIN_VALUE

    /** Ein Punkt wurde hier gezählt: Leiste für [point] zeigen. */
    fun onPoint(
        point: EventId,
        nowMillis: Long,
    ) {
        pointId = point
        hideAt = nowMillis + visibleMillis
    }

    /** Wert gewählt: noch kurz zeigen, dann ausblenden (verkürzt nur, verlängert nie). */
    fun onChosen(nowMillis: Long) {
        hideAt = minOf(hideAt, nowMillis + afterChoiceMillis)
    }

    fun dismiss() {
        pointId = null
    }

    /** Punkt, für den die Leiste gerade sichtbar ist, sonst `null`. */
    fun visiblePoint(nowMillis: Long): EventId? = pointId?.takeIf { nowMillis < hideAt }

    companion object {
        const val VISIBLE_MILLIS = 5_000L
        const val AFTER_CHOICE_MILLIS = 1_500L
    }
}

/** Was ein Tap auf die Aktionsleiste ([eu.advantly.wear.scoring.ActionStrip]) gerade bewirkt. */
sealed interface StripAction {
    /** Aufschlag-Info für den letzten Punkt wählen (Segmente = [ServeBarUi.options]). */
    data class Serve(
        val bar: ServeBarUi,
    ) : StripAction

    /** „Rückgängig“ nach „Punkt abziehen“ (Redo). */
    data object UndoRemoval : StripAction

    /** Auswahl „Wer schlägt auf?“ öffnen. */
    data object ChooseServer : StripAction
}

/** Reine Regeln für Aufschlag-Leiste, Aufschlägerwahl, Aktionsleiste und Versionshinweis. */
object OperationRules {
    /** Reihenfolge der Leiste: „1. · 2. · DF“. */
    private val SERVE_ORDER = listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN, ServeOutcome.DOUBLE_FAULT)

    /** Aufschlag-Leiste für [match], falls sie für [visiblePoint] gerade sichtbar ist (nur Zähler mit Aufschlag-Statistik). */
    fun serveBar(
        match: ActiveMatch,
        visiblePoint: EventId?,
    ): ServeBarUi? {
        if (!match.isScorer || !match.serveInfoEnabled || visiblePoint == null) return null
        val last = match.lastPoint?.takeIf { it.id == visiblePoint } ?: return null
        val options = SERVE_ORDER.filter { it != ServeOutcome.DOUBLE_FAULT || last.doubleFaultAllowed }
        return ServeBarUi(last.id, options, last.serve)
    }

    /** Offene Aufschlägerwahl für die Uhr (nur Zähler); Spieler über [player] aufgelöst. */
    fun serverChoice(
        match: ActiveMatch,
        player: (PlayerId) -> PlayerUi?,
    ): ServerChoiceUi? {
        if (!match.isScorer) return null
        val choice = match.serverChoice ?: return null
        val players = choice.players.mapNotNull(player)
        if (players.isEmpty()) return null
        // Game 1 des ersten Satzes hat der Start (Erstaufschläger im Assistenten) schon entschieden
        val decidedAtStart = choice.game == 1 && match.state.sets.isEmpty()
        return ServerChoiceUi(choice.game, choice.side, players, choice.chosen, prompt = choice.chosen == null && !decidedAtStart)
    }

    /**
     * Aktion der Aktionsleiste: Aufschlag-Leiste vor „Rückgängig“ (nach „Punkt abziehen“, solange der Hinweis steht und
     * Redo möglich ist) vor „Aufschläger wählen“. Nur am Zähler ohne Sperre und ohne offene Rückfrage.
     */
    fun stripAction(
        live: LiveUi,
        toast: WatchToast?,
    ): StripAction? {
        if (!live.isScorer || live.locked || live.overlay != null) return null
        live.serveBar?.let { return StripAction.Serve(it) }
        if (toast is WatchToast.PointRemoved && live.canRedo) return StripAction.UndoRemoval
        if (live.serverChoice?.prompt == true) return StripAction.ChooseServer
        return null
    }

    /** Segment [index] der Aufschlag-Leiste → Wert. */
    fun serveOption(
        bar: ServeBarUi,
        index: Int,
    ): ServeOutcome? = bar.options.getOrNull(index)

    /**
     * Versionsunterschied zu einer erreichbaren Gegenstelle (W2-Vertrag §5): `PEER_TOO_OLD` → das andere Gerät (Handy)
     * aktualisieren, `PEER_TOO_NEW` → diese Uhr. Gleiche Abbildung wie der Phone-Port (`SessionMatchStore`).
     */
    fun updateRequired(sync: SyncStatus): UpdateRequired? =
        when (sync.versionMismatch) {
            CompatibilityStatus.PEER_TOO_OLD -> UpdateRequired.OTHER_DEVICE
            CompatibilityStatus.PEER_TOO_NEW -> UpdateRequired.THIS_DEVICE
            CompatibilityStatus.COMPATIBLE, null -> null
        }
}
