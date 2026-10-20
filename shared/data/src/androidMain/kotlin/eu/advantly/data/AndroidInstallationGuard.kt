package eu.advantly.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import java.io.IOException

/** Kein Ersatz für eine Installation: Bei Speicherfehlern existiert ausschließlich dieser Fehlerstatus. */
sealed interface AndroidInstallationAvailability {
    data class Ready(
        val installation: AndroidInstallationState,
    ) : AndroidInstallationAvailability

    data object Unavailable : AndroidInstallationAvailability
}

/** Prozessweiter Android-Schutz, einschließlich Service-/Tile-Kaltstart. Unbekannte Originale bleiben ungeöffnet. */
object AndroidInstallationGuard {
    private val states = mutableMapOf<String, AndroidInstallationState>()
    private val unavailable = mutableSetOf<String>()

    /** Fehlgeschlagene Prüfung bleibt bis zum expliziten Retry gesperrt; Dienste lösen keine Wiederholung aus. */
    @Synchronized
    fun check(context: Context): AndroidInstallationAvailability {
        val key = context.applicationContext.getDatabasePath(DEFAULT_DATABASE_NAME).absolutePath
        if (key in unavailable) return AndroidInstallationAvailability.Unavailable
        return try {
            AndroidInstallationAvailability.Ready(get(context))
        } catch (_: IOException) {
            unavailable += key
            AndroidInstallationAvailability.Unavailable
        } catch (_: SQLiteException) {
            unavailable += key
            AndroidInstallationAvailability.Unavailable
        } catch (_: ErrnoException) {
            unavailable += key
            AndroidInstallationAvailability.Unavailable
        }
    }

    /** Wiederholt ausschließlich dieselbe Dateiprüfung; keine Änderung an Generation oder Verifikations-Hold. */
    @Synchronized
    fun retry(context: Context): AndroidInstallationAvailability {
        unavailable -= context.applicationContext.getDatabasePath(DEFAULT_DATABASE_NAME).absolutePath
        return check(context)
    }

    fun privateTransportAllowed(context: Context): Boolean =
        (check(context) as? AndroidInstallationAvailability.Ready)?.installation?.privateTransportAllowed == true

    @Synchronized
    fun get(context: Context): AndroidInstallationState {
        val app = context.applicationContext
        val databases = checkNotNull(app.getDatabasePath(DEFAULT_DATABASE_NAME).parentFile)
        val key = databases.canonicalPath
        return states.getOrPut(key) {
            InstallationFiles(
                databases,
                app.noBackupFilesDir,
                initialize = { file, token ->
                    val driver = AndroidSqliteDriver(AdvantlyDatabase.Schema, app, file.name)
                    try {
                        AdvantlyDatabase(driver).settingQueries.put(InstallationFiles.BINDING_KEY, token)
                    } finally {
                        driver.close()
                    }
                },
                verifyCopy = { file, token ->
                    SQLiteDatabase
                        // Nur die private Kopie darf SQLite bei Bedarf aus WAL/Journal wiederherstellen.
                        .openDatabase(
                            file.absolutePath,
                            null,
                            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                            // Der Standardhandler darf die Kopie weder löschen noch als leere DB neu öffnen.
                            DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Installation verification copy is corrupt") },
                        ).use { database ->
                            val hasSetting =
                                database
                                    .rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'setting'", null)
                                    .use { it.moveToFirst() }
                            if (!hasSetting) return@use false
                            val columns = mutableSetOf<String>()
                            database.rawQuery("PRAGMA table_info(setting)", null).use { cursor ->
                                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                            }
                            if (!columns.containsAll(listOf("key", "value"))) return@use false
                            database
                                .rawQuery("SELECT value FROM setting WHERE key = ?", arrayOf(InstallationFiles.BINDING_KEY))
                                .use { cursor -> cursor.moveToFirst() && cursor.getString(0) == token && !cursor.moveToNext() }
                        }
                },
                syncDirectory = ::syncInstallationDirectory,
            ).select()
        }
    }
}

/** Verzeichnis-fsync, soweit das Android-Dateisystem es unterstützt; kein Nachweis für Hardware-Stromausfälle. */
private fun syncInstallationDirectory(directory: File) {
    val unsupported = setOf(OsConstants.EINVAL, OsConstants.ENOSYS, OsConstants.EOPNOTSUPP)
    val descriptor =
        try {
            Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        } catch (error: ErrnoException) {
            if (error.errno in unsupported) return
            throw error
        }
    try {
        try {
            Os.fsync(descriptor)
        } catch (error: ErrnoException) {
            if (error.errno !in unsupported) throw error
        }
    } finally {
        Os.close(descriptor)
    }
}
