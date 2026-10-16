package eu.advantly.wear.live

import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.ServerChosen
import eu.advantly.match.session.ActiveMatch
import eu.advantly.match.session.OperationTimings
import eu.advantly.match.session.SyncStatus
import eu.advantly.presentation.match.UpdateRequired
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.wear.scoring.ScoringGesture
import eu.advantly.wear.scoring.StripHit

// Bedienung W2 (Feldtest 2026-10-15, docs/w2-ui-vertrag.md): Aufschlag-Leiste, Aufschlägerwahl im Doppel,
// Aktionsleiste im Mittelband und Versionshinweis – reine Modelle und Regeln ohne Android (JVM-getestet).

/**
 * Aufschlag-Leiste nach dem Punkt (W2-Vertrag §2, 02 §7.2): „1. Aufschlag · 2. Aufschlag · Doppelfehler“ für [pointId]
 * (= `lastPoint`).
 *
 * @property options wählbare Werte; `DOUBLE_FAULT` nur, wenn die Rückschlagseite den Punkt gewann
 * @property selected aktuell erfasster Wert (markieren) oder `null`
 * @property remainingMillis Restzeit bis zum Ausblenden zum Zeitpunkt dieses Zustands (Restzeit-Anzeige in den Segmenten)
 * @property hideAtMillis Ausblendzeitpunkt (monotone Zeit des Controllers); ändert sich nur bei neuem Punkt bzw. Auswahl –
 *   Schlüssel, an dem die Anzeige neu startet
 */
data class ServeBarUi(
    val pointId: EventId,
    val options: List<ServeOutcome>,
    val selected: ServeOutcome?,
    val remainingMillis: Long = 0,
    val hideAtMillis: Long = 0,
)

/**
 * Offene Aufschlägerwahl im Doppel (02 §6.5.5, W2-Vertrag §2 `serverChoice`), nur am Zähler.
 *
 * @property game 1 = Satzbeginn (alle vier wählbar), 2 = erstes Aufschlagspiel des anderen Teams
 * @property side Team, das nach Regel aufschlägt (Game 1: Vorschlag, dessen Spieler stehen vorn)
 * @property players wählbare Spieler in der Reihenfolge der Engine
 * @property chosen in diesem Fenster bereits gewählter Spieler, sonst `null`
 * @property prompt Hinweis „Aufschlag wählen“ im Mittelband zeigen: Wahl offen und nicht schon beim Start getroffen
 *   (Game 1 des ersten Satzes legt der Start-Assistent über den Erstaufschläger fest – es sei denn, in diesem Fenster
 *   wurde schon gewählt und eine Korrektur hat die Wahl wieder aufgehoben)
 */
data class ServerChoiceUi(
    val game: Int,
    val side: Side,
    val players: List<PlayerUi>,
    val chosen: PlayerId?,
    val prompt: Boolean,
)

/**
 * Wurde im Eröffnungsfenster (Game 1 des ersten Satzes) schon ein Aufschläger gewählt? Hebt eine Korrektur diese Wahl
 * wieder auf (Engine: `chosen = null`, 02 §6.5.5), ist das nicht mehr die beim Start erledigte Wahl – der Hinweis
 * erscheint wieder. Rekonstruiert aus dem gespeicherten Event-Log ([chosenEvents]: wirksame und unwirksame
 * `ServerChosen` des Matches), damit es auch nach Prozessverlust bzw. neuer Activity stimmt. Gelesen wird nur in genau
 * diesem Fenster und nur, wenn sich die Zahl der Events geändert hat.
 */
