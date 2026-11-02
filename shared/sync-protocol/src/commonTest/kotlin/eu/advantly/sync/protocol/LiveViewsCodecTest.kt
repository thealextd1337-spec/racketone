package eu.advantly.sync.protocol

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveViewsCodecTest {
    private fun view(
        id: String = "m1",
        teamA: String = "Alex",
        teamB: String = "Jonas",
    ) = LiveView(
        matchId = MatchId(id),
        relation = LiveRelation.PLAYER,
        sport = Sport.TENNIS,
        teamA = teamA,
        teamB = teamB,
        sets = listOf(listOf(6, 4), listOf(3, 6)),
        games = listOf(2, 1),
        points = listOf("40", "AD"),
        serving = Side.B,
        scorerConnected = true,
        ageMs = 4_000,
    )

    @Test
    fun subscriptionRoundTripsAndDropsForeignVersions() {
        val value = LiveViewSubscription(LiveViewSubscription.CURRENT_VERSION)
        assertEquals(value, LiveViewSubscriptionCodec.decode(LiveViewSubscriptionCodec.encode(value)))
        assertEquals(value, LiveViewSubscriptionCodec.decode("""{"v":1,"future":true}""".encodeToByteArray()))
        listOf("{}", "not json", """{"v":0}""", """{"v":2}""", "x".repeat(1_025)).forEach {
            assertNull(LiveViewSubscriptionCodec.decode(it.encodeToByteArray()))
        }
        assertEquals("/advantly/live/subscribe", LiveViewSubscription.MESSAGE_PATH)
        assertEquals("/advantly/live/views", LiveViews.MESSAGE_PATH)
    }

    @Test
    fun viewsRoundTripWithAllSportsAndRelations() {
        val rally =
            LiveView(MatchId("m2"), LiveRelation.VISITOR, Sport.BADMINTON, "A", "B", points = listOf("19", "21"), finished = true)
        val value = LiveViews(1, listOf(view(), rally, view("m3").copy(relation = LiveRelation.SCORER, sport = Sport.PADEL)))
        assertEquals(value, LiveViewsCodec.decode(LiveViewsCodec.encode(value)))
        assertEquals(LiveViews(1), LiveViewsCodec.decode(LiveViewsCodec.encode(LiveViews(1))))
    }

    @Test
    fun foreignVersionsAndBrokenMessagesAreDropped() {
        listOf(
            "not json",
            "{}",
            """{"v":2,"matches":[]}""",
            """{"v":0,"matches":[]}""",
            """{"v":1,"matches":[{"matchId":"m","relation":"owner","sport":"TENNIS","teamA":"a","teamB":"b"}]}""",
            "x".repeat(LiveViews.MAX_BYTES + 1),
        ).forEach { assertNull(LiveViewsCodec.decode(it.encodeToByteArray()), it) }
        val tooMany = (1..6).joinToString(",") { """{"matchId":"m$it","relation":"player","sport":"TENNIS","teamA":"a","teamB":"b"}""" }
        assertNull(LiveViewsCodec.decode("""{"v":1,"matches":[$tooMany]}""".encodeToByteArray()))
    }

    @Test
    fun invalidEntriesAreDroppedButTheRestStays() {
        val json =
            """{"v":1,"future":1,"matches":[
              {"matchId":"","relation":"player","sport":"TENNIS","teamA":"a","teamB":"b"},
              {"matchId":"m","relation":"player","sport":"TENNIS","teamA":"a","teamB":"b","games":[1]},
              {"matchId":"m","relation":"player","sport":"TENNIS","teamA":"a","teamB":"b","points":["1234","0"]},
              {"matchId":"ok","relation":"visitor","sport":"PICKLEBALL","teamA":"a","teamB":"b","extra":"x"}
            ]}"""
        val decoded = assertNotNull(LiveViewsCodec.decode(json.encodeToByteArray()))
        assertEquals(listOf(MatchId("ok")), decoded.matches.map { it.matchId })
    }

    @Test
    fun encodeKeepsAtMostFiveMatchesAndShortensNames() {
        val long = "Maximilian-Alexander von Hohenzollern-Sigmaringen"
        val value = LiveViews(1, (1..8).map { view("m$it", teamA = long, teamB = "😀".repeat(30)) })
        val bytes = LiveViewsCodec.encode(value)
        assertTrue(bytes.size <= LiveViews.MAX_BYTES)
        val decoded = assertNotNull(LiveViewsCodec.decode(bytes))
        assertEquals(LiveViews.MAX_MATCHES, decoded.matches.size)
        decoded.matches.forEach {
            assertTrue(it.teamA.length <= LiveViews.MAX_NAME_CHARS && it.teamA.endsWith("…"))
            assertTrue(it.teamB.length <= LiveViews.MAX_NAME_CHARS)
            assertFalse(
                it.teamB
                    .dropLast(1)
                    .last()
                    .isHighSurrogate(),
            )
        }
    }

    @Test
    fun encodeDropsTrailingMatchesUntilTheLimitFits() {
        // 4-Byte-Zeichen ohne Kürzung auf Namenslänge: viele Sätze treiben jeden Eintrag nahe an 500 Bytes
        val heavy = view(teamA = "😀".repeat(11), teamB = "😀".repeat(11)).copy(sets = List(9) { listOf(1_000_000, 1_000_000) })
        val bytes = LiveViewsCodec.encode(LiveViews(1, (1..5).map { heavy.copy(matchId = MatchId("match-$it")) }))
        assertTrue(bytes.size <= LiveViews.MAX_BYTES)
        val kept = assertNotNull(LiveViewsCodec.decode(bytes)).matches
        assertTrue(kept.size in 1 until LiveViews.MAX_MATCHES)
        assertEquals((1..kept.size).map { MatchId("match-$it") }, kept.map { it.matchId })
    }

    @Test
    fun noTelemetryOrHealthFieldsOnTheWire() {
        val text = LiveViewsCodec.encode(LiveViews(1, listOf(view()))).decodeToString().lowercase()
        listOf("heart", "bpm", "steps", "calor", "telemetry", "health", "puls").forEach { assertFalse(it in text, it) }
        val fields = LiveView.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }
        assertEquals(
            listOf(
                "matchId",
                "relation",
                "sport",
                "teamA",
                "teamB",
                "sets",
                "games",
                "points",
                "serving",
                "finished",
                "scorerConnected",
                "ageMs",
            ),
            fields,
        )
    }

    @Test
    fun diagnosticsNeverContainNames() {
        assertFalse("Alex" in view().toString())
        assertFalse("Alex" in LiveViews(1, listOf(view())).toString())
    }
}
