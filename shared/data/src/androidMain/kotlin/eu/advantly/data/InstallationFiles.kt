package eu.advantly.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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

/** Keine Freigabe-API: Konto-/Zugriffsprüfung und explizite lokale Übernahme folgen erst nach D2/D3. */
@ConsistentCopyVisibility
data class AndroidInstallationState internal constructor(
    val activeDatabaseName: String,
    val cacheNamespace: String,
    val recovery: AndroidRecoveryRequirement,
    val retainedFamilies: List<RetainedDatabaseFamily>,
) {
    val privateTransportAllowed: Boolean get() = recovery == AndroidRecoveryRequirement.NONE
}

/**
 * Dateiauswahl vor produktiven SQL-Opens. Eine nicht im Backup gespeicherte Reservierung belegt ausschließlich die eigene,
 * noch unveröffentlichte Zwischenfamilie. Fremde Familien werden nie initialisiert, verändert oder gelöscht.
 * Validierung öffnet nur eine vollständige private Kopie; I/O-/SQLite-Fehler brechen ohne Umschaltung ab.
 *
 * Leistungsgrenze dieses Schutzschnitts: Jeder Prozessstart kopiert die aktive Familie synchron, derzeit auch vom
 * Main-Thread. Dafür wird zusätzlicher freier Speicher in Familiengröße benötigt; große Familien können den Start
 * blockieren und ein ANR auslösen. Eine asynchrone Initialisierung ist eine separate spätere Arbeit.
 */
internal class InstallationFiles(
    private val databases: File,
    private val noBackup: File,
    private val initialize: (File, String) -> Unit,
    private val verifyCopy: (File, String) -> Boolean,
    private val checkpoint: (String) -> Unit = {},
    private val files: InstallationFileAccess = InstallationFileAccess(),
    private val syncDirectory: (File) -> Unit = {},
) {
    private data class Reservation(
        val token: String,
        val held: Boolean,
        val ready: Boolean,
    )

    fun select(): AndroidInstallationState {
        Files.createDirectories(databases.toPath())
        Files.createDirectories(noBackup.toPath())
        return RandomAccessFile(File(noBackup, "installation-v1.lock"), "rw").use { lock ->
            lock.channel.lock().use {
                cleanupChecks()
                selectLocked()
            }
        }
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
                return state(name, token, held || retained.isNotEmpty(), retained)
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
        var token: String
        do {
            token = UUID.randomUUID().toString()
        } while (ownedFiles(databaseName(token)).any(::exists))
        val reservation = Reservation(token, held, ready = false)
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
            check(database.createNewFile()) { "Reserved database already exists" }
            checkpoint("reserved")
            initialize(database, reservation.token)
            familyFiles(name).filter(::exists).forEach { file -> RandomAccessFile(file, "rw").use { it.fd.sync() } }
            syncDirectory(databases)
        }
        val retained = inventory.filter { it.databaseName != name }
        val held = reservation.held || retained.isNotEmpty()
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
        if (parts.size != 4 || parts[3] !in setOf("reserved", "ready")) return null
        return Reservation(marker.first, marker.second, parts[3] == "ready")
    }

    private fun parseMarker(text: String?): Pair<String, Boolean>? {
        val parts = text?.split('\n') ?: return null
        if (parts.size != 3 || parts[0] != VERSION || !TOKEN.matches(parts[1]) || parts[2] !in setOf("normal", "held")) return null
        return parts[1] to (parts[2] == "held")
    }

    private fun writeReservation(reservation: Reservation) =
        atomicWrite(
            File(noBackup, RESERVATION),
            "$VERSION\n${reservation.token}\n${mode(reservation.held)}\n${if (reservation.ready) "ready" else "reserved"}",
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
    )

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
