package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.LiveMatch
import eu.advantly.cloud.sync.VisitorView
import eu.advantly.core.model.Side
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/** Wie ich ein fremdes laufendes Match verfolge; ein Zähler (angenommene Rolle) steht bei „Ich spiele mit“. */
enum class LiveRelation { PLAYER, SCORER, VISITOR }

/** Ein laufendes fremdes Match zur Anzeige (Handy „Live“, Uhr-Provider); nie Telemetrie, nie Profil-/Geräte-IDs. */
data class LiveEntry(
    val relation: LiveRelation,
    /** Teams, Sätze, Games, Punkte, Tiebreak wie bei „Ich schaue zu“. */
    val score: WatchedMatch,
    val serving: Side?,
    val scorerConnected: Boolean,
    /** Letzte Änderung am Server; `null` bei unlesbarem Zeitstempel. */
    val updatedAtMs: Long?,
) {
    val matchId: String get() = score.match.id
}

/** `null` für eine unbekannte [LiveMatch.relation] (neuere Server-Version). */
fun LiveMatch.toLiveEntry(): LiveEntry? {
    val relation = LiveRelation.entries.firstOrNull { it.name.lowercase() == relation } ?: return null
    // Gleiche bereinigte Projektion wie visitor_view: dieselbe Auswertung wie „Ich schaue zu“.
    val view = VisitorView(matchId, sport, discipline, "in_progress", channel = "", teams = participants, live = projection)
    return LiveEntry(
        relation,
        view.toWatched(),
        Side.entries.firstOrNull { it.name == (projection?.get("serving") as? JsonPrimitive)?.content },
        scorerConnected,
        runCatching { Instant.parse(updatedAt).toEpochMilliseconds() }.getOrNull(),
    )
}
