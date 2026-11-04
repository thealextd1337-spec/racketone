package eu.advantly.presentation.match

/**
 * Anzeige eines Platzes in Cloud-Ansichten (Feed, Live, Zuschauer; plan-0.6.3 §3.1/§3.6). Die Texte („Spieler N“,
 * „Gelöschter Spieler“) lokalisiert erst die UI; Handy und Uhr-Anzeige nummerieren damit identisch.
 */
sealed interface CloudPlayerName {
    data class Named(
        val name: String,
    ) : CloudPlayerName

    /** „Spieler [number]“ aus dem festen Platz, für alle Betrachter gleich. */
    data class Slot(
        val number: Int,
    ) : CloudPlayerName

    /** „Gelöschter Spieler“: Vorrang vor Platznummer und Namen. */
    data object Deleted : CloudPlayerName
}

/** Snapshot-Name, den der Server in redigierte Plätze schreibt (`match_participants.display_name`). */
const val SERVER_DELETED_NAME = "Gelöschter Spieler"

/** Sprachneutraler Server-Name unbenannter Plätze (`push_participants`). */
private const val UNNAMED = "?"

/**
 * [team] `A`/`B`, [position] 1..2. Redigiert ([deleted] oder der Server-Snapshot-Name, wo das Flag fehlt, z. B. in
 * `visitor_view`) → [CloudPlayerName.Deleted]; `?`, leer oder `null` an einem nicht verknüpften Platz → „Spieler N“.
 * Ein echter Gastname „?“ wird bewusst mit umgedeutet.
 */
fun cloudPlayerName(
    name: String?,
    team: String,
    position: Int,
    doubles: Boolean,
    linked: Boolean = false,
    deleted: Boolean = false,
): CloudPlayerName {
    val trimmed = name?.trim().orEmpty()
    return when {
        deleted || trimmed == SERVER_DELETED_NAME -> CloudPlayerName.Deleted
        !linked && (trimmed.isEmpty() || trimmed == UNNAMED) -> CloudPlayerName.Slot(slotNumber(team, position, doubles))
        else -> CloudPlayerName.Named(trimmed)
    }
}

/** Einzel A = 1, B = 2; Doppel A1 = 1, A2 = 2, B1 = 3, B2 = 4. */
fun slotNumber(
    team: String,
    position: Int,
    doubles: Boolean,
): Int = if (team == "A") position else position + if (doubles) 2 else 1
