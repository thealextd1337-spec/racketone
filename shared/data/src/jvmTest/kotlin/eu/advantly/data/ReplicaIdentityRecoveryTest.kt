package eu.advantly.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Datenrechte nach bereits geprüftem Login; dies ersetzt nicht die Wiederherstellungsprüfung des Hosts. */
class ReplicaIdentityRecoveryTest {
    private val profileA = "11111111-1111-4111-8111-111111111111"
    private val profileB = "22222222-2222-4222-8222-222222222222"
    private val sameName = "Alex"
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    private inner class Fixture : AutoCloseable {
        private val file = File.createTempFile("replica-identity-recovery", ".db")
        private var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        private var nextId = 0
        var data: LocalData
            private set

        init {
            AdvantlyDatabase.Schema.create(driver)
            data = LocalData(driver)
        }

        private fun id(): String = "opaque-${nextId++}"

        fun login(
            profile: String,
            replacementScope: String? = null,
        ): CloudUploadSession {
            val scope = replacementScope ?: data.accountIdentities.accountScope(profile, ::id)
            data.accountIdentities.select(scope, profile, 0, "PHONE", ::id)
            data.players.rename(checkNotNull(data.players.self()).id, sameName)
            val session = data.cloudUploads.activate(profile)
            data.accountIdentities.bindCurrent(session)
            val proof = data.cloudUploads.capture(session, 1)
            data.replicas.activate(data.replicas.captureContext()) { data.cloudUploads.requireCurrent(proof) }
            return session
        }

        fun createMatch(id: String): String {
            val self = checkNotNull(data.players.self()).id
            val guest = PlayerId("$id-guest")
            data.players.addGuest(guest, "Chris", 0)
            val lineup = Lineup(listOf(self), listOf(guest), self)
            val device = checkNotNull(data.settings.get("session.deviceId"))
            val genesis =
                ScoreEvent(
                    EventId("$id-created"),
                    MatchId(id),
                    DeviceId(device),
                    1,
                    1,
                    1,
                    null,
                    MatchCreated(format, lineup, createdBy = self),
                ).toStoredEvent()
            data.events.append(genesis)
            data.matches.insertIfAbsent(MatchId(id), format, lineup, MatchMeta(), 1)
            return device
        }

        fun reopen() {
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            data = LocalData(driver)
        }

        override fun close() {
            driver.close()
            file.delete()
        }
    }

    @Test
    fun identicalNamesDoNotJoinPrivateMatchesAcrossLoginAndReopen(): Unit =
        Fixture().use { f ->
            f.login(profileA)
            val selfA = assertNotNull(f.data.players.self()).id
            f.createMatch("private-a")
            f.login(profileB)
            val selfB = assertNotNull(f.data.players.self()).id
            assertNotEquals(selfA, selfB)
            assertEquals(
                sameName,
                f.data.players
                    .self()
                    ?.displayName,
            )
            assertNull(f.data.players.get(selfA))
            assertNull(f.data.matches.get(MatchId("private-a")))
            assertTrue(
                f.data.events
                    .eventsFor("private-a")
                    .isEmpty(),
            )
            f.createMatch("private-b")

            f.reopen()
            f.login(profileA)
            assertEquals(
                selfA,
                f.data.players
                    .self()
                    ?.id,
            )
            assertEquals(
                sameName,
                f.data.players
                    .self()
                    ?.displayName,
            )
            assertNotNull(f.data.matches.get(MatchId("private-a")))
            assertEquals(
                listOf("private-a-created"),
                f.data.events
                    .eventsFor("private-a")
                    .map { it.id },
            )
            assertNull(f.data.matches.get(MatchId("private-b")))
            assertTrue(
                f.data.events
                    .eventsFor("private-b")
                    .isEmpty(),
            )
            assertNull(f.data.players.get(selfB))
            assertEquals(
                setOf("private-a"),
                f.data.matches
                    .history()
                    .map { it.id.value }
                    .toSet(),
            )
        }

    @Test
    fun replacementDeviceNeedsSameProfileAndCannotReassignOldDevice(): Unit =
        Fixture().use { f ->
            f.login(profileA)
            val previousDevice = f.createMatch("private-a")
            f.reopen()

            // Ein neues Gerät mit verifiziertem, anderem Konto erbt trotz gleichem Namen keine Daten.
            val wrongAccount = f.login(profileB, "replacement-for-b")
            val deviceB = assertNotNull(f.data.settings.get("session.deviceId"))
            assertNotEquals(previousDevice, deviceB)
            assertNull(f.data.matches.get(MatchId("private-a")))
            assertTrue(
                f.data.cloudUploads
                    .capture(wrongAccount, 50)
                    .events
                    .isEmpty(),
            )
            assertFails { f.data.cloudUploads.bindOwnedDevices(wrongAccount, setOf(previousDevice)) }
            assertFails { f.data.accountIdentities.adopt(wrongAccount, setOf(previousDevice)) }
            assertTrue(
                f.data.cloudUploads
                    .capture(wrongAccount, 50)
                    .events
                    .isEmpty(),
            )

            // Nach geprüftem Login mit derselben UID bleibt die alte Eigentümerbindung erhalten.
            val recovered = f.login(profileA, "replacement-for-a")
            val replacementDevice = assertNotNull(f.data.settings.get("session.deviceId"))
            assertNotEquals(previousDevice, replacementDevice)
            assertNotEquals(deviceB, replacementDevice)
            assertNotNull(f.data.matches.get(MatchId("private-a")))
            assertEquals(
                listOf(previousDevice),
                f.data.cloudUploads
                    .capture(recovered, 50)
                    .events
                    .map { it.deviceId },
            )
            assertEquals(
                setOf(previousDevice, replacementDevice),
                f.data.accountIdentities
                    .ownedSources(profileA)
                    .map { it.deviceId }
                    .toSet(),
            )
            assertEquals(
                setOf(deviceB),
                f.data.accountIdentities
                    .ownedSources(profileB)
                    .map { it.deviceId }
                    .toSet(),
            )
        }

    @Test
    fun restoredSameProfileRejectsOldResponsesAndKeepsForeignNamesakesHidden(): Unit =
        Fixture().use { f ->
            val initial = f.login(profileA)
            f.createMatch("private-a")
            val oldSnapshot = f.data.cloudUploads.capture(initial, 50)
            val oldContext = f.data.replicas.captureContext()
            f.login(profileB)
            f.createMatch("private-b")
            f.reopen()
            val recovered = f.login(profileA)
            val currentProof = f.data.cloudUploads.capture(recovered, 50)

            assertFails { f.data.cloudUploads.acknowledge(oldSnapshot, setOf("private-a-created")) }
            assertFails {
                f.data.replicas.applyAccess(oldContext, "private-b", 100, ReplicaAccessLevel.MEMBER) {
                    f.data.cloudUploads.requireCurrent(currentProof)
                }
            }
            assertEquals(
                listOf("private-a-created"),
                f.data.cloudUploads
                    .capture(recovered, 50)
                    .events
                    .map { it.id },
            )
            assertNull(f.data.matches.get(MatchId("private-b")))
            val fresh = f.data.cloudUploads.capture(recovered, 50)
            f.data.cloudUploads.acknowledge(fresh, setOf("private-a-created"))
            assertTrue(
                f.data.cloudUploads
                    .capture(recovered, 50)
                    .events
                    .isEmpty(),
            )

            val other = f.login(profileB)
            assertEquals(
                listOf("private-b-created"),
                f.data.cloudUploads
                    .capture(other, 50)
                    .events
                    .map { it.id },
            )
        }
}
