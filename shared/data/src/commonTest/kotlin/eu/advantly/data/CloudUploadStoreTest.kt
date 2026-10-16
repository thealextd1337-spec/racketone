package eu.advantly.data

import eu.advantly.core.model.MatchId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudUploadStoreTest {
    private val data = LocalData(createTestDriver())

    private fun event(
        id: String,
        device: String = "phone",
        seq: Long = 1,
        match: String = "match",
    ) = StoredEvent(id, match, device, seq, seq, """{"type":"PointWon","side":"A"}""", type = "PointWon")

    private fun ownPhone(profileId: String = "alice"): CloudUploadSession =
        data.cloudUploads.activate(profileId).also { data.cloudUploads.bindOwnedDevices(it, setOf("phone")) }

    @Test
    fun activationDoesNotClaimAnyExistingEvents() {
        data.events.append(event("one"))
        val session = data.cloudUploads.activate("alice")
        assertTrue(
            data.cloudUploads
                .capture(session, 10)
                .events
                .isEmpty(),
        )
        assertEquals(1, data.cloud.pendingCount())
        data.cloudUploads.bindOwnedDevices(session, setOf("phone"))
        assertEquals(
            listOf("one"),
            data.cloudUploads
                .capture(session, 10)
                .events
                .map { it.id },
        )
    }

    @Test
    fun selectionExcludesForeignAndUnboundDevicesAndRespectsLimit() {
        data.events.appendAll(
            listOf(event("a2", seq = 2), event("b", "bob-phone"), event("unknown", "unknown"), event("a1")),
        )
        val bob = data.cloudUploads.activate("bob")
        data.cloudUploads.bindOwnedDevices(bob, setOf("bob-phone"))
        val alice = ownPhone()
        assertEquals(
            listOf("a1"),
            data.cloudUploads
                .capture(alice, 1)
                .events
                .map { it.id },
        )
        assertEquals(
            listOf("a1", "a2"),
            data.cloudUploads
                .capture(alice, 10)
                .events
                .map { it.id },
        )
        assertFailsWith<IllegalArgumentException> { data.cloudUploads.capture(alice, 0) }
    }

    @Test
    fun accountSwitchInvalidatesResponsesEvenAfterReturningToOriginalAccount() {
        data.events.append(event("one"))
        val alice = ownPhone()
        val beforeSwitch = data.cloudUploads.capture(alice, 10)
        val bob = data.cloudUploads.activate("bob")
        assertFailsWith<IllegalStateException> { data.cloudUploads.requireCurrent(beforeSwitch) }
        assertFailsWith<IllegalStateException> { data.cloudUploads.acknowledge(beforeSwitch, setOf("one")) }
        assertTrue(
            data.cloudUploads
                .capture(bob, 10)
                .events
                .isEmpty(),
        )
        val aliceAgain = data.cloudUploads.activate("alice")
        assertFailsWith<IllegalStateException> { data.cloudUploads.acknowledge(beforeSwitch, setOf("one")) }
        val current = data.cloudUploads.capture(aliceAgain, 10)
        data.cloudUploads.acknowledge(current, setOf("one"))
        assertEquals(0, data.cloud.pendingCount())
        assertEquals(1, data.events.unconfirmedCount())
    }

    @Test
    fun logoutImmediatelyInvalidatesSelectionsAndResponses() {
        data.events.append(event("one"))
        val session = ownPhone()
        val snapshot = data.cloudUploads.capture(session, 10)
        data.cloudUploads.deactivate()
        assertFailsWith<IllegalStateException> { data.cloudUploads.capture(session, 10) }
        assertFailsWith<IllegalStateException> { data.cloudUploads.acknowledge(snapshot, setOf("one")) }
        assertEquals(1, data.cloud.pendingCount())
    }

    @Test
    fun sessionsAndSnapshotsCannotBeUsedAtAnotherRepository() {
        val other = LocalData(createTestDriver())
        val session = ownPhone()
        val snapshot = data.cloudUploads.capture(session, 10)
        other.cloudUploads.activate("alice")
        assertFailsWith<IllegalStateException> { other.cloudUploads.capture(session, 10) }
        assertFailsWith<IllegalStateException> { other.cloudUploads.acknowledge(snapshot, emptySet()) }
    }

    @Test
    fun activationAtAnotherInstanceOnTheSameDatabaseInvalidatesOldSession() {
        val driver = createTestDriver()
        val first = LocalData(driver)
        first.events.append(event("one"))
        val alice = first.cloudUploads.activate("alice")
        first.cloudUploads.bindOwnedDevices(alice, setOf("phone"))
        val snapshot = first.cloudUploads.capture(alice, 10)
        val second = LocalData(driver)
        // Eine neue Instanz übernimmt keine laufende Sitzung aus der Datenbank.
        assertFailsWith<IllegalStateException> { second.cloudUploads.capture(alice, 10) }
        val next = second.cloudUploads.activate("alice")
        assertFailsWith<IllegalStateException> { first.cloudUploads.acknowledge(snapshot, setOf("one")) }
        assertEquals(
            listOf("one"),
            second.cloudUploads
                .capture(next, 10)
                .events
                .map { it.id },
        )
        second.cloudUploads.deactivate()
        assertFailsWith<IllegalStateException> { second.cloudUploads.capture(next, 10) }
    }

    @Test
    fun ownedDevicesCannotBeReassignedAndConflictingBindingRollsBackAllDevices() {
        ownPhone()
        val bob = data.cloudUploads.activate("bob")
        assertFailsWith<IllegalStateException> {
            data.cloudUploads.bindOwnedDevices(bob, linkedSetOf("new-device", "phone"))
        }
        data.events.appendAll(listOf(event("one"), event("new", "new-device")))
        assertTrue(
            data.cloudUploads
                .capture(bob, 10)
                .events
                .isEmpty(),
        )
        val alice = data.cloudUploads.activate("alice")
        data.cloudUploads.bindOwnedDevices(alice, setOf("phone", "new-device"))
        assertEquals(
            setOf("one", "new"),
            data.cloudUploads
                .capture(alice, 10)
                .events
                .map { it.id }
                .toSet(),
        )
    }

    @Test
    fun replyCannotAcknowledgeAnEventOutsideItsSnapshot() {
        data.events.appendAll(listOf(event("one"), event("two", seq = 2)))
        val session = ownPhone()
        val snapshot = data.cloudUploads.capture(session, 1)
        assertFailsWith<IllegalArgumentException> { data.cloudUploads.acknowledge(snapshot, setOf("one", "two")) }
        assertFailsWith<IllegalArgumentException> {
            data.cloudUploads.acknowledge(snapshot, setOf("one"), mapOf("two" to "forbidden"))
        }
        assertEquals(2, data.cloud.pendingCount())
        assertTrue(data.cloud.rejectedEvents().isEmpty())
    }

    @Test
    fun partialReplyAndLaterDeviceAckDoNotChangeSelectionOrOtherEvents() {
        data.events.appendAll(listOf(event("one"), event("two", seq = 2), event("three", seq = 3)))
        val session = ownPhone()
        val snapshot = data.cloudUploads.capture(session, 10)
        data.events.markConfirmedUpTo("match", "phone", 1)
        data.events.append(event("four", seq = 4))
        data.cloudUploads.acknowledge(snapshot, setOf("one"), mapOf("two" to "forbidden"))
        assertEquals(
            listOf("three", "four"),
            data.cloudUploads
                .capture(session, 10)
                .events
                .map { it.id },
        )
        assertEquals(mapOf("two" to "forbidden"), data.cloud.rejectedEvents())
        assertEquals(3, data.events.unconfirmedCount())
        // Wiederholung bestätigt nur die alten IDs; verspätete Ablehnung nimmt keinen Erfolg zurück.
        data.cloudUploads.acknowledge(snapshot, emptySet(), mapOf("one" to "forbidden"))
        assertEquals(mapOf("two" to "forbidden"), data.cloud.rejectedEvents())
    }

    @Test
    fun mutatedReturnedListCannotWidenAcknowledgementScope() {
        data.events.appendAll(listOf(event("one"), event("two", seq = 2), event("three", seq = 3)))
        val snapshot = data.cloudUploads.capture(ownPhone(), 2)
        val copy = snapshot.events as MutableList<StoredEvent>
        copy.add(event("three", seq = 3))
        assertEquals(2, snapshot.events.size)
        assertFailsWith<IllegalArgumentException> { data.cloudUploads.acknowledge(snapshot, setOf("three")) }
        assertEquals(3, data.cloud.pendingCount())
    }

    @Test
    fun deletedMatchCannotBeResurrectedByLateResponse() {
        data.events.append(event("one"))
        val session = ownPhone()
        val snapshot = data.cloudUploads.capture(session, 10)
        data.deleteMatch(MatchId("match"))
        data.cloudUploads.acknowledge(snapshot, setOf("one"))
        assertEquals(0, data.events.count("match"))
        assertTrue(
            data.cloudUploads
                .capture(session, 10)
                .events
                .isEmpty(),
        )
    }

    @Test
    fun replacedEventCannotReceiveAnOldAcknowledgement() {
        data.events.append(event("one"))
        val snapshot = data.cloudUploads.capture(ownPhone(), 10)
        data.events.deleteMatch("match")
        data.events.append(event("one").copy(payload = "changed"))
        assertFailsWith<IllegalStateException> { data.cloudUploads.acknowledge(snapshot, setOf("one")) }
        assertEquals(1, data.cloud.pendingCount())
    }

    @Test
    fun sessionsAndSnapshotsCannotEscapeAnOuterTransaction() {
        val session = ownPhone()
        data.transaction {
            assertFailsWith<IllegalStateException> { data.cloudUploads.activate("bob") }
            assertFailsWith<IllegalStateException> { data.cloudUploads.deactivate() }
            assertFailsWith<IllegalStateException> { data.cloudUploads.capture(session, 10) }
        }
        data.cloudUploads.requireCurrent(data.cloudUploads.capture(session, 10))
    }

    @Test
    fun acknowledgementsRollBackWithTheEnclosingDataTransaction() {
        data.events.append(event("one"))
        val snapshot = data.cloudUploads.capture(ownPhone(), 10)
        assertFailsWith<IllegalStateException> {
            data.transaction {
                data.cloudUploads.acknowledge(snapshot, setOf("one"))
                error("Lokaler Fehler nach Bestätigung")
            }
        }
        assertEquals(1, data.cloud.pendingCount())
    }
}
