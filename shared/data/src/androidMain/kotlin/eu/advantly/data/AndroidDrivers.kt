package eu.advantly.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase

/** Datei-basierter Treiber im App-Datenverzeichnis (Android Phone + Wear). */
fun createAndroidDriver(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
): SqlDriver {
    val selected =
        if (name == DEFAULT_DATABASE_NAME || name.startsWith("advantly-install-")) {
            val active = AndroidInstallationGuard.get(context).activeDatabaseName
            require(name == DEFAULT_DATABASE_NAME || name == active) { "Database is retained for verification" }
            active
        } else {
            name
        }
    return AndroidSqliteDriver(AdvantlyDatabase.Schema, context.applicationContext, selected)
}

/** Datei-basierter [EventStore] im App-Datenverzeichnis (Android Phone + Wear). */
fun createAndroidEventStore(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
): EventStore = SqlDelightEventStore(createAndroidDriver(context, name))

/** Datei-basierte [LocalData] im App-Datenverzeichnis (eine Instanz je Prozess verwenden). */
fun createAndroidLocalData(context: Context): LocalData =
    LocalData(createAndroidDriver(context, AndroidInstallationGuard.get(context).activeDatabaseName))
