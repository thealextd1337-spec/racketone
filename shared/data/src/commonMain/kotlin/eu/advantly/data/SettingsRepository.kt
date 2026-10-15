package eu.advantly.data

import eu.advantly.data.db.SettingQueries

/** Einfache Einstellungen als Key-Value (Texte); typisierte Helfer für Boolean/Long. */
class SettingsRepository internal constructor(
    private val queries: SettingQueries,
) {
    fun get(key: String): String? = queries.selectValue(key).executeAsOneOrNull()

    fun put(
        key: String,
        value: String,
    ) {
        queries.put(key, value)
    }

    fun remove(key: String) {
        queries.remove(key)
    }

    /** Alle Einträge, nach Schlüssel sortiert. */
    fun all(): Map<String, String> = queries.selectAll().executeAsList().associate { it.key to it.value_ }

    /** Wert zu [key]; fehlt er, wird [create] einmalig gespeichert und geliefert (z. B. Geräte-ID). */
    fun getOrPut(
        key: String,
        create: () -> String,
    ): String =
        queries.transactionWithResult {
            get(key) ?: create().also { put(key, it) }
        }

    fun getBoolean(
        key: String,
        default: Boolean,
    ): Boolean = get(key)?.toBooleanStrictOrNull() ?: default

    fun putBoolean(
        key: String,
        value: Boolean,
    ) = put(key, value.toString())

    fun getLong(
        key: String,
        default: Long,
    ): Long = get(key)?.toLongOrNull() ?: default

    fun putLong(
        key: String,
        value: Long,
    ) = put(key, value.toString())
}
