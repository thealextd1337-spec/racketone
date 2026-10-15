package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Environment
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.Undo
import eu.advantly.data.db.AdvantlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Repositories der Datenschicht (P1-F). Läuft auf JVM, Android-Host und (CI) auf dem iOS-/watchOS-Simulator. */
class LocalDataTest {
    private val data = LocalData(createTestDriver())
    private val tennis = Presets.TENNIS_SINGLES_BO3.format()
    private val padel = Presets.PADEL_PREMIER_2026.format()
    private val singles = Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me"))
    private val doubles = Lineup(listOf(PlayerId("me"), PlayerId("max")), listOf(PlayerId("bea"), PlayerId("cem")), PlayerId("me"))

    @Test
    fun schemaVersionIsPinned() {
        assertEquals(LocalData.SCHEMA_VERSION, AdvantlyDatabase.Schema.version)
    }

    @Test
    fun scoreEventsRoundTripThroughTheStore() {
        val m = MatchId("m1")
        val events =
            listOf(
                ScoreEvent(
                    EventId("g"),
                    m,
                    DeviceId("phone"),
                    1,
                    1,
                    10,
                    null,
                    MatchCreated(tennis, singles, MatchMeta(surface = Surface.CLAY)),
                ),
                ScoreEvent(EventId("e1"), m, DeviceId("watch"), 1, 2, 20, EventId("g"), PointWon(Side.A)),
                ScoreEvent(EventId("e2"), m, DeviceId("watch"), 2, 3, 30, EventId("e1"), Undo(EventId("e1"))),
                ScoreEvent(EventId("e3"), m, DeviceId("phone"), 2, 4, 40, EventId("e2"), Correction.server(PlayerId("bea"))),
            )
        val stored = events.map { it.toStoredEvent() }
        assertEquals("""{"type":"PointWon","side":"A"}""", stored[1].payload)
        assertEquals(listOf("MatchCreated", "PointWon", "Undo", "Correction"), stored.map { it.type })
        data.events.appendAll(stored.reversed())
        assertEquals(events, data.events.scoreEvents(m))
        // unlesbare Payload (z. B. neuerer Event-Typ) wird übersprungen, nicht geworfen
        data.events.append(StoredEvent("x", "m1", "phone", 3, 5, """{"type":"FromTheFuture"}"""))
        assertEquals(events, data.events.scoreEvents(m))
    }

    @Test
    fun matchHeaderIsIdempotentAndTracksProgress() {
        val id = MatchId("m1")
        val meta = MatchMeta(surface = Surface.HARD, environment = Environment.INDOOR)
        assertTrue(data.matches.insertIfAbsent(id, tennis, singles, meta, createdAtMs = 1_000))
        assertFalse(data.matches.insertIfAbsent(id, padel, doubles, MatchMeta(), createdAtMs = 2_000))
        val created = assertNotNull(data.matches.get(id))
        assertEquals(tennis, created.format)
        assertEquals(singles, created.lineup)
        assertEquals(meta, created.meta)
        assertEquals(Sport.TENNIS, created.sport)
        assertEquals(MatchStatus.IN_PROGRESS, created.status)
        assertEquals(ScoreSummary.EMPTY, created.score)

        // 6:0 6:0 für A → Ergebnis-Cache
        var engine = ScoringEngine.replay(tennis, singles, emptyList())
        repeat(48) { i ->
            val e = ScoreEvent(EventId("e$i"), id, DeviceId("d"), i + 1L, i + 1L, 0, null, PointWon(Side.A))
            engine = ScoringEngine.apply(engine, e)
        }
        val summary = ScoreSummary.of(engine.state)
        data.matches.updateProgress(id, engine.state.status, summary, updatedAtMs = 9_000)
        val finished = assertNotNull(data.matches.get(id))
        assertTrue(finished.isFinished)
        assertEquals(Side.A, finished.score.winner)
        assertEquals(ResultReason.COMPLETED, finished.score.reason)
        assertEquals(listOf(SetScore(6, 0), SetScore(6, 0)), finished.score.sets)
        assertNull(finished.score.current)
        assertEquals(9_000, finished.updatedAtMs)
        assertEquals(emptyList(), data.matches.active())
    }

