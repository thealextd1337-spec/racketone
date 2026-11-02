package eu.advantly.sync.protocol

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Uhr → Handy: Die Zuschau-Ansicht der Uhr ist sichtbar (plan-live-mitspieler §3.2). Jede Nachricht löst genau einen
 * Serverabruf auf dem Handy aus; kein Ack, keine Queue, keine Persistenz.
 */
@Serializable
data class LiveViewSubscription(
    val v: Int,
) {
    companion object {
        /** Nur bei inkompatiblen Änderungen erhöhen. */
        const val CURRENT_VERSION: Int = 1
        const val MIN_VERSION: Int = 1
        const val MESSAGE_PATH: String = "/advantly/live/subscribe"

        /** Abstand der Abo-Nachrichten, solange die Ansicht sichtbar und nicht im Ambient-Modus ist. */
        const val INTERVAL_MS: Long = 10_000
    }
}

object LiveViewSubscriptionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(value: LiveViewSubscription): ByteArray = json.encodeToString(LiveViewSubscription.serializer(), value).encodeToByteArray()

    fun decode(bytes: ByteArray): LiveViewSubscription? {
        if (bytes.size > 1_024) return null
        return try {
            json.decodeFromString(LiveViewSubscription.serializer(), bytes.decodeToString()).takeIf {
                it.v in LiveViewSubscription.MIN_VERSION..LiveViewSubscription.CURRENT_VERSION
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Warum ich ein fremdes Match sehen darf (`cloud_my_live_matches_v1`, plan-live-mitspieler §8). */
@Serializable
enum class LiveRelation {
    @SerialName("player")
    PLAYER,

    @SerialName("visitor")
    VISITOR,

    @SerialName("scorer")
    SCORER,
}

/**
 * Anzeige-Stand eines fremden laufenden Matches, bereinigt wie `visitor_view`: nur Namen und Spielstand, **nie**
 * Telemetrie, Gesundheitsdaten oder Spieler-IDs.
 *
 * @property sets abgeschlossene Sätze als `[a, b]` (Spiele bzw. Rally-Punkte)
 * @property games Spiele im laufenden Satz `[a, b]`; `null` bei Rally-Sportarten oder unbekannt
 * @property points Punktanzeige `[a, b]` (z. B. `"40"`/`"AD"` oder Rally-Stand), je höchstens 3 Zeichen
 * @property ageMs Alter des Stands beim Absenden des Handys (Handy-Uhrzeit minus `updatedAt`); relativ, damit die
 *   Uhrzeiten von Handy und Uhr nicht übereinstimmen müssen
 */
@Serializable
data class LiveView(
    val matchId: MatchId,
    val relation: LiveRelation,
    val sport: Sport,
    val teamA: String,
    val teamB: String,
    val sets: List<List<Int>> = emptyList(),
    val games: List<Int>? = null,
    val points: List<String>? = null,
    val serving: Side? = null,
    val finished: Boolean = false,
    val scorerConnected: Boolean = false,
    val ageMs: Long? = null,
) {
    override fun toString(): String = "LiveView(<redacted>)"

    internal fun isValid(): Boolean =
        matchId.value.isNotBlank() &&
            sets.size <= LiveViews.MAX_SETS &&
            sets.all { it.size == 2 && it.all { n -> n >= 0 } } &&
            (games == null || (games.size == 2 && games.all { it >= 0 })) &&
            (points == null || (points.size == 2 && points.all { it.length in 1..3 })) &&
            (ageMs == null || ageMs >= 0)
}

/**
 * Handy → Uhr: Antwort auf eine [LiveViewSubscription], höchstens [MAX_MATCHES] Matches und [MAX_BYTES] Bytes.
 * Eine leere Liste heißt „nichts live“ (auch: nicht angemeldet).
 */
@Serializable
data class LiveViews(
    val v: Int,
    val matches: List<LiveView> = emptyList(),
) {
    override fun toString(): String = "LiveViews(<redacted>, ${matches.size})"

    companion object {
        /** Nur bei inkompatiblen Änderungen erhöhen; additive Felder bleiben in derselben Version. */
        const val CURRENT_VERSION: Int = 1
        const val MIN_VERSION: Int = 1
        const val MESSAGE_PATH: String = "/advantly/live/views"
        const val MAX_MATCHES: Int = 5
        const val MAX_BYTES: Int = 2_048
        const val MAX_NAME_CHARS: Int = 24
        const val MAX_SETS: Int = 9
    }
}

/** Eigener, kleiner Wire-Codec; kürzt beim Senden, verwirft beim Empfang ungültige Einträge und fremde Versionen. */
object LiveViewsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Kürzt auf [LiveViews.MAX_MATCHES] Matches und [LiveViews.MAX_NAME_CHARS] Zeichen je Name, lässt ungültige
     * Einträge weg und danach so lange die letzten Matches, bis höchstens [LiveViews.MAX_BYTES] Bytes übrig sind.
     */
    fun encode(value: LiveViews): ByteArray {
        var matches =
            value.matches
                .map { it.copy(teamA = shorten(it.teamA), teamB = shorten(it.teamB)) }
                .filter { it.isValid() }
                .take(LiveViews.MAX_MATCHES)
        while (true) {
            val bytes = json.encodeToString(LiveViews.serializer(), value.copy(matches = matches)).encodeToByteArray()
            if (bytes.size <= LiveViews.MAX_BYTES || matches.isEmpty()) return bytes
            matches = matches.dropLast(1)
        }
    }

    fun decode(bytes: ByteArray): LiveViews? {
        if (bytes.size > LiveViews.MAX_BYTES) return null
        return try {
            json
                .decodeFromString(LiveViews.serializer(), bytes.decodeToString())
                .takeIf { it.v in LiveViews.MIN_VERSION..LiveViews.CURRENT_VERSION && it.matches.size <= LiveViews.MAX_MATCHES }
                ?.let { it.copy(matches = it.matches.filter { match -> match.isValid() }) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun shorten(name: String): String {
        val trimmed = name.trim()
        if (trimmed.length <= LiveViews.MAX_NAME_CHARS) return trimmed
        var end = LiveViews.MAX_NAME_CHARS - 1
        if (trimmed[end - 1].isHighSurrogate()) end-- // kein halbes Emoji
        return trimmed.take(end).trimEnd() + "…"
    }
}
