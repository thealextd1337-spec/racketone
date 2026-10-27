package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Lokaler Bestand für geteilte Matches (Pull-Snapshots, Upload-Hinweise); der Host kapselt Datenbank und Sitzungsgrenze. */
interface SharedMatchesLocal {
    fun feed(profileId: String): List<PulledMatch>

    /** Mitglieds-Snapshot von [matchId] für dieses Konto, sonst `null`. */
    fun match(
        profileId: String,
        matchId: String,
    ): PulledMatch?

    /** Server-Hinweise je Platz (`not_friend`, `link_rejected`, `profile_already_in_match`) zur letzten Besetzung. */
    fun linkNotes(
        profileId: String,
        matchId: String,
    ): Map<String, String>
}

/**
 * Geteiltes Match eines Freundes (Feed, L13): ausschließlich anzeigbare Felder der bereinigten Sicht – keine Profil-,
 * Ersteller- oder Geräte-IDs, keine Telemetrie. Namen sind Aliase von Freunden bzw. die Snapshot-Namen der übrigen Plätze.
 */
data class FeedMatch(
    val id: String,
    /** `null`: dem Client unbekannte Sportart. */
    val sport: Sport?,
    val doubles: Boolean,
    /** `scheduled` | `in_progress` | `paused` | `awaiting_decision` | `finished` */
    val status: String,
    val winner: Side?,
    val resultReason: String?,
    val startedAt: String?,
    val endedAt: String?,
    val teamA: List<String>,
    val teamB: List<String>,
    /** Satzstände (A, B), soweit die bereinigte Projektion sie enthält. */
    val sets: List<Pair<Int, Int>>,
)

/** Eigene Sicht auf ein Match im Detail; ohne Konto, vor dem Hochladen oder ohne eigenen Platz ohne Aktionen. */
data class MatchSharing(
    val matchId: String,
    /** `null`: kein eigener verknüpfter Platz in der Cloud; sonst „Mit Freunden teilen“ an/aus. */
    val shared: Boolean? = null,
    /** Ein Freund hat mich eingetragen (`linked`): „War ich nicht“ möglich. */
    val canReject: Boolean = false,
    /**
     * Fremdes Match, in dem ich verknüpft bin: „Aus meinem Verlauf ausblenden“. Eigene Matches entfernt „Löschen“, das bei
     * verknüpften Freunden serverseitig ebenfalls nur meine Sicht ausblendet.
     */
    val canHide: Boolean = false,
    /** Nach „War ich nicht“ bzw. „Ausblenden“: das Match gehört nicht mehr zu meiner Sicht. */
    val gone: Boolean = false,
    /** Server-Hinweise je Platz (Slot-ID → Grund) zur zuletzt hochgeladenen Besetzung. */
    val linkNotes: Map<String, String> = emptyMap(),
)

internal fun PulledMatch.toFeed(): FeedMatch {
    fun team(name: String) = participants.filter { it.team == name }.sortedBy { it.position }.map { it.displayName }
    // Feed: bereinigt als [[a,b],…]; Mitglieder bekommen die rohe Client-Summary mit Objekten {a,b,…}. Grenzen wie der
    // Server (höchstens 9 Sätze, 0..99, ein ungültiger Satz verwirft alle).
    val parsed =
        (summary?.get("sets") as? JsonArray)
            ?.takeIf { it.size <= 9 }
            ?.map { set ->
                val pair =
                    when (set) {
                        is JsonArray -> set.takeIf { it.size == 2 }?.let { it[0] to it[1] }
                        is JsonObject -> set["a"]?.let { a -> set["b"]?.let { b -> a to b } }
                        else -> null
                    }
                val a = (pair?.first as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                val b = (pair?.second as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (a != null && b != null && a in 0..99 && b in 0..99) a to b else null
            }.orEmpty()
    val sets = if (parsed.any { it == null }) emptyList() else parsed.filterNotNull()
    return FeedMatch(
        id = id,
        sport = Sport.entries.firstOrNull { it.name.lowercase() == sport },
        doubles = discipline == "doubles",
        status = status,
        winner = Side.entries.firstOrNull { it.name == winner },
        resultReason = resultReason,
        startedAt = startedAt,
        endedAt = endedAt,
        teamA = team("A"),
        teamB = team("B"),
        sets = sets,
    )
}
