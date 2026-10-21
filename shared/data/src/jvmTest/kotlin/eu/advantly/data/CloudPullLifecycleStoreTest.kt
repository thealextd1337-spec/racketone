package eu.advantly.data

import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.PlayerAssigned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudPullLifecycleStoreTest {
    private fun match(
        id: String,
        redactionEpoch: Long = 0,
    ) = CloudPullMatch(id, 1, """{"version":1}""", "member", 1, redactionEpoch, false, pullRecord(id))

    private fun page(
        matches: List<CloudPullMatch>,
        cursor: Long,
        epoch: Long,
        hasMore: Boolean = false,
    ) = CloudPullMetadataPage(matches, emptyList(), emptyList(), cursor, hasMore, epoch)

    private fun redacted(seq: Long) =
        ReplicaRedactionEvent(
            StoredEvent(
                "redacted-$seq",
                "match",
                "remote",
                seq,
                seq,
                DataJson.encodeToString(
                    EventPayload.serializer(),
                    PlayerAssigned(PlayerId("slot-a"), Player(PlayerId("player-$seq"), "Gelöschter Spieler"), PlayerId("slot-b")),
                ),
                type = "PlayerAssigned",
            ),
            seq,
        )

    @Test
    fun absentOnlyAfterCompleteCatalogOfTheSameEpoch() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            assertTrue(data.cloudPulls.commitMetadata("alice", run, 0, page(listOf(match("a"), match("b")), 1, 1)) {})
            val ctx = data.replicas.captureContext()
            assertTrue(!data.cloudPulls.commitMetadata("alice", run, 1, page(emptyList(), 1, 2)) {})
            assertTrue(data.cloudPulls.commitMetadata("alice", run, 0, page(listOf(match("a")), 1, 2, hasMore = true)) {})
            // Unvollständiger Katalog bei G = 2: "b" fehlt bisher nur auf dieser Seite.
            assertEquals(0, data.cloudPulls.applyMissing("alice", run) {})
            assertEquals(ReplicaAccessLevel.MEMBER, data.replicas.access(ctx, "b")!!.access)
            assertEquals(1, data.replicas.access(ctx, "b")!!.generation)
            assertTrue(data.cloudPulls.commitMetadata("alice", run, 1, page(emptyList(), 2, 2)) {})
            assertEquals(1, data.cloudPulls.applyMissing("alice", run) {})
            val absent = data.replicas.access(ctx, "b")!!
            assertEquals(ReplicaAccessLevel.ABSENT, absent.access)
            assertEquals(2, absent.generation)
            assertNull(data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, "b"))
            assertEquals(0, data.cloudPulls.pendingRemovals("alice"))
            assertEquals(0, data.cloudPulls.applyMissing("alice", run) {})
            assertEquals(2, data.replicas.access(ctx, "a")!!.generation)
        }
    }

    @Test
    fun abortedCloudRedactionPageRollsBackTransferStartAllDataPagesAndCursor() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata("alice", run, 0, page(listOf(match("match", redactionEpoch = 1)), 1, 1)) {}
            val ctx = data.replicas.captureContext()
            val work = data.cloudPulls.eventWork("alice", "match")!!
            val target = assertNotNull(data.cloudPulls.redactionWork(work, run))
            assertEquals(CloudPullRedactionWork("match", 1, 1, null, RedactionCursor(null, 0)), target)
            val ids = (100..299).map { "player-$it" }
            val events = (1L..500L).map(::redacted)
            var armed = true
            // Seitenabbruch, sobald die erste Datenseite der Cloudseite gestaged ist.
            val fence = { if (armed) check((data.replicas.redactionProgress(ctx, "match")?.nextPageIndex ?: 0) < 1) }
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.stageRedaction(work, run, target, 500, ids, events, idsDone = true, eventsDone = true, fence = fence)
            }
            assertNull(data.replicas.redactionProgress(ctx, "match"))
            assertEquals(target, data.cloudPulls.redactionWork(work, run))
            armed = false
            assertTrue(
                data.cloudPulls.stageRedaction(work, run, target, 500, ids, events, idsDone = true, eventsDone = true, fence = fence),
            )
            val progress = data.replicas.redactionProgress(ctx, "match")!!
            assertTrue(progress.completed && progress.nextPageIndex > 1)
            assertEquals(RedactionCursor("player-299", 500), progress.cursor)
            val pages = (0 until progress.nextPageIndex).map { data.replicas.exportRedactionPage(ctx, progress.transfer.id, it)!! }
            assertTrue(pages.all { DataJson.encodeToString(RedactionPage.serializer(), it).encodeToByteArray().size <= 64 * 1024 })
            assertEquals(ids, pages.flatMap { it.playerIds })
            assertEquals(events.map { it.serverSeq }, pages.flatMap { page -> page.events.map { it.serverSeq } })
            assertEquals(1, data.replicas.access(ctx, "match")!!.redactionEpoch)
            assertEquals(0, data.cloudPulls.pendingRedactions("alice"))
            assertNull(data.cloudPulls.redactionWork(work, run))
        }
    }

    @Test
    fun staleRedactionRequestCannotStageAfterEpochChange() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata("alice", run, 0, page(listOf(match("match", redactionEpoch = 1)), 1, 1)) {}
            val work = data.cloudPulls.eventWork("alice", "match")!!
            val stale = data.cloudPulls.redactionWork(work, run)!!
            data.cloudPulls.commitMetadata(
                "alice",
                run,
                1,
                page(listOf(match("match", redactionEpoch = 2).copy(serverVersion = 2)), 2, 1),
            ) {}
            val current = data.cloudPulls.eventWork("alice", "match")!!
            assertFailsWith<IllegalStateException> {
                data.cloudPulls.stageRedaction(current, run, stale, 1, listOf("player-1"), emptyList(), true, true) {}
            }
            assertNull(data.replicas.redactionProgress(data.replicas.captureContext(), "match"))
            assertEquals(2, data.cloudPulls.redactionWork(current, run)!!.redactionEpoch)
        }
    }

    @Test
    fun selectionChangeInvalidatesRunContextEvenForAToBToA() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver).selectPullAccount()
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            data.selectPullAccount("bob").selectPullAccount("alice")
            assertFailsWith<IllegalStateException> { data.cloudPulls.commitMetadata("alice", run, 0, page(listOf(match("a")), 1, 1)) {} }
            assertEquals(0, data.cloudPulls.progress("alice").cursor)
            assertNull(data.replicas.access(data.replicas.captureContext(), "a"))
            // Neuer Lauf = neue Autorisierung des Kontexts; ein fremdes Konto bekommt keinen Lauf.
            val fresh = data.cloudPulls.beginRun("alice") {}.runSerial
            assertTrue(data.cloudPulls.commitMetadata("alice", fresh, 0, page(listOf(match("a")), 1, 1)) {})
            assertFailsWith<IllegalStateException> { data.cloudPulls.beginRun("bob") {} }
        }
    }
}
