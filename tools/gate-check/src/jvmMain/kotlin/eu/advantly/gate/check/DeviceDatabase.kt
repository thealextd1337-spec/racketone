package eu.advantly.gate.check

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.MatchId
import eu.advantly.data.LocalData
import eu.advantly.data.StoredEvent
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Kopf eines Matches aus `match_header` (Ergebnis-Cache), nur zur Plausibilisierung gegen den Fold. */
data class HeaderInfo(
    val sport: String,
    val status: String,
    val createdAtMs: Long,
    val setsWonA: Int,
    val setsWonB: Int,
    val resultReason: String?,
)

/**
 * Ein Match in einer Geräte-Datenbank.
 *
 * @property events alle Events in globaler Ordnung (wie `EventStore.eventsFor`)
 * @property unconfirmedIds Events mit `confirmed = 0` (noch keine Gegenstelle hat sie bestätigt)
 * @property assignments Personen-Zuweisungen `match_assignment` (Slot → Spieler-JSON), nur für den Fingerabdruck
 */
data class MatchData(
    val events: List<StoredEvent>,
    val unconfirmedIds: Set<String>,
    val header: HeaderInfo?,
    val assignments: Map<String, String?> = emptyMap(),
)

/**
 * Lesender Schnappschuss einer Advantly-Datenbank (Handy oder Uhr).
 *
 * @property label Name aus der Kommandozeile (z. B. `handy`, `pixel`)
 * @property ownDeviceId eigene Installations-ID (`session.deviceId`)
 * @property knownKinds bekannte Gerätearten der Gegenstellen (`session.peerKind.<deviceId>` → `PHONE`/`WATCH`)
 * @property tombstones gelöschte Matches (Match-ID → Löschzeit)
 */
data class DeviceSnapshot(
    val label: String,
    val ownDeviceId: String?,
    val knownKinds: Map<String, String>,
    val matches: Map<String, MatchData>,
    val tombstones: Map<String, Long>,
)

/**
 * Lädt eine vom Gerät gezogene `advantly.db` (inkl. `advantly.db-wal`, falls vorhanden). Gelesen wird **eine Kopie** in
 * einem temporären Verzeichnis: Öffnen und Schema-Prüfung dürfen die abgelegten Originaldateien nicht verändern.
 */
object DeviceDatabase {
    private val SIDE_FILES = listOf("-wal", "-shm", "-journal")

    fun load(
        label: String,
        file: File,
    ): DeviceSnapshot {
        require(file.isFile) { "Datenbank nicht gefunden: ${file.absolutePath}" }
        val dir = Files.createTempDirectory("advantly-gate-").toFile()
        try {
            val copy = File(dir, "advantly.db")
            Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            SIDE_FILES.forEach { suffix ->
                val side = File(file.path + suffix)
                if (side.isFile) Files.copy(side.toPath(), File(copy.path + suffix).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}", Properties(), AdvantlyDatabase.Schema)
            try {
                return read(label, driver)
            } finally {
                driver.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Liest alle Matches aus [driver] (auch für Tests mit In-Memory-Datenbanken). */
    fun read(
        label: String,
        driver: SqlDriver,
    ): DeviceSnapshot {
        val data = LocalData(driver)
        val settings = data.settings
        val ownDeviceId = settings.get(DEVICE_ID_KEY)
        val kinds =
            stringPairs(driver, "SELECT key, value FROM setting WHERE key LIKE '$PEER_KIND_PREFIX%'")
                .associate { (key, value) -> key.removePrefix(PEER_KIND_PREFIX) to value }
        val tombstones = data.matches.deleted().associate { it.matchId.value to it.deletedAtMs }
        val ids =
            (
                strings(driver, "SELECT DISTINCT match_id FROM match_event") +
                    strings(driver, "SELECT id FROM match_header")
            ).toSortedSet()
        val matches =
            ids.associateWith { id ->
                val unconfirmed =
                    data.events
                        .unconfirmedEvents(id)
                        .map { it.id }
                        .toSet()
                val header =
                    data.matches.get(MatchId(id))?.let {
                        HeaderInfo(
                            sport = it.sport.name,
                            status = it.status.name,
                            createdAtMs = it.createdAtMs,
                            setsWonA = it.score.setsWonA,
                            setsWonB = it.score.setsWonB,
                            resultReason = it.score.reason?.name,
                        )
                    }
                val assignments =
                    stringPairs(
                        driver,
                        "SELECT slot_id, coalesce(player_json, '') FROM match_assignment WHERE match_id = '${id.replace("'", "''")}'",
                    ).associate { (slot, json) -> slot to json.ifEmpty { null } }
                MatchData(data.events.eventsFor(id), unconfirmed, header, assignments)
            }
        return DeviceSnapshot(label, ownDeviceId, kinds, matches, tombstones)
    }

    private fun strings(
        driver: SqlDriver,
        sql: String,
    ): List<String> =
        driver
            .executeQuery(null, sql, { cursor ->
                val out = mutableListOf<String>()
                while (cursor.next().value) cursor.getString(0)?.let(out::add)
                QueryResult.Value(out)
            }, 0)
            .value

    private fun stringPairs(
        driver: SqlDriver,
        sql: String,
    ): List<Pair<String, String>> =
        driver
            .executeQuery(null, sql, { cursor ->
                val out = mutableListOf<Pair<String, String>>()
                while (cursor.next().value) out += cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty()
                QueryResult.Value(out)
            }, 0)
            .value

    /** Gleicher Schlüssel wie `MatchSessions.DEVICE_ID_KEY` (match-session wird hier bewusst nicht eingebunden). */
    const val DEVICE_ID_KEY = "session.deviceId"

    /** Gleiches Präfix wie `MatchSession.PEER_KIND_PREFIX` (privat dort). */
    const val PEER_KIND_PREFIX = "session.peerKind."
}
