package eu.advantly.core.scoring

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Side

/** Art eines Highlights in der Match-Zeitleiste (Plan Puls-Highlights §2). */
enum class MomentKind {
    BREAK_POINT,
    BREAK,
    DECIDING_POINT,
    TIEBREAK_STARTED,
    SET_POINT,
    MATCH_POINT,
    SET_WON,
    MATCH_WON,
    COMEBACK,
}

/** Ausgang einer Chance (Breakball, Satz-/Matchball, Entscheidungspunkt). */
enum class MomentOutcome { CONVERTED, SAVED }

/**
 * Ein wirksamer Punkt mit mindestens einer [MomentKind].
 *
 * @property pointIndex Index in [ScoringEngine.pointHistory] (nur wirksame Punkte)
 * @property wallClockMs aus dem `ScoreEvent` durchgereicht – das Modul liest keine Uhr
 * @property side wer die Chance hatte (bei [MomentOutcome.SAVED] die Seite, deren Chance abgewehrt wurde) bzw. sonst
 *   den Punkt gewann
 * @property outcome nur bei Chancen ([MomentKind.BREAK_POINT], [MomentKind.SET_POINT], [MomentKind.MATCH_POINT],
 *   [MomentKind.DECIDING_POINT]), sonst `null`. Mehrere Chancen auf einem Punkt: maßgeblich ist die höchste
 *   (Matchball > Satzball > Breakball > Entscheidungspunkt); haben beide Seiten sie, hat der Punktgewinner verwertet.
 */
data class MatchMoment(
    val pointIndex: Int,
    val eventId: EventId,
    val wallClockMs: Long,
    val side: Side,
    val kinds: Set<MomentKind>,
    val outcome: MomentOutcome?,
)

/**
 * Leitet Highlights rein aus dem Punktverlauf ab (deterministisch, ohne Uhr). Undo und abgelehnte Events fallen heraus,
 * weil [ScoringEngine.pointHistory] nur wirksame Punkte liefert. Keine Verzweigung je Sportart: Die Engine setzt Flags
 * und Effekte bereits regelgerecht.
 *
 * - [MomentKind.BREAK_POINT]: ein Flag mit `breakPoint` vor dem Punkt (nur Tennis/Padel-Standardspiel)
 * - [MomentKind.BREAK]: `GAME_WON` eines Standardspiels durch den Rückschläger
 * - [MomentKind.DECIDING_POINT]: `current.decidingPoint` vor dem Punkt – Padel Golden/Star Point, Tennis No-Ad,
 *   Badminton am Deckel (29:29 bzw. 20:20) und bei „1 Punkt Vorsprung“ (z. B. 10:10 in 5×11). **Tischtennis:** Die
 *   Presets spielen mit 2 Punkten Vorsprung ohne Deckel, `decidingPoint` ist dort `false` (10:10 ist Einstand); nur ein
 *   benutzerdefiniertes Format mit 1 Punkt Vorsprung setzt ihn. Pickleball: immer `null`.
 * - [MomentKind.SET_POINT] / [MomentKind.MATCH_POINT]: Flag-Level vor dem Punkt; ein Matchball verdrängt den Satzball
 * - [MomentKind.TIEBREAK_STARTED]: Effekt `TIEBREAK_STARTED` (Punkt zum 6:6) bzw. erster Punkt eines Match-Tiebreaks
 *   (dort gibt es keinen Effekt, ein Match-Tiebreak beginnt mit dem Satz)
 * - [MomentKind.SET_WON] / [MomentKind.MATCH_WON]: Effekt nach dem Punkt
 * - [MomentKind.COMEBACK] (E4): beim Satzgewinn, wenn der Sieger im Satz einen Satz-/Matchball abgewehrt hat oder
 *   zurücklag – um mindestens [COMEBACK_GAMES] Spiele (Tennis/Padel-Satz) bzw. [COMEBACK_POINTS] Punkte (Rally-Sätze,
 *   Pickleball-Games, Match-Tiebreak) bzw. um mindestens [COMEBACK_POINTS] Punkte im Tiebreak eines Tennis/Padel-Satzes
 *   (z. B. 0:5 → 7:5). Beim Matchgewinn zusätzlich, wenn der Sieger Satz 1 verloren hat.
 */
object MatchMoments {
    const val COMEBACK_GAMES = 2
    const val COMEBACK_POINTS = 5

