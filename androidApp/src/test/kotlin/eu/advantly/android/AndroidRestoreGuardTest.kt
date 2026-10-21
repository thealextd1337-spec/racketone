package eu.advantly.android

import android.app.Application
import android.database.DefaultDatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.os.Looper
import android.system.ErrnoException
import android.system.OsConstants
import com.google.android.gms.wearable.MessageEvent
import eu.advantly.android.auth.createPhoneAccount
import eu.advantly.android.sync.PhoneSyncListenerService
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.LocalData
import eu.advantly.data.createAndroidDriver
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.match.session.MatchSessions
import eu.advantly.sync.protocol.PeerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLinux
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class AndroidRestoreGuardTest {
    @Before
    fun resetProcessSingletons() {
        AndroidMatchSessions.current()?.close()
        AndroidMatchSessions::class.java
            .getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(AndroidMatchSessions, null)
        clearGuardCache()
    }

    @Test
    fun activeDriverCorruptionCannotDeleteOrReplaceIdentity() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidInstallationGuard.get(app)
        val original = app.getDatabasePath(first.activeDatabaseName)
        val seed = createAndroidDriver(app)
        val data = LocalData(seed)
        data.settings.put(MatchSessions.DEVICE_ID_KEY, "preserved-source")
        data.settings.put("identity.authority", "preserved-authority")
        seed.close()
        val jdbc = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        jdbc.connect("jdbc:sqlite:${original.absolutePath}", Properties()).use { database ->
            database.createStatement().use {
                it.execute("PRAGMA writable_schema=ON")
                it.execute("UPDATE sqlite_master SET rootpage=2147483647 WHERE name='match_event'")
                it.execute("PRAGMA writable_schema=OFF")
            }
        }
        val family = original.parentFile!!.listFiles()!!.associate { it.name to hash(it) }
        val marker = File(app.noBackupFilesDir, "installation-v1").readBytes().toList()
        // Setting bleibt absichtlich lesbar: Die bisherige Guardprüfung allein entdeckt die Eventseite nicht.
        assertEquals(first, AndroidInstallationGuard.get(app))
        val driver = createAndroidDriver(app)
        try {
            val failure =
                runCatching {
                    driver
                        .executeQuery(
                            null,
                            "SELECT payload FROM match_event",
                            { it.next() },
                            0,
                        ).value
                }.exceptionOrNull()
            val after = original.parentFile!!.listFiles()!!.associate { it.name to hash(it) }
            val allowed = AndroidInstallationGuard.privateTransportAllowed(app)
            println(
                "Active event corruption: failure=${failure?.javaClass?.simpleName}; familyUnchanged=${family == after}; allowed=$allowed",
            )
            assertEquals(family, after)
            assertTrue(failure is SQLiteDatabaseCorruptException)
            assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
            assertEquals(marker, File(app.noBackupFilesDir, "installation-v1").readBytes().toList())
        } finally {
            driver.close()
        }
    }

    @Test
    fun corruptionAfterBootstrapRevokesExistingTransport() {
        val app = RuntimeEnvironment.getApplication()
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val first = AndroidInstallationGuard.get(app)
        val source = session.deviceId.value
        val authority = session.data.settings.get("identity.authority")
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        assertTrue(WearDataLayerInbound.isAttached)
        val original = app.getDatabasePath(first.activeDatabaseName)
        val jdbc = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        val offset =
            jdbc.connect("jdbc:sqlite:${original.absolutePath}", Properties()).use { db ->
                db.createStatement().use { statement ->
                    val page =
                        statement.executeQuery("SELECT rootpage FROM sqlite_master WHERE name='match_event'").use {
                            it.next()
                            it.getLong(1)
                        }
                    val size =
                        statement.executeQuery("PRAGMA page_size").use {
                            it.next()
                            it.getLong(1)
                        }
                    (page - 1) * size
                }
            }
        RandomAccessFile(original, "rw").use {
            it.seek(offset)
            it.writeByte(0)
        }
        val before = original.parentFile!!.listFiles()!!.associate { it.name to hash(it) }
        val driver = createAndroidDriver(app)
        try {
            assertThrows(SQLiteDatabaseCorruptException::class.java) {
                driver.executeQuery(null, "SELECT payload FROM match_event", { it.next() }, 0).value
            }
            assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
            assertNull(AndroidMatchSessions.current())
            assertFalse(WearDataLayerInbound.isAttached)
            var consumed = 0
            assertFalse(
                WearDataLayerInbound.deliverQueuedItem(
                    "node",
                    "peer",
                    listOf("advantly", "link", "q", "node", "old"),
                    byteArrayOf(1),
                ) {
                    consumed++
                },
            )
            assertEquals(0, consumed)
            assertEquals(AndroidInstallationAvailability.Unavailable, AndroidInstallationGuard.check(app))
            assertEquals(before, original.parentFile!!.listFiles()!!.associate { it.name to hash(it) })
            jdbc.connect("jdbc:sqlite:${original.absolutePath}", Properties()).use { db ->
                db.createStatement().use { statement ->
                    val actual =
                        statement.executeQuery("SELECT value FROM setting WHERE key='session.deviceId'").use {
                            it.next()
                            it.getString(1)
                        }
                    assertEquals(source, actual)
                    val actualAuthority =
                        statement.executeQuery("SELECT value FROM setting WHERE key='identity.authority'").use {
                            if (it.next()) it.getString(1) else null
                        }
                    assertEquals(authority, actualAuthority)
                }
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun copyCheckDetectsEventCorruptionBeforeProductiveOpen() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidInstallationGuard.get(app)
        val original = app.getDatabasePath(first.activeDatabaseName)
        val jdbc = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        jdbc.connect("jdbc:sqlite:${original.absolutePath}", Properties()).use { db ->
            db.createStatement().use { statement ->
                statement.execute("PRAGMA writable_schema=ON")
                statement.execute("UPDATE sqlite_master SET rootpage=2147483647 WHERE name='match_event'")
                statement.execute("PRAGMA writable_schema=OFF")
            }
        }
        val family = original.parentFile!!.listFiles()!!.associate { it.name to hash(it) }
        val marker = File(app.noBackupFilesDir, "installation-v1").readBytes().toList()
        clearGuardCache()
        repeat(2) {
            assertEquals(AndroidInstallationAvailability.Unavailable, AndroidInstallationGuard.retry(app))
            assertFalse(WearDataLayerInbound.isAttached)
            assertEquals(family, original.parentFile!!.listFiles()!!.associate { it.name to hash(it) })
            assertEquals(marker, File(app.noBackupFilesDir, "installation-v1").readBytes().toList())
        }
    }

    @Test
    fun validDatabaseWithoutSettingTableIsStillVerificationMismatch() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidInstallationGuard.get(app)
        val original = app.getDatabasePath(first.activeDatabaseName)
        SQLiteDatabase.openDatabase(original.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TABLE setting") }
        val before = hash(original)
        clearGuardCache()
        val result = AndroidInstallationGuard.check(app) as AndroidInstallationAvailability.Ready
        assertNotEquals(first.activeDatabaseName, result.installation.activeDatabaseName)
        assertFalse(result.installation.privateTransportAllowed)
        assertEquals(before, hash(original))
    }

    @Test
    fun backgroundFailureRevokesBeforeMainThreadCleanup() {
        val app = RuntimeEnvironment.getApplication()
        AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        assertTrue(WearDataLayerInbound.isAttached)
        val reporter =
            Thread { AndroidInstallationGuard.storageFailed(app, android.database.sqlite.SQLiteDiskIOException("background I/O")) }
        reporter.start()
        reporter.join(5_000)
        assertFalse(reporter.isAlive)
        assertFalse(AndroidInstallationGuard.privateTransportAllowed(app))
        assertNull(AndroidMatchSessions.current())
        var consumed = 0
        WearDataLayerInbound.deliverQueuedItem(
            "node",
            "peer",
            listOf("advantly", "link", "q", "node", "old"),
            byteArrayOf(1),
        ) { consumed++ }
        assertEquals(0, consumed)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(WearDataLayerInbound.isAttached)
        assertEquals(0, consumed)
    }

    @Test
    fun retryFromStaleErrorViewOnHealthyInstallationKeepsBootstrapRelease() {
        val app = RuntimeEnvironment.getApplication()
        val session = AndroidMatchSessions.get(app, PeerKind.PHONE)
        val state = AndroidInstallationGuard.get(app)
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        assertEquals(AndroidInstallationAvailability.Ready(state), AndroidInstallationGuard.retry(app))
        assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        assertSame(session, AndroidMatchSessions.get(app, PeerKind.PHONE))
        assertTrue(WearDataLayerInbound.isAttached)
    }

    @Test
    fun serviceColdStartHoldsOldPrivateTrafficAndFreshSessionCountsOffline() {
        val app = RuntimeEnvironment.getApplication()
        val driver = createAndroidDriver(app, "seed.db")
        val old = LocalData(driver)
        old.settings.put(MatchSessions.DEVICE_ID_KEY, "old-device-source")
        old.players.ensureSelf("Identical name", 1) { PlayerId("old-self") }
        val oldSession = MatchSessions.create(old, null, ManualScheduler(), PeerKind.PHONE)
        oldSession.start()
        val lineup = Lineup(listOf(PlayerId("old-self")), listOf(PlayerId("old-opponent")), PlayerId("old-self"))
        oldSession.create(Presets.TENNIS_SINGLES_BO3.format(), lineup)
        driver.close()
        val original = app.getDatabasePath("advantly.db")
        assertTrue(app.getDatabasePath("seed.db").renameTo(original))
        val originalHash = hash(original)
        val service = Robolectric.buildService(PhoneSyncListenerService::class.java).create().get()
        service.onMessageReceived(oldMessage())
        assertFalse(WearDataLayerInbound.isAttached)
        var consumed = 0
        assertFalse(
            WearDataLayerInbound.deliverQueuedItem(
                "fresh-node",
                "old-node",
                listOf("advantly", "link", "q", "fresh-node", "durable-private-item"),
                byteArrayOf(1, 2, 3),
            ) { consumed++ },
        )
        assertEquals(0, consumed)
        val active = AndroidMatchSessions.get(app, PeerKind.PHONE)
        assertNotEquals("old-device-source", active.deviceId.value)
        assertFalse(AndroidInstallationGuard.get(app).privateTransportAllowed)
        assertTrue(
            active.data.matches
                .recent(100)
                .isEmpty(),
        )
        assertTrue(
            active.data.players
                .all()
                .none { it.id == PlayerId("old-self") || it.displayName == "Identical name" },
        )
        assertTrue(active.data.accountIdentities.managed)
        assertTrue(
            active.data.accountIdentities.scope!!
                .startsWith("installation:"),
        )
        assertNotEquals(null, active.data.settings.get("identity.authority"))
        val self =
            active.data.players
                .self()!!
                .id
        val fresh = active.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(PlayerId("fresh-opponent")), self))
        assertTrue(active.recordPoint(Side.A))
        assertEquals(2L, active.data.events.count(fresh.value))
        assertEquals(originalHash, hash(original))
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        assertNull(activity.createPhoneAccount("https://must-never-open.invalid", "key"))
    }

    @Test
    fun activeTokenCanBeReadFromPendingWalWithoutOpeningOrCheckpointingOriginal() {
        val app = RuntimeEnvironment.getApplication()
        val state = AndroidInstallationGuard.get(app)
        val original = app.getDatabasePath(state.activeDatabaseName)
        val jdbc = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        jdbc.connect("jdbc:sqlite:${original.absolutePath}", Properties()).use { database ->
            database.createStatement().use {
                it.execute("DELETE FROM setting WHERE key = 'installation.binding.v1'")
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("PRAGMA wal_autocheckpoint=0")
                it.execute("INSERT INTO setting VALUES ('installation.binding.v1', '${state.cacheNamespace}')")
            }
            assertTrue(File("${original.absolutePath}-wal").length() > 0)
            val family = listOf(original, File("${original.absolutePath}-wal"), File("${original.absolutePath}-shm"))
            val hashes = family.associateWith(::hash)
            clearGuardCache()
            assertEquals(state, AndroidInstallationGuard.get(app))
            hashes.forEach { (file, before) -> assertEquals(before, hash(file)) }
        }
    }

    @Test
    fun knownInstallationKeepsAccountScopeAndSourceAcrossValidatedRestart() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidInstallationGuard.get(app)
        val driver = createAndroidDriver(app)
        val data = LocalData(driver)
        var id = 0
        val device = data.accountIdentities.select("retained-account-scope", "same-uid", 1, "PHONE") { "source-${++id}" }
        driver.close()
        clearGuardCache()
        val next = AndroidInstallationGuard.get(app)
        assertEquals(first, next)
        assertTrue(next.privateTransportAllowed)
        val restartedDriver = createAndroidDriver(app)
        val restarted = LocalData(restartedDriver)
        assertEquals(device, restarted.settings.get(MatchSessions.DEVICE_ID_KEY))
        assertEquals("same-uid", restarted.accountIdentities.profile)
        assertEquals("retained-account-scope", restarted.accountIdentities.scope)
        restartedDriver.close()
    }

    @Test
    @Config(shadows = [UnsupportedDirectorySyncLinux::class, RejectDefaultCorruptionHandler::class])
    fun corruptSettingRootPageCannotBeDeletedAndReopenedAsAnEmptyVerifiedCopy() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidInstallationGuard.get(app)
        val original = app.getDatabasePath(first.activeDatabaseName)
        val jdbc = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        jdbc.connect("jdbc:sqlite:${original.absolutePath}", Properties()).use { database ->
            database.createStatement().use {
                it.execute("PRAGMA writable_schema=ON")
                it.execute("UPDATE sqlite_master SET rootpage=2147483647 WHERE name='setting'")
                it.execute("PRAGMA writable_schema=OFF")
            }
        }
        val originalHash = hash(original)
        val marker = File(app.noBackupFilesDir, "installation-v1")
        val markerHash = hash(marker)
        val files = original.parentFile!!.list()!!.sorted()
        clearGuardCache()
        assertThrows(SQLiteException::class.java) { AndroidInstallationGuard.get(app) }
        assertEquals(AndroidInstallationAvailability.Unavailable, AndroidInstallationGuard.check(app))
        repeat(2) { assertEquals(AndroidInstallationAvailability.Unavailable, AndroidInstallationGuard.retry(app)) }
        assertEquals(0, RejectDefaultCorruptionHandler.calls)
        assertEquals(originalHash, hash(original))
        assertEquals(markerHash, hash(marker))
        assertEquals(files, original.parentFile!!.list()!!.sorted())
    }

    @Test
    fun failedSqliteOpenCannotPublishAReplacementInstallation() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidInstallationGuard.get(app)
        val original = app.getDatabasePath(first.activeDatabaseName)
        original.writeText("not a SQLite database: open failure")
        val originalHash = hash(original)
        val marker = File(app.noBackupFilesDir, "installation-v1")
        val markerHash = hash(marker)
        val files = original.parentFile!!.list()!!.sorted()
        clearGuardCache()
        assertThrows(SQLiteException::class.java) { AndroidInstallationGuard.get(app) }
        assertEquals(originalHash, hash(original))
        assertEquals(markerHash, hash(marker))
        assertEquals(files, original.parentFile!!.list()!!.sorted())
    }

    @Test
    fun successfullyInspectedMissingSettingTableIsAnUntrustedDatabase() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidInstallationGuard.get(app)
        val original = app.getDatabasePath(first.activeDatabaseName)
        SQLiteDatabase.openDatabase(original.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE setting")
        }
        val before = hash(original)
        clearGuardCache()
        val next = AndroidInstallationGuard.get(app)
        assertFalse(next.privateTransportAllowed)
        assertNotEquals(first.activeDatabaseName, next.activeDatabaseName)
        assertEquals(before, hash(original))
    }

    @Test
    fun intactMarkerAndSidecarCannotValidateAnAndroidDatabaseWithMissingToken() {
        val app = RuntimeEnvironment.getApplication()
        val first = AndroidInstallationGuard.get(app)
        val original = app.getDatabasePath(first.activeDatabaseName)
        SQLiteDatabase.openDatabase(original.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM setting WHERE key = 'installation.binding.v1'")
        }
        val before = hash(original)
        clearGuardCache()
        val next = AndroidInstallationGuard.get(app)
        assertFalse(next.privateTransportAllowed)
        assertNotEquals(first.activeDatabaseName, next.activeDatabaseName)
        assertEquals(before, hash(original))
    }

    private fun clearGuardCache() {
        val field = AndroidInstallationGuard::class.java.getDeclaredField("states").apply { isAccessible = true }
        (field.get(AndroidInstallationGuard) as MutableMap<*, *>).clear()
        val failures = AndroidInstallationGuard::class.java.getDeclaredField("unavailable").apply { isAccessible = true }
        (failures.get(AndroidInstallationGuard) as MutableSet<*>).clear()
    }

    private fun oldMessage(): MessageEvent =
        object : MessageEvent {
            override fun getRequestId() = 1

            override fun getPath() = WearDataLayerTransport.MESSAGE_PATH

            override fun getData() = byteArrayOf(1, 2, 3)

            override fun getSourceNodeId() = "old-node"
        }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
}

/** Der Guard muss einen eigenen werfenden Handler wählen; niemals den löschenden Android-Standardhandler. */
@Implements(DefaultDatabaseErrorHandler::class)
class RejectDefaultCorruptionHandler {
    @Implementation
    fun onCorruption(database: SQLiteDatabase) {
        calls++
        throw SQLiteDatabaseCorruptException("Default corruption handler was selected")
    }

    companion object {
        var calls = 0
    }
}

/** Robolectric/Windows kann Verzeichnisse nicht öffnen; modelliert gezielt einen nicht unterstützten fsync. */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
class UnsupportedDirectorySyncLinux : ShadowLinux() {
    @Implementation
    override fun open(
        path: String,
        flags: Int,
        mode: Int,
    ): FileDescriptor {
        if (File(path).isDirectory) throw ErrnoException("open", OsConstants.EINVAL)
        return super.open(path, flags, mode)
    }
}
