package eu.advantly.cloud.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloudProfileBoundaryTest {
    private val current = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT)

    @Test fun suspensionInvalidatesCloudLeasesPermanentlyAndOnlyFreshPermittedProfileCanResume() {
        val gate = CloudSessionGate({ CloudCredentials("alice", "session-a", "token") })
        gate.publish(gate.captureProfile(), current)
        val cloudLease = gate.capture()
        val oldProfile = gate.captureProfile()
        gate.suspendCloud()
        assertFailsWith<CloudException> { gate.publish(oldProfile, current) }
        gate.publish(gate.captureProfile(canResumeCloud = false), current)
        assertFailsWith<CloudException> { gate.capture() }
        gate.publish(gate.captureProfile(), current)
        assertEquals("alice", gate.capture().credentials.profileId)
        assertFailsWith<CloudException> { gate.requireCurrent(cloudLease) }
    }

    @Test fun laterDeletionOrConsentProfileCannotBeOverwrittenByOlderCurrentResponse() {
        val gate = CloudSessionGate({ CloudCredentials("alice", "session-a", "token") })
        gate.suspendCloud()
        val older = gate.captureProfile()
        val newer = gate.captureProfile()
        gate.publish(newer, current.copy(consentStatus = ConsentStatus.DECLINED))
        assertFailsWith<CloudException> { gate.publish(older, current) }
        assertFailsWith<CloudException> { gate.capture() }
        val deletion = current.copy(deletionRequested = true)
        gate.publish(gate.captureProfile(), deletion)
        gate.publish(gate.captureProfile(), deletion)
        assertFailsWith<CloudException> { gate.capture() }
        gate.publish(gate.captureProfile(), current)
        assertEquals(false, gate.session.value?.deletionRequested)
    }

    @Test fun profilePathNeverBypassesLogoutOrAccountAndSessionChanges() {
        var sdk = CloudCredentials("alice", "session-a", "token")
        val gate = CloudSessionGate({ sdk })
        val old = gate.captureProfile()
        gate.stop()
        assertFailsWith<CloudException> { gate.captureProfile() }
        assertFailsWith<CloudException> { gate.publish(old, current) }
        val login = gate.stop()
        sdk = CloudCredentials("bob", "session-b", "token-b")
        gate.completeTransition(login)
        val bob = gate.captureProfile()
        sdk = CloudCredentials("bob", "session-new", "new-token")
        assertFailsWith<CloudException> { gate.publish(bob, current.copy(profileId = "bob")) }
    }
}
