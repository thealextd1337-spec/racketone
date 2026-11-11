package eu.advantly.presentation.match

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.data.scoreEvents
import eu.advantly.data.toStoredEvent
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.ScorerChoice
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.stats.HistoryOutcome
import eu.advantly.presentation.stats.HistoryRole
import eu.advantly.presentation.stats.gamesWon
import eu.advantly.presentation.stats.headToHead
import eu.advantly.presentation.stats.historyPeople
import eu.advantly.presentation.stats.outcome
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import eu.advantly.match.session.WallClock as SessionClock

private const val T0 = 1_760_000_000_000L

/**
 * Perspektive (plan-pro §2.3): Alex (Konto A) legt das Match an, Jonas (Konto B) zieht es als Mitglied. Steht Jonas in
 * Team B, zählt ein Sieg von B für ihn als Sieg, die Games von B als seine Games und Alex als Gegner – auf dem echten
 * Weg über Pull-Snapshot, Store-Kopf und Statistikfunktionen. Einzel und Doppel, Jonas auf beiden Seiten.
 */
class TeamPerspectiveTest {
    private val scheduler = ManualScheduler()
    private val clock = SessionClock { T0 + scheduler.nowMs() }
    private var seed = 0
    private val closers = mutableListOf<() -> Unit>()
    private val sessions = mutableMapOf<LocalData, MatchSession>()

    @AfterTest
    fun tearDown() = closers.reversed().forEach { it() }

