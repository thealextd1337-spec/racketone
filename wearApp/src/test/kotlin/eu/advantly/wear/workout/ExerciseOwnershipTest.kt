package eu.advantly.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseOwnershipTest {
    @Test
    fun mapsHealthServicesTrackedStatus() {
        assertEquals(TrackedStatus.OTHER_APP, TrackedStatus.fromHealthServices(1))
        assertEquals(TrackedStatus.OWNED, TrackedStatus.fromHealthServices(2))
        assertEquals(TrackedStatus.NONE, TrackedStatus.fromHealthServices(3))
        assertEquals("UNKNOWN (0) ist ein Fehler, keine leere Uhr", TrackedStatus.FAILED, TrackedStatus.fromHealthServices(0))
        assertEquals(TrackedStatus.FAILED, TrackedStatus.fromHealthServices(null))
    }

    @Test
    fun recoveryDistinguishesOwnForeignNoneAndError() {
        assertEquals(RecoveryAction.REATTACH, ExerciseOwnership.onRecover(TrackedStatus.OWNED))
        assertEquals(RecoveryAction.RESTART, ExerciseOwnership.onRecover(TrackedStatus.NONE))
        assertEquals(
            "fremdes Training nie stillschweigend ersetzen",
            RecoveryAction.ASK_USER,
            ExerciseOwnership.onRecover(TrackedStatus.OTHER_APP),
        )
        assertEquals("Abfragefehler ist kein Neustart", RecoveryAction.RETRY_OR_FAIL, ExerciseOwnership.onRecover(TrackedStatus.FAILED))
    }

    @Test
    fun startReplacesForeignExerciseOnlyAfterConfirmation() {
        assertEquals(StartAction.ASK_USER, ExerciseOwnership.onStart(TrackedStatus.OTHER_APP, userConfirmedReplace = false))
        assertEquals(StartAction.START, ExerciseOwnership.onStart(TrackedStatus.OTHER_APP, userConfirmedReplace = true))
        assertEquals(StartAction.START, ExerciseOwnership.onStart(TrackedStatus.NONE, userConfirmedReplace = false))
        assertEquals(StartAction.START, ExerciseOwnership.onStart(TrackedStatus.OWNED, userConfirmedReplace = false))
        assertEquals(StartAction.RETRY_OR_FAIL, ExerciseOwnership.onStart(TrackedStatus.FAILED, userConfirmedReplace = true))
    }

    @Test
    fun endIsConfirmedOnlyWhenNoOwnExerciseRuns() {
        assertTrue(ExerciseOwnership.isEndConfirmed(TrackedStatus.NONE))
        assertTrue("fremdes Training bleibt, unseres ist weg", ExerciseOwnership.isEndConfirmed(TrackedStatus.OTHER_APP))
        assertFalse(ExerciseOwnership.isEndConfirmed(TrackedStatus.OWNED))
        assertFalse("unklarer Status gilt nicht als beendet", ExerciseOwnership.isEndConfirmed(TrackedStatus.FAILED))
    }

    @Test
    fun foreignExerciseIsNeverEnded() {
        assertFalse(ExerciseOwnership.needsEnd(TrackedStatus.OTHER_APP))
        assertFalse(ExerciseOwnership.needsEnd(TrackedStatus.NONE))
        assertTrue(ExerciseOwnership.needsEnd(TrackedStatus.OWNED))
        assertTrue(ExerciseOwnership.needsEnd(TrackedStatus.FAILED))
    }
}
