package eu.advantly.data

import app.cash.sqldelight.db.QueryResult
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.db.AdvantlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paket 5: Die Spielerabfragen prüfen die Lesbarkeit per korreliertem `EXISTS` statt `id IN (SELECT … view)`. Das Ergebnis
 * muss in jedem Zugriffszustand exakt der alten Form entsprechen (lesbar, nicht lesbar, Redaktion, fremdes Konto).
 */
class ReadablePlayerQueryTest {
    private val driver = createInMemoryJvmDriver()
    private val data = LocalData(driver)
    private val db = AdvantlyDatabase(driver)
    private val format = Presets.TENNIS_ONE_SET.format()
    private val owner = PlayerId("owner")
    private val slot = Placeholders.id("opponent")
    private var index = 0
    private var hidden = 0

    private fun select(account: String) = data.accountIdentities.select("scope-$account", account, 0, "WATCH") { "$account-${index++}" }

    private fun ids(
        sql: String,
        arg: String? = null,
    ): List<String> =
        driver
            .executeQuery(null, sql, { c ->
                val out = mutableListOf<String>()
                while (c.next().value) out += c.getString(0)!!
                QueryResult.Value(out)
            }, if (arg == null) 0 else 1) { if (arg != null) bindString(0, arg) }
            .value

    /** Alt gegen neu für jeden gespeicherten Spieler (plus eine unbekannte ID); zählt die nicht lesbaren. */
    private fun assertLegacyParity() {
        val all = ids("SELECT id FROM player") + "missing"
        all.forEach { id ->
            assertEquals(
                ids("SELECT id FROM player WHERE id = ? AND id IN (SELECT id FROM replica_readable_player)", id),
                listOfNotNull(
                    db.playerQueries
                        .selectReadableById(id)
                        .executeAsOneOrNull()
                        ?.id,
                ),
                id,
            )
        }
        assertEquals(
            ids(
                "SELECT id FROM player WHERE id IN (SELECT id FROM replica_readable_player) " +
                    "ORDER BY kind DESC, coalesce(last_used_at_ms, 0) DESC, display_name, id",
            ),
            db.playerQueries
                .selectReadableAll()
                .executeAsList()
                .map { it.id },
        )
        assertEquals(
            ids(
                "SELECT id FROM player WHERE kind = 'SELF' AND id IN (SELECT id FROM replica_readable_player) " +
                    "ORDER BY created_at_ms, id LIMIT 1",
            ),
            listOfNotNull(
                db.playerQueries
                    .selectReadableSelf()
                    .executeAsOneOrNull()
                    ?.id,
            ),
        )
        hidden += all.size - 1 -
            db.playerQueries
                .selectReadableAll()
                .executeAsList()
                .size
    }

    private fun event(
        id: String,
        seq: Long,
        payload: EventPayload,
    ) = ScoreEvent(
        EventId(id),
        MatchId("cloud"),
        DeviceId("server-device"),
        seq,
        seq,
        seq,
        if (seq == 1L) null else EventId("genesis"),
        payload,
    ).toStoredEvent()

    @Test
    fun existsFormMatchesLegacyInEveryAccessState() {
        select("A")
        val self = data.players.ensureSelf("", 0) { PlayerId("self-a") }.id
        data.players.addGuest(PlayerId("early"), "Early", 1)
        assertLegacyParity() // vor der Aktivierung: alles lesbar

        val ctx = data.replicas.captureContext()
        data.replicas.activate(ctx) {}
        // Personen ohne eigene Metadaten: nur über ein lesbares Lineup sichtbar (bzw. gar nicht)
        listOf("partner", "stranger").forEach { driver.execute(null, "INSERT INTO player VALUES ('$it', '$it', 'GUEST', 1, NULL)", 0) }
        val device = DeviceId("A-0")
        val local = MatchId("local")
        val lineup = Lineup(listOf(self), listOf(PlayerId("partner")), self)
        data.transaction {
            data.events.append(
                ScoreEvent(EventId("local-genesis"), local, device, 1, 1, 1, null, MatchCreated(format, lineup, MatchMeta(), device, self))
                    .toStoredEvent(),
            )
            data.matches.insertIfAbsent(local, format, lineup, MatchMeta(), 1)
        }
        data.players.addGuest(PlayerId("guest"), "Guest", 2)
        assertLegacyParity()

        // Cloud-Match eines Mitglieds mit Zuweisung und Verzeichnisspieler, danach Redaktion
        data.replicas.applyAccess(ctx, "cloud", 1, ReplicaAccessLevel.MEMBER) {}
        data.replicas.markReady(ctx, "cloud", 1, 0) {}
        val cloudLineup = Lineup(listOf(owner), listOf(slot), owner)
        data.cloud.storeDownloadedMatch(
            MatchRecord(MatchId("cloud"), format, cloudLineup, MatchMeta(), 1, 1, MatchStatus.IN_PROGRESS, ScoreSummary.EMPTY, null),
            "A",
        )
        data.cloud.storeDownloaded(
            listOf(
                event("genesis", 1, MatchCreated(format, cloudLineup, createdBy = owner)),
                event("assignment", 2, PlayerAssigned(slot, Player(PlayerId("deleted"), "Sensitive name"), owner)),
                event("point", 3, PointWon(Side.A)),
            ),
        ) {}
        data.cloud.storeDownloadedPlayer(LocalPlayer(PlayerId("deleted"), "Sensitive name", PlayerKind.GUEST, 0, null), "A")
        data.players.upsertRemote(Player(owner, "Owner"), 0)
        assertLegacyParity()

        val start = data.replicas.beginRedaction(ctx, RedactionTransfer("t", "cloud", 1, 1, 3)) {}
        data.replicas.stageRedaction(
            ctx,
            "t",
            RedactionPage(0, start, RedactionCursor("deleted", 0), listOf("deleted"), emptyList(), true, true),
        ) {}
        data.replicas.completeRedaction(ctx, "t") {}
        assertLegacyParity()

        // Fremdes Konto: eigene Spieler und Matches von A sind nicht mehr lesbar
        select("B")
        data.players.ensureSelf("", 3) { PlayerId("self-b") }
        assertLegacyParity()
        select("A")
        assertLegacyParity()
        assertTrue(hidden > 0, "Szenario muss auch nicht lesbare Spieler enthalten")
    }
}
