package eu.advantly.core.model

/** Reservierter ID-Raum für unbesetzte Match-Plätze. Keine Person und keine Spielerzeile. */
object Placeholders {
    private const val PREFIX = "placeholder:"

    fun id(unique: String): PlayerId = PlayerId(PREFIX + unique)

    fun isPlaceholder(id: PlayerId): Boolean = id.value.startsWith(PREFIX)
}
