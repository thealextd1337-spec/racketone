package eu.advantly.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Kein Ersatz für eine Installation: Bei Speicherfehlern existiert ausschließlich dieser Fehlerstatus. */
sealed interface AndroidInstallationAvailability {
    data class Ready(
        val installation: AndroidInstallationState,
    ) : AndroidInstallationAvailability

    data object Unavailable : AndroidInstallationAvailability
}

/** Prozessweiter Android-Schutz, einschließlich Service-/Tile-Kaltstart. Unbekannte Originale bleiben ungeöffnet. */
object AndroidInstallationGuard {
    // Geschrieben nur unter der Objektsperre; nebenläufig lesbar, damit [checkAsync] ein bekanntes Ergebnis ohne Sperre sieht.
    private val states = ConcurrentHashMap<String, AndroidInstallationState>()
    private val unavailable: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val bootstrapped = mutableSetOf<String>()

    private val failures = mutableMapOf<String, Exception>()
    private val failureListeners = mutableMapOf<String, MutableSet<(Exception) -> Unit>>()

    private fun key(context: Context) = context.applicationContext.getDatabasePath(DEFAULT_DATABASE_NAME).absolutePath

    /** Nur pro Prozess gesperrt. Ein Prozessneustart prüft neu; Dienste im selben Prozess warten auf expliziten Retry. */
    @Synchronized
    fun check(context: Context): AndroidInstallationAvailability {
        if (key(context) in unavailable) return AndroidInstallationAvailability.Unavailable
        return capture(context) { AndroidInstallationAvailability.Ready(get(context)) }
            ?: AndroidInstallationAvailability.Unavailable
    }

    /**
     * Für Main-Thread-Einstiege (Application, Activity, Tile/Complication, Dienst-`onCreate`, JobService): [check] mit
     * Kopie, quick_check und Übernahme läuft auf einem eigenen Hintergrundthread, [onResult] danach auf dem Main-Thread.
     * Ist das Ergebnis dieses Prozesses schon bekannt, ruft der Main-Thread [onResult] sofort auf (ohne Sperre und I/O).
     * Nebenläufige Aufrufer teilen eine Prüfung: [check] ist gesperrt und hält das Ergebnis je Prozess fest. Bis zu ihrem
     * Abschluss öffnet niemand produktiv; [storage]/[get] auf anderen Threads warten an derselben Sperre.
     */
    fun checkAsync(
        context: Context,
        onResult: (AndroidInstallationAvailability) -> Unit,
    ) {
        val app = context.applicationContext
        val key = key(app)
        val known =
            if (key in unavailable) {
                AndroidInstallationAvailability.Unavailable
            } else {
                states[key]?.let(AndroidInstallationAvailability::Ready)
            }
        if (known != null && Looper.myLooper() == Looper.getMainLooper()) return onResult(known)
        background(app, ::check, onResult)
    }

