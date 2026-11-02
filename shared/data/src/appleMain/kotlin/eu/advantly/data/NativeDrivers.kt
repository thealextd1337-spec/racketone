package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext
import eu.advantly.data.db.AdvantlyDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * SQLDelight-native-driver auf der System-SQLite (iOS + watchOS, gelinkt mit `-lsqlite3`).
 * [inMemory] = true für Tests; [name] muss dann je Datenbank eindeutig sein. Eine Datei-Datenbank liegt nie im iCloud- oder
 * Geräte-Backup ([excludeFromBackup]).
 */
fun createNativeDriver(
    name: String = DEFAULT_DATABASE_NAME,
    inMemory: Boolean = false,
): SqlDriver {
    val schema = AdvantlyDatabase.Schema
    if (!inMemory) excludeFromBackup(name)
    return NativeSqliteDriver(
        DatabaseConfiguration(
            name = name,
            version = schema.version.toInt(),
            inMemory = inMemory,
            create = { connection -> wrapConnection(connection) { schema.create(it) } },
            upgrade = { connection, oldVersion, newVersion ->
                wrapConnection(connection) { schema.migrate(it, oldVersion.toLong(), newVersion.toLong()) }
            },
        ),
    )
}

/**
 * Pulsverläufe bleiben nur auf Uhr und Handy (Plan Puls §10): Das Datenbankverzeichnis von sqliter – Datenbank samt
 * `-wal`, `-shm` und `-journal` – wird vom iCloud- und Geräte-Backup ausgeschlossen. Das Flag gilt für das Verzeichnis und
 * alles darin; es wird bei jedem Start erneut gesetzt (ein Wiederherstellen kann es entfernen). Scheitert es, öffnet die
 * App trotzdem (kein Datenverlust), das Backup enthielte dann aber die Datenbank wie bisher.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun excludeFromBackup(name: String): Boolean {
    val directory = NSURL.fileURLWithPath(DatabaseFileContext.databasePath(name, null)).URLByDeletingLastPathComponent ?: return false
    NSFileManager.defaultManager.createDirectoryAtURL(directory, withIntermediateDirectories = true, attributes = null, error = null)
    return directory.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
}

/** Datei-basierter [EventStore] (iOS/watchOS: Standard-Datenbankverzeichnis von sqliter). */
fun createNativeEventStore(name: String = DEFAULT_DATABASE_NAME): EventStore = SqlDelightEventStore(createNativeDriver(name))

/** Datei-basierte [LocalData] (iOS/watchOS; eine Instanz je Prozess verwenden). */
fun createNativeLocalData(name: String = DEFAULT_DATABASE_NAME): LocalData = LocalData(createNativeDriver(name))
