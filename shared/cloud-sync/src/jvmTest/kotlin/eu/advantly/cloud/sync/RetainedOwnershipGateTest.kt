package eu.advantly.cloud.sync

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.SessionConfig
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetainedOwnershipGateTest {
    private class Phone {
        val data = LocalData(createInMemoryJvmDriver())
        var sdk: CloudCredentials? = CloudCredentials("alice", "session-a", "token")
        var phone = newPhone()
        var gate = newGate()
        val current = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT)

        init {
            data.players.ensureSelf("Alex", 0) { PlayerId("self") }
            phone.start()
            gate.publish(gate.captureProfile(), current)
        }

        private fun newPhone() = MatchSession(data, null, ManualScheduler(), SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE))

        private fun newGate() = CloudSessionGate({ sdk }, data.cloudUploads, ownership = phone)

        fun restart() {
            gate.stop(leaveIdentity = false)
            phone.close()
            phone = newPhone()
            phone.start()
            gate = newGate()
        }

        fun create() =
            phone
                .create(
                    Presets.TENNIS_SINGLES_BO3.format(),
                    Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
                ).also { assertTrue(phone.recordPoint(Side.A)) }

        fun assertGuestBoundary(transition: () -> Unit) {
            val priorDevice = phone.deviceId
            val priorScope = data.accountIdentities.scope
            val match = create()
            val historical = data.events.eventsFor(match.value)
            transition()
            assertNull(data.accountIdentities.profile)
            assertNotEquals(priorDevice, phone.deviceId)
            assertNotEquals(priorScope, data.accountIdentities.scope)
            val fresh = create()
            assertTrue(data.events.eventsFor(fresh.value).all { it.deviceId == phone.deviceId.value })
            assertEquals(historical, data.events.eventsFor(match.value))
            assertEquals(priorScope, data.accountIdentities.sourceScope(priorDevice.value))
            assertFailsWith<CloudException> { gate.capture() }
            val captured = data.cloudUploads.capture(data.cloudUploads.activate("alice"), 1000)
            assertTrue(captured.events.none { it.matchId == fresh.value })
            data.cloudUploads.deactivate()
        }
    }

    @Test fun deletionAfterSuspensionLeavesRetainedIdentity() {
        val fixture = Phone()
        try {
            fixture.gate.suspendCloud()
            assertEquals("alice", fixture.data.accountIdentities.profile)
            fixture.assertGuestBoundary { fixture.gate.suspendCloud(leaveIdentity = true) }
        } finally {
            fixture.phone.close()
        }
    }

    @Test fun sdkAccountSwitchAfterSuspensionLeavesRetainedIdentity() {
        val fixture = Phone()
        try {
            fixture.gate.suspendCloud()
            fixture.assertGuestBoundary {
                fixture.sdk = CloudCredentials("bob", "session-b", "token-b")
                fixture.gate.synchronize()
            }
        } finally {
            fixture.phone.close()
        }
    }

    @Test fun sdkLogoutAfterSuspensionLeavesRetainedIdentity() {
        val fixture = Phone()
        try {
            fixture.gate.suspendCloud()
            fixture.assertGuestBoundary {
                fixture.sdk = null
                fixture.gate.synchronize()
            }
        } finally {
            fixture.phone.close()
        }
    }

    @Test fun verifiedDeletionOnNewHostLeavesPersistedIdentity() {
        val fixture = Phone()
        try {
            fixture.restart()
            fixture.assertGuestBoundary {
                fixture.gate.publish(fixture.gate.captureProfile(), fixture.current.copy(onboarded = false, deletionRequested = true))
            }
        } finally {
            fixture.phone.close()
        }
    }

    @Test fun newHostWithDifferentSdkAccountLeavesPersistedIdentityBeforeVerification() {
        val fixture = Phone()
        try {
            fixture.restart()
            fixture.assertGuestBoundary {
                fixture.sdk = CloudCredentials("bob", "session-b", "token-b")
                fixture.gate.synchronize()
            }
        } finally {
            fixture.phone.close()
        }
    }

    @Test fun sameAccountRestoreHostCloseAndSuspensionKeepLocalIdentityWithoutUploadPermission() {
        val fixture = Phone()
        try {
            val prior = fixture.phone.deviceId
            val match = fixture.create()
            val historical = fixture.data.events.eventsFor(match.value)
            fixture.gate.suspendCloud()
            val fresh = fixture.create()
            assertEquals(prior, fixture.phone.deviceId)
            assertFailsWith<CloudException> { fixture.gate.capture() }
            fixture.restart()
            fixture.gate.synchronize()
            assertEquals(prior, fixture.phone.deviceId)
            assertFailsWith<CloudException> { fixture.gate.capture() }
            fixture.sdk = CloudCredentials("alice", "session-new", "token-new")
            fixture.gate.publish(fixture.gate.captureProfile(), fixture.current)
            assertEquals(prior, fixture.phone.deviceId)
            assertEquals("alice", fixture.data.accountIdentities.profile)
            assertEquals(historical, fixture.data.events.eventsFor(match.value))
            assertTrue(
                fixture.data.events
                    .eventsFor(fresh.value)
                    .all { it.deviceId == prior.value },
            )
        } finally {
            fixture.phone.close()
        }
    }
}