    @Test
    fun historyFiltersBySportAndDateNewestFirst() {
        data.matches.insertIfAbsent(MatchId("t1"), tennis, singles, MatchMeta(), createdAtMs = 1_000)
        data.matches.insertIfAbsent(MatchId("p1"), padel, doubles, MatchMeta(), createdAtMs = 2_000)
        data.matches.insertIfAbsent(MatchId("t2"), tennis, singles, MatchMeta(), createdAtMs = 3_000)
        assertEquals(listOf("t2", "p1", "t1"), data.matches.history().map { it.id.value })
        assertEquals(listOf("t2", "t1"), data.matches.history(sport = Sport.TENNIS).map { it.id.value })
        assertEquals(listOf("p1"), data.matches.history(fromMs = 1_500, toMs = 3_000).map { it.id.value })
        assertEquals(listOf("p1"), data.matches.history(limit = 1, offset = 1).map { it.id.value })
        assertEquals(
            listOf("t2", "p1", "t1"),
            data.matches
                .active()
                .sortedByDescending { it.createdAtMs }
                .map { it.id.value },
        )
        assertEquals(
            listOf("t2", "p1"),
            data.matches
                .recent(2)
                .sortedByDescending { it.createdAtMs }
                .map { it.id.value },
        )
    }

    @Test
    fun followUpsAndLastLineup() {
        data.matches.insertIfAbsent(MatchId("m1"), tennis, singles, MatchMeta(), createdAtMs = 1_000)
        data.matches.insertIfAbsent(MatchId("m2"), padel, doubles, MatchMeta(), createdAtMs = 2_000, previousMatchId = MatchId("m1"))
        assertEquals(listOf("m2"), data.matches.followUps(MatchId("m1")).map { it.id.value })
        assertEquals(MatchId("m1"), data.matches.get(MatchId("m2"))?.previousMatchId)
        assertEquals(singles, data.matches.lastLineup(Sport.TENNIS))
        assertEquals(doubles, data.matches.lastLineup(Sport.PADEL, Discipline.DOUBLES))
        assertNull(data.matches.lastLineup(Sport.PADEL, Discipline.SINGLES))
        assertNull(data.matches.lastLineup(Sport.PICKLEBALL))
    }

    @Test
    fun deleteMatchRemovesHeaderAndEvents() {
        val id = MatchId("m1")
        data.matches.insertIfAbsent(id, tennis, singles, MatchMeta(), createdAtMs = 1_000)
        data.events.append(StoredEvent("e1", "m1", "d", 1, 1, "{}"))
        data.deleteMatch(id)
        assertNull(data.matches.get(id))
        assertEquals(0, data.events.count("m1"))
    }

    @Test
    fun selfPlayerIsCreatedOnceWithStableId() {
        assertNull(data.players.self())
        var calls = 0
        val self = data.players.ensureSelf("Ich", nowMs = 1) { PlayerId("uuid-${++calls}") }
        assertEquals(PlayerId("uuid-1"), self.id)
        assertEquals(PlayerKind.SELF, self.kind)
        assertEquals(self, data.players.ensureSelf("Anderer Name", nowMs = 2) { PlayerId("uuid-${++calls}") })
        assertEquals(1, calls)
        data.players.rename(self.id, "Alex")
        assertEquals("Alex", data.players.self()?.displayName)
        assertFalse(data.players.deleteGuest(self.id))
    }

