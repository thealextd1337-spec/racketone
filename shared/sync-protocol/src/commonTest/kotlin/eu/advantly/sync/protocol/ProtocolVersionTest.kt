package eu.advantly.sync.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolVersionTest {
    @Test
    fun overlappingRangesAreCompatible() {
        assertEquals(Compatibility(CompatibilityStatus.COMPATIBLE, 1), ProtocolVersion.negotiate(1, 1))
        // Gegenstelle spricht 3, liest aber noch 1 → es wird weiter in 1 geschrieben
        assertEquals(Compatibility(CompatibilityStatus.COMPATIBLE, 1), ProtocolVersion.negotiate(3, 1))
        assertEquals(2, ProtocolVersion.negotiate(2, 1, localVersion = 3, localMinSupported = 2).version)
    }

    @Test
    fun disjointRangesAreIncompatible() {
        assertEquals(CompatibilityStatus.PEER_TOO_NEW, ProtocolVersion.negotiate(3, 2).status)
        assertEquals(CompatibilityStatus.PEER_TOO_OLD, ProtocolVersion.negotiate(1, 1, localVersion = 3, localMinSupported = 2).status)
        assertFalse(ProtocolVersion.negotiate(3, 2).isCompatible)
    }

    @Test
    fun acceptsOnlySupportedVersions() {
        assertTrue(ProtocolVersion.accepts(ProtocolVersion.CURRENT))
        assertFalse(ProtocolVersion.accepts(ProtocolVersion.CURRENT + 1))
        assertFalse(ProtocolVersion.accepts(0))
    }
}
