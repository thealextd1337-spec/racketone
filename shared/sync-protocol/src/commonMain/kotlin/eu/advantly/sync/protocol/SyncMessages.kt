package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.ScoreEvent
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Nachricht des Geräte-Syncs (01 §4.3). JSON: flaches Objekt mit Diskriminator `type` und Protokollversion `v`,
 * z. B. `{"type":"Summary","v":1,"matches":{"m1":{"watch":3}}}`. Kodierung über [SyncCodec].
 */
@Serializable
sealed interface SyncMessage {
    /** Protokollversion des Absenders ([ProtocolVersion.CURRENT]). */
    val v: Int
}

/** Art eines Geräts – nur Anzeige (z. B. „Uhr zählt“, Sync-Pill „Uhr verbunden“). */
@Serializable
enum class PeerKind {
    PHONE,
    WATCH,
    OTHER,
}

/**
 * Verbindungsaufbau und Versionsprüfung. Wird beim (Wieder-)Verbinden gesendet; der Empfänger antwortet mit
 * `Summary` (Anti-Entropie) und – falls noch nicht geschehen – mit seinem eigenen `Hello`.
 *
 * @property minV älteste Version, die der Absender noch lesen kann
 * @property activeMatches Matches, die beim Absender gerade offen sind
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("Hello")
data class Hello(
    val deviceId: DeviceId,
    val kind: PeerKind,
    val activeMatches: List<MatchId> = emptyList(),
    @EncodeDefault val minV: Int = ProtocolVersion.MIN_SUPPORTED,
    @EncodeDefault override val v: Int = ProtocolVersion.CURRENT,
) : SyncMessage

/**
 * Anti-Entropie: „Was habe ich?“ je Match als [VersionVector] (01 §4.3 Regel 3). Der Empfänger sendet daraufhin alle
 * Events, die der Absender laut Vektor nicht hat ([AntiEntropy.missingRanges]).
 *
 * @property matches Match-ID (Text) → Versionsvektor
 * @property reply `true` = Antwort auf ein `Summary` („mir fehlt etwas, bitte nachsenden“); auf eine Antwort wird nie
 *   erneut mit `Summary` geantwortet (keine Ping-Pong-Schleifen)
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("Summary")
data class Summary(
    val matches: Map<String, VersionVector>,
    val reply: Boolean = false,
    @EncodeDefault override val v: Int = ProtocolVersion.CURRENT,
) : SyncMessage {
    /** Vektor für [matchId] oder [VersionVector.EMPTY]. */
    fun vector(matchId: MatchId): VersionVector = matches[matchId.value] ?: VersionVector.EMPTY
}

/**
 * Events eines Matches – Live-Push einzelner Taps und Nachlieferung (Batch). Der Empfang ist idempotent über die
 * Event-`id` (01 §4.3 Regel 2). Events mit anderer `matchId` als [matchId] verwirft der Empfänger.
 *
 * @property more `true` = weitere Teile derselben Nachlieferung folgen. Nur ein Hinweis: Der Empfänger speichert und
 *   wendet jede Nachricht sofort an (Fold ist reihenfolgeunabhängig).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("Events")
data class Events(
    val matchId: MatchId,
    val events: List<ScoreEvent>,
    val more: Boolean = false,
    @EncodeDefault override val v: Int = ProtocolVersion.CURRENT,
) : SyncMessage

/**
 * Match-Kopf und Favoriten-Lineups (01 §4.3), damit die Uhr ein Match anzeigen, starten oder fortsetzen kann.
 * Der Zählstand kommt nie hierüber, sondern immer aus den Events.
 *
 * @property match Kopf des Matches oder `null` (nur Spieler/Favoriten)
 * @property players Anzeigenamen der Spieler aus Lineup und Favoriten
 * @property favorites Favoriten-Lineups („Wie letztes Mal“, Schnellstart auf der Uhr)
 * @property self eigener Spieler des Absenders („Ich“). Das Handy ist Identitätsquelle: Eine Uhr übernimmt den `self`
 *   eines Handys als ihren eigenen Spieler; ein Handy merkt sich den `self` anderer eigener Geräte als Alias (W1-I).
 *   `null` bei älteren Absendern.
 * @property placeholderIds dauerhaft migrierte Uhr-Slot-IDs; dürfen nie als Gäste importiert werden
 * @property recentPlayers höchstens 24 zuletzt gespielte Personen vom Handy, ohne Self/Platzhalter; null = kein Verzeichnis-Update
 * @property selfAliases frühere bzw. bekannte andere IDs desselben Besitzers (z. B. der Uhr-eigene Spieler vor der
 *   Übernahme) – Matches mit diesen IDs zeigen den Besitzer, keinen Gast
 * @property watchMatch nur vom Handy: welches seiner (ggf. mehreren) offenen Matches die eigene Uhr zählt bzw. anzeigt
 *   (F22, 01 §4.3 „Uhr-Match“); `null` = keine Angabe (Uhr, ältere Absender)
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("MatchMeta")
data class MatchMetaMessage(
    val match: MatchHeader? = null,
    val players: List<Player> = emptyList(),
    val favorites: List<FavoriteLineup> = emptyList(),
    val self: Player? = null,
    val selfAliases: List<PlayerId> = emptyList(),
    val placeholderIds: List<PlayerId> = emptyList(),
    val recentPlayers: List<Player>? = null,
    /** Additiv in Protokoll 3: nur IDs, damit lokale Uhr-Historie entfernte Gäste nicht erneut anbietet. */
    val archivedPlayerIds: List<PlayerId> = emptyList(),
    val watchMatch: WatchAssignment? = null,
    @EncodeDefault override val v: Int = ProtocolVersion.CURRENT,
) : SyncMessage

