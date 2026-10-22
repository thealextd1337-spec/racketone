package eu.advantly.match.session

import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.sync.protocol.Deleted
import eu.advantly.sync.protocol.DeletedMatch
import eu.advantly.sync.protocol.IdentityEnvelope
import eu.advantly.sync.protocol.Summary
import eu.advantly.sync.protocol.SyncCodec
import eu.advantly.sync.protocol.SyncMessage
import eu.advantly.sync.protocol.VersionVector
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ein gesperrtes Match verwirft nie den Rest einer Nachricht (Summary, Löschmarkierungen). */
class ReceiveIsolationTest {
    private val pair = LifecyclePair()

    @AfterTest
    fun tearDown() = pair.close()

    /** Zwei eigene Matches am Handy, das erste per Pull entzogen (FEED); Envelope aus einem echten Uhr-Paket. */
    private fun lockedAndOpen(): Triple<MatchId, MatchId, IdentityEnvelope> {
        pair.activate("alice")
        val locked = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        val open = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        val ctx = pair.phoneData.replicas.captureContext()
        pair.phoneData.replicas.applyAccess(ctx, locked.value, 1, ReplicaAccessLevel.FEED) {}
        val envelope =
            pair.watchLink
                .decoded()
                .last { it.identity != null }
                .identity!!
        return Triple(locked, open, envelope)
    }

    private fun fromWatch(
        message: SyncMessage,
        envelope: IdentityEnvelope,
    ) = pair.phoneLink.replay("watch", SyncCodec.encode(message, envelope))

    @Test
    fun aLockedMatchDoesNotBlockTheSummaryOfTheOthers() {
        val (locked, open, envelope) = lockedAndOpen()
        assertTrue(
            pair.phoneData.events
                .unconfirmedCountByMatch()
                .containsKey(open.value),
        )
        val vector = VersionVector.of(mapOf(pair.phone.deviceId to 1L))
        fromWatch(Summary(linkedMapOf(locked.value to vector, open.value to vector), reply = true), envelope)
        assertNull(pair.phoneData.events.unconfirmedCountByMatch()[open.value], "zweites Match bestätigt")
    }

    @Test
    fun aLockedMatchDoesNotBlockTheOtherTombstones() {
        val (locked, open, envelope) = lockedAndOpen()
        fromWatch(Deleted(listOf(DeletedMatch(locked, 1), DeletedMatch(open, 1))), envelope)
        assertTrue(pair.phoneData.matches.isDeleted(open))
        assertFalse(pair.phoneData.matches.isDeleted(locked))
    }
}
