package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import eu.advantly.data.db.AdvantlyDatabase

/**
 * SQLDelight-native-driver auf der System-SQLite (iOS + watchOS, gelinkt mit `-lsqlite3`).
 * [inMemory] = true für Tests; [name] muss dann je Datenbank eindeutig sein.
 */
fun createNativeDriver(
    name: String = DEFAULT_DATABASE_NAME,
    inMemory: Boolean = false,
): SqlDriver {
    val schema = AdvantlyDatabase.Schema
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

/** Datei-basierter [EventStore] (iOS/watchOS: Standard-Datenbankverzeichnis von sqliter). */
fun createNativeEventStore(name: String = DEFAULT_DATABASE_NAME): EventStore = SqlDelightEventStore(createNativeDriver(name))

/** Datei-basierte [LocalData] (iOS/watchOS; eine Instanz je Prozess verwenden). */
fun createNativeLocalData(name: String = DEFAULT_DATABASE_NAME): LocalData = LocalData(createNativeDriver(name))
