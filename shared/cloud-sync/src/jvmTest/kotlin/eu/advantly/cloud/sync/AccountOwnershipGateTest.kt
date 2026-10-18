package eu.advantly.cloud.sync

import eu.advantly.data.AdoptionSource
import eu.advantly.data.CloudUploadSession
import eu.advantly.data.LocalAccountOwnership
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountOwnershipGateTest {
    private class Owner : LocalAccountOwnership {
        var active: String? = "persisted-alice"
        var activations = 0
        var leaves = 0
        var detaches = 0

        override fun detachAccountSession() {
            detaches++
        }

        var failActivation = false
        var failLeave = false

        override fun leaveAccount() {
            leaves++
            if (failLeave) error("database unavailable")
            active = null
        }

        override fun activateAccount(
            profileId: String,
            session: CloudUploadSession,
        ) {
            if (failActivation) error("ownership conflict")
            activations++
            active = profileId
        }

        override fun adoptionSources(): List<AdoptionSource> = emptyList()

        override fun adoptSources(
            session: CloudUploadSession,
            deviceIds: Set<String>,
        ) = Unit
    }

    @Test
    fun unknownRestoreDoesNotRotateButVerifiedAbsenceAndLogoutDo() {
        val data = LocalData(createInMemoryJvmDriver())
        val owner = Owner()
        var sdk: CloudCredentials? = null
        val gate = CloudSessionGate({ sdk }, data.cloudUploads, ownership = owner)
        gate.synchronize()
        assertEquals("persisted-alice", owner.active)
        gate.verifiedAbsent()
        assertNull(owner.active)
        sdk = CloudCredentials("alice", "session", "token")
        val lease = gate.capture(false)
        gate.publish(lease, CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
        assertEquals("alice", owner.active)
        assertEquals("alice", gate.session.value?.profileId)
        sdk = CloudCredentials("alice", "session", "refreshed-token")
        gate.synchronize()
        gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
        assertEquals(1, owner.activations)
        val beforeReplacement = owner.detaches
        sdk = CloudCredentials("alice", "replacement-session", "new-token")
        gate.synchronize()
        assertEquals(beforeReplacement + 1, owner.detaches)
        assertEquals("alice", owner.active)
        gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
        val beforeClose = owner.detaches
        gate.stop(leaveIdentity = false)
        assertEquals(beforeClose + 1, owner.detaches)
        assertEquals("alice", owner.active)
        gate.stop()
        assertNull(owner.active)
    }

    @Test
    fun ownershipFailureCannotPublishOrKeepUploadAuthorization() {
        val data = LocalData(createInMemoryJvmDriver())
        val owner = Owner().also { it.failActivation = true }
        val gate = CloudSessionGate({ CloudCredentials("alice", "session", "token") }, data.cloudUploads, ownership = owner)
        val lease = gate.capture(false)
        assertFailsWith<IllegalStateException> { gate.publish(lease, CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT)) }
        assertNull(gate.session.value)
        assertFailsWith<CloudException> { gate.requireCurrent(lease) }
    }

    @Test
    fun callbackFailureStillRevokesPublishedAndCapturedSessionsBeforeLogoutOrDowngrade() {
        val data = LocalData(createInMemoryJvmDriver())
        val owner = Owner()
        val gate = CloudSessionGate({ CloudCredentials("alice", "session", "token") }, data.cloudUploads, ownership = owner)
        gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
        val lease = gate.capture()
        val captured = gate.captureUpload(lease, 10)
        owner.failLeave = true
        assertFailsWith<IllegalStateException> { gate.publish(lease, CloudSession("alice", false)) }
        assertNull(gate.session.value)
        assertFailsWith<IllegalStateException> { data.cloudUploads.requireCurrent(captured) }
        assertFailsWith<CloudException> { gate.requireCurrent(lease) }
        assertTrue(owner.leaves > 0)
    }
}
