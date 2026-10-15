package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.data.db.AdvantlyDatabase

/**
 * Lokale Datenbank eines Geräts (01 §5.1, Single Source of Truth): Event-Log, Match-Köpfe, Spieler,
 * Favoriten-Lineups und Settings über **einen** [SqlDriver].
 *
 * Erzeugen: `createInMemoryJvmLocalData()` (Tests/Desktop), `createAndroidLocalData(context)`,
 * `createNativeLocalData()` (iOS/watchOS). Synchron und nicht thread-sicher – ein Thread bzw. eine serielle Queue.
 */
class LocalData(
    driver: SqlDriver,
) {
    private val database = AdvantlyDatabase(driver)

    val events: EventStore = SqlDelightEventStore(database.matchEventQueries)
    val matches: MatchRepository = MatchRepository(database.matchHeaderQueries, database.matchTombstoneQueries)
    val players: PlayerRepository = PlayerRepository(database.playerQueries)
    val favorites: LineupFavoriteRepository = LineupFavoriteRepository(database.lineupFavoriteQueries)
    val settings: SettingsRepository = SettingsRepository(database.settingQueries)

    /** Führt [block] in einer Transaktion aus (alles oder nichts). */
    fun <T> transaction(block: () -> T): T = database.transactionWithResult { block() }

    /**
     * Löscht ein Match vollständig (Kopf + Events) und hinterlässt eine dauerhafte Löschmarkierung, damit es durch
     * keinen Abgleich wieder auftaucht – „Wischen → löschen“ in der Historie bzw. empfangene Löschung.
     */
    fun deleteMatch(
        id: MatchId,
        deletedAtMs: Long = 0,
    ) {
        database.transaction {
            matches.markDeleted(id, deletedAtMs)
            events.deleteMatch(id.value)
            matches.delete(id)
        }
    }

    /** F20: nur die durch wear.guest.* belegten Slots mit unveränderten DE/EN-Standardnamen migrieren. */
    fun migrateWatchPlaceholders() {
        val names =
            mapOf(
                "PARTNER" to setOf("Partner"),
                "OPPONENT_1" to setOf("Gegner 1", "Opponent 1"),
                "OPPONENT_2" to setOf("Gegner 2", "Opponent 2"),
            )
        transaction {
            names.forEach { (slot, defaults) ->
                val key = "wear.guest.$slot"
                val id = settings.get(key)?.let(::PlayerId) ?: return@forEach
                val player = players.get(id)
                if (player == null || player.displayName in defaults || player.kind == PlayerKind.PLACEHOLDER) {
                    players.markPlaceholder(id, player?.createdAtMs ?: 0)
                    settings.remove(key)
                }
            }
        }
    }

    fun isPlaceholder(id: PlayerId): Boolean = Placeholders.isPlaceholder(id) || players.get(id)?.kind == PlayerKind.PLACEHOLDER

    // --- Identität des Besitzers über eigene Geräte (W1-I) -----------------------------------------------

    /**
     * Andere Spieler-IDs desselben Besitzers, z. B. der Uhr-eigene Self-Player vor der Übernahme der Handy-Identität.
     * Matches mit diesen IDs zeigen den Besitzer („Du“), keinen Gast.
     */
    fun selfAliases(): Set<PlayerId> =
        settings
            .get(SELF_ALIASES_KEY)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.map { PlayerId(it) }
            ?.toSet()
            .orEmpty()

    /** Merkt [ids] als Aliase des Besitzers (der aktuelle Self wird ausgelassen). `true`, wenn sich etwas geändert hat. */
    fun addSelfAliases(ids: Collection<PlayerId>): Boolean {
        val self = players.self()?.id
        val current = selfAliases()
        val next = current + ids.filter { it != self && it.value.isNotBlank() && ',' !in it.value }
        if (next == current) return false
        settings.put(SELF_ALIASES_KEY, next.joinToString(",") { it.value })
        return true
    }

    /** [id] ist der Besitzer selbst oder einer seiner Aliase. */
    fun isSelf(id: PlayerId): Boolean = id == players.self()?.id || id in selfAliases()

    /**
     * Übernimmt [owner] (Self-Player des Handys) als eigenen Spieler; ein bisheriger, anderer Self wird Alias.
     * `true`, wenn sich die Identität geändert hat.
     */
    fun adoptSelf(
        owner: Player,
        nowMs: Long,
    ): Boolean =
        transaction {
            val previous = players.adoptSelf(owner, nowMs) ?: return@transaction false
            addSelfAliases(listOf(previous.id))
            true
        }

    companion object {
        /** Settings-Schlüssel der Self-Aliase (kommagetrennte Spieler-IDs). */
        const val SELF_ALIASES_KEY: String = "players.selfAliases"

        /**
         * Schema-Version der lokalen Datenbank. Vor dem ersten Release ändert sich das Schema ohne Migration
         * (Version bleibt 1, Entwicklungsgeräte: App-Daten löschen). Ab Release: `.sqm`-Migrationen, Version steigt.
         */
        const val SCHEMA_VERSION: Long = 1
    }
}
