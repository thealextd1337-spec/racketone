package eu.advantly.match.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.LocalData
import eu.advantly.data.db.AdvantlyDatabase
import java.util.Properties

// Android-Host-Tests laufen auf der JVM ohne Android-Framework → JDBC statt android-driver
actual fun testLocalData(): LocalData = LocalData(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema))
