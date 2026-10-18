package eu.advantly.presentation.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PhoneExportPortTest {
    @Test fun initialStateExposesNoAccountArtifactOrAction() {
        val state = PhoneExportState()
        assertEquals(PhoneExportPhase.UNAVAILABLE, state.phase)
        assertEquals(emptySet(), state.allowedActions)
        assertFalse(state.busy)
        assertNull(state.error)
        assertNull(state.exportedAtMs)
        assertNull(state.expiresAtMs)
        assertNull(state.byteLength)
        assertNull(state.retryAtMs)
    }

    @Test fun onlyLocalRequestStatusAndDownloadWorkIsBusy() {
        val busyPhases = setOf(PhoneExportPhase.REQUESTING, PhoneExportPhase.CHECKING, PhoneExportPhase.DOWNLOADING)
        for (phase in PhoneExportPhase.entries) {
            assertEquals(phase in busyPhases, PhoneExportState(phase = phase).busy, phase.name)
        }
        assertFalse(PhoneExportState(phase = PhoneExportPhase.PENDING).busy)
        assertFalse(PhoneExportState(phase = PhoneExportPhase.SERVER_BUSY).busy)
    }
}
