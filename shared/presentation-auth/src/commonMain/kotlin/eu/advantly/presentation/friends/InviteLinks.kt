package eu.advantly.presentation.friends

/**
 * Einladungslinks (plan-einladungen §3.1): nur `https://advantly.eu/i/#<64 Hex>` (auch `/i/index.html#…`) oder der
 * nackte Token. Alles andere – `http`, fremder Host, `www`, Port, anderer Pfad, Query, Prozent-Kodierung, falsche
 * Länge – ist kein Advantly-Link. Schema und Host vergleichen ohne Groß-/Kleinschreibung (RFC 3986), der Token wird
 * kleingeschrieben, weil der Server den Hash über genau diesen Text bildet.
 */
object InviteLinks {
    const val PREFIX = "https://advantly.eu/i/#"

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val BASE = Regex("^(?i:https://advantly\\.eu)/i/(index\\.html)?$")

    fun link(token: String): String = PREFIX + token

    /** Token aus [input] (Link oder nackter Token, Leerraum außen erlaubt), sonst `null`. */
    fun parse(input: String): String? {
        val text = input.trim()
        if (HEX64.matches(text)) return text.lowercase()
        val hash = text.indexOf('#')
        if (hash < 0 || !BASE.matches(text.substring(0, hash))) return null
        return text.substring(hash + 1).takeIf(HEX64::matches)?.lowercase()
    }
}