    /** [retry] außerhalb des Main-Threads (Fehleransicht); [onResult] läuft auf dem Main-Thread. */
    fun retryAsync(
        context: Context,
        onResult: (AndroidInstallationAvailability) -> Unit,
    ) = background(context.applicationContext, ::retry, onResult)

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "advantly-installation").apply { isDaemon = true } }

    private fun background(
        app: Context,
        action: (Context) -> AndroidInstallationAvailability,
        onResult: (AndroidInstallationAvailability) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        worker.execute {
            val result = runCatching { action(app) }
            // Programmierfehler wie bisher auf dem Main-Thread werfen, statt Aufrufer ohne Ergebnis warten zu lassen.
            main.post { onResult(result.getOrThrow()) }
        }
    }

    /** Wiederholt Datei- und Kopienprüfung, niemals Verifikationsfreigabe oder Generationswechsel als I/O-Fallback. */
    @Synchronized
    fun retry(context: Context): AndroidInstallationAvailability {
        val key = key(context)
        // Veraltete Fehleransicht auf gesunder Installation: Bootstrap-Freigabe nicht ohne erneutes prepare() verwerfen.
        if (key !in unavailable) return check(context)
        unavailable -= key
        bootstrapped -= key
        failures.remove(key)
        states.remove(key)
        return check(context)
    }

    fun requireReady(context: Context): AndroidInstallationState =
        when (val result = check(context)) {
            is AndroidInstallationAvailability.Ready -> result.installation
            AndroidInstallationAvailability.Unavailable -> throw checkNotNull(failures[key(context)])
        }

    /** Dieselbe Fehlergrenze umfasst auch Bootstrap/Commit und produktive Scheduler-Speicherarbeit. */
    fun <T : Any> storage(
        context: Context,
        block: () -> T,
    ): T? {
        if (check(context) !is AndroidInstallationAvailability.Ready) return null
        return capture(context, block)
    }

    private fun <T : Any> capture(
        context: Context,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (error: IOException) {
            storageFailed(context, error)
            null
        } catch (error: ErrnoException) {
            storageFailed(context, error)
            null
        } catch (error: DirectoryIteratorException) {
            if (!AndroidStorageFailures.isExpected(error)) throw error
            storageFailed(context, error)
            null
        } catch (error: UncheckedIOException) {
            if (!AndroidStorageFailures.isExpected(error)) throw error
            storageFailed(context, error)
            null
        } catch (error: SQLiteException) {
            if (!AndroidStorageFailures.isExpected(error)) throw error
            storageFailed(context, error)
            null
        }

    /** Auch vom produktiven Corruption-/Driverpfad aufgerufen: aktive Eingänge sofort entziehen, keine neue DB wählen. */
    @Synchronized
    fun storageFailed(
        context: Context,
        error: Exception,
    ) {
        require(AndroidStorageFailures.isExpected(error))
        val key = key(context)
        states.remove(key)
        bootstrapped -= key
        failures.putIfAbsent(key, error)
        if (!unavailable.add(key)) return
        failureListeners[key]?.toList()?.forEach { listener ->
            try {
                listener(error)
            } catch (cleanup: Exception) {
                // Aufräumfehler bleiben an der ursprünglichen Ursache sichtbar; niemals einen Erfolgsstatus melden.
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
        }
    }

    @Synchronized
    fun onUnavailable(
        context: Context,
        listener: (Exception) -> Unit,
    ): () -> Unit {
        val key = key(context)
        val listeners = failureListeners.getOrPut(key) { mutableSetOf() }
        listeners += listener
        return { synchronized(this) { listeners -= listener } }
    }

    @Synchronized
    fun beginBootstrap(context: Context) {
        bootstrapped -= key(context)
    }

    /** Nur die Android-Sessionfactory darf nach erfolgreichem Storage-Commit die Prozessgeneration freigeben. */
    @Synchronized
    fun bootstrapCommitted(context: Context) {
        requireReady(context)
        bootstrapped += key(context)
    }

    @Synchronized
    fun privateTransportAllowed(context: Context): Boolean =
        key(context) in bootstrapped &&
            (check(context) as? AndroidInstallationAvailability.Ready)?.installation?.privateTransportAllowed == true

    /**
     * Recovery-Paket, ausdrückliche Nutzerentscheidung im Hold oder später nach [keepWithoutRestore]:
     * [AndroidInstallationState.restorable] als neue private Kopie übernehmen. Wirkt erst beim nächsten Prozessstart (der
     * Host startet neu); bis dahin bleibt der Zustand dieses Prozesses unverändert.
     */
    @Synchronized
    fun requestRestore(context: Context) {
        val installation = requireReady(context)
        val source = checkNotNull(installation.restorable) { "Nothing to restore" }
        files(context.applicationContext).requestRestore(installation.cacheNamespace, source)
    }

    /** Ausdrücklich ohne Altbestand weiter; alle Familien bleiben unverändert erhalten. Wirkt beim nächsten Prozessstart. */
    @Synchronized
    fun keepWithoutRestore(context: Context) {
        val installation = requireReady(context)
        check(!installation.privateTransportAllowed) { "Nothing to decide" }
        files(context.applicationContext).keepCurrent(installation.cacheNamespace)
    }

    @Synchronized
    fun get(context: Context): AndroidInstallationState {
        val app = context.applicationContext
        val key = key(app)
        // Auch direkte Android-Cache-/Driveraufrufe dürfen einen Prozessfehler nicht durch Dateiauswahl umgehen.
        if (key in unavailable) throw checkNotNull(failures[key])
        return states.getOrPut(key) { files(app).select() }
    }

    private fun files(app: Context) =
        checkNotNull(app.getDatabasePath(DEFAULT_DATABASE_NAME).parentFile).let { databases ->
            InstallationFiles(
                databases,
                app.noBackupFilesDir,
                initialize = { file, token ->
                    val driver = newAndroidSqliteDriver(app, file.name)
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
                            database.rawQuery("PRAGMA quick_check", null).use { result ->
                                if (!result.moveToFirst() || result.getString(0) != "ok" || result.moveToNext()) {
                                    throw SQLiteDatabaseCorruptException("Installation verification copy failed quick_check")
                                }
                            }
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
                restore = { file, token -> restoreInstallationCopy(app, file, token) },
            )
        }

    /** Setting der übernommenen Kopie: Die Session stellt sie vor ihrem ersten Zugriff einmalig um (Wert = Token). */
    const val RESTORED_KEY = "installation.restored.v1"
}

/**
 * Nur die eigene private Kopie: Integrität prüfen, auf das aktuelle Schema migrieren, an das neue Token binden.
 * Ohne Guard-Callbacks und ohne Standard-Fehlerhandler, der eine beschädigte Datei löschen und leer neu anlegen würde.
 */
private fun restoreInstallationCopy(
    app: Context,
    file: File,
    token: String,
) {
    val corrupt = DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Restored copy is corrupt") }
    SQLiteDatabase
        .openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS, corrupt)
        .use { database ->
            database.rawQuery("PRAGMA quick_check", null).use { result ->
                if (!result.moveToFirst() || result.getString(0) != "ok" || result.moveToNext()) {
                    throw SQLiteDatabaseCorruptException("Restored copy failed quick_check")
                }
            }
        }
    val driver =
        AndroidSqliteDriver(
            AdvantlyDatabase.Schema,
            app,
            file.name,
            callback =
                object : AndroidSqliteDriver.Callback(AdvantlyDatabase.Schema) {
                    override fun onCorruption(db: SupportSQLiteDatabase): Unit =
                        throw SQLiteDatabaseCorruptException("Restored copy is corrupt")
                },
        )
    try {
        val settings = AdvantlyDatabase(driver).settingQueries
        settings.put(InstallationFiles.BINDING_KEY, token)
        settings.put(AndroidInstallationGuard.RESTORED_KEY, token)
    } finally {
        driver.close()
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
