package eu.advantly.data

import eu.advantly.core.model.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountIdentityRepositoryTest {
    private val data = LocalData(createTestDriver())
    private var next = 0

    private fun id(): String = "identity-${++next}"

    private fun select(profile: String): CloudUploadSession {
        val session = data.cloudUploads.activate(profile)
        data.accountIdentities.select(data.accountIdentities.accountScope(profile, ::id), profile, 1, "PHONE", ::id)
        data.accountIdentities.bindCurrent(session)
        return session
    }

    @Test
    fun explicitSelectionExcludesImportedAliasesAndKeepsPayloadAndAcknowledgements() {
        val identities = data.accountIdentities
        data.players.ensureSelf("Alex", 0) { PlayerId("old-self") }
        data.addSelfAliases(listOf(PlayerId("imported-self")))
        identities.initialize("old-phone", "old-self", "PHONE")
        identities.recordSource("old-watch", "watch-self", "WATCH", "legacy-watch", false)
        val rows =
            listOf("old-phone", "old-watch", "foreign").mapIndexed { index, source ->
                StoredEvent("event-$index", "match", source, 1, 1, "payload-$index")
            }
        data.events.appendAll(rows)
        data.events.markConfirmed("match", listOf(StoredSequence("old-phone", 1)))
        data.cloud.acknowledge(setOf("event-0"))
        val alice = select("alice")
        assertEquals(setOf("old-phone", "old-watch"), identities.adoptionSources().map { it.deviceId }.toSet())
        assertFalse(data.isSelf(PlayerId("imported-self")))
        assertFalse(data.isSelf(PlayerId("old-self")))
        assertTrue(
            data.cloudUploads
                .capture(alice, 10)
                .events
                .isEmpty(),
        )
        identities.adopt(alice, setOf("old-phone", "old-watch"))
        assertEquals(rows.toSet(), data.events.eventsFor("match").toSet())
        assertTrue(data.isSelf(PlayerId("old-self")))
        assertTrue(data.isSelf(PlayerId("watch-self")))
        assertEquals(
            listOf("event-1"),
            data.cloudUploads
                .capture(alice, 10)
                .events
                .map { it.id },
        )
        assertEquals(2L, data.events.unconfirmedCount())
    }

    @Test
    fun conflictingAdoptionRollsBackWholeSelectionAndNeverReassignsOwner() {
        val identities = data.accountIdentities
        identities.initialize("old-phone", "old-self", "PHONE")
        identities.recordSource("old-watch", "watch-self", "WATCH", "legacy-watch", false)
        data.events.appendAll(
            listOf(
                StoredEvent("one", "match", "old-phone", 1, 1, "one"),
                StoredEvent("two", "match", "old-watch", 1, 1, "two"),
            ),
        )
        val alice = select("alice")
        identities.adopt(alice, setOf("old-watch"))
        val bob = select("bob")
        assertFailsWith<IllegalStateException> { identities.adopt(bob, linkedSetOf("old-phone", "old-watch")) }
        assertTrue(
            data.cloudUploads
                .capture(bob, 10)
                .events
                .isEmpty(),
        )
        assertEquals(listOf("old-phone"), identities.adoptionSources().map { it.deviceId })
        assertFailsWith<IllegalStateException> { data.cloudUploads.bindOwnedDevices(bob, setOf("old-watch")) }
    }

    @Test
    fun accountsAndGuestReuseTheirIdentitiesWithoutHistoricalRewrites() {
        val identities = data.accountIdentities
        identities.initialize("old-phone", "old-self", "PHONE")
        select("alice")
        val aliceDevice = data.settings.get("session.deviceId")
        val aliceSelf = data.players.self()?.id
        select("bob")
        val bobDevice = data.settings.get("session.deviceId")
        assertFalse(aliceDevice == bobDevice)
        assertFalse(aliceSelf == data.players.self()?.id)
        identities.select("guest-scope", null, 2, "PHONE", ::id)
        val guestDevice = data.settings.get("session.deviceId")
        select("alice")
        assertEquals(aliceDevice, data.settings.get("session.deviceId"))
        assertEquals(aliceSelf, data.players.self()?.id)
        identities.select("guest-scope", null, 3, "PHONE", ::id)
        assertEquals(guestDevice, data.settings.get("session.deviceId"))
    }
}
