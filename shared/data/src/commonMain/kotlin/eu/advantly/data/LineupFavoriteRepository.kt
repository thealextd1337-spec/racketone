package eu.advantly.data

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Sport
import eu.advantly.data.db.LineupFavoriteQueries
import eu.advantly.data.db.Lineup_favorite
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Favoriten-Lineup („Wie letztes Mal“, Revanche-Presets, Schnellstart auf der Uhr).
 *
 * @property id aus Sport, Disziplin und Besetzung abgeleitet ([LineupFavoriteRepository.keyOf]) – auf allen Geräten gleich
 * @property a Spieler der Side A, [b] der Side B (Reihenfolge wie zuletzt gespielt)
 */
data class FavoriteLineup(
    val id: String,
    val sport: Sport,
    val discipline: Discipline,
    val a: List<PlayerId>,
    val b: List<PlayerId>,
    val name: String?,
    val lastUsedAtMs: Long,
    val useCount: Int,
)

/** Favoriten-Lineups; Zusammenführen mit anderen Geräten ist idempotent (Maximum von Zeit und Zähler). */
class LineupFavoriteRepository internal constructor(
    private val queries: LineupFavoriteQueries,
) {
    /** Merkt eine gespielte Besetzung (beim Matchstart): neu anlegen oder Zähler/Zeit erhöhen. */
    fun recordUse(
        sport: Sport,
        discipline: Discipline,
        a: List<PlayerId>,
        b: List<PlayerId>,
        nowMs: Long,
    ): FavoriteLineup =
        queries.transactionWithResult {
            val id = keyOf(sport, discipline, a, b)
            queries.insertOrIgnore(id, sport.name, discipline.name, encode(a), encode(b), null, nowMs, 0)
            queries.touch(nowMs, id)
            checkNotNull(get(id))
        }

    /** Übernimmt einen Favoriten von einem anderen Gerät (neuere Zeit und höherer Zähler gewinnen, Name falls gesetzt). */
    fun merge(favorite: FavoriteLineup) {
        queries.transaction {
            queries.insertOrIgnore(
                favorite.id,
                favorite.sport.name,
                favorite.discipline.name,
                encode(favorite.a),
                encode(favorite.b),
                favorite.name,
                favorite.lastUsedAtMs,
                favorite.useCount.toLong(),
            )
            queries.merge(favorite.name, favorite.lastUsedAtMs, favorite.useCount.toLong(), favorite.id)
        }
    }

    fun get(id: String): FavoriteLineup? = queries.selectById(id).executeAsOneOrNull()?.toFavorite()

    /** Zuletzt verwendete Favoriten, optional nur einer Sportart. */
    fun recent(
        sport: Sport? = null,
        limit: Long = 20,
    ): List<FavoriteLineup> = queries.selectRecent(sport?.name, limit).executeAsList().map { it.toFavorite() }

    fun rename(
        id: String,
        name: String?,
    ) {
        queries.rename(name, id)
    }

    fun delete(id: String) {
        queries.deleteById(id)
    }

    private fun Lineup_favorite.toFavorite(): FavoriteLineup =
        FavoriteLineup(
            id = id,
            sport = Sport.valueOf(sport),
            discipline = Discipline.valueOf(discipline),
            a = decode(side_a),
            b = decode(side_b),
            name = name,
            lastUsedAtMs = last_used_at_ms,
            useCount = use_count.toInt(),
        )

    companion object {
        private val IDS = ListSerializer(String.serializer())

        /**
         * Stabiler Schlüssel: Sport, Disziplin und die Spieler je Side (innerhalb der Side sortiert – die Aufschlag-
         * reihenfolge ist kein anderer Favorit).
         */
        fun keyOf(
            sport: Sport,
            discipline: Discipline,
            a: List<PlayerId>,
            b: List<PlayerId>,
        ): String = listOf(sport.name, discipline.name, team(a), team(b)).joinToString("|")

        private fun team(ids: List<PlayerId>): String = ids.map { it.value }.sorted().joinToString(",")

        private fun encode(ids: List<PlayerId>): String = DataJson.encodeToString(IDS, ids.map { it.value })

        private fun decode(json: String): List<PlayerId> = DataJson.decodeFromString(IDS, json).map { PlayerId(it) }
    }
}
