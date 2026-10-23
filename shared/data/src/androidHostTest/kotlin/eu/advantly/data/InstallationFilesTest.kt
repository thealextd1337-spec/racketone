package eu.advantly.data

import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstallationFilesTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("installation-test-").toFile()
        val databases = File(root, "databases").apply { mkdirs() }
        val noBackup = File(root, "no-backup").apply { mkdirs() }
        var selectedNoBackup = noBackup
        val marker get() = File(noBackup, "installation-v1")
        var beforeVerify: () -> Unit = {}
        var afterInitializeTable: () -> Unit = {}
        var fileAccess = InstallationFileAccess()
        var syncDirectory: (File) -> Unit = {}
        var restore: (File, String) -> Unit = { file, token ->
            connection(file).use { database ->
                database.prepareStatement("INSERT OR REPLACE INTO setting VALUES (?, ?)").use {
                    it.setString(1, InstallationFiles.BINDING_KEY)
                    it.setString(2, token)
                    it.executeUpdate()
                }
            }
        }

        /** Echte SQLite-Altfamilie mit Settings-Tabelle und einem privaten Datensatz. */
        fun legacy(
            name: String = "advantly.db",
            value: String = "private original",
        ): File =
            File(databases, name).also { file ->
                connection(file).use { database ->
                    database.createStatement().use {
                        it.execute("CREATE TABLE setting (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                        it.execute("INSERT INTO setting VALUES ('private', '$value')")
                    }
                }
            }

        fun value(
            file: File,
            key: String,
        ): String? =
            connection(file).use { database ->
                database.prepareStatement("SELECT value FROM setting WHERE key = ?").use { statement ->
                    statement.setString(1, key)
                    statement.executeQuery().use { if (it.next()) it.getString(1) else null }
                }
            }

        fun guard(checkpoint: (String) -> Unit = {}) =
            InstallationFiles(
                databases,
                selectedNoBackup,
                initialize = { file, token ->
                    connection(file).use { database ->
                        database.createStatement().use { it.execute("CREATE TABLE setting (key TEXT PRIMARY KEY, value TEXT NOT NULL)") }
                        afterInitializeTable()
                        database.prepareStatement("INSERT INTO setting VALUES (?, ?)").use {
                            it.setString(1, InstallationFiles.BINDING_KEY)
                            it.setString(2, token)
                            it.executeUpdate()
                        }
                    }
                },
                verifyCopy = { file, token ->
                    beforeVerify()
                    connection(file).use { database ->
                        database.createStatement().use { statement ->
                            statement.executeQuery("SELECT value FROM setting WHERE key = '${InstallationFiles.BINDING_KEY}'").use {
                                it.next() && it.getString(1) == token
                            }
                        }
                    }
                },
                checkpoint,
                files = fileAccess,
                syncDirectory = syncDirectory,
                restore = { file, token -> restore(file, token) },
            )

        fun files(): Map<String, String> = databases.listFiles().orEmpty().associate { it.name to hash(it) }

        fun assertPreserved(originals: Map<String, String>) {
            originals.forEach { (name, hash) -> assertEquals(hash, hash(File(databases, name)), name) }
        }

        override fun close() {
            root.deleteRecursively()
        }

        companion object {
            fun connection(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

            fun hash(file: File): String =
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
    }

    @Test
    fun wrappedDirectoryIoIsStillAnIoFailure() {
        for (wrap in listOf<(IOException) -> RuntimeException>(::DirectoryIteratorException, ::UncheckedIOException)) {
            Fixture().use { fixture ->
                val first = fixture.guard().select()
                val before = fixture.files()
                val marker = Fixture.hash(fixture.marker)
                val cause = IOException("readdir failed")
                fixture.fileAccess =
                    object : InstallationFileAccess() {
                        override fun readMetadata(file: File): String? = throw wrap(cause)
                    }
                assertSame(cause, assertFailsWith<IOException> { fixture.guard().select() })
                fixture.assertPreserved(before)
                assertEquals(marker, Fixture.hash(fixture.marker))
                fixture.fileAccess = InstallationFileAccess()
                assertEquals(first, fixture.guard().select())
            }
        }
    }

    @Test
    fun parentDirectoryAliasKeepsKnownInstallationUsableOnSecondSelection() {
        Fixture().use { fixture ->
            val alias = File(fixture.root.parentFile, "installation-alias-${java.util.UUID.randomUUID()}")
            linkDirectory(alias, fixture.root)
            try {
                val selectedAlias = shortDirectoryAlias(alias)
                fixture.selectedNoBackup = File(selectedAlias, "no-backup")
                assertTrue(Files.isSameFile(fixture.selectedNoBackup.toPath(), fixture.noBackup.toPath()))
                println("Alias root: ${fixture.selectedNoBackup.absolutePath}; canonical: ${fixture.selectedNoBackup.canonicalPath}")
                val first = fixture.guard().select()
                val originals = fixture.files()
                val marker = Fixture.hash(fixture.marker)
                assertEquals(first, fixture.guard().select())
                assertEquals(marker, Fixture.hash(fixture.marker))
                assertEquals(originals, fixture.files())
            } finally {
                Files.deleteIfExists(alias.toPath())
            }
        }
    }

    @Test
    fun privateCheckDirectoryAliasItselfIsNeverFollowedOrRemoved() {
        Fixture().use { fixture ->
            val first = fixture.guard().select()
            val foreign = File(fixture.root, "foreign-check-target").apply { mkdirs() }
            val content = File(foreign, "keep.db").apply { writeText("foreign original") }
            val alias = File(fixture.noBackup, "installation-check-123456789")
            linkDirectory(alias, foreign, junctionOnWindows = true)
            try {
                val originals = fixture.files()
                val marker = Fixture.hash(fixture.marker)
                assertFailsWith<IOException> { fixture.guard().select() }
                assertEquals("foreign original", content.readText())
                assertTrue(Files.exists(alias.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                assertEquals(originals, fixture.files())
                assertEquals(marker, Fixture.hash(fixture.marker))
            } finally {
                Files.deleteIfExists(alias.toPath())
            }
            assertEquals(first, fixture.guard().select())
        }
    }

    @Test
    fun readyReservationWithMismatchedTokenPreservesPublishedAndUnpublishedFamilies() {
        listOf("initialized", "published").forEach { interruptedAt ->
            Fixture().use { fixture ->
                assertFailsWith<IllegalStateException> {
                    fixture.guard { if (it == interruptedAt) error("process death") }.select()
                }
                val original = fixture.databases.listFiles()!!.single { it.name.endsWith(".db") }
                Fixture.connection(original).use { database ->
                    database.createStatement().use { it.execute("UPDATE setting SET value = 'mismatched-token'") }
                }
                val originals = fixture.files()
                val selected = fixture.guard().select()
                assertFalse(selected.privateTransportAllowed)
                assertNotEquals(original.name, selected.activeDatabaseName)
                assertEquals(listOf(original.name), selected.retainedFamilies.map { it.databaseName })
                fixture.assertPreserved(originals)
                assertEquals(selected, fixture.guard().select())
                fixture.assertPreserved(originals)
            }
        }
    }

    @Test
    fun validationIoDuringReadyReservationResumePreservesMarkerJournalAndPreviousHoldIntent() {
        listOf(false, true).forEach { legacy ->
            listOf("initialized", "published").forEach { interruptedAt ->
                Fixture().use { fixture ->
                    if (legacy) File(fixture.databases, "advantly.db").writeText("foreign legacy")
                    assertFailsWith<IllegalStateException> {
                        fixture.guard { if (it == interruptedAt) error("process death") }.select()
                    }
                    val journal = File(fixture.noBackup, "installation-v1.reservation")
                    val token = journal.readText().split('\n')[1]
                    val originalFiles = fixture.files()
                    val journalHash = Fixture.hash(journal)
                    val markerHash = fixture.marker.takeIf { it.exists() }?.let(Fixture::hash)
                    fixture.beforeVerify = { throw IOException("resume ENOSPC") }
                    assertFailsWith<IOException> { fixture.guard().select() }
                    assertEquals(originalFiles, fixture.files())
                    assertEquals(journalHash, Fixture.hash(journal))
                    assertEquals(markerHash, fixture.marker.takeIf { it.exists() }?.let(Fixture::hash))
                    fixture.beforeVerify = {}
                    val resumed = fixture.guard().select()
                    assertEquals(token, resumed.cacheNamespace)
                    assertEquals(!legacy, resumed.privateTransportAllowed)
                    assertEquals(resumed, fixture.guard().select())
                }
            }
        }
    }

    private fun shortDirectoryAlias(alias: File): File {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return alias
        val process =
            ProcessBuilder("cmd.exe", "/c", "for", "%I", "in", "(%INSTALLATION_TEST_ALIAS%)", "do", "@echo", "%~sI")
                .apply { environment()["INSTALLATION_TEST_ALIAS"] = "\"${alias.absolutePath}\"" }
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        assertEquals(0, process.waitFor(), output)
        return File(output)
    }

    private fun linkDirectory(
        alias: File,
        target: File,
        junctionOnWindows: Boolean = false,
    ) {
        if (!junctionOnWindows || !System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            try {
                Files.createSymbolicLink(alias.toPath(), target.toPath())
                return
            } catch (failure: java.nio.file.FileSystemException) {
                if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) throw failure
            }
        }
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            val process =
                ProcessBuilder("cmd.exe", "/c", "mklink", "/J", alias.absolutePath, target.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), output)
        }
    }

    @Test
    fun failedReservationDirectorySyncCannotCreateDatabaseFilesOrLosePreviousHoldIntent() {
        listOf(false, true).forEach { legacy ->
            Fixture().use { fixture ->
                if (legacy) File(fixture.databases, "advantly.db").writeText("foreign legacy")
                val originals = fixture.files()
                fixture.syncDirectory = { throw IOException("directory fsync I/O failure") }
                assertFailsWith<IOException> { fixture.guard().select() }
                assertEquals(originals, fixture.files())
                val journal = File(fixture.noBackup, "installation-v1.reservation").readText().split('\n')
                assertEquals(if (legacy) "held" else "normal", journal[2])
                fixture.syncDirectory = {}
                val selected = fixture.guard().select()
                assertEquals(journal[1], selected.cacheNamespace)
                assertEquals(!legacy, selected.privateTransportAllowed)
                fixture.assertPreserved(originals)
            }
        }
    }

    @Test
    fun metadataAttributeCopyAndCleanupIoFailuresNeverBecomeProofMismatches() {
        listOf("marker", "proof", "attributes", "copy", "cleanup").forEach { failingOperation ->
            Fixture().use { fixture ->
                val first = fixture.guard().select()
                val originals = fixture.files()
                val marker = Fixture.hash(fixture.marker)
                val failure =
                    if (failingOperation == "attributes") {
                        java.nio.file.AccessDeniedException(first.activeDatabaseName)
                    } else {
                        IOException("injected $failingOperation failure")
                    }
                fixture.fileAccess =
                    object : InstallationFileAccess() {
                        override fun readMetadata(file: File): String? {
                            if ((failingOperation == "marker" && file.name == "installation-v1") ||
                                (failingOperation == "proof" && file.name.endsWith(".proof"))
                            ) {
                                throw failure
                            }
                            return super.readMetadata(file)
                        }

                        override fun attributes(file: File): java.nio.file.attribute.BasicFileAttributes? {
                            if (failingOperation == "attributes" && file.name == first.activeDatabaseName) throw failure
                            return super.attributes(file)
                        }

                        override fun copy(
                            source: File,
                            target: File,
                        ) {
                            if (failingOperation == "copy") throw failure
                            super.copy(source, target)
                        }

                        override fun removeCheckDirectory(directory: File) {
                            if (failingOperation == "cleanup") throw failure
                            super.removeCheckDirectory(directory)
                        }
                    }
                assertSame(failure, assertFailsWith<IOException> { fixture.guard().select() }, failingOperation)
                assertEquals(originals, fixture.files(), failingOperation)
                assertEquals(marker, Fixture.hash(fixture.marker), failingOperation)
                fixture.fileAccess = InstallationFileAccess()
                assertEquals(first, fixture.guard().select(), failingOperation)
                assertEquals(originals, fixture.files(), failingOperation)
            }
        }
    }

    @Test
    fun failedAbandonedCopyCleanupStopsBeforeValidationOrDatabaseWrites() {
        Fixture().use { fixture ->
            val first = fixture.guard().select()
            val originals = fixture.files()
            val marker = Fixture.hash(fixture.marker)
            val abandoned = File(fixture.noBackup, "installation-check-123456789").apply { mkdirs() }
            val copied = File(abandoned, "candidate.db").apply { writeText("unremoved private copy") }
            var verified = 0
            fixture.beforeVerify = { verified++ }
            fixture.fileAccess =
                object : InstallationFileAccess() {
                    override fun removeCheckDirectory(directory: File) = throw IOException("cleanup denied")
                }
            assertFailsWith<IOException> { fixture.guard().select() }
            assertEquals(0, verified)
            assertEquals("unremoved private copy", copied.readText())
            assertEquals(originals, fixture.files())
            assertEquals(marker, Fixture.hash(fixture.marker))
            fixture.fileAccess = InstallationFileAccess()
            assertEquals(first, fixture.guard().select())
            assertFalse(abandoned.exists())
        }
    }

    @Test
    fun verificationIoFailureLeavesMarkerAndFamilyUnchangedAndNextStartRetriesOriginal() {
        Fixture().use { fixture ->
            val first = fixture.guard().select()
            val before = fixture.files()
            val marker = Fixture.hash(fixture.marker)
            fixture.beforeVerify = { throw IOException("ENOSPC") }
            assertFailsWith<IOException> { fixture.guard().select() }
            assertEquals(before, fixture.files())
            assertEquals(marker, Fixture.hash(fixture.marker))
            fixture.beforeVerify = {}
            assertEquals(first, fixture.guard().select())
            assertEquals(before, fixture.files())
            assertEquals(marker, Fixture.hash(fixture.marker))
        }
    }

    @Test
    fun freshFirstStartCrashResumesOnlyItsReservedFamilyAndRemainsNormal() {
        listOf("reservation-pending", "reservation", "reserved", "initialized", "proof-pending", "proof", "marker-pending", "published")
            .forEach { interruptedAt ->
                Fixture().use { fixture ->
                    assertFailsWith<IllegalStateException> {
                        fixture.guard { if (it == interruptedAt) error("process death") }.select()
                    }
                    val journal =
                        File(fixture.noBackup, "installation-v1.reservation").takeIf { it.exists() }
                            ?: File(fixture.noBackup, "installation-v1.reservation.pending")
                    val token = journal.readText().split('\n')[1]
                    val ownName = "advantly-install-$token.db"
                    val resumed = fixture.guard().select()
                    assertTrue(resumed.privateTransportAllowed, interruptedAt)
                    assertEquals(ownName, resumed.activeDatabaseName, interruptedAt)
                    assertEquals(token, resumed.cacheNamespace, interruptedAt)
                    assertTrue(resumed.retainedFamilies.isEmpty(), interruptedAt)
                    assertEquals(resumed, fixture.guard().select())
                }
            }
    }

    @Test
    fun crashWithLegacyOriginalRetainsHoldAndOnlyResumesItsOwnReservation() {
        listOf("reservation-pending", "reservation", "reserved", "initialized", "proof-pending", "proof", "marker-pending", "published")
            .forEach { interruptedAt ->
                Fixture().use { fixture ->
                    File(fixture.databases, "advantly.db").writeText("private original")
                    File(fixture.databases, "advantly.db-wal").writeText("uncheckpointed original")
                    val originals = fixture.files()
                    assertFailsWith<IllegalStateException> {
                        fixture.guard { if (it == interruptedAt) error("process death") }.select()
                    }
                    val journal =
                        File(fixture.noBackup, "installation-v1.reservation").takeIf { it.exists() }
                            ?: File(fixture.noBackup, "installation-v1.reservation.pending")
                    val token = journal.readText().split('\n')[1]
                    val ownName = "advantly-install-$token.db"
                    val resumed = fixture.guard().select()
                    assertFalse(resumed.privateTransportAllowed, interruptedAt)
                    assertEquals(ownName, resumed.activeDatabaseName, interruptedAt)
                    assertEquals(listOf("advantly.db"), resumed.retainedFamilies.map { it.databaseName })
                    fixture.assertPreserved(originals)
                    assertEquals(resumed, fixture.guard().select())
                    fixture.assertPreserved(originals)
                }
            }
    }

    @Test
    fun initializationFailureCannotTurnFreshReservationIntoForeignRecoveryData() {
        Fixture().use { fixture ->
            fixture.afterInitializeTable = { throw IOException("disk full during initialization") }
            assertFailsWith<IOException> { fixture.guard().select() }
            val ownName = fixture.databases.list()!!.single { it.endsWith(".db") }
            fixture.afterInitializeTable = {}
            val resumed = fixture.guard().select()
            assertEquals(ownName, resumed.activeDatabaseName)
            assertTrue(resumed.privateTransportAllowed)
            assertTrue(resumed.retainedFamilies.isEmpty())
            assertEquals(resumed, fixture.guard().select())
        }
    }

    @Test
    fun incompleteOwnInitializationCanRecreateCorruptIntermediateBytesWithoutAttestingForeignData() {
        Fixture().use { fixture ->
            assertFailsWith<IllegalStateException> { fixture.guard { if (it == "reserved") error("process death") }.select() }
            val own = fixture.databases.listFiles()!!.single { it.name.endsWith(".db") }
            own.writeText("incomplete SQLite initialization")
            val resumed = fixture.guard().select()
            assertEquals(own.name, resumed.activeDatabaseName)
            assertTrue(resumed.privateTransportAllowed)
            assertEquals(resumed, fixture.guard().select())
        }
    }

    @Test
    fun tornPendingWritesKeepCommittedReservationProvenanceAndPreviousHoldIntent() {
        listOf(false, true).forEach { legacy ->
            listOf("reservation", "proof", "marker").forEach { target ->
                Fixture().use { fixture ->
                    if (legacy) File(fixture.databases, "advantly.db").writeText("foreign legacy")
                    val originals = fixture.files()
                    assertFailsWith<IllegalStateException> {
                        fixture.guard { if (it == "initialized") error("process death") }.select()
                    }
                    val journal = File(fixture.noBackup, "installation-v1.reservation")
                    val token = journal.readText().split('\n')[1]
                    val pending =
                        when (target) {
                            "reservation" -> File(fixture.noBackup, "installation-v1.reservation.pending")
                            "marker" -> File(fixture.noBackup, "installation-v1.pending")
                            else -> File(fixture.databases, "advantly-install-$token.db.proof.pending")
                        }
                    pending.writeText("torn metadata write")
                    val resumed = fixture.guard().select()
                    assertEquals("advantly-install-$token.db", resumed.activeDatabaseName)
                    assertEquals(!legacy, resumed.privateTransportAllowed)
                    assertEquals(if (legacy) listOf("advantly.db") else emptyList(), resumed.retainedFamilies.map { it.databaseName })
                    fixture.assertPreserved(originals)
                    assertEquals(resumed, fixture.guard().select())
                }
            }
        }
    }

    @Test
    fun tornFirstReservationWithNoDatabaseCannotCreatePermanentRecoveryHold() {
        Fixture().use { fixture ->
            File(fixture.noBackup, "installation-v1.reservation.pending").writeText("torn first write")
            val selected = fixture.guard().select()
            assertTrue(selected.privateTransportAllowed)
            assertTrue(selected.retainedFamilies.isEmpty())
            assertEquals(selected, fixture.guard().select())
        }
    }

    @Test
    fun missingOrCorruptJournalCannotAdoptAnExistingForeignFamily() {
        listOf("absent", "corrupt").forEach { journalState ->
            Fixture().use { fixture ->
                assertFailsWith<IllegalStateException> {
                    fixture.guard { if (it == "initialized") error("process death") }.select()
                }
                val journal = File(fixture.noBackup, "installation-v1.reservation")
                if (journalState == "absent") Files.delete(journal.toPath()) else journal.writeText("corrupt journal")
                val originals = fixture.files()
                val next = fixture.guard().select()
                assertFalse(next.privateTransportAllowed)
                assertTrue(next.activeDatabaseName !in originals)
                fixture.assertPreserved(originals)
                assertEquals(next, fixture.guard().select())
            }
        }
    }

    @Test
    fun abandonedPrivateCheckCopiesAreRemovedBeforeValidationAndForeignDirectoriesArePreserved() {
        Fixture().use { fixture ->
            val first = fixture.guard().select()
            val abandoned = File(fixture.noBackup, "installation-check-123456789").apply { mkdirs() }
            File(abandoned, "candidate.db").writeText("abandoned private copy")
            val foreign = File(fixture.noBackup, "installation-check-foreign").apply { mkdirs() }
            val foreignFile = File(foreign, "keep.db").apply { writeText("foreign") }
            val originals = fixture.files()
            assertEquals(first, fixture.guard().select())
            assertFalse(abandoned.exists())
            assertEquals("foreign", foreignFile.readText())
            fixture.assertPreserved(originals)
        }
    }

    @Test
    fun emptyInstallationPublishesMatchingDatabaseTokenAndKeepsOfflineIdentityOnRestart() {
        Fixture().use { fixture ->
            val first = fixture.guard().select()
            assertTrue(first.privateTransportAllowed)
            assertTrue(first.retainedFamilies.isEmpty())
            val hashes = fixture.files()
            assertEquals(first, fixture.guard().select())
            fixture.assertPreserved(hashes)
        }
    }

    @Test
    fun markerlessLegacyWithPendingWalAndJournalRemainsByteIdentical() {
        Fixture().use { fixture ->
            val legacy = File(fixture.databases, "advantly.db")
            Fixture.connection(legacy).use { connection ->
                connection.createStatement().use {
                    it.execute("PRAGMA journal_mode=WAL")
                    it.execute("PRAGMA wal_autocheckpoint=0")
                    it.execute("CREATE TABLE private_data (name TEXT)")
                    it.execute("INSERT INTO private_data VALUES ('same-name-old-private')")
                }
                File(fixture.databases, "advantly.db-journal").writeBytes(byteArrayOf(1, 2, 3, 4))
                assertTrue(File(fixture.databases, "advantly.db-wal").length() > 0)
                assertTrue(File(fixture.databases, "advantly.db-shm").exists())
                val hashes = fixture.files()
                val first = fixture.guard().select()
                assertFalse(first.privateTransportAllowed)
                assertNotEquals("advantly.db", first.activeDatabaseName)
                assertEquals(hashes.keys.sorted(), first.retainedFamilies.single().files)
                assertEquals(first, fixture.guard().select())
                fixture.assertPreserved(hashes)
            }
        }
    }

    @Test
    fun knownCandidateTokenIsValidatedFromRecentWalAndOriginalFamilyIsUnchanged() {
        Fixture().use { fixture ->
            val first = fixture.guard().select()
            val database = File(fixture.databases, first.activeDatabaseName)
            Fixture.connection(database).use { connection ->
                connection.createStatement().use {
                    it.execute("DELETE FROM setting")
                    it.execute("PRAGMA journal_mode=WAL")
                    it.execute("PRAGMA wal_autocheckpoint=0")
                }
                connection.prepareStatement("INSERT INTO setting VALUES (?, ?)").use {
                    it.setString(1, InstallationFiles.BINDING_KEY)
                    it.setString(2, first.cacheNamespace)
                    it.executeUpdate()
                }
                assertTrue(File("${database.absolutePath}-wal").length() > 0)
                val hashes = fixture.files()
                assertEquals(first, fixture.guard().select())
                fixture.assertPreserved(hashes)
            }
        }
    }

    @Test
    fun copiedGuardedDatabaseWithoutMarkerIsDiscoveredAndNeverAttested() {
        Fixture().use { source ->
            val prior = source.guard().select()
            Fixture().use { restored ->
                source.databases
                    .listFiles()
                    .orEmpty()
                    .forEach { it.copyTo(File(restored.databases, it.name)) }
                val hashes = restored.files()
                val selected = restored.guard().select()
                assertFalse(selected.privateTransportAllowed)
                assertNotEquals(prior.activeDatabaseName, selected.activeDatabaseName)
                assertNotEquals(prior.cacheNamespace, selected.cacheNamespace)
                assertEquals(prior.activeDatabaseName, selected.retainedFamilies.single().databaseName)
                restored.assertPreserved(hashes)
            }
        }
    }

    @Test
    fun matchingMarkerAndSidecarCannotAttestSwappedDatabaseWithDifferentOrMissingToken() {
        listOf(true, false).forEach { withDifferentToken ->
            Fixture().use { fixture ->
                val first = fixture.guard().select()
                Fixture().use { replacement ->
                    val other = replacement.guard().select()
                    val otherFile = File(replacement.databases, other.activeDatabaseName)
                    if (!withDifferentToken) {
                        Fixture.connection(otherFile).use { database ->
                            database.createStatement().use { it.execute("DELETE FROM setting") }
                        }
                    }
                    otherFile.copyTo(File(fixture.databases, first.activeDatabaseName), overwrite = true)
                }
                val hashes = fixture.files()
                val selected = fixture.guard().select()
                assertFalse(selected.privateTransportAllowed)
                assertNotEquals(first.activeDatabaseName, selected.activeDatabaseName)
                fixture.assertPreserved(hashes)
            }
        }
    }

    @Test
    fun corruptMarkerOrSidecarPreservesEveryDatabaseByte() {
        listOf("marker", "proof").forEach { corrupt ->
            Fixture().use { fixture ->
                val first = fixture.guard().select()
                if (corrupt == "marker") fixture.marker.writeText("damaged")
                if (corrupt == "proof") File(fixture.databases, "${first.activeDatabaseName}.proof").writeText("damaged")
                val hashes = fixture.files()
                val selected = fixture.guard().select()
                assertFalse(selected.privateTransportAllowed)
                assertNotEquals(first.activeDatabaseName, selected.activeDatabaseName)
                fixture.assertPreserved(hashes)
                assertEquals(selected, fixture.guard().select())
                fixture.assertPreserved(hashes)
            }
        }
    }

    @Test
    fun interruptedInitializationWithRestoredGuardedFamilyPreservesItsOriginalTokenAndHold() {
        listOf("reserved", "initialized", "proof", "published").forEach { interruptedAt ->
            Fixture().use { source ->
                val original = source.guard().select()
                Fixture().use { restored ->
                    source.databases.listFiles()!!.forEach { it.copyTo(File(restored.databases, it.name)) }
                    val originals = restored.files()
                    assertFailsWith<IllegalStateException> {
                        restored.guard { if (it == interruptedAt) error("process death") }.select()
                    }
                    val selected = restored.guard().select()
                    assertFalse(selected.privateTransportAllowed)
                    assertNotEquals(original.activeDatabaseName, selected.activeDatabaseName)
                    assertNotEquals(original.cacheNamespace, selected.cacheNamespace)
                    assertEquals(listOf(original.activeDatabaseName), selected.retainedFamilies.map { it.databaseName })
                    restored.assertPreserved(originals)
                    assertEquals(selected, restored.guard().select())
                    restored.assertPreserved(originals)
                }
            }
        }
    }

    @Test
    fun interruptedProofOrPointerPublicationCannotReuseAnOtherwiseValidCandidate() {
        listOf("pointer", "proof").forEach { interrupted ->
            Fixture().use { fixture ->
                val first = fixture.guard().select()
                val pending =
                    if (interrupted == "pointer") {
                        File(fixture.noBackup, "installation-v1.pending")
                    } else {
                        File(fixture.databases, "${first.activeDatabaseName}.proof.pending")
                    }
                pending.writeText("interrupted publication")
                val hashes = fixture.files()
                val next = fixture.guard().select()
                assertFalse(next.privateTransportAllowed)
                assertNotEquals(first.activeDatabaseName, next.activeDatabaseName)
                fixture.assertPreserved(hashes)
                assertEquals(next, fixture.guard().select())
            }
        }
    }

    @Test
    fun damagedActivePointerAndForeignPathsAreNeverOpened() {
        Fixture().use { fixture ->
            fixture.marker.writeText("advantly-installation-v1\n../../foreign\nnormal")
            val foreign = File(fixture.databases, "other-app.db").apply { writeText("foreign") }
            val foreignHash = Fixture.hash(foreign)
            val selected = fixture.guard().select()
            assertFalse(selected.privateTransportAllowed)
            assertTrue(selected.retainedFamilies.isEmpty())
            assertEquals(foreignHash, Fixture.hash(foreign))
        }
    }

    // --- Recovery-Paket: ausdrückliche Entscheidung im Hold ---------------------------------------------------------

    @Test
    fun explicitRestoreCopiesTheRetainedFamilyIntoANewOwnFamilyAndKeepsEveryOriginalByteIdentical() {
        Fixture().use { fixture ->
            fixture.legacy()
            val held = fixture.guard().select()
            assertFalse(held.privateTransportAllowed)
            assertEquals("advantly.db", held.restorable)
            val originals = fixture.files()

            fixture.guard().requestRestore(held.cacheNamespace, "advantly.db")
            fixture.assertPreserved(originals)
            val restored = fixture.guard().select()
            assertTrue(restored.privateTransportAllowed)
            assertTrue(restored.activeDatabaseName !in setOf("advantly.db", held.activeDatabaseName))
            assertEquals(setOf("advantly.db", held.activeDatabaseName), restored.retainedFamilies.map { it.databaseName }.toSet())
            assertEquals(null, restored.restorable)
            val copy = File(fixture.databases, restored.activeDatabaseName)
            assertEquals("private original", fixture.value(copy, "private"))
            assertEquals(restored.cacheNamespace, fixture.value(copy, InstallationFiles.BINDING_KEY))
            fixture.assertPreserved(originals)
            assertEquals(restored, fixture.guard().select())
        }
    }

    @Test
    fun interruptedRestoreResumesOnlyItsOwnCopyAtEveryStep() {
        listOf("reservation-pending", "restored", "initialized", "proof-pending", "proof", "marker-pending", "published")
            .forEach { interruptedAt ->
                Fixture().use { fixture ->
                    fixture.legacy()
                    val held = fixture.guard().select()
                    fixture.guard().requestRestore(held.cacheNamespace, "advantly.db")
                    val originals = fixture.files()
                    assertFailsWith<IllegalStateException>(interruptedAt) {
                        fixture.guard { if (it == interruptedAt) error("process death") }.select()
                    }
                    val resumed = fixture.guard().select()
                    assertTrue(resumed.privateTransportAllowed, interruptedAt)
                    assertTrue(resumed.activeDatabaseName !in setOf("advantly.db", held.activeDatabaseName), interruptedAt)
                    assertEquals(
                        "private original",
                        fixture.value(File(fixture.databases, resumed.activeDatabaseName), "private"),
                        interruptedAt,
                    )
                    originals.filterKeys { !it.startsWith(resumed.activeDatabaseName) }.let(fixture::assertPreserved)
                    assertEquals(resumed, fixture.guard().select(), interruptedAt)
                }
            }
    }

    @Test
    fun failedRestoreSwitchesNothingAndOffersTheSameDecisionAgain() {
        Fixture().use { fixture ->
            fixture.legacy()
            val held = fixture.guard().select()
            fixture.guard().requestRestore(held.cacheNamespace, "advantly.db")
            val originals = fixture.files()
            fixture.restore = { _, _ -> throw IllegalStateException("downgrade or corrupt copy") }
            val failed = fixture.guard().select()
            assertTrue(failed.restoreFailed)
            assertFalse(failed.privateTransportAllowed)
            assertEquals(held.activeDatabaseName, failed.activeDatabaseName)
            assertEquals("advantly.db", failed.restorable)
            assertEquals(originals.keys, fixture.files().keys, "Keine Reste der abgebrochenen Kopie")
            fixture.assertPreserved(originals)
            assertFalse(File(fixture.noBackup, "installation-v1.reservation").exists())
            val again = fixture.guard().select()
            assertFalse(again.restoreFailed)
            assertEquals(held.activeDatabaseName, again.activeDatabaseName)
        }
    }

    @Test
    fun keepingWithoutRestoreReleasesTheHoldButKeepsEveryFamilyAndANewFamilyHoldsAgain() {
        Fixture().use { fixture ->
            fixture.legacy()
            val held = fixture.guard().select()
            val originals = fixture.files()
            fixture.guard().keepCurrent(held.cacheNamespace)
            val kept = fixture.guard().select()
            assertTrue(kept.privateTransportAllowed)
            assertEquals(held.activeDatabaseName, kept.activeDatabaseName)
            assertEquals(listOf("advantly.db"), kept.retainedFamilies.map { it.databaseName })
            fixture.assertPreserved(originals)

            // Eine später auftauchende Familie (erneuter Restore) ist nicht entschieden.
            fixture.legacy("advantly-install-00000000-0000-4000-8000-000000000001.db", "second image")
            val again = fixture.guard().select()
            assertFalse(again.privateTransportAllowed)
            assertEquals("advantly-install-00000000-0000-4000-8000-000000000001.db", again.restorable)
        }
    }

    @Test
    fun decisionsRequireTheCurrentTokenAndAKnownForeignFamily() {
        Fixture().use { fixture ->
            fixture.legacy()
            val held = fixture.guard().select()
            val other = "00000000-0000-4000-8000-000000000009"
            assertFailsWith<IllegalStateException> { fixture.guard().requestRestore(other, "advantly.db") }
            assertFailsWith<IllegalStateException> { fixture.guard().keepCurrent(other) }
            assertFailsWith<IllegalStateException> { fixture.guard().requestRestore(held.cacheNamespace, held.activeDatabaseName) }
            assertFailsWith<IllegalStateException> { fixture.guard().requestRestore(held.cacheNamespace, "advantly-missing.db") }
            assertEquals(held, fixture.guard().select())
        }
    }

    @Test
    fun restorableIsTheMostRecentlyWrittenRetainedFamily() {
        Fixture().use { fixture ->
            fixture.legacy().setLastModified(1_000_000)
            val newer = fixture.legacy("advantly-install-00000000-0000-4000-8000-000000000002.db", "active on old device")
            newer.setLastModified(2_000_000)
            assertEquals(newer.name, fixture.guard().select().restorable)
        }
    }
}
