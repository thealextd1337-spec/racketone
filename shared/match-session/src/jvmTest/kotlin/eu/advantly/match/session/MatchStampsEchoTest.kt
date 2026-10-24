package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.sync.protocol.DeviceSeq
import eu.advantly.sync.protocol.LocalMatchStamp
import eu.advantly.sync.protocol.Summary
import eu.advantly.sync.protocol.VersionVector
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Review A3: Der zurückgeschickte Beleg („mir fehlt alles“) gilt nur für die Gegenstelle, deren Summary ihn trug. */
class MatchStampsEchoTest {
    private val pair = LifecyclePair()

    @AfterTest
    fun tearDown() = pair.close()

    @Test
    fun anEchoedStampGoesBackOnlyToItsOwnPeer() {
        pair.activate("alice")
        val data = pair.phoneData
        val source = MatchSessions.deviceId(data).value
        val stamp = LocalMatchStamp(DeviceId(source), EventId("genesis"), data.accountIdentities.scope!!)
        val key = "missing-match"
        val vector = VersionVector.fromSequences(listOf(DeviceSeq(DeviceId(source), 3)))
        val stamps = MatchStamps(data) { null }

        val accepted = stamps.verified("watch-a", Summary(mapOf(key to vector), v = 4, stamps = mapOf(key to stamp))) as Summary
        assertEquals(setOf(key), accepted.matches.keys, "Beleg einer eigenen Quelle angenommen")

        val reply = Summary(mapOf(key to VersionVector.EMPTY), reply = true)
        val toA = stamps.stamped("watch-a", reply, { emptyMap() }, {}) as Summary
        val toB = stamps.stamped("watch-b", reply, { emptyMap() }, {}) as Summary
        assertEquals(mapOf(key to stamp), toA.stamps, "dieselbe Gegenstelle bekommt ihren Beleg zurück")
        assertTrue(toB.matches.isEmpty() && toB.stamps.isEmpty(), "eine andere nie")
    }
}
