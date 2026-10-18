package eu.advantly.cloud.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/** Strukturbeweis für Statusabfragen unabhängig von schwankenden Laufzeitmessungen. */
internal class InventoryRecordingDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    var recording = false
    val reads = mutableListOf<String>()
    val writes = mutableListOf<String>()
    var afterRead: (String) -> Unit = {}

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (recording) writes += sql
        return delegate.execute(identifier, sql, parameters, binders)
    }

    /** Vollständiger persistierter Testbestand einschließlich Herkunft, Revisionen, Acks und Nutzdaten. */
    fun persistedState(): Map<String, List<List<String?>>> {
        fun rows(
            sql: String,
            columns: Int,
        ): List<List<String?>> =
            delegate
                .executeQuery(
                    null,
                    sql,
                    { cursor ->
                        QueryResult.Value(buildList { while (cursor.next().value) add(List(columns) { cursor.getString(it) }) })
                    },
                    0,
                ).value
        return rows("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", 1)
            .associate { (name) ->
                val table = "\"${requireNotNull(name).replace("\"", "\"\"")}\""
                val columns = rows("PRAGMA table_info($table)", 6).size
                name to rows("SELECT * FROM $table ORDER BY ${(1..columns).joinToString()}", columns)
            }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (recording) reads += sql
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders).also { afterRead(sql) }
    }
}
