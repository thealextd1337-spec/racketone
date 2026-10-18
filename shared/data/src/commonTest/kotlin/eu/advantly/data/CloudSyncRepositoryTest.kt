package eu.advantly.data

import eu.advantly.core.model.MatchId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudSyncRepositoryTest {
    private val data = LocalData(createTestDriver())

    private fun event(
        id: String,
        seq: Long = 1,
        match: String = "match",
    ) = StoredEvent(id, match, "phone", seq, seq, """{"type":"PointWon","side":"A"}""", type = "PointWon")

    @Test
    fun everyDownloadFenceRollsBackRowsAcknowledgementsAndOuterCursor() {
        fun prepare(): LocalData {
            val local = LocalData(createTestDriver())
            local.events.appendAll(listOf(event("confirmed").copy(confirmed = true), event("rejected", 2)))
            local.cloud.acknowledge(setOf("confirmed"), mapOf("rejected" to "forbidden"))
            local.cloud.saveCursor("alice", "pull", 7)
            local.deleteMatch(MatchId("deleted"))
            return local
        }
        val downloaded = listOf(event("new", 3), event("confirmed"), event("rejected", 2), event("deleted", match = "deleted"))
        val probe = prepare()
        var boundaryCount = 0
        probe.cloud.storeDownloaded(downloaded) { boundaryCount++ }
        assertTrue(boundaryCount >= downloaded.size)
        assertEquals(3, probe.events.count("match"))
        assertEquals(0, probe.events.count("deleted"))
        assertEquals(0, probe.cloud.pendingCount())
        assertTrue(probe.cloud.rejectedEvents().isEmpty())
        assertEquals(listOf("rejected", "new"), probe.events.unconfirmedEvents("match").map { it.id })
        for (abortAt in 1..boundaryCount) {
            val local = prepare()
            val rows = local.events.eventsFor("match")
            var boundaries = 0
            assertFailsWith<IllegalStateException> {
                local.transaction {
                    local.cloud.saveCursor("alice", "pull", 8)
                    local.cloud.storeDownloaded(downloaded) { check(++boundaries != abortAt) }
                }
            }
            assertEquals(abortAt, boundaries)
            assertEquals(rows, local.events.eventsFor("match"))
            assertEquals(0, local.events.count("deleted"))
            assertEquals(0, local.cloud.pendingCount())
            assertEquals(mapOf("rejected" to "forbidden"), local.cloud.rejectedEvents())
            assertEquals(listOf("rejected"), local.events.unconfirmedEvents("match").map { it.id })
            assertEquals(7, local.cloud.cursor("alice", "pull"))
        }
    }

    @Test
    fun deviceAndCloudAcknowledgementsAreIndependentAndSurviveReopening() {
        val driver = createTestDriver()
        val first = LocalData(driver)
        first.events.appendAll(listOf(event("one"), event("two", 2)))
        first.events.markConfirmedUpTo("match", "phone", 1)
        assertEquals(2, first.cloud.pendingCount())
        assertTrue(
            first.cloud
                .pendingEvents(1)
                .single()
                .confirmed,
        )
        first.cloud.acknowledge(setOf("two"))
        val reopened = LocalData(driver)
        assertEquals(listOf("one"), reopened.cloud.pendingEvents(10).map { it.id })
        assertEquals(1, reopened.events.unconfirmedCount())
        // Wiederholung über den Geräte-Sync darf keine Cloud-Markierung zurücksetzen.
        reopened.events.append(event("two", 2))
        assertEquals(1, reopened.cloud.pendingCount())
    }

    @Test
    fun partialAcknowledgementKeepsMissingPendingAndRejectsWithoutChangingPayload() {
        val rows = listOf(event("one"), event("two", 2), event("three", 3))
        data.events.appendAll(rows)
        data.cloud.acknowledge(setOf("one"), mapOf("two" to "forbidden"))
        assertEquals(rows, data.events.eventsFor("match"))
        assertEquals(listOf("three"), data.cloud.pendingEvents(10).map { it.id })
        assertEquals(mapOf("two" to "forbidden"), data.cloud.rejectedEvents())
        // Verspätete Ablehnung darf eine bereits erfolgreiche Bestätigung nicht rückgängig machen.
        data.cloud.acknowledge(emptySet(), mapOf("one" to "forbidden"))
        assertEquals(mapOf("two" to "forbidden"), data.cloud.rejectedEvents())
        data.cloud.acknowledge(setOf("two"))
        assertTrue(data.cloud.rejectedEvents().isEmpty())
    }

    @Test
    fun pendingSelectionIsBoundedAndDeterministicAcrossMatches() {
        data.events.appendAll(listOf(event("b", 2), event("z", match = "another"), event("a")))
        assertEquals(listOf("z", "a"), data.cloud.pendingEvents(2).map { it.id })
        assertFailsWith<IllegalArgumentException> { data.cloud.pendingEvents(0) }
    }

    @Test
    fun repeatedDownloadsKeepDeviceBacklogWithoutEchoUpload() {
        val rows = listOf(event("one"), event("two", 2))
        data.events.append(rows.first())
        // Selbst ein altes Mapping mit confirmed=true darf keinen Geräte-Ack vortäuschen.
        data.cloud.storeDownloaded(rows.map { it.copy(confirmed = true) })
        data.cloud.storeDownloaded(rows.map { it.copy(confirmed = true) })
        assertEquals(2, data.events.count("match"))
        assertEquals(rows, data.events.unconfirmedEvents("match"))
        assertEquals(0, data.cloud.pendingCount())
    }

    @Test
    fun reorderedAndSpacedJsonIncludingNestedObjectsIsAnUnchangedDuplicate() {
        val original = event("one").copy(payload = """{"type":"PointWon","side":"A","extra":{"a":1,"b":2}}""")
        data.events.append(original)
        data.cloud.storeDownloaded(
            listOf(original.copy(payload = """{ "extra": {"b":2, "a":1}, "side":"A", "type":"PointWon" }""")),
        )
        assertEquals(listOf(original), data.events.eventsFor("match"))
        assertEquals(listOf(original), data.events.unconfirmedEvents("match"))
        assertEquals(0, data.cloud.pendingCount())
    }

    @Test
    fun legacyTypeInPayloadMatchesExplicitDownloadedTypeWithoutRewritingLocalRow() {
        val original = event("one").copy(type = "")
        data.events.append(original)
        data.cloud.storeDownloaded(listOf(original.copy(type = "PointWon", payload = """{"side":"A","type":"PointWon"}""")))
        assertEquals(listOf(original), data.events.eventsFor("match"))
        assertEquals(0, data.cloud.pendingCount())
        assertEquals(1, data.events.unconfirmedCount())
    }

    @Test
    fun downloadsPreserveExistingDeviceConfirmation() {
        data.events.appendAll(listOf(event("one"), event("two", 2)))
        data.events.markConfirmedUpTo("match", "phone", 1)
        data.cloud.storeDownloaded(listOf(event("one"), event("two", 2), event("three", 3)))
        assertEquals(listOf("two", "three"), data.events.unconfirmedEvents("match").map { it.id })
        assertEquals(0, data.cloud.pendingCount())
    }

    @Test
    fun genuinelyChangedJsonValueRollsBackTheWholeDownload() {
        val original = event("one").copy(payload = """{"type":"PointWon","side":"A","extra":{"a":1}}""")
        data.events.append(original)
        assertFailsWith<IllegalArgumentException> {
            data.cloud.storeDownloaded(
                listOf(event("two", 2), original.copy(payload = """{"side":"A","type":"PointWon","extra":{"a":2}}""")),
            )
        }
        assertEquals(listOf(original), data.events.eventsFor("match"))
        assertEquals(1, data.cloud.pendingCount())
    }

    @Test
    fun immutableMetadataDifferencesCannotPassAsDuplicateJson() {
        val original = event("one")
        data.events.append(original)
        val conflicting =
            listOf(
                original.copy(matchId = "other"),
                original.copy(deviceId = "other"),
                original.copy(deviceSeq = 2),
                original.copy(lamport = 2),
                original.copy(wallClockMs = 42),
                original.copy(parentId = "parent"),
                original.copy(type = "Paused"),
            )
        conflicting.forEach { row ->
            assertFailsWith<IllegalArgumentException> { data.cloud.storeDownloaded(listOf(row)) }
            assertEquals(listOf(original), data.events.eventsFor("match"))
            assertEquals(1, data.cloud.pendingCount())
        }
    }

    @Test
    fun malformedNewEventRollsBackEarlierInserts() {
        assertFailsWith<IllegalArgumentException> {
            data.cloud.storeDownloaded(listOf(event("one"), event("two", 2).copy(payload = "[]")))
        }
        assertEquals(0, data.events.count("match"))
    }

    @Test
    fun conflictingDownloadRollsBackEventsAcknowledgementAndCursorTogether() {
        val original = event("one")
        data.events.append(original)
        data.cloud.saveCursor("owner", "pull", 7)
        assertFailsWith<IllegalArgumentException> {
            data.transaction {
                data.cloud.saveCursor("owner", "pull", 8)
                data.cloud.storeDownloaded(listOf(event("two", 2), original.copy(payload = "changed")))
            }
        }
        assertEquals(listOf(original), data.events.eventsFor("match"))
        assertEquals(1, data.cloud.pendingCount())
        assertEquals(1, data.events.unconfirmedCount())
        assertEquals(7, data.cloud.cursor("owner", "pull"))
    }

    @Test
    fun sequenceConflictRollsBackTheWholeDownloadedBatch() {
        data.events.append(event("one"))
        assertFailsWith<IllegalStateException> {
            data.cloud.storeDownloaded(listOf(event("two", 2), event("same-sequence")))
        }
        assertEquals(listOf("one"), data.events.eventsFor("match").map { it.id })
        assertEquals(1, data.cloud.pendingCount())
    }

    @Test
    fun idFromAnotherMatchCannotBeAcknowledgedByDownload() {
        data.events.append(event("one"))
        assertFailsWith<IllegalArgumentException> {
            data.cloud.storeDownloaded(listOf(event("one", match = "other")))
        }
        assertEquals(1, data.cloud.pendingCount())
    }

    @Test
    fun invalidAcknowledgementsDoNotPartiallyApply() {
        data.events.append(event("one"))
        assertFailsWith<IllegalArgumentException> { data.cloud.acknowledge(setOf("one"), mapOf("one" to "forbidden")) }
        assertFailsWith<IllegalArgumentException> { data.cloud.acknowledge(setOf("one"), mapOf("two" to "")) }
        assertEquals(1, data.cloud.pendingCount())
    }

    @Test
    fun cursorsAreAccountScopedAndFullResyncCanResetThem() {
        data.cloud.saveCursor("alice", "pull", 42)
        data.cloud.saveCursor("alice", "visibilityEpoch", 8)
        data.cloud.saveCursor("bob", "pull", 3)
        assertEquals(0, data.cloud.cursor("bob", "visibilityEpoch"))
        data.cloud.saveCursor("alice", "pull", 0)
        assertEquals(0, data.cloud.cursor("alice", "pull"))
        assertEquals(8, data.cloud.cursor("alice", "visibilityEpoch"))
        data.cloud.clearCursors("alice")
        assertEquals(0, data.cloud.cursor("alice", "visibilityEpoch"))
        assertEquals(3, data.cloud.cursor("bob", "pull"))
        assertFailsWith<IllegalArgumentException> { data.cloud.saveCursor("bob", "pull", -1) }
        assertEquals(3, data.cloud.cursor("bob", "pull"))
    }

    @Test
    fun deletedMatchesLeaveNoPendingOrRejectedRows() {
        data.events.appendAll(listOf(event("one"), event("two", 2)))
        data.cloud.acknowledge(emptySet(), mapOf("two" to "forbidden"))
        data.deleteMatch(MatchId("match"))
        assertEquals(0, data.cloud.pendingCount())
        assertTrue(data.cloud.rejectedEvents().isEmpty())
        data.cloud.storeDownloaded(listOf(event("one"), event("two", 2), event("other", match = "other")))
        assertEquals(0, data.events.count("match"))
        assertEquals(1, data.events.count("other"))
    }
}
