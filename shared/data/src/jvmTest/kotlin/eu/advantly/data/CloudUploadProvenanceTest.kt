package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.db.AdvantlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudUploadProvenanceTest {
    private class Fixture : AutoCloseable {
        val driver = createInMemoryJvmDriver()
        val data = LocalData(driver)
        val database = AdvantlyDatabase(driver)
        val format = Presets.TENNIS_SINGLES_BO3.format()

        fun source(
            device: String,
            self: String = device,
            local: Boolean = false,
        ) {
            data.accountIdentities.recordSource(device, self, if (local) "PHONE" else "WATCH", device, local)
        }

        fun session(
            profile: String,
            vararg devices: String,
        ): CloudUploadSession = data.cloudUploads.activate(profile).also { data.cloudUploads.bindOwnedDevices(it, devices.toSet()) }

        fun match(
            id: String,
            device: String,
            creator: String,
        ) {
            val lineup = Lineup(listOf(PlayerId(creator)), listOf(PlayerId("opponent")), PlayerId(creator))
            data.events.append(
                ScoreEvent(
                    EventId("$id-genesis"),
                    MatchId(id),
                    DeviceId(device),
                    1,
                    1,
                    1,
                    null,
                    MatchCreated(format, lineup, createdBy = PlayerId(creator)),
                ).toStoredEvent(),
            )
            data.matches.insertIfAbsent(MatchId(id), format, lineup, MatchMeta(), 1)
        }

        fun assertStable(session: CloudUploadSession): CloudUploadSnapshot {
            val before = data.cloudUploads.inventory(session)
            val selected = data.cloudUploads.capture(session, 500)
            assertEquals(before, selected.inventory)
            assertEquals(before, data.cloudUploads.inventory(session))
            return selected
        }

        override fun close() = driver.close()
    }

    @Test
    fun metadataOnlyInventoryUsesTheSameSourcePreferenceWithoutClaimingUnknownGuestsOrForeignSources() {
        Fixture().use { f ->
            f.data.players.ensureSelf("Alex", 0) { PlayerId("me") }
            f.data.players.upsertRemote(Player(PlayerId("old-guest"), "Old"), 0)
            f.data.players.upsertRemote(Player(PlayerId("foreign-self"), "Foreign"), 0)
            f.data.players.markPlaceholder(PlayerId("placeholder"), 0)
            f.data.accountIdentities.initialize("phone", "me", "PHONE")
            f.source("foreign", "foreign-self")
            f.source("placeholder-source", "placeholder")
            f.source("a-peer", "preferred")
            f.source("z-local", "preferred", local = true)
            // Beide Quellen liegen bei der Neuanlage vor; dieselbe U3-Präferenz gilt auch am Schreib-Hook.
            f.data.players.upsertRemote(Player(PlayerId("preferred"), "Preferred"), 0)
            f.session("bob", "foreign", "z-local")
            val session = f.session("alice", "phone", "placeholder-source", "a-peer")
            assertEquals(CloudUploadInventory(0, 0, 1), f.data.cloudUploads.inventory(session))
            val selected = f.assertStable(session)
            assertEquals(listOf("me"), selected.players.map { it.value.id })
            assertEquals(
                "z-local",
                f.data.cloud
                    .metadataState(CloudMetadataKind.PLAYER, "preferred")
                    ?.sourceDeviceId,
            )
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.PLAYER, "old-guest")
                    ?.origin,
            )
            selected.metadataRevisions.forEach { version ->
                assertEquals(
                    0L,
                    f.data.cloud
                        .metadataState(version.kind, version.id)
                        ?.uploadedRevision,
                )
            }
            f.data.cloudUploads.acknowledge(selected, emptySet(), metadata = selected.metadataRevisions)
            f.data.players.rename(PlayerId("me"), "Alexandra")
            assertEquals(CloudUploadInventory(0, 0, 1), f.data.cloudUploads.inventory(session))
            assertTrue(f.assertStable(session).events.isEmpty())
        }
    }

    @Test
    fun aLaterPlayerSourceAndOwnershipProofAreVisibleBeforeAnyUploadSelection() {
        Fixture().use { f ->
            val session = f.session("alice")
            f.data.players.upsertRemote(Player(PlayerId("late-self"), "Alex"), 0)
            assertEquals(CloudUploadInventory(0, 0, 0), f.data.cloudUploads.inventory(session))
            f.source("late-watch", "late-self")
            assertEquals(CloudUploadInventory(0, 0, 0), f.data.cloudUploads.inventory(session))
            f.data.cloudUploads.bindOwnedDevices(session, setOf("late-watch"))
            assertEquals(CloudUploadInventory(0, 0, 1), f.data.cloudUploads.inventory(session))
            assertEquals(listOf("late-self"), f.assertStable(session).players.map { it.value.id })
            assertEquals(
                0L,
                f.data.cloud
                    .metadataState(CloudMetadataKind.PLAYER, "late-self")
                    ?.uploadedRevision,
            )
        }
    }

    @Test
    fun playerCreationAfterSourceProofUsesOnlyThatProofAndPreservesExistingMetadata() {
        Fixture().use { f ->
            f.source("own", "own-self")
            f.source("foreign", "foreign-self")
            f.source("unbound", "unbound-self")
            f.session("bob", "foreign")
            val session = f.session("alice", "own", "future-own")
            f.match("match", "own", "own-self")
            f.data.players.upsertRemote(Player(PlayerId("own-self"), "Own"), 0)
            f.data.players.upsertRemote(Player(PlayerId("foreign-self"), "Foreign"), 0)
            f.data.players.upsertRemote(Player(PlayerId("unbound-self"), "Unbound"), 0)
            f.data.players.upsertRemote(Player(PlayerId("unproven"), "Unknown"), 0)
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.PLAYER, "unproven")
                    ?.origin,
            )
            assertEquals(CloudUploadInventory(1, 0, 4), f.data.cloudUploads.inventory(session))
            assertEquals(listOf("own-self"), f.assertStable(session).players.map { it.value.id })
            val selected = f.data.cloudUploads.capture(session, 500)
            f.data.cloudUploads.acknowledge(
                selected,
                selected.events.mapTo(mutableSetOf()) { it.id },
                metadata = selected.metadataRevisions,
            )
            val own = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "own-self")
            val participants = f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match")
            f.source("future-own", "own-self", local = true)
            f.data.players.upsertRemote(Player(PlayerId("own-self"), "Own"), 0)
            assertEquals(own, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "own-self"))
            assertEquals(participants, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            assertEquals(CloudUploadInventory(0, 0, 0), f.assertStable(session).inventory)
            f.data.cloud.storeDownloadedPlayer(
                LocalPlayer(PlayerId("cloud-self"), "Cloud", PlayerKind.GUEST, 0, null),
                "alice",
                owned = true,
            )
            val downloaded = f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "cloud-self")
            f.source("cloud-proof", "cloud-self")
            f.data.players.upsertRemote(Player(PlayerId("cloud-self"), "Cloud"), 0)
            assertEquals(downloaded, f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "cloud-self"))
        }
    }

    @Test
    fun aLateSourceProofInvalidatesAnAcknowledgedParticipantRevisionBeforeStatusReading() {
        Fixture().use { f ->
            f.source("phone", "me", local = true)
            val session = f.session("alice", "phone", "late-source")
            f.match("match", "phone", "me")
            f.data.players.upsertRemote(Player(PlayerId("me"), "Own"), 0)
            f.data.players.upsertRemote(Player(PlayerId("opponent"), "Alias"), 0)
            val selected = f.data.cloudUploads.capture(session, 500)
            f.data.cloudUploads.acknowledge(
                selected,
                selected.events.mapTo(mutableSetOf()) { it.id },
                metadata = selected.metadataRevisions,
            )
            val before = requireNotNull(f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            f.source("late-source", "opponent")
            val after = requireNotNull(f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            assertEquals(before.selected.revision + 1, after.selected.revision)
            assertEquals(before.uploadedRevision, after.uploadedRevision)
            assertEquals(CloudUploadInventory(0, 0, 2), f.data.cloudUploads.inventory(session))
            val rest = f.assertStable(session)
            assertEquals(after, f.data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, "match"))
            assertEquals(
                "opponent",
                rest.participants
                    .single()
                    .value.participants
                    .last()
                    .playerId,
            )
        }
    }

    @Test
    fun sourcePlayerCreationHooksAreAtomicAndDoNotTurnKindsIntoOwnershipProofs() {
        Fixture().use { f ->
            f.source("own", "self")
            f.source("placeholder-source", "placeholder")
            f.source("archive-source", "archived")
            val session = f.session("alice", "own", "placeholder-source", "archive-source")
            f.data.players.adoptSelf(Player(PlayerId("self"), "Self"), 0)
            f.data.players.markPlaceholder(PlayerId("placeholder"), 0)
            f.data.players.archiveRemote(PlayerId("archived"), 0)
            f.data.players.adoptSelf(Player(PlayerId("unproven-self"), "Unknown"), 0)
            f.data.players.markPlaceholder(PlayerId("unproven-placeholder"), 0)
            f.data.players.archiveRemote(PlayerId("unproven-archive"), 0)
            listOf("unproven-self", "unproven-placeholder", "unproven-archive").forEach {
                assertEquals(
                    CloudMetadataOrigin.UNKNOWN,
                    f.data.cloud
                        .metadataState(CloudMetadataKind.PLAYER, it)
                        ?.origin,
                )
            }
            assertEquals(CloudUploadInventory(0, 0, 2), f.assertStable(session).inventory)
            f.source("rollback", "rollback-self")
            f.driver.execute(
                null,
                """CREATE TRIGGER fail_player_resolution BEFORE UPDATE OF origin ON cloud_metadata
                WHEN NEW.kind='PLAYER' AND NEW.entity_id='rollback-self'
                BEGIN SELECT RAISE(ABORT,'test_player_resolution_failure'); END""",
                0,
            )
            assertFailsWith<Exception> { f.data.players.upsertRemote(Player(PlayerId("rollback-self"), "Rollback"), 0) }
            assertNull(f.data.players.get(PlayerId("rollback-self")))
            assertNull(f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "rollback-self"))
            assertFailsWith<Exception> { f.data.players.adoptSelf(Player(PlayerId("rollback-self"), "Rollback"), 0) }
            assertEquals(
                PlayerId("unproven-self"),
                f.data.players
                    .self()
                    ?.id,
            )
            assertFailsWith<Exception> { f.data.players.markPlaceholder(PlayerId("rollback-self"), 0) }
            assertFailsWith<Exception> { f.data.players.archiveRemote(PlayerId("rollback-self"), 0) }
            assertNull(f.data.players.get(PlayerId("rollback-self")))
            assertNull(f.data.cloud.metadataState(CloudMetadataKind.PLAYER, "rollback-self"))
            f.data.players.upsertRemote(Player(PlayerId("late-rollback"), "Unproven"), 0)
            f.driver.execute(
                null,
                """CREATE TRIGGER fail_source_resolution BEFORE UPDATE OF origin ON cloud_metadata
                WHEN NEW.kind='PLAYER' AND NEW.entity_id='late-rollback'
                BEGIN SELECT RAISE(ABORT,'test_source_resolution_failure'); END""",
                0,
            )
            assertFailsWith<Exception> { f.source("late-rollback-source", "late-rollback") }
            assertNull(
                f.database.localIdentityQueries
                    .source("late-rollback-source")
                    .executeAsOneOrNull(),
            )
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.PLAYER, "late-rollback")
                    ?.origin,
            )
        }
    }

    @Test
    fun localCreationWithoutAnActiveIdentityStillRequiresAnExistingSourceProof() {
        Fixture().use { f ->
            f.source("self-source", "self")
            f.source("guest-source", "guest")
            val session = f.session("alice", "self-source", "guest-source")
            f.data.players.ensureSelf("Self", 0) { PlayerId("self") }
            f.data.players.addGuest(PlayerId("guest"), "Guest", 0)
            f.data.players.addGuest(PlayerId("unproven-guest"), "Unproven", 0)
            assertEquals(CloudUploadInventory(0, 0, 2), f.data.cloudUploads.inventory(session))
            assertEquals(setOf("self", "guest"), f.assertStable(session).players.mapTo(mutableSetOf()) { it.value.id })
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.PLAYER, "unproven-guest")
                    ?.origin,
            )
        }
    }

    @Test
    fun bindingAnExistingSelfAliasResolvesAnUnknownMatchBeforeStatusReading() {
        Fixture().use { f ->
            f.source("watch", "watch-self")
            f.source("phone", "phone-self", local = true)
            val session = f.session("alice", "watch")
            f.match("match", "watch", "phone-self")
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.HEADER, "match")
                    ?.origin,
            )
            assertEquals(CloudUploadInventory(1, 0, 0), f.data.cloudUploads.inventory(session))
            val original = f.data.events.eventsFor("match")
            f.data.cloudUploads.bindOwnedDevices(session, setOf("phone"))
            assertEquals(CloudUploadInventory(1, 0, 3), f.data.cloudUploads.inventory(session))
            assertEquals(listOf("match"), f.assertStable(session).headers.map { it.value.id })
            assertEquals(original, f.data.events.eventsFor("match"))
        }
    }

    @Test
    fun recordingAnAlreadyOwnedAliasCanAuthorizeAnotherOwnedGenesisButForeignAndUnboundAliasesCannot() {
        Fixture().use { f ->
            f.source("watch", "watch-self")
            f.session("bob", "foreign-alias-device")
            val session = f.session("alice", "watch", "own-alias-device")
            f.match("own", "watch", "new-own-self")
            f.match("foreign", "watch", "foreign-self")
            f.match("unbound", "watch", "unbound-self")
            val originals = listOf("own", "foreign", "unbound").associateWith { f.data.events.eventsFor(it) }
            f.source("own-alias-device", "new-own-self")
            f.source("foreign-alias-device", "foreign-self")
            f.source("unbound-alias-device", "unbound-self")
            assertEquals(CloudUploadInventory(3, 0, 3), f.data.cloudUploads.inventory(session))
            assertEquals(listOf("own"), f.assertStable(session).headers.map { it.value.id })
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.HEADER, "foreign")
                    ?.origin,
            )
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.HEADER, "unbound")
                    ?.origin,
            )
            originals.forEach { (id, events) -> assertEquals(events, f.data.events.eventsFor(id)) }
        }
    }

    @Test
    fun activationRepairsProvenExistingUnknownMetadataAndTheCursorPassesUnresolvablePages() {
        Fixture().use { f ->
            f.source("phone", "me", local = true)
            f.session("alice", "phone")
            f.match("z-last", "phone", "me")
            f.driver.execute(null, "UPDATE cloud_metadata SET origin='UNKNOWN', source_device_id=NULL WHERE entity_id='z-last'", 0)
            f.data.transaction {
                for (index in 0..500) {
                    val id = "a-${index.toString().padStart(4, '0')}"
                    f.data.events.append(StoredEvent("$id-event", id, "phone", 1, 1, "{}", type = "MatchCreated"))
                    f.database.matchHeaderQueries.insertOrIgnore(
                        id,
                        "TENNIS",
                        "SINGLES",
                        "{}",
                        "{}",
                        "{}",
                        null,
                        null,
                        1,
                        1,
                        "IN_PROGRESS",
                        null,
                    )
                }
            }
            val firstPage =
                f.database.cloudMetadataQueries
                    .unknownMatchesForProfilePage(null, "alice")
                    .executeAsList()
            assertEquals(500, firstPage.size)
            assertEquals(
                2,
                f.database.cloudMetadataQueries
                    .unknownMatchesForProfilePage(firstPage.last(), "alice")
                    .executeAsList()
                    .size,
            )
            val session = f.data.cloudUploads.activate("alice")
            assertEquals(
                CloudMetadataOrigin.LOCAL,
                f.data.cloud
                    .metadataState(CloudMetadataKind.HEADER, "z-last")
                    ?.origin,
            )
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.HEADER, "a-0000")
                    ?.origin,
            )
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.HEADER, "a-0500")
                    ?.origin,
            )
            assertEquals(CloudUploadInventory(502, 0, 3), f.data.cloudUploads.inventory(session))
            assertFalse(
                f.data.cloud
                    .rejectedEvents()
                    .isNotEmpty(),
            )
        }
    }

    @Test
    fun acknowledgedUnknownMetadataWithAProvenGenesisIsNotResolvedAgainOnActivationOrBinding() {
        Fixture().use { f ->
            f.source("phone", "me", local = true)
            val session = f.session("alice", "phone")
            f.match("acknowledged", "phone", "me")
            val selected = f.data.cloudUploads.capture(session, 500)
            f.data.cloudUploads.acknowledge(
                selected,
                selected.events.mapTo(mutableSetOf()) { it.id },
                metadata = selected.metadataRevisions,
            )
            f.driver.execute(null, "UPDATE cloud_metadata SET origin='UNKNOWN', source_device_id=NULL", 0)
            f.driver.execute(
                null,
                """CREATE TRIGGER reject_history_resolution BEFORE UPDATE OF origin ON cloud_metadata
                WHEN NEW.kind='HEADER' BEGIN SELECT RAISE(ABORT,'acknowledged_history_revisited'); END""",
                0,
            )
            val current = f.data.cloudUploads.activate("alice")
            f.data.cloudUploads.bindOwnedDevices(current, setOf("phone"))
            assertEquals(CloudUploadInventory(0, 0, 0), f.data.cloudUploads.inventory(current))
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.HEADER, "acknowledged")
                    ?.origin,
            )
        }
    }

    @Test
    fun provenanceFailuresRollbackOwnershipAndSourceWritesAndNeverExposeAPartiallyActivatedSession() {
        fun failResolution(f: Fixture) {
            f.driver.execute(
                null,
                """CREATE TRIGGER fail_resolution BEFORE UPDATE OF origin ON cloud_metadata
                WHEN NEW.kind='HEADER' AND NEW.origin!='UNKNOWN'
                BEGIN SELECT RAISE(ABORT,'test_binding_failure'); END""",
                0,
            )
        }
        Fixture().use { f ->
            f.source("phone", "me", local = true)
            f.source("alias", "creator")
            val original = f.session("alice", "phone")
            f.match("match", "phone", "creator")
            failResolution(f)
            assertFailsWith<Exception> { f.data.cloudUploads.bindOwnedDevices(original, setOf("alias")) }
            assertNull(
                f.database.cloudUploadQueries
                    .deviceOwner("alias")
                    .executeAsOneOrNull(),
            )
            assertEquals(CloudUploadInventory(1, 0, 0), f.data.cloudUploads.inventory(original))
            f.driver.execute(null, "INSERT INTO cloud_device_owner VALUES ('alias','alice')", 0)
            val epoch =
                f.database.cloudUploadQueries
                    .generation()
                    .executeAsOne()
            assertFailsWith<Exception> { f.data.cloudUploads.activate("alice") }
            assertEquals(
                epoch,
                f.database.cloudUploadQueries
                    .generation()
                    .executeAsOne(),
            )
            assertFailsWith<IllegalStateException> { f.data.cloudUploads.inventory(original) }
            assertEquals(
                CloudMetadataOrigin.UNKNOWN,
                f.data.cloud
                    .metadataState(CloudMetadataKind.HEADER, "match")
                    ?.origin,
            )
        }
        Fixture().use { f ->
            f.source("phone", "me", local = true)
            val session = f.session("alice", "phone", "alias")
            f.match("match", "phone", "creator")
            failResolution(f)
            assertFailsWith<Exception> { f.source("alias", "creator") }
            assertNull(
                f.database.localIdentityQueries
                    .source("alias")
                    .executeAsOneOrNull(),
            )
            assertEquals(
                "alice",
                f.database.cloudUploadQueries
                    .deviceOwner("alias")
                    .executeAsOneOrNull(),
            )
            assertEquals(CloudUploadInventory(1, 0, 0), f.data.cloudUploads.inventory(session))
            assertEquals(1L, f.data.events.unconfirmedCount())
        }
    }
}
