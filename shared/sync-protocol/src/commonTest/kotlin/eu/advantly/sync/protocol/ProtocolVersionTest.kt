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
        assertEquals(CompatibilityStatus.PEER_TOO_NEW, ProtocolVersion.negotiate(5, 5).status)
        assertEquals(CompatibilityStatus.PEER_TOO_OLD, ProtocolVersion.negotiate(1, 1, localVersion = 3, localMinSupported = 2).status)
        assertFalse(ProtocolVersion.negotiate(5, 5).isCompatible)
    }

    /** W2 (Protokoll 2) ↔ W3-M (Protokoll 3, STOPPED + Uhr-Zuweisung): auf beiden Seiten sichtbar inkompatibel. */
    @Test
    fun version2And3AreIncompatibleInBothDirections() {
        // neues Gerät sieht ein altes (W2 bzw. W1)
        assertEquals(Compatibility(CompatibilityStatus.PEER_TOO_OLD, null), ProtocolVersion.negotiate(2, 2))
        assertEquals(Compatibility(CompatibilityStatus.PEER_TOO_OLD, null), ProtocolVersion.negotiate(1, 1))
        // altes Gerät (CURRENT = MIN_SUPPORTED = 2) sieht ein neues
        assertEquals(
            Compatibility(CompatibilityStatus.PEER_TOO_NEW, null),
            ProtocolVersion.negotiate(ProtocolVersion.CURRENT, ProtocolVersion.MIN_SUPPORTED, localVersion = 2, localMinSupported = 2),
        )
        assertEquals(Compatibility(CompatibilityStatus.COMPATIBLE, 3), ProtocolVersion.negotiate(3, 3))
        assertFalse(ProtocolVersion.accepts(2))
        assertFalse(ProtocolVersion.accepts(1))
    }

    /**
     * Protokoll 4 (Welle 3): liest 3 weiter (ohne Konto bleibt eine v3-Gegenstelle kompatibel). Mit Konto meldet ein Gerät
     * `minV = 4` (Session), dann ist 3↔4 auf beiden Seiten ein Updatezustand.
     */
    @Test
    fun version4StillReadsThreeAndMinimumFourIsAnUpdateState() {
        assertEquals(4, ProtocolVersion.CURRENT)
        assertEquals(3, ProtocolVersion.MIN_SUPPORTED)
        assertTrue(ProtocolVersion.accepts(3) && ProtocolVersion.accepts(4))
        assertEquals(Compatibility(CompatibilityStatus.COMPATIBLE, 3), ProtocolVersion.negotiate(3, 3))
        assertEquals(Compatibility(CompatibilityStatus.COMPATIBLE, 4), ProtocolVersion.negotiate(4, 4))
        // mit Konto (4..4) gegen ein Gerät, das nur 3 spricht
        assertEquals(Compatibility(CompatibilityStatus.PEER_TOO_OLD, null), ProtocolVersion.negotiate(3, 3, 4, 4))
        assertEquals(Compatibility(CompatibilityStatus.PEER_TOO_NEW, null), ProtocolVersion.negotiate(4, 4, 3, 3))
    }

    @Test
    fun acceptsOnlySupportedVersions() {
        assertTrue(ProtocolVersion.accepts(ProtocolVersion.CURRENT))
        assertFalse(ProtocolVersion.accepts(ProtocolVersion.CURRENT + 1))
        assertFalse(ProtocolVersion.accepts(0))
    }
}