class OpeningChoiceCheck(
    private val chosenEvents: (MatchId) -> List<Pair<EventId, ServerChosen>>,
) {
    private var cache: Pair<Pair<MatchId, Int>, Boolean>? = null

    fun choiceMade(match: ActiveMatch?): Boolean {
        val choice = match?.serverChoice ?: return false
        if (choice.game != 1 || match.state.sets.isNotEmpty() || choice.chosen != null) return false
        val set = match.state.current?.setIndex ?: return false
        val key = match.id to match.eventCount
        cache?.takeIf { it.first == key }?.let { return it.second }
        val made = OperationRules.choiceMadeInSet(chosenEvents(match.id), set, match.state)
        cache = key to made
        return made
    }
}

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

    /** Ausblendzeitpunkt der sichtbaren Leiste (monotone Zeit). */
    val hideAtMillis: Long get() = hideAt

    /** Restzeit der sichtbaren Leiste (≥ 0). */
    fun remainingMillis(nowMillis: Long): Long = (hideAt - nowMillis).coerceAtLeast(0)

    companion object {
        const val VISIBLE_MILLIS = OperationTimings.SERVE_BAR_VISIBLE_MILLIS
        const val AFTER_CHOICE_MILLIS = OperationTimings.SERVE_BAR_AFTER_CHOICE_MILLIS
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
    /** Reihenfolge der Leiste: „1. Aufschlag · 2. Aufschlag · Doppelfehler“. */
    private val SERVE_ORDER = listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN, ServeOutcome.DOUBLE_FAULT)

    /**
     * Relative Breite des Segments „Doppelfehler“: ein langes Wort ohne Umbruchstelle („Doppelfehler“), die anderen
     * Labels brechen zweizeilig um („1.“ / „Aufschlag“).
     */
    const val DOUBLE_FAULT_WEIGHT = 1.3f

    /** Relative Segmentbreiten der Aufschlag-Leiste (Darstellung und Treffer, [eu.advantly.wear.scoring.ActionStrip]). */
    fun serveWeights(bar: ServeBarUi): List<Float> = bar.options.map { if (it == ServeOutcome.DOUBLE_FAULT) DOUBLE_FAULT_WEIGHT else 1f }

    /** Aufschlag-Leiste für [match], falls sie für [visiblePoint] gerade sichtbar ist (nur Zähler mit Aufschlag-Statistik). */
    fun serveBar(
        match: ActiveMatch,
        visiblePoint: EventId?,
        remainingMillis: Long = 0,
        hideAtMillis: Long = 0,
    ): ServeBarUi? {
        if (!match.isScorer || !match.serveInfoEnabled || visiblePoint == null) return null
        val last = match.lastPoint?.takeIf { it.id == visiblePoint } ?: return null
        val options = SERVE_ORDER.filter { it != ServeOutcome.DOUBLE_FAULT || last.doubleFaultAllowed }
        return ServeBarUi(last.id, options, last.serve, remainingMillis, hideAtMillis)
    }

    /**
     * Offene Aufschlägerwahl für die Uhr (nur Zähler); Spieler über [player] aufgelöst. [choiceMadeInWindow] = im
     * Eröffnungsfenster wurde schon gewählt (siehe [OpeningChoiceCheck]) – dann ist `chosen = null` eine aufgehobene
     * Wahl und wird wieder erfragt, auch in Game 1 des ersten Satzes.
     */
    fun serverChoice(
        match: ActiveMatch,
        player: (PlayerId) -> PlayerUi?,
        choiceMadeInWindow: Boolean = false,
    ): ServerChoiceUi? {
        if (!match.isScorer) return null
        val choice = match.serverChoice ?: return null
        val players = choice.players.mapNotNull(player)
        if (players.isEmpty()) return null
        // Game 1 des ersten Satzes hat der Start (Erstaufschläger im Assistenten) schon entschieden
        val decidedAtStart = choice.game == 1 && match.state.sets.isEmpty() && !choiceMadeInWindow
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

    /** Gab es in Satz [set] eine Aufschlägerwahl, die die Engine angenommen hat (nicht ignoriert, nicht rollenfremd)? */
    fun choiceMadeInSet(
        chosen: List<Pair<EventId, ServerChosen>>,
        set: Int,
        state: MatchState,
    ): Boolean = chosen.any { (id, event) -> event.set == set && id !in state.ignoredEvents && id !in state.rejectedByRole }

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

/** Ziel einer Berührung – festgelegt beim Aufsetzen (Down), nicht erst beim Loslassen. */
sealed interface TouchTarget {
    /** Begann auf der Aktionsleiste mit dieser Aktion: darf nie zählen oder abziehen. */
    data class Strip(
        val action: StripAction,
    ) : TouchTarget

    /** Begann im Schutzstreifen der Leiste: nur Navigation (Wischen zur Steuerung). */
    data object Guard : TouchTarget

    /** Begann auf den Zählflächen: wie bisher. */
    data object Score : TouchTarget
}

/** Was eine abgeschlossene Geste auslöst. */
sealed interface TouchDecision {
    /** Punkt für die Hälfte unter dem Aufsetzpunkt ([eu.advantly.wear.scoring.TapZones]). */
    data object Point : TouchDecision

    /** Letzten Punkt der Hälfte unter dem Aufsetzpunkt abziehen. */
    data object RemovePoint : TouchDecision

    data object Unlock : TouchDecision

    data object Undo : TouchDecision

    data object OpenControls : TouchDecision

    /** Leistenaktion ausführen (Segment über den Aufsetzpunkt). */
    data class Strip(
        val action: StripAction,
    ) : TouchDecision

    data object None : TouchDecision
}

/**
 * Gesten des Score-Screens als reine Funktion (JVM-getestet): Das Ziel steht beim Aufsetzen fest; eine Berührung, die auf
 * der Leiste oder ihrem Schutzstreifen beginnt, zählt und zieht nie ab. Ist die Leiste beim Loslassen weg oder gehört
 * sie zu einem anderen Punkt, wird die Eingabe verworfen – kein Rückfall aufs Zählen.
 */
object TouchRouting {
    fun target(
        strip: StripAction?,
        hit: StripHit,
    ): TouchTarget =
        when {
            strip == null || hit == StripHit.OUTSIDE -> TouchTarget.Score
            hit == StripHit.STRIP -> TouchTarget.Strip(strip)
            else -> TouchTarget.Guard
        }

    fun decide(
        target: TouchTarget,
        gesture: ScoringGesture,
        stripNow: StripAction?,
        locked: Boolean,
        scorer: Boolean,
    ): TouchDecision {
        if (gesture == ScoringGesture.SWIPE_LEFT) return if (locked) TouchDecision.None else TouchDecision.OpenControls
        return when (target) {
            is TouchTarget.Strip -> {
                if (gesture == ScoringGesture.TAP) {
                    stillShown(target.action, stripNow)?.let { TouchDecision.Strip(it) } ?: TouchDecision.None
                } else {
                    TouchDecision.None
                }
            }

            TouchTarget.Guard -> {
                TouchDecision.None
            }

            TouchTarget.Score -> {
                when (gesture) {
                    ScoringGesture.TAP -> {
                        if (scorer) TouchDecision.Point else TouchDecision.None
                    }

                    ScoringGesture.LONG_PRESS -> {
                        if (locked) {
                            TouchDecision.Unlock
                        } else if (scorer) {
                            TouchDecision.RemovePoint
                        } else {
                            TouchDecision.None
                        }
                    }

                    ScoringGesture.SWIPE_DOWN -> {
                        if (scorer) TouchDecision.Undo else TouchDecision.None
                    }

                    ScoringGesture.SWIPE_LEFT, ScoringGesture.NONE -> {
                        TouchDecision.None
                    }
                }
            }
        }
    }

    /**
     * Die beim Aufsetzen getroffene Leiste, sofern sie beim Loslassen noch steht – die Aufschlag-Leiste nur für denselben
     * Punkt (die aktuelle Fassung, z. B. mit neuer Markierung); sonst `null`.
     */
    fun stillShown(
        atDown: StripAction,
        now: StripAction?,
    ): StripAction? =
        when {
            now == null -> null
            atDown is StripAction.Serve && now is StripAction.Serve && atDown.bar.pointId == now.bar.pointId -> now
            atDown is StripAction.Serve || now is StripAction.Serve -> null
            atDown == now -> now
            else -> null
        }
}
