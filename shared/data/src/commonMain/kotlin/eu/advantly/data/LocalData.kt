package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver
import eu.advantly.core.model.MatchId
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
    val matches: MatchRepository = MatchRepository(database.matchHeaderQueries)
    val players: PlayerRepository = PlayerRepository(database.playerQueries)
    val favorites: LineupFavoriteRepository = LineupFavoriteRepository(database.lineupFavoriteQueries)
    val settings: SettingsRepository = SettingsRepository(database.settingQueries)

    /** Führt [block] in einer Transaktion aus (alles oder nichts). */
    fun <T> transaction(block: () -> T): T = database.transactionWithResult { block() }

    /** Löscht ein Match vollständig (Kopf + Events) – „Wischen → löschen“ in der Historie. */
    fun deleteMatch(id: MatchId) {
        database.transaction {
            events.deleteMatch(id.value)
            matches.delete(id)
        }
    }

    companion object {
        /**
         * Schema-Version der lokalen Datenbank. Vor dem ersten Release ändert sich das Schema ohne Migration
         * (Version bleibt 1, Entwicklungsgeräte: App-Daten löschen). Ab Release: `.sqm`-Migrationen, Version steigt.
         */
        const val SCHEMA_VERSION: Long = 1
    }
}
