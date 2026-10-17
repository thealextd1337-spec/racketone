package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.IdentityEnvelope
import eu.advantly.sync.protocol.OwnerContext
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountPairingTest {
    private var next = 0

    private fun id(): String = "nonce-${++next}"

    @Test
    fun persistedEpochAndCurrentChallengeRejectStaleOffersAndAuthorityReplacement() {
        val data = testLocalData()
        val watch = AccountPairing(data, PeerKind.WATCH, ::id)
        var selected: String? = null
        val context = OwnerContext("authority", 4, "alice")

        fun offer(
            owner: OwnerContext,
            recipient: String? = watch.nonce,
        ) = Hello(
            DeviceId("phone"),
            PeerKind.PHONE,
            session = "phone-session",
            identityProtocol = 1,
            ownerContext = owner,
            recipientSession = recipient,
        )
        assertFalse(watch.observe("phone", offer(context, "old-nonce"), true) { selected = it })
        assertNull(selected)
        assertFalse(watch.observe("phone", offer(context), false) { selected = it })
        assertTrue(watch.observe("phone", offer(context), true) { selected = it })
        assertEquals("alice", selected)
        val restarted = AccountPairing(data, PeerKind.WATCH, ::id)
        assertEquals(context, restarted.context)
        assertFalse(restarted.observe("phone", offer(context.copy(epoch = 3), restarted.nonce), true) { error("stale") })
        assertFalse(restarted.observe("phone", offer(context.copy(scope = "bob"), restarted.nonce), true) { error("conflict") })
        assertFalse(restarted.observe("phone", offer(context.copy(authority = "other"), restarted.nonce), true) { error("replacement") })
    }

    @Test
    fun envelopeRequiresBothSessionChallengesAndExactScopeAndEpoch() {
        val data = testLocalData()
        val phone = AccountPairing(data, PeerKind.PHONE, ::id)
        phone.rotate("alice")
        val hello =
            Hello(
                DeviceId("watch"),
                PeerKind.WATCH,
                session = "watch-session",
                identityProtocol = 1,
                ownerContext = phone.context,
                recipientSession = phone.nonce,
            )
        assertTrue(phone.observe("watch", hello, true) { error("phone cannot follow") })
        val incoming = IdentityEnvelope(phone.context!!, "watch-session", phone.nonce)
        assertTrue(phone.accepts("watch", incoming))
        assertFalse(phone.accepts("watch", incoming.copy(senderSession = "old")))
        assertFalse(phone.accepts("watch", incoming.copy(recipientSession = "old")))
        assertFalse(phone.accepts("watch", incoming.copy(context = incoming.context.copy(scope = "bob"))))
        assertFalse(phone.accepts("watch", null))
        phone.rotate("bob")
        assertFalse(phone.accepts("watch", incoming))
        assertNull(phone.envelope("watch"))
    }

    @Test
    fun failedStoredTransitionRollsBackIdentityContextEpochAndMemory() {
        val data = testLocalData()
        val pairing = AccountPairing(data, PeerKind.PHONE, ::id)
        data.accountIdentities.initialize("legacy-device", "legacy-self", "PHONE")
        pairing.rotate("alice")
        val original = pairing.context
        val nonce = pairing.nonce
        assertFailsWith<IllegalStateException> {
            data.transaction {
                data.accountIdentities.select("bob", "bob", 1, "PHONE", ::id)
                pairing.rotate("bob")
                error("conflict after identity selection")
            }
        }
        assertEquals("legacy:legacy-device", data.accountIdentities.scope)
        assertFalse(data.accountIdentities.managed)
        assertEquals(original, pairing.context)
        assertEquals(nonce, pairing.nonce)
        assertEquals(original, AccountPairing(data, PeerKind.PHONE, ::id).context)
    }
}
