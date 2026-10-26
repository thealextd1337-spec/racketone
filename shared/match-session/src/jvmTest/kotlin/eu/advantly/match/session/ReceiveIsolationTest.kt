package eu.advantly.match.session

import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Presets
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.scoreEvents
import eu.advantly.sync.protocol.Deleted
import eu.advantly.sync.protocol.DeletedMatch
import eu.advantly.sync.protocol.IdentityEnvelope
import eu.advantly.sync.protocol.LocalMatchStamp
import eu.advantly.sync.protocol.MatchDataStamp
import eu.advantly.sync.protocol.Summary
import eu.advantly.sync.protocol.SyncCodec
import eu.advantly.sync.protocol.SyncMessage
import eu.advantly.sync.protocol.VersionVector
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ein gesperrtes Match verwirft nie den Rest einer Nachricht (Summary, Löschmarkierungen). Unter Protokoll 4 fällt es schon
 * an der Belegprüfung je Eintrag heraus; unter 3 greift die Schreibsperre des Stores (Sperrpfad bleibt abgesichert).
 */
class ReceiveIsolationTest {
    private var pair: LifecyclePair? = null

    @AfterTest
    fun tearDown() {
        pair?.close()
    }

    private class Setup(
        val pair: LifecyclePair,
        val locked: MatchId,
        val open: MatchId,
        val envelope: IdentityEnvelope,
        /** Die Belege, die die Uhr für beide Matches schicken würde (Genesis des Handys im aktuellen Scope). */
        val stamps: Map<String, MatchDataStamp>,
    ) {
        fun fromWatch(message: SyncMessage) = pair.phoneLink.replay("watch", SyncCodec.encode(message, envelope))
    }

    /** Zwei eigene Matches am Handy, das erste per Pull entzogen (FEED); Envelope aus einem echten Uhr-Paket. */
    private fun lockedAndOpen(versions: IntRange): Setup {
        val pair = LifecyclePair(versions).also { this.pair = it }
        pair.activate("alice")
        val locked = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        val open = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        pair.settle()
        val stamps =
            listOf(locked, open).associate { match ->
                val genesis =
                    pair.phoneData.events
                        .scoreEvents(match)
                        .first { it.payload is MatchCreated }
                match.value to LocalMatchStamp(genesis.deviceId, genesis.id, pair.phoneData.accountIdentities.scope!!)
            }
        val ctx = pair.phoneData.replicas.captureContext()
        pair.phoneData.replicas.applyAccess(ctx, locked.value, 1, ReplicaAccessLevel.FEED) {}
        val envelope =
            pair.watchLink
                .decoded()
                .last { it.identity != null }
                .identity!!
        return Setup(pair, locked, open, envelope, stamps)
    }

    private fun summaryKeepsTheOthers(versions: IntRange) {
        val setup = lockedAndOpen(versions)
        val pair = setup.pair
        assertTrue(
            pair.phoneData.events
                .unconfirmedCountByMatch()
                .containsKey(setup.open.value),
        )
        val vector = VersionVector.of(mapOf(pair.phone.deviceId to 1L))
        val matches = linkedMapOf(setup.locked.value to vector, setup.open.value to vector)
        setup.fromWatch(
            if (versions.last >= 4) Summary(matches, reply = true, v = 4, stamps = setup.stamps) else Summary(matches, reply = true),
        )
        assertNull(pair.phoneData.events.unconfirmedCountByMatch()[setup.open.value], "zweites Match bestätigt")
    }

    private fun tombstonesKeepTheOthers(versions: IntRange) {
        val setup = lockedAndOpen(versions)
        val tombstones = listOf(DeletedMatch(setup.locked, 1), DeletedMatch(setup.open, 1))
        setup.fromWatch(if (versions.last >= 4) Deleted(tombstones, v = 4, stamps = setup.stamps) else Deleted(tombstones))
        assertTrue(
            setup.pair.phoneData.matches
                .isDeleted(setup.open),
        )
        assertFalse(
            setup.pair.phoneData.matches
                .isDeleted(setup.locked),
        )
    }

    @Test
    fun aLockedMatchDoesNotBlockTheSummaryOfTheOthers() = summaryKeepsTheOthers(3..4)

    @Test
    fun aLockedMatchDoesNotBlockTheOtherTombstones() = tombstonesKeepTheOthers(3..4)

    @Test
    fun underProtocolThreeTheStoreLockDoesNotBlockTheSummaryOfTheOthers() = summaryKeepsTheOthers(3..3)

    @Test
    fun underProtocolThreeTheStoreLockDoesNotBlockTheOtherTombstones() = tombstonesKeepTheOthers(3..3)
}
