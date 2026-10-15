package eu.advantly.wear.workout

/** Ergebnis von `getCurrentExerciseInfo()`, plattformunabhängig (Abfragefehler eingeschlossen). */
enum class TrackedStatus {
    /** Unsere App besitzt die laufende Exercise. */
    OWNED,

    /** Eine andere App (z. B. Fitbit, Samsung Health) zeichnet gerade ein Training auf. */
    OTHER_APP,

    /** Keine Exercise aktiv. */
    NONE,

    /** Abfrage fehlgeschlagen oder Status `UNKNOWN`. */
    FAILED,
    ;

    companion object {
        /** Abbildung von `ExerciseTrackedStatus` (health-services-client 1.1.0: 1 = andere App, 2 = eigene, 3 = keine). */
        fun fromHealthServices(value: Int?): TrackedStatus =
            when (value) {
                1 -> OTHER_APP
                2 -> OWNED
                3 -> NONE
                else -> FAILED
            }
    }
}

/** Was nach einem Prozess-/Service-Neustart mit einer als aktiv markierten Session passiert. */
enum class RecoveryAction { REATTACH, RESTART, ASK_USER, RETRY_OR_FAIL }

/** Ergebnis beim Wiederanbinden an eine eigene, weiterlaufende Exercise. */
sealed interface ReattachResult {
    /** Weiterzählen; [selection] ist `null`, wenn das Gerät keinen passenden Typ kennt (wie beim Start). */
    data class Active(
        val selection: ExerciseSelection?,
    ) : ReattachResult

    /** Capabilities nach allen Wiederholungen nicht abrufbar → Fehlerzustand mit „Erneut versuchen“. */
    data object CapabilitiesUnavailable : ReattachResult
}

/** Was beim Start einer neuen Session passiert. */
enum class StartAction { START, ASK_USER, RETRY_OR_FAIL }

/**
 * Entscheidungen rund um den Besitz der Health-Services-Exercise. Grundsatz: Eine **fremde** Exercise wird nie
 * stillschweigend ersetzt (das würde z. B. ein laufendes Samsung-Health-Training beenden), sondern nur nach
 * ausdrücklicher Nutzerentscheidung; ein Abfragefehler ist kein „keine Exercise“.
 */
object ExerciseOwnership {
    fun onRecover(status: TrackedStatus): RecoveryAction =
        when (status) {
            TrackedStatus.OWNED -> RecoveryAction.REATTACH
            TrackedStatus.NONE -> RecoveryAction.RESTART
            TrackedStatus.OTHER_APP -> RecoveryAction.ASK_USER
            TrackedStatus.FAILED -> RecoveryAction.RETRY_OR_FAIL
        }

    /** [capabilities] `null` heißt: Abfrage endgültig fehlgeschlagen – dann nicht stillschweigend `ACTIVE` setzen. */
    fun onReattach(
        sport: WorkoutSport,
        capabilities: ExerciseCapabilitiesView?,
        permissions: WorkoutPermissions,
    ): ReattachResult =
        if (capabilities == null) {
            ReattachResult.CapabilitiesUnavailable
        } else {
            ReattachResult.Active(ExerciseTypeSelector.select(sport, capabilities, permissions))
        }

    fun onStart(
        status: TrackedStatus,
        userConfirmedReplace: Boolean,
    ): StartAction =
        when (status) {
            // Eigene (verwaiste) Exercise: startExercise ersetzt sie, das ist unsere eigene Session.
            TrackedStatus.OWNED, TrackedStatus.NONE -> StartAction.START

            TrackedStatus.OTHER_APP -> if (userConfirmedReplace) StartAction.START else StartAction.ASK_USER

            TrackedStatus.FAILED -> StartAction.RETRY_OR_FAIL
        }

    /** Beim Beenden: Bei eigener Exercise oder unklarem Status `endExercise` (versuchen); eine fremde nie anfassen. */
    fun needsEnd(status: TrackedStatus): Boolean = status == TrackedStatus.OWNED || status == TrackedStatus.FAILED

    /** Ende bestätigt, wenn keine eigene Exercise mehr läuft (eine fremde bleibt unangetastet). */
    fun isEndConfirmed(status: TrackedStatus): Boolean = status == TrackedStatus.NONE || status == TrackedStatus.OTHER_APP
}