/**
 * Zuweisung des „Uhr-Matches“ durch das Handy (F22, 01 §4.3): Das Handy kann mehrere Matches parallel führen, die eigene
 * Uhr zählt bzw. zeigt höchstens eines davon. Die Uhr folgt nur diesem Match (kein Auto-Follow auf andere Matches des
 * Handys). Zuweisungen desselben Handys werden über [assignedAtMs] geordnet (die jüngste gilt; ältere, z. B. durch
 * Wiederholung verspätet zugestellte, ignoriert die Uhr).
 *
 * @property matchId das Uhr-Match; `null` = die Uhr ist frei (folgt keinem Match des Handys)
 * @property assignedAtMs Wanduhr des Handys bei der Zuweisung (Ordnung der Zuweisungen; bei [explicit] zusätzlich die
 *   Grenze, ab der ein auf der Uhr gestartetes Match als jünger gilt)
 * @property explicit vom Nutzer gewählt („Meine Uhr“, „Auf der Uhr zählen“): Die Uhr folgt ihr auch weg von einem auf ihr
 *   gestarteten Match, das vor der Zuweisung begann; implizite Zuweisungen (freie Uhr, Folge-Match, Uhr-Start) unterliegen
 *   der Rennregel „jüngeres Uhr-Match gewinnt“ (01 §4.9)
 */
@Serializable
data class WatchAssignment(
    val matchId: MatchId?,
    val assignedAtMs: Long,
    val explicit: Boolean = false,
)

/**
 * Löschmarkierungen (Tombstones): Diese Matches wurden auf einem gekoppelten Gerät gelöscht. Der Empfänger löscht sie
 * ebenfalls (ein offenes wird geschlossen) und ignoriert danach dauerhaft alle Events und Köpfe dazu. Wird bei jedem
 * `Hello` vollständig gesendet und beim Löschen sofort. P1: nur eigene Geräte; P2 (geteilte Matches): Löschen betrifft
 * nur die eigene Sicht.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("Deleted")
data class Deleted(
    val matches: List<DeletedMatch>,
    @EncodeDefault override val v: Int = ProtocolVersion.CURRENT,
) : SyncMessage

/** Ein gelöschtes Match mit Zeitpunkt der Löschung (Wanduhr des löschenden Geräts). */
@Serializable
data class DeletedMatch(
    val matchId: MatchId,
    val deletedAtMs: Long,
)

/**
 * Unveränderlicher Kopf eines Matches. Format, Besetzung und Metadaten stehen zusätzlich in der Genesis (`MatchCreated`);
 * hier kommen die Daten dazu, die nicht im Event-Log stehen.
 *
 * @property previousMatchId Vorgänger bei einem verknüpften Folge-Match (02 §5.2)
 */
@Serializable
data class MatchHeader(
    val matchId: MatchId,
    val format: MatchFormat,
    val lineup: Lineup,
    val meta: MatchMeta = MatchMeta(),
    val createdAtMs: Long,
    val previousMatchId: MatchId? = null,
)

/**
 * Favoriten-Lineup auf dem Draht. [id] ist auf allen Geräten gleich (abgeleitet aus Sport, Disziplin und Besetzung),
 * damit wiederholte Übertragungen idempotent sind.
 */
@Serializable
data class FavoriteLineup(
    val id: String,
    val sport: Sport,
    val discipline: Discipline,
    val a: List<PlayerId>,
    val b: List<PlayerId>,
    val name: String? = null,
    val lastUsedAtMs: Long = 0,
    val useCount: Int = 0,
)
