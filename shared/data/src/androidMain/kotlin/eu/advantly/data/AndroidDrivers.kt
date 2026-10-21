package eu.advantly.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase

/** Datei-basierter Treiber im App-Datenverzeichnis (Android Phone + Wear). */
fun createAndroidDriver(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
): SqlDriver {
    val selected =
        if (name == DEFAULT_DATABASE_NAME || name.startsWith("advantly-install-")) {
            val active = AndroidInstallationGuard.requireReady(context).activeDatabaseName
            require(name == DEFAULT_DATABASE_NAME || name == active) { "Database is retained for verification" }
            active
        } else {
            name
        }
    return newAndroidSqliteDriver(context.applicationContext, selected)
}

/** Datei-basierter [EventStore] im App-Datenverzeichnis (Android Phone + Wear). */
fun createAndroidEventStore(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
): EventStore = SqlDelightEventStore(createAndroidDriver(context, name))

/** Datei-basierte [LocalData] im App-Datenverzeichnis (eine Instanz je Prozess verwenden). */
fun createAndroidLocalData(context: Context): LocalData =
    LocalData(createAndroidDriver(context, AndroidInstallationGuard.get(context).activeDatabaseName))

/** Kein Default-onCorruption: Die aktive Familie wird weder gelöscht noch leer neu geöffnet. */
internal fun newAndroidSqliteDriver(
    context: Context,
    name: String,
): SqlDriver {
    val delegate =
        AndroidSqliteDriver(
            AdvantlyDatabase.Schema,
            context,
            name,
            factory = AndroidStorageOpenHelperFactory(context),
            callback =
                object : AndroidSqliteDriver.Callback(AdvantlyDatabase.Schema) {
                    override fun onCorruption(db: SupportSQLiteDatabase) {
                        val error = SQLiteDatabaseCorruptException("Active database is corrupt")
                        AndroidInstallationGuard.storageFailed(context, error)
                        throw error
                    }
                },
        )
    return object : SqlDriver by delegate {
        private fun <T> report(block: () -> T): T =
            try {
                block()
            } catch (error: Exception) {
                if (AndroidStorageFailures.isExpected(error)) AndroidInstallationGuard.storageFailed(context, error)
                throw error
            }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = report { delegate.execute(identifier, sql, parameters, binders) }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = report { delegate.executeQuery(identifier, sql, mapper, parameters, binders) }

        override fun newTransaction(): QueryResult<Transacter.Transaction> = report { delegate.newTransaction() }
    }
}

/** Auch native COMMIT-/Rollback-/Close-Fehler erreichen dieselbe Prozesssperre wie SQL-Statements. */
internal class AndroidStorageOpenHelperFactory(
    private val context: Context,
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    private fun <T> report(block: () -> T): T =
        try {
            block()
        } catch (error: Exception) {
            if (AndroidStorageFailures.isExpected(error)) AndroidInstallationGuard.storageFailed(context, error)
            throw error
        }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        return protect(helper)
    }

    fun protect(helper: SupportSQLiteOpenHelper): SupportSQLiteOpenHelper {
        fun protect(database: SupportSQLiteDatabase) =
            object : SupportSQLiteDatabase by database {
                override fun setTransactionSuccessful() = report { database.setTransactionSuccessful() }

                override fun endTransaction() = report { database.endTransaction() }

                override fun close() = report { database.close() }
            }
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase: SupportSQLiteDatabase get() = report { protect(helper.writableDatabase) }
            override val readableDatabase: SupportSQLiteDatabase get() = report { protect(helper.readableDatabase) }

            override fun close() = report { helper.close() }
        }
    }
}