    fun derive(points: List<PointRecord>): List<MatchMoment> {
        val moments = mutableListOf<MatchMoment>()
        var set = -1
        var deficit = SidePair(0, 0)
        var tiebreakDeficit = SidePair(0, 0)
        var saved = SidePair(false, false)
        points.forEachIndexed { index, record ->
            val before = record.before
            val after = record.after
            val current = before.current
            val winner = record.point.side
            if (before.sets.size != set) {
                set = before.sets.size
                deficit = SidePair(0, 0)
                tiebreakDeficit = SidePair(0, 0)
                saved = SidePair(false, false)
            }
            val flags = before.statusFlags
            val matchPoint = flags.filter { it.level == FlagLevel.MATCH_POINT }.mapTo(HashSet()) { it.side }
            val setPoint = flags.filter { it.level == FlagLevel.SET_POINT }.mapTo(HashSet()) { it.side }
            val breakPoint = flags.filter { it.breakPoint }.mapTo(HashSet()) { it.side }
            val deciding = current?.decidingPoint == true
            // Comeback-Merkmale des laufenden Satzes, Stand vor dem Punkt
            if (current != null) {
                val score = if (current.setKind == SetKind.REGULAR) current.games else null
                val pair = score ?: current.points
                deficit = SidePair.of { side -> maxOf(deficit[side], pair[side.other] - pair[side]) }
                // Tiebreak im Standardsatz: Rückstand in Punkten, unabhängig von den Spielen
                if (current.gameKind == GameKind.TIEBREAK) {
                    val p = current.points
                    tiebreakDeficit = SidePair.of { side -> maxOf(tiebreakDeficit[side], p[side.other] - p[side]) }
                }
            }
            val against = matchPoint + setPoint
            if (winner.other in against && winner !in against) saved = saved.with(winner, true)

            val kinds = LinkedHashSet<MomentKind>()
            if (matchPoint.isNotEmpty()) {
                kinds += MomentKind.MATCH_POINT
            } else if (setPoint.isNotEmpty()) {
                kinds += MomentKind.SET_POINT
            }
            if (breakPoint.isNotEmpty()) kinds += MomentKind.BREAK_POINT
            if (deciding) kinds += MomentKind.DECIDING_POINT
            if (Effect.GAME_WON in after.effects && current?.gameKind == GameKind.STANDARD && winner != before.serve?.side) {
                kinds += MomentKind.BREAK
            }
            val matchTiebreakStart =
                current?.gameKind == GameKind.MATCH_TIEBREAK && current.points.a + current.points.b == 0
            if (Effect.TIEBREAK_STARTED in after.effects || matchTiebreakStart) kinds += MomentKind.TIEBREAK_STARTED
            val setWon = Effect.SET_WON in after.effects
            val matchWon = Effect.MATCH_WON in after.effects
            if (setWon) kinds += MomentKind.SET_WON
            if (matchWon) kinds += MomentKind.MATCH_WON
            val tiebreakComeback = tiebreakDeficit[winner] >= COMEBACK_POINTS
            if (setWon && (tiebreakComeback || isComeback(current, deficit[winner], saved[winner]))) kinds += MomentKind.COMEBACK
            if (matchWon && after.sets.firstOrNull()?.winner == winner.other) kinds += MomentKind.COMEBACK
            if (kinds.isEmpty()) return@forEachIndexed

            val holders =
                when {
                    matchPoint.isNotEmpty() -> matchPoint
                    setPoint.isNotEmpty() -> setPoint
                    breakPoint.isNotEmpty() -> breakPoint
                    deciding -> setOf(Side.A, Side.B)
                    else -> emptySet()
                }
            val converted = winner in holders
            moments +=
                MatchMoment(
                    pointIndex = index,
                    eventId = record.event.id,
                    wallClockMs = record.event.wallClockMs,
                    side = if (holders.isEmpty() || converted) winner else winner.other,
                    kinds = kinds,
                    outcome =
                        when {
                            holders.isEmpty() -> null
                            converted -> MomentOutcome.CONVERTED
                            else -> MomentOutcome.SAVED
                        },
                )
        }
        return moments
    }

    private fun isComeback(
        current: CurrentSet?,
        deficit: Int,
        saved: Boolean,
    ): Boolean {
        if (saved) return true
        val threshold = if (current?.setKind == SetKind.REGULAR) COMEBACK_GAMES else COMEBACK_POINTS
        return deficit >= threshold
    }
}
