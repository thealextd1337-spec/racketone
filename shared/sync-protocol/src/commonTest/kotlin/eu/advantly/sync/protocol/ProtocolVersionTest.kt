package eu.advantly.sync.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolVersionTest {
    @Test
    fun overlappingRangesAreCompatible() {
        assertEquals(
            Compatibility(CompatibilityStatus.COMPATIBLE, 1),
            ProtocolVersion.negotiate(1, 1, localVersion = 1, localMinSupported = 1),
        )
        // Gegenstelle spricht 3, liest aber noch 1 → es wird weiter in 1 geschrieben
        assertEquals(
            Compatibility(CompatibilityStatus.COMPATIBLE, 1),
            ProtocolVersion.negotiate(3, 1, localVersion = 1, localMinSupported = 1),
        )
        assertEquals(2, ProtocolVersion.negotiate(2, 1, localVersion = 3, localMinSupported = 2).version)
    }

    @Test
    fun disjointRangesAreIncompatible() {
        assertEquals(CompatibilityStatus.PEER_TOO_NEW, ProtocolVersion.negotiate(4, 4).status)
        assertEquals(CompatibilityStatus.PEER_TOO_OLD, ProtocolVersion.negotiate(1, 1, localVersion = 3, localMinSupported = 2).status)
        assertFalse(ProtocolVersion.negotiate(4, 4).isCompatible)
    }

    /** W1 (Protokoll 1) ↔ W2 (Protokoll 2): auf beiden Seiten sichtbar inkompatibel, nie halb-synchron. */
    @Test
    fun version2And3AreIncompatibleInBothDirections() {
        assertEquals(3, ProtocolVersion.CURRENT)
        assertEquals(3, ProtocolVersion.MIN_SUPPORTED)
        // neues Gerät sieht ein altes
        assertEquals(Compatibility(CompatibilityStatus.PEER_TOO_OLD, null), ProtocolVersion.negotiate(1, 1))
        // altes Gerät (CURRENT = MIN_SUPPORTED = 1) sieht ein neues
        assertEquals(
            Compatibility(CompatibilityStatus.PEER_TOO_NEW, null),
            ProtocolVersion.negotiate(ProtocolVersion.CURRENT, ProtocolVersion.MIN_SUPPORTED, localVersion = 1, localMinSupported = 1),
        )
        assertEquals(Compatibility(CompatibilityStatus.COMPATIBLE, 3), ProtocolVersion.negotiate(3, 3))
        assertFalse(ProtocolVersion.accepts(1))
    }

    @Test
    fun acceptsOnlySupportedVersions() {
        assertTrue(ProtocolVersion.accepts(ProtocolVersion.CURRENT))
        assertFalse(ProtocolVersion.accepts(ProtocolVersion.CURRENT + 1))
        assertFalse(ProtocolVersion.accepts(0))
    }
}
