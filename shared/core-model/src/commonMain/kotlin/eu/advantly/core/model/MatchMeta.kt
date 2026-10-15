package eu.advantly.core.model

import kotlinx.serialization.Serializable

/** Platzbelag (02 §10). Reine Metadaten, ohne Wirkung auf die Zählregeln. */
@Serializable
enum class Surface {
    CLAY,
    HARD,
    GRASS,
    ARTIFICIAL_GRASS,
    CARPET,
    GRANULAR,
    WOOD,
    OTHER,
}

/** Halle oder draußen (02 §10) – bewusst getrennt vom [Surface], da „Halle“ kein Belag ist. */
@Serializable
enum class Environment {
    INDOOR,
    OUTDOOR,
}

/**
 * Metadaten aus `MatchCreated.meta` (02 §10). Keine Regelwirkung; nur Statistik und UI-Hinweise.
 *
 * @property courtPace ITF Court Pace Rating 1–5 oder `null`
 * @property mixed Mixed-Doppel: steuert nur UI-Hinweise (02 §9.11)
 * @property watchWorkout „Workout auf meiner Uhr mitlaufen lassen“ (04 §3.1, Standard an, unabhängig vom Zähler): die
 *   Uhr des Besitzers startet beim Match eine Workout-Session. Fehlt das Feld (ältere Daten), gilt `true`.
 */
@Serializable
data class MatchMeta(
    val surface: Surface? = null,
    val environment: Environment? = null,
    val courtPace: Int? = null,
    val mixed: Boolean = false,
    val venue: String? = null,
    val watchWorkout: Boolean = true,
)
