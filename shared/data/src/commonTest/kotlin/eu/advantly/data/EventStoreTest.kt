package eu.advantly.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Läuft auf JVM, Android-Host und (CI/macOS) mit dem native-driver auf iOS-/watchOS-Simulator. */
class EventStoreTest {
    private val store: EventStore = SqlDelightEventStore(createTestDriver())

    private fun event(
        id: String,
        matchId: String = "m1",
        deviceId: String = "watch",
        deviceSeq: Long = 1,
        lamport: Long = 1,
        payload: String = """{"type":"PointWon","side":"A"}""",
    ) = StoredEvent(id, matchId, deviceId, deviceSeq, lamport, payload)

    @Test
    fun appendStoresAndReturnsEvent() {
        val e = event("e1")
        assertEquals(AppendResult.INSERTED, store.append(e))
        assertEquals(listOf(e), store.eventsFor("m1"))
        assertEquals(1, store.count("m1"))
    }

    @Test
    fun appendIsIdempotentById() {
        val e = event("e1")
        store.append(e)
        assertEquals(AppendResult.DUPLICATE, store.append(e))
        // auch wenn die Wiederholung (fehlerhaft) andere Inhalte trägt: erstes Event bleibt
        assertEquals(AppendResult.DUPLICATE, store.append(e.copy(lamport = 99, payload = "{}")))
        assertEquals(listOf(e), store.eventsFor("m1"))
    }

    @Test
    fun sameDeviceSequenceWithOtherIdIsConflict() {
        store.append(event("e1", deviceSeq = 1))
        assertEquals(AppendResult.SEQUENCE_CONFLICT, store.append(event("e2", deviceSeq = 1)))
        assertEquals(1, store.count("m1"))
    }

    @Test
    fun sameSequenceOnOtherDeviceOrMatchIsAllowed() {
        assertEquals(AppendResult.INSERTED, store.append(event("e1", deviceId = "watch", deviceSeq = 1)))
        assertEquals(AppendResult.INSERTED, store.append(event("e2", deviceId = "phone", deviceSeq = 1)))
        assertEquals(AppendResult.INSERTED, store.append(event("e3", matchId = "m2", deviceId = "watch", deviceSeq = 1)))
        assertEquals(2, store.count("m1"))
        assertEquals(1, store.count("m2"))
    }

    @Test
    fun eventsAreOrderedByLamportThenDeviceThenId() {
        // absichtlich in "falscher" Reihenfolge eingefügt
        val inserted =
            listOf(
                event("c", deviceId = "watch", deviceSeq = 2, lamport = 3),
                event("b2", deviceId = "watch", deviceSeq = 1, lamport = 2),
                event("b1", deviceId = "phone", deviceSeq = 2, lamport = 2),
                event("a", deviceId = "phone", deviceSeq = 1, lamport = 1),
                event("z", deviceId = "phone", deviceSeq = 3, lamport = 3),
            )
        inserted.forEach { store.append(it) }
        assertEquals(listOf("a", "b1", "b2", "z", "c"), store.eventsFor("m1").map { it.id })
    }

    @Test
    fun eventsForFiltersByMatch() {
        store.append(event("e1", matchId = "m1"))
        store.append(event("e2", matchId = "m2"))
        assertEquals(listOf("e2"), store.eventsFor("m2").map { it.id })
        assertEquals(emptyList(), store.eventsFor("unknown"))
        assertEquals(0, store.count("unknown"))
    }

    @Test
    fun allColumnsRoundTrip() {
        val e = event("e1").copy(wallClockMs = 1_760_000_000_123, parentId = "e0", type = "PointWon")
        store.append(e)
        assertEquals(e, store.eventsFor("m1").single())
    }

    @Test
    fun appendAllReportsEachEventInOneTransaction() {
        val results =
            store.appendAll(
                listOf(
                    event("e1", deviceSeq = 1),
                    event("e2", deviceSeq = 2),
                    event("e1", deviceSeq = 1),
                    event("e3", deviceSeq = 2),
                ),
            )
        assertEquals(listOf(AppendResult.INSERTED, AppendResult.INSERTED, AppendResult.DUPLICATE, AppendResult.SEQUENCE_CONFLICT), results)
        assertEquals(2, store.count("m1"))
        assertEquals(emptyList(), store.appendAll(emptyList()))
    }

    @Test
    fun rangeQueriesAndMaxima() {
        (1L..5L).forEach { store.append(event("w$it", deviceId = "watch", deviceSeq = it, lamport = it * 2)) }
        store.append(event("p1", deviceId = "phone", deviceSeq = 1, lamport = 3))
        store.append(event("x1", matchId = "m2", deviceId = "watch", deviceSeq = 9, lamport = 99))
        assertEquals(listOf("w2", "w3", "w4"), store.eventsInRange("m1", "watch", 2, 4).map { it.id })
        assertEquals(emptyList(), store.eventsInRange("m1", "phone", 2, 9))
        assertEquals(
            listOf(StoredSequence("phone", 1)) + (1L..5L).map { StoredSequence("watch", it) },
            store.sequences("m1"),
        )
        assertEquals(10, store.maxLamport("m1"))
        assertEquals(5, store.maxDeviceSeq("m1", "watch"))
        assertEquals(0, store.maxDeviceSeq("m1", "unknown"))
        assertEquals(0, store.maxLamport("unknown"))
        store.deleteMatch("m1")
        assertEquals(0, store.count("m1"))
        assertEquals(1, store.count("m2"))
    }

    @Test
    fun confirmationTracksTheOutgoingBacklogAcrossMatches() {
        (1L..4L).forEach { store.append(event("w$it", deviceSeq = it, lamport = it)) }
        store.append(event("x1", matchId = "m2", deviceSeq = 1))
        store.append(event("r1", deviceId = "phone", deviceSeq = 1, lamport = 9).copy(confirmed = true))
        assertEquals(5, store.unconfirmedCount())
        assertEquals(listOf("m1", "m2"), store.unconfirmedMatches())
        store.markConfirmed("m1", listOf(StoredSequence("watch", 2)))
        store.markConfirmedUpTo("m1", "watch", 1)
        assertEquals(listOf("w3", "w4"), store.unconfirmedEvents("m1").map { it.id })
        store.markConfirmedUpTo("m1", "watch", 9)
        store.markConfirmed("m2", listOf(StoredSequence("watch", 1)))
        assertEquals(0, store.unconfirmedCount())
        assertEquals(emptyList(), store.unconfirmedMatches())
    }

    @Test
    fun largeLongValuesAndUnicodePayloadRoundTrip() {
        val e = event("e1", deviceSeq = Long.MAX_VALUE, lamport = Long.MAX_VALUE - 1, payload = """{"name":"Müller ✓ 🎾"}""")
        store.append(e)
        assertEquals(e, store.eventsFor("m1").single())
    }
}
