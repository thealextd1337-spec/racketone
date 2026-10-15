package eu.advantly.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase

/** Datei-basierter Treiber im App-Datenverzeichnis (Android Phone + Wear). */
fun createAndroidDriver(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
): SqlDriver = AndroidSqliteDriver(AdvantlyDatabase.Schema, context.applicationContext, name)

/** Datei-basierter [EventStore] im App-Datenverzeichnis (Android Phone + Wear). */
fun createAndroidEventStore(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
): EventStore = SqlDelightEventStore(createAndroidDriver(context, name))

/** Datei-basierte [LocalData] im App-Datenverzeichnis (eine Instanz je Prozess verwenden). */
fun createAndroidLocalData(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
): LocalData = LocalData(createAndroidDriver(context, name))
