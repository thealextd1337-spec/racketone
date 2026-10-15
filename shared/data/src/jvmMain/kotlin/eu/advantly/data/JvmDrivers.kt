package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.util.Properties

/** In-Memory-SQLite über JDBC (JVM-Tests, Desktop-Preview). Jeder Aufruf liefert eine eigene, leere Datenbank. */
fun createInMemoryJvmDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema)

/** [EventStore] auf einer frischen In-Memory-Datenbank (JVM). */
fun createInMemoryJvmEventStore(): EventStore = SqlDelightEventStore(createInMemoryJvmDriver())
