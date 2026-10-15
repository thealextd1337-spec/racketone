package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver

/** Frische, leere In-Memory-Datenbank je Aufruf (JVM/Android-Host: JDBC, Apple: native-driver). */
expect fun createTestDriver(): SqlDriver
