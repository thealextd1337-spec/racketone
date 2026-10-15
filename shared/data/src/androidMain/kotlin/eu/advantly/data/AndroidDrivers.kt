package eu.advantly.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase

/** Datei-basierter [EventStore] im App-Datenverzeichnis (Android Phone + Wear). */
fun createAndroidEventStore(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
): EventStore = SqlDelightEventStore(AndroidSqliteDriver(AdvantlyDatabase.Schema, context.applicationContext, name))
