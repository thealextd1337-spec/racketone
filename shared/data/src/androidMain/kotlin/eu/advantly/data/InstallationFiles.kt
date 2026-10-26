package eu.advantly.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Nur Inventar: zurückgehaltene Dateien werden weder geöffnet noch verschoben oder gelöscht. */
data class RetainedDatabaseFamily(
    val databaseName: String,
    val files: List<String>,
)

enum class AndroidRecoveryRequirement {
    NONE,
    VERIFICATION_REQUIRED,
}

/**
 * Ein Hold endet nur durch ausdrückliche Entscheidung ([AndroidInstallationGuard.requestRestore] bzw.
 * [AndroidInstallationGuard.keepWithoutRestore]); Login oder Retry heben ihn nie auf. Freigabe übernommener Daten
 * entscheidet danach die Datenebene (UID, ausdrückliche Adoption).
 *
 * @property restorable die zuletzt geschriebene Altfamilie, die übernommen werden kann: im Hold und nach „ohne Altbestand
 *   weiter“, solange genau dieselben Familien vorhanden sind (späterer Übernahmeweg); nach einer Übernahme nicht mehr
 * @property restoreFailed die angeforderte Übernahme ist in diesem Prozessstart gescheitert; nichts wurde umgeschaltet
 */
@ConsistentCopyVisibility
data class AndroidInstallationState internal constructor(
    val activeDatabaseName: String,
    val cacheNamespace: String,
    val recovery: AndroidRecoveryRequirement,
    val retainedFamilies: List<RetainedDatabaseFamily>,
    val restorable: String? = null,
    val restoreFailed: Boolean = false,
) {
    val privateTransportAllowed: Boolean get() = recovery == AndroidRecoveryRequirement.NONE
}

/**
 * Dateiauswahl vor produktiven SQL-Opens. Eine nicht im Backup gespeicherte Reservierung belegt ausschließlich die eigene,
 * noch unveröffentlichte Zwischenfamilie. Fremde Familien werden nie initialisiert, verändert oder gelöscht.
 * Validierung öffnet nur eine vollständige private Kopie; I/O-/SQLite-Fehler brechen ohne Umschaltung ab.
 *
 * Leistungsgrenze dieses Schutzschnitts: Jeder Prozessstart kopiert die aktive Familie einschließlich SQLite-quick_check
 * (gemessen: 1 MB < 0,3 s, 100 MB auf der Uhr bis 8 s) und braucht zusätzlichen freien Speicher in Familiengröße.
 * Main-Thread-Einstiege prüfen über [AndroidInstallationGuard.checkAsync] auf einem Hintergrundthread und zeigen bzw.
 * liefern bis dahin einen Ladezustand; Listener-Dienste warten auf ihrem Worker-Thread. Der Start dauert also weiterhin
 * so lange, blockiert aber nicht den Main-Thread. Synchron auf dem Main-Thread bleiben nur die kleinen Metadaten-Schreib-
 * vorgänge der Nutzerentscheidung ([AndroidInstallationGuard.requestRestore], [AndroidInstallationGuard.keepWithoutRestore]).
 *
 * Recovery-Paket: Eine ausdrückliche Übernahme ist eine Reservierung mit Quellfamilie. Die Quelle wird nur kopiert, nie
 * geöffnet oder verändert; [restore] prüft, migriert und bindet ausschließlich die eigene Kopie. Scheitert das, endet
 * die Übernahme ohne Umschaltung. Entschiedene Familien bleiben erhalten; ihr Beleg ist an das aktive Token gebunden.
 */
