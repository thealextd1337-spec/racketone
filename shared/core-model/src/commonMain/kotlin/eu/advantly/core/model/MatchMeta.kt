package eu.advantly.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
 * Vorab-Wahl „Aufschläge“ (02 §7.2, Entscheidung 2026-10-28): was die Aufschlag-Leiste nach dem Punkt erfasst. Nur
 * Tennis/Padel, ohne Regelwirkung (nicht im Fold).
 */
@Serializable
enum class ServeTracking {
    /** „Nein“: keine Aufschlag-Leiste. */
    OFF,

    /** „Nur Doppelfehler“: Leiste nur bei Punkten der Rückschlagseite; 1./2. Aufschlag bleiben unbekannt. */
    DOUBLE_FAULTS_ONLY,

    /** „Ja“: „2. Aufschlag“ bzw. „Doppelfehler“ wählen; ohne Eingabe gilt „1. Aufschlag“. */
    FULL,
}

/**
 * Metadaten aus `MatchCreated.meta` (02 §10). Keine Regelwirkung; nur Statistik und UI-Hinweise.
 *
 * @property courtPace ITF Court Pace Rating 1–5 oder `null`
 * @property mixed Mixed-Doppel: steuert nur UI-Hinweise (02 §9.11)
 * @property watchWorkout „Workout auf meiner Uhr mitlaufen lassen“ (04 §3.1, Standard an, unabhängig vom Zähler): die
 *   Uhr des Besitzers startet beim Match eine Workout-Session. Fehlt das Feld (ältere Daten), gilt `true`.
 * @property serveTracking „Aufschläge“ (Standard [ServeTracking.FULL]). Wire-Form abwärtskompatibel
 *   ([MatchMetaSerializer]): `serveStats` (seit 2026-10-15) wird weiter geschrieben, `serveTracking` nur zusätzlich für
 *   [ServeTracking.DOUBLE_FAULTS_ONLY].
 */
@Serializable(with = MatchMetaSerializer::class)
data class MatchMeta(
    val surface: Surface? = null,
    val environment: Environment? = null,
    val courtPace: Int? = null,
    val mixed: Boolean = false,
    val venue: String? = null,
    val watchWorkout: Boolean = true,
    val serveTracking: ServeTracking = ServeTracking.FULL,
) {
    /** Altes Ja/Nein „Aufschlag-Statistik erfassen“: Aufschlag-Leiste überhaupt anbieten. */
    val serveStats: Boolean get() = serveTracking != ServeTracking.OFF
}

/**
 * Wire-Form von [MatchMeta] (Default-Werte schreibt der Codec nicht). Kompatibilität in beide Richtungen:
 * - alt → neu: fehlt `serveTracking`, gilt `serveStats` (`true`/fehlend → FULL, `false` → OFF);
 * - neu → alt: `serveStats = serveTracking != OFF` bleibt erhalten, ältere Geräte (0.2.0) ignorieren das unbekannte
 *   `serveTracking` und zeigen bei „Nur Doppelfehler“ ihre volle Leiste. FULL und OFF erzeugen dasselbe JSON wie bisher.
 */
@Serializable
@SerialName("eu.advantly.core.model.MatchMeta")
private class MatchMetaWire(
    val surface: Surface? = null,
    val environment: Environment? = null,
    val courtPace: Int? = null,
    val mixed: Boolean = false,
    val venue: String? = null,
    val watchWorkout: Boolean = true,
    val serveStats: Boolean = true,
    val serveTracking: ServeTracking? = null,
)

object MatchMetaSerializer : KSerializer<MatchMeta> {
    private val wire = MatchMetaWire.serializer()
    override val descriptor: SerialDescriptor = wire.descriptor

    override fun serialize(
        encoder: Encoder,
        value: MatchMeta,
    ) = encoder.encodeSerializableValue(
        wire,
        with(value) {
            MatchMetaWire(
                surface,
                environment,
                courtPace,
                mixed,
                venue,
                watchWorkout,
                serveStats,
                serveTracking.takeIf { it == ServeTracking.DOUBLE_FAULTS_ONLY },
            )
        },
    )

    override fun deserialize(decoder: Decoder): MatchMeta =
        with(decoder.decodeSerializableValue(wire)) {
            MatchMeta(
                surface,
                environment,
                courtPace,
                mixed,
                venue,
                watchWorkout,
                serveTracking ?: if (serveStats) ServeTracking.FULL else ServeTracking.OFF,
            )
        }
}