    @Test
    fun guestsAndRemotePlayers() {
        data.players.ensureSelf("Ich", nowMs = 1) { PlayerId("me") }
        data.players.addGuest(PlayerId("bea"), "Bea", nowMs = 2)
        data.players.addGuest(PlayerId("cem"), "Cem", nowMs = 3)
        data.players.markUsed(listOf(PlayerId("cem")), nowMs = 10)
        assertEquals(listOf("me", "cem", "bea"), data.players.all().map { it.id.value })
        // vom anderen Gerät: neuer Name für einen Gast, neuer Spieler, aber nie der eigene Self-Player
        data.players.upsertRemote(Player(PlayerId("bea"), "Bea M."), nowMs = 20)
        data.players.upsertRemote(Player(PlayerId("dan"), "Dan"), nowMs = 20)
        data.players.upsertRemote(Player(PlayerId("me"), "Uhr-Ich"), nowMs = 20)
        assertEquals("Bea M.", data.players.get(PlayerId("bea"))?.displayName)
        assertEquals(PlayerKind.GUEST, data.players.get(PlayerId("dan"))?.kind)
        assertEquals("Ich", data.players.get(PlayerId("me"))?.displayName)
        assertTrue(data.players.deleteGuest(PlayerId("dan")))
        assertNull(data.players.get(PlayerId("dan")))
    }

    @Test
    fun favoritesCountUsesAndMergeIdempotently() {
        val first = data.favorites.recordUse(Sport.PADEL, Discipline.DOUBLES, doubles.a, doubles.b, nowMs = 100)
        assertEquals(1, first.useCount)
        // andere Aufschlagreihenfolge innerhalb der Teams = derselbe Favorit
        val again = data.favorites.recordUse(Sport.PADEL, Discipline.DOUBLES, doubles.a.reversed(), doubles.b, nowMs = 200)
        assertEquals(first.id, again.id)
        assertEquals(2, again.useCount)
        assertEquals(200, again.lastUsedAtMs)
        data.favorites.recordUse(Sport.TENNIS, Discipline.SINGLES, singles.a, singles.b, nowMs = 150)
        assertEquals(listOf(Sport.PADEL, Sport.TENNIS), data.favorites.recent().map { it.sport })
        assertEquals(listOf(Sport.TENNIS), data.favorites.recent(Sport.TENNIS).map { it.sport })

        // Abgleich mit einem anderen Gerät: Maximum von Zeit und Zähler, Name wird übernommen; doppelt = No-op
        val remote = again.copy(name = "Donnerstag", lastUsedAtMs = 150, useCount = 5)
        data.favorites.merge(remote)
        data.favorites.merge(remote)
        val merged = assertNotNull(data.favorites.get(first.id))
        assertEquals(Triple("Donnerstag", 200L, 5), Triple(merged.name, merged.lastUsedAtMs, merged.useCount))
        val unknown = remote.copy(id = "x", sport = Sport.PICKLEBALL)
        data.favorites.merge(unknown)
        assertEquals(unknown, data.favorites.get("x"))
        data.favorites.delete("x")
        assertNull(data.favorites.get("x"))
    }

    @Test
    fun settingsKeyValue() {
        assertNull(data.settings.get("k"))
        data.settings.put("k", "v")
        data.settings.put("k", "w")
        assertEquals("w", data.settings.get("k"))
        assertEquals("id-1", data.settings.getOrPut("device.id") { "id-1" })
        assertEquals("id-1", data.settings.getOrPut("device.id") { "id-2" })
        data.settings.putBoolean("b", true)
        data.settings.putLong("l", Long.MAX_VALUE)
        assertTrue(data.settings.getBoolean("b", false))
        assertEquals(Long.MAX_VALUE, data.settings.getLong("l", 0))
        assertEquals(7, data.settings.getLong("missing", 7))
        assertEquals(mapOf("b" to "true", "device.id" to "id-1", "k" to "w", "l" to Long.MAX_VALUE.toString()), data.settings.all())
        data.settings.remove("k")
        assertNull(data.settings.get("k"))
    }
}