    private fun session(data: LocalData): MatchSession =
        MatchSession(
            data,
            null,
            scheduler,
            SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE),
            clock,
            RandomIdGenerator(Random(++seed)),
        ).also {
            it.start()
            closers += it::close
            sessions[data] = it
        }

    private fun signIn(
        data: LocalData,
        session: MatchSession,
        profile: String,
    ) {
        session.activateAccount(profile, data.cloudUploads.activate(profile))
        scheduler.advanceBy(2_000)
        data.replicas.activate(data.replicas.captureContext()) {}
        scheduler.advanceBy(2_000)
    }

    /** Ein Platz aus Sicht des Servers (`sync_pull`): Team, Position und das verknüpfte Konto. */
    private data class Slot(
        val id: String,
        val profile: String? = null,
        val status: String? = null,
    )

    /** Alex zählt: Team B holt das erste Game, dann „Beenden, wie es steht“ – Sieger B, Games 0:1. */
    private fun alexMatch(
        format: MatchFormat,
        a: List<Slot>,
        b: List<Slot>,
    ): Pair<MatchId, LocalData> {
        val data = createInMemoryJvmLocalData()
        data.players.ensureSelf("Alex", 0) { PlayerId("alex") }
        val phone = session(data)
        signIn(data, phone, "A")
        val lineup = Lineup(a.map { PlayerId(it.id) }, b.map { PlayerId(it.id) }, PlayerId("alex"))
        val match = phone.create(format, lineup, MatchMeta(), ScorerChoice.ThisDevice)
        repeat(4) { check(phone.recordPoint(Side.B)) }
        check(phone.endMatch(EndReason.STOPPED))
        return match to data
    }

    /** Jonas' Handy (Konto B) zieht das Match als Mitglied, mit den Teilnehmern, wie `sync_pull` sie liefert. */
    private fun jonasStore(
        match: MatchId,
        alex: LocalData,
        a: List<Slot>,
        b: List<Slot>,
    ): SessionMatchStore {
        val data = createInMemoryJvmLocalData()
        data.players.ensureSelf("Jonas", 0) { PlayerId("jonas") }
        val phone = session(data)
        signIn(data, phone, "B")
        // Namen kennt Jonas' Handy (z. B. aus früheren Matches), auch seinen Platz unter Alex' ID: Er ist trotzdem „ich“.
        (a + b).forEach { data.players.rememberGuest(PlayerId(it.id), it.id, 0) }
        val participants =
            listOf("A" to a, "B" to b).flatMap { (team, slots) ->
                slots.mapIndexed { i, slot ->
                    """{"slotId":"${slot.id}","team":"$team","position":${i + 1},"playerId":null,
                    "profileId":${slot.profile?.let { "\"$it\"" }},"linkStatus":${slot.status?.let { "\"$it\"" }},
                    "displayName":"${slot.id}","deletedProfile":false}"""
                }
            }
        val record = alex.matches.get(match)!!
        val run = data.cloudPulls.beginRun("B") {}.runSerial
        val page =
            CloudPullMetadataPage(
                listOf(
                    CloudPullMatch(
                        match.value,
                        1,
                        """{"createdBy":"A","participants":[${participants.joinToString(",")}]}""",
                        "member",
                        1,
                        0,
                        false,
                        record,
                    ),
                ),
                emptyList(),
                emptyList(),
                1,
                false,
                1,
            )
        check(data.cloudPulls.commitMetadata("B", run, data.cloudPulls.progress("B").cursor, page) {})
        data.cloud.storeDownloaded(alex.events.scoreEvents(match).map { it.toStoredEvent() }) {}
        data.replicas.markReady(data.replicas.captureContext(), match.value, 1, 0) {}
        return SessionMatchStore(phone, { clock.nowMs() }, monotonic = { scheduler.nowMs() }).also {
            closers += it::close
            scheduler.advanceBy(2_000)
        }
    }

    private val alex = Slot("alex", "A", "self")

    private fun assertPerspective(
        format: MatchFormat,
        a: List<Slot>,
        b: List<Slot>,
        jonasSide: Side,
    ) {
        val (match, alexData) = alexMatch(format, a, b)
        val summary = jonasStore(match, alexData, a, b).matches.value.single()
        val won = jonasSide == Side.B
        assertEquals(jonasSide, summary.setup.ownSide)
        assertEquals(if (won) HistoryOutcome.WIN else HistoryOutcome.LOSS, summary.outcome(), "Sieg/Niederlage")
        assertEquals(if (won) 1 else 0, summary.gamesWon, "gewonnene Games")
        val jonasSlot = (a + b).single { it.profile == "B" }.id
        val people = historyPeople(summary.setup, setOf(PlayerId("jonas")))
        assertEquals(null, people.firstOrNull { it.player.id.value == jonasSlot }, "Jonas ist keine H2H-Person")
        val alexRole = if ((if (alex in a) Side.A else Side.B) == jonasSide) HistoryRole.PARTNER else HistoryRole.OPPONENT
        assertEquals(alexRole, people.single { it.player.id.value == "alex" }.role, "Rolle von Alex")
        val h2h = headToHead(listOf(summary), PlayerId("alex"), alexRole, setOf(PlayerId("jonas")))
        assertEquals(if (won) 1 to 0 else 0 to 1, h2h.wins to h2h.losses, "Bilanz mit/gegen Alex")
        // Bilanz mit dem Freund über sein Konto: Alex' eigener Platz trägt im Pull sein Konto „A“.
        assertEquals("A", people.single { it.player.id.value == "alex" }.profile, "Konto hinter Alex")
        val friend = headToHead(listOf(summary), "A", alexRole, setOf(PlayerId("jonas")))
        assertEquals(h2h.wins to h2h.losses, friend.wins to friend.losses, "Freundes-Bilanz mit/gegen Alex")
    }

    /** Vor dem Fix: B gewinnt, Jonas (B) sah eine Niederlage, 0 Games und Alex als „Partner“. */
    @Test
    fun singlesFriendInTeamB() =
        assertPerspective(Presets.TENNIS_SINGLES_BO3.format(), listOf(alex), listOf(Slot("jonas-bei-alex", "B", "linked")), Side.B)

    @Test
    fun singlesAssignedGuestInTeamB() =
        assertPerspective(Presets.TENNIS_SINGLES_BO3.format(), listOf(alex), listOf(Slot("gast", "B", "assigned")), Side.B)

    @Test
    fun singlesFriendInTeamA() =
        assertPerspective(Presets.TENNIS_SINGLES_BO3.format(), listOf(Slot("jonas-bei-alex", "B", "linked")), listOf(alex), Side.A)

    @Test
    fun doublesFriendInTeamB() =
        assertPerspective(
            Presets.TENNIS_DOUBLES_MTB10.format(),
            listOf(alex, Slot("mia")),
            listOf(Slot("tom"), Slot("jonas-bei-alex", "B", "linked")),
            Side.B,
        )

    @Test
    fun doublesFriendInTeamA() =
        assertPerspective(
            Presets.TENNIS_DOUBLES_MTB10.format(),
            listOf(alex, Slot("jonas-bei-alex", "B", "linked")),
            listOf(Slot("tom"), Slot("mia")),
            Side.A,
        )

    /** Eigene Matches bleiben wie bisher: Alex (A) sieht dasselbe Match als Niederlage, Jonas' Platz als Gegner. */
    @Test
    fun ownMatchKeepsSideA() {
        val (match, alexData) = alexMatch(Presets.TENNIS_SINGLES_BO3.format(), listOf(alex), listOf(Slot("jonas-bei-alex", "B", "linked")))
        alexData.players.rememberGuest(PlayerId("jonas-bei-alex"), "Jonas", 0)
        val store = SessionMatchStore(sessions.getValue(alexData), { clock.nowMs() }, monotonic = { scheduler.nowMs() })
        closers += store::close
        val summary = store.matches.value.single { it.matchId == match }
        assertEquals(Side.A, summary.setup.ownSide)
        assertEquals(HistoryOutcome.LOSS, summary.outcome())
        assertEquals(0, summary.gamesWon)
        assertEquals(HistoryRole.OPPONENT, historyPeople(summary.setup, setOf(PlayerId("alex"))).single().role)
    }
}
