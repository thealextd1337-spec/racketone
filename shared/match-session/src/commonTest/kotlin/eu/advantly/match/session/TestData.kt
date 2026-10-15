package eu.advantly.match.session

import eu.advantly.data.LocalData

/** Frische, leere Datenbank je Aufruf (JVM/Android-Host: JDBC in-memory, Apple: native-driver in-memory). */
expect fun testLocalData(): LocalData