internal class InstallationFiles(
    private val databases: File,
    private val noBackup: File,
    private val initialize: (File, String) -> Unit,
    private val verifyCopy: (File, String) -> Boolean,
    private val checkpoint: (String) -> Unit = {},
    private val files: InstallationFileAccess = InstallationFileAccess(),
    private val syncDirectory: (File) -> Unit = {},
    private val restore: (File, String) -> Unit = { _, _ -> throw UnsupportedOperationException("restore") },
) {
    private data class Reservation(
        val token: String,
        val held: Boolean,
        val ready: Boolean,
        val source: String? = null,
        val decided: String? = null,
    )

    private var restoreFailed = false

    fun select(): AndroidInstallationState = locked { selectLocked() }

    /**
     * Ausdrückliche Übernahme der Altfamilie [source], im Hold oder später nach [keepCurrent]; wirkt beim nächsten [select]
     * (neuer Prozess). Der Entscheidungsbeleg gilt für die jetzt vorhandenen Familien, geschrieben wird er erst nach
     * gelungener Kopie: Scheitert sie, bleibt die bisherige Entscheidung bestehen.
     */
    fun requestRestore(
        activeToken: String,
        source: String,
    ) = locked {
        val inventory = inventory()
        check(readReservation() == null && parseMarker(files.readMetadata(File(noBackup, MARKER)))?.first == activeToken)
        check(source != databaseName(activeToken) && inventory.any { it.databaseName == source }) { "Unknown restore source" }
        writeReservation(Reservation(newToken(), held = false, ready = false, source = source, decided = digest(inventory)))
        syncDirectory(noBackup)
    }

    /**
     * Ausdrücklich ohne Altbestand weiter: alle Familien bleiben unverändert erhalten und gelten als entschieden. Solange
     * genau diese Familien vorhanden sind, bleibt [requestRestore] später möglich.
     */
    fun keepCurrent(activeToken: String) =
        locked {
            check(readReservation() == null && parseMarker(files.readMetadata(File(noBackup, MARKER)))?.first == activeToken)
            val retained = inventory().filter { it.databaseName != databaseName(activeToken) }
            atomicWrite(File(noBackup, DECIDED), "${decision(activeToken, retained)}\n$KEPT")
            atomicWrite(File(noBackup, MARKER), "$VERSION\n$activeToken\n${mode(false)}")
        }

    private fun <T> locked(block: () -> T): T =
        try {
            Files.createDirectories(databases.toPath())
            Files.createDirectories(noBackup.toPath())
            RandomAccessFile(File(noBackup, "installation-v1.lock"), "rw").use { lock ->
                lock.channel.lock().use {
                    cleanupChecks()
                    block()
                }
            }
        } catch (error: DirectoryIteratorException) {
            val cause = error.cause ?: throw error
            error.suppressed.filter { it !== cause }.forEach(cause::addSuppressed)
            throw cause
        } catch (error: UncheckedIOException) {
            val cause = error.cause ?: throw error
            error.suppressed.filter { it !== cause }.forEach(cause::addSuppressed)
            throw cause
        }

    private fun selectLocked(): AndroidInstallationState {
        val inventory = inventory()
        val reservation = readReservation()
        if (reservation != null) return resume(reservation, inventory)
        val markerFile = File(noBackup, MARKER)
        val marker = parseMarker(files.readMetadata(markerFile))
        val hasReservation = exists(File(noBackup, RESERVATION)) || exists(File(noBackup, "$RESERVATION.pending"))
        if (marker != null && !hasReservation && !exists(File(noBackup, "$MARKER.pending"))) {
            val (token, held) = marker
            val name = databaseName(token)
            val database = File(databases, name)
            val proof = files.readMetadata(File(databases, "$name.proof"))
            if (proof == "$VERSION\n$token" && !exists(File(databases, "$name.proof.pending")) &&
                files.attributes(database)?.isRegularFile == true && validateCopy(database, token)
            ) {
                val retained = inventory.filter { it.databaseName != name }
                return state(name, token, held || !decided(token, retained), retained)
            }
        }
        val held =
            inventory.isNotEmpty() || exists(markerFile) || exists(File(noBackup, "$MARKER.pending")) ||
                exists(File(noBackup, RESERVATION))
        return begin(held, inventory)
    }

    private fun begin(
        held: Boolean,
        inventory: List<RetainedDatabaseFamily>,
    ): AndroidInstallationState {
        val reservation = Reservation(newToken(), held, ready = false)
        writeReservation(reservation) // Dauerhafte Herkunft vor createNewFile; der Hold-Intent gehört zum Journal.
        checkpoint("reservation")
        return resume(reservation, inventory)
    }

    private fun resume(
        reservation: Reservation,
        inventory: List<RetainedDatabaseFamily>,
    ): AndroidInstallationState {
        val name = databaseName(reservation.token)
        val database = File(databases, name)
        val attributes = files.attributes(database)
        if (attributes != null && !attributes.isRegularFile) throw IOException("Reserved database is not a regular file")
        val published = parseMarker(files.readMetadata(File(noBackup, MARKER)))?.first == reservation.token
        val preserve = reservation.ready || published || exists(File(databases, "$name.proof"))
        val matches = preserve && attributes != null && attributes.size() > 0 && validateCopy(database, reservation.token)
        if (!matches && preserve) {
            // Bereits fertiggestellte/ggf. veröffentlichte Daten sind niemals löschbare Zwischenprodukte.
            return begin(held = true, inventory)
        }
        if (!matches) {
            val own = ownedFiles(name)
            own.forEach { file ->
                val entry = files.attributes(file)
                if (entry != null && !entry.isRegularFile) throw IOException("Reserved family contains a non-regular file")
            }
            own.forEach { files.delete(it) }
            syncDirectory(databases)
            // Eine pending Reservierung zuerst vollständig veröffentlichen, bevor eine Datei erzeugt wird.
            writeReservation(reservation)
            val source = reservation.source
            if (source == null) {
                check(database.createNewFile()) { "Reserved database already exists" }
                checkpoint("reserved")
                initialize(database, reservation.token)
            } else if (!restoreCopy(source, database, reservation.token)) {
                return abandonRestore(name)
            } else {
                checkpoint("restored")
            }
            familyFiles(name).filter(::exists).forEach { file -> RandomAccessFile(file, "rw").use { it.fd.sync() } }
            syncDirectory(databases)
        }
        val retained = inventory.filter { it.databaseName != name }
        reservation.decided?.let { atomicWrite(File(noBackup, DECIDED), "$VERSION\n${reservation.token}\n$it") }
        val held = reservation.held || !decided(reservation.token, retained)
        writeReservation(reservation.copy(held = held, ready = true))
        checkpoint("initialized")
        atomicWrite(File(databases, "$name.proof"), "$VERSION\n${reservation.token}")
        checkpoint("proof")
        atomicWrite(File(noBackup, MARKER), "$VERSION\n${reservation.token}\n${mode(held)}")
        checkpoint("published")
        files.delete(File(noBackup, RESERVATION))
        files.delete(File(noBackup, "$RESERVATION.pending"))
        syncDirectory(noBackup)
        return state(name, reservation.token, held, retained)
    }

    private fun readReservation(): Reservation? {
        val committedText = files.readMetadata(File(noBackup, RESERVATION))
        val pendingText = files.readMetadata(File(noBackup, "$RESERVATION.pending"))
        val committed = parseReservation(committedText)
        val pending = parseReservation(pendingText)
        if (committedText != null && committed == null) return null
        if (committed != null) {
            // Eine unterbrochene neue Journalversion entwertet die zuvor atomar veröffentlichte Herkunft nicht.
            return if (pending?.token == committed.token) {
                committed.copy(held = committed.held || pending.held, ready = committed.ready || pending.ready)
            } else {
                committed
            }
        }
        return pending ?: committed
    }

    private fun parseReservation(text: String?): Reservation? {
        val parts = text?.split('\n') ?: return null
        val marker = parseMarker(parts.take(3).joinToString("\n")) ?: return null
        if (parts.size !in 4..5 || parts[3] !in setOf("reserved", "ready")) return null
        val restore = parts.getOrNull(4)?.let { line -> RESTORE_LINE.matchEntire(line)?.groupValues ?: return null }
        return Reservation(marker.first, marker.second, parts[3] == "ready", restore?.get(1), restore?.get(2))
    }

    private fun parseMarker(text: String?): Pair<String, Boolean>? {
        val parts = text?.split('\n') ?: return null
        if (parts.size != 3 || parts[0] != VERSION || !TOKEN.matches(parts[1]) || parts[2] !in setOf("normal", "held")) return null
        return parts[1] to (parts[2] == "held")
    }

    private fun writeReservation(reservation: Reservation) =
        atomicWrite(
            File(noBackup, RESERVATION),
            "$VERSION\n${reservation.token}\n${mode(reservation.held)}\n${if (reservation.ready) "ready" else "reserved"}" +
                reservation.source?.let { "\nrestore $it ${reservation.decided}" }.orEmpty(),
        )

    private fun state(
        name: String,
        token: String,
        held: Boolean,
        retained: List<RetainedDatabaseFamily>,
    ) = AndroidInstallationState(
        name,
        token,
        if (held) AndroidRecoveryRequirement.VERIFICATION_REQUIRED else AndroidRecoveryRequirement.NONE,
        retained,
        // Zuletzt geschriebene Familie: nach einem Restore ist das die zuletzt aktive des Altgeräts. Eigentum entscheidet erst die UID.
        restorable =
            if (held || files.readMetadata(File(noBackup, DECIDED)) == "${decision(token, retained)}\n$KEPT") {
                retained.maxByOrNull { family -> family.files.maxOf(::modified) }?.databaseName
            } else {
                null
            },
        restoreFailed = restoreFailed,
    )

    private fun modified(name: String): Long = files.attributes(File(databases, name))?.lastModifiedTime()?.toMillis() ?: 0

    private fun newToken(): String {
        var token: String
        do {
            token = UUID.randomUUID().toString()
        } while (ownedFiles(databaseName(token)).any(::exists))
        return token
    }

    /** Entscheidungsbeleg: genau diese Familienmenge für genau dieses aktive Token; jede neue Familie hält wieder an. */
    private fun decided(
        token: String,
        retained: List<RetainedDatabaseFamily>,
    ) = retained.isEmpty() || files.readMetadata(File(noBackup, DECIDED)) in decision(token, retained).let { setOf(it, "$it\n$KEPT") }

    private fun decision(
        token: String,
        retained: List<RetainedDatabaseFamily>,
    ) = "$VERSION\n$token\n${digest(retained)}"

    private fun digest(families: List<RetainedDatabaseFamily>): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                families
                    .map { it.databaseName }
                    .sorted()
                    .joinToString("\n")
                    .toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }

    /** Nur die eigene Reservierung wird beschrieben; jeder Fehler beim Kopieren, Prüfen oder Migrieren beendet die Übernahme. */
    private fun restoreCopy(
        source: String,
        database: File,
        token: String,
    ): Boolean {
        val sources = familyFiles(source).filter(::exists)
        if (sources.none { it.name == source } || sources.any { files.attributes(it)?.isRegularFile != true }) return false
        return try {
            sources.forEach { files.copy(it, File(databases, database.name + it.name.removePrefix(source))) }
            restore(database, token)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun abandonRestore(name: String): AndroidInstallationState {
        ownedFiles(name).forEach { files.delete(it) }
        syncDirectory(databases)
        files.delete(File(noBackup, RESERVATION))
        files.delete(File(noBackup, "$RESERVATION.pending"))
        syncDirectory(noBackup)
        restoreFailed = true
        return selectLocked()
    }

    private fun inventory(): List<RetainedDatabaseFamily> {
        val names = Files.newDirectoryStream(databases.toPath()).use { entries -> entries.map { it.fileName.toString() } }
        val families = names.mapNotNull { FILE_NAME.matchEntire(it)?.groupValues?.get(1) }.distinct().sorted()
        return families.map { name ->
            RetainedDatabaseFamily(name, names.filter { FILE_NAME.matchEntire(it)?.groupValues?.get(1) == name }.sorted())
        }
    }

    private fun cleanupChecks() {
        Files.newDirectoryStream(noBackup.toPath()).use { entries ->
            entries.filter { CHECK_DIRECTORY.matches(it.fileName.toString()) }.forEach { entry ->
                cleanupCheck(entry.toFile())
            }
        }
    }

    private fun cleanupCheck(directory: File) {
        val lexicalRoot = noBackup.toPath().toAbsolutePath().normalize()
        val path = directory.toPath().toAbsolutePath().normalize()
        val attributes = files.attributes(directory)
        if (path.parent != lexicalRoot || !CHECK_DIRECTORY.matches(directory.name) ||
            attributes?.isDirectory != true || attributes.isSymbolicLink
        ) {
            throw IOException("Private check directory is outside the expected no-backup root")
        }
        // Elternaliase sind erlaubt; der Prüfordner selbst muss unmittelbar im aufgelösten Stamm liegen.
        val realRoot = lexicalRoot.toRealPath()
        if (path.toRealPath() != realRoot.resolve(directory.name)) {
            throw IOException("Private check directory resolves outside its expected location")
        }
        files.removeCheckDirectory(directory)
    }

    private fun validateCopy(
        database: File,
        token: String,
    ): Boolean {
        val directory = Files.createTempDirectory(noBackup.toPath(), "installation-check-").toFile()
        var failure: Exception? = null
        return try {
            familyFiles(database.name).filter(::exists).forEach { source ->
                if (files.attributes(source)?.isRegularFile != true) throw IOException("Database family cannot be copied safely")
                files.copy(source, File(directory, "candidate.db${source.name.removePrefix(database.name)}"))
            }
            verifyCopy(File(directory, "candidate.db"), token)
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            try {
                cleanupCheck(directory)
            } catch (cleanup: Exception) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun familyFiles(name: String) = SUFFIXES.map { File(databases, name + it) }

    private fun ownedFiles(name: String) =
        familyFiles(name) + listOf(File(databases, "$name.proof"), File(databases, "$name.proof.pending"))

    private fun exists(file: File) = files.attributes(file) != null

    private fun atomicWrite(
        file: File,
        contents: String,
    ) {
        val pending = File(file.parentFile, "${file.name}.pending")
        FileOutputStream(pending).use { stream ->
            stream.write(contents.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        val point =
            when (file.name) {
                RESERVATION -> "reservation-pending"
                MARKER -> "marker-pending"
                DECIDED -> "decided-pending"
                else -> "proof-pending"
            }
        checkpoint(point)
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(checkNotNull(file.parentFile))
    }

    companion object {
        const val BINDING_KEY = "installation.binding.v1"
        private const val VERSION = "advantly-installation-v1"
        private const val MARKER = "installation-v1"
        private const val RESERVATION = "installation-v1.reservation"
        private const val DECIDED = "installation-v1.decided"
        private const val KEPT = "kept"
        private val RESTORE_LINE = Regex("restore (advantly(?:-install-[0-9a-f-]{36})?\\.db) ([0-9a-f]{64})")
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val FILE_NAME = Regex("(advantly(?:-install-[0-9a-f-]{36})?\\.db)(?:-wal|-shm|-journal|\\.proof(?:\\.pending)?)?")
        private val CHECK_DIRECTORY = Regex("installation-check-[0-9]+")
        private val SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        private fun databaseName(token: String) = "advantly-install-$token.db"

        private fun mode(held: Boolean) = if (held) "held" else "normal"
    }
}

/** Enge Dateinaht für reproduzierbare I/O-Fehler. Nur tatsächliches Fehlen ist ein normaler Rückgabewert. */
internal open class InstallationFileAccess {
    open fun attributes(file: File): BasicFileAttributes? =
        try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }

    open fun readMetadata(file: File): String? {
        val entry = attributes(file) ?: return null
        if (!entry.isRegularFile || entry.size() !in 1..512) return null
        return file.readText(Charsets.UTF_8)
    }

    open fun copy(
        source: File,
        target: File,
    ) {
        Files.copy(source.toPath(), target.toPath())
    }

    open fun delete(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    open fun removeCheckDirectory(directory: File) {
        // walkFileTree folgt keinen Symlinks; Fehler werden auch beim letzten Verzeichnis nicht verschluckt.
        Files.walkFileTree(
            directory.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
