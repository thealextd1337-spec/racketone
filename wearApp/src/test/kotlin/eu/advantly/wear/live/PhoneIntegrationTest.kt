package eu.advantly.wear.live

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.ScorerChoice
import eu.advantly.wear.WatchHarness
import eu.advantly.wear.match.FormatChoice
import eu.advantly.wear.workout.WorkoutCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zusammenspiel mit der Handy-Session nach W1-I: Identität, „Meine Uhr“, Workout-Wunsch, Verbindungsstatus. */
class PhoneIntegrationTest {
    private val h = WatchHarness()
    private val c = h.controller

    private fun startOnWatch() {
        c.newMatch()
        c.selectSport(Sport.TENNIS)
        c.selectDiscipline(eu.advantly.core.model.Discipline.SINGLES)
        c.selectFormat(FormatChoice.TENNIS_BO3)
        assertTrue(c.selectServer(Side.A, 0))
    }

    /** Handy-Self anlegen und neu verbinden, damit die Uhr ihn per `MatchMeta` übernimmt. */
    private fun phoneWithOwner(): PlayerId {
        val owner =
            h.phoneData.players
                .ensureSelf("Alex", 1L) { PlayerId("phone-owner") }
                .id
        h.offline("watch")
        h.settle()
        h.online("watch")
        h.settle()
        return owner
    }

    @Test
    fun watchAdoptsThePhoneIdentityForNewMatches() {
        val watchOwnSelf = h.directory.self()
        val owner = phoneWithOwner()
        assertEquals(
            owner,
            h.watchData.players
                .self()
                ?.id,
        )
        assertTrue("alter Uhr-Self bleibt der Besitzer", h.directory.isSelf(watchOwnSelf))
        startOnWatch()
        val match = h.watch.state.value.match!!
        assertEquals(owner, match.lineup.a.first())
        val me =
            c.state.value.live!!
                .teams.a
                .first()
        assertTrue(me.isSelf)
    }

    @Test
    fun myWatchPlaceholderIsHandedToTheWatchOnItsFirstHello() {
        h.offline("watch") // Uhr-App geschlossen / Energiesparmodus
        h.settle()
        h.phone.create(
            Presets.TENNIS_SINGLES_BO3.format(),
            Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me")),
            scorer = ScorerChoice.MyWatch,
        )
        h.settle()
        val onPhone = h.phone.state.value.match!!
        assertTrue(onPhone.scorerDeviceId == MatchSession.PENDING_WATCH || onPhone.scorerDeviceId == h.watch.deviceId)
        h.online("watch") // Uhr wacht auf (Listener-Dienst) und meldet sich
        h.settle()
        val live = c.state.value.live!!
        assertEquals(LiveRole.SCORER, live.role)
        assertTrue(c.point(Side.A))
        h.settle()
        assertEquals(
            h.watch.deviceId,
            h.phone.state.value.match!!
                .scorerDeviceId,
        )
        assertEquals(
            1,
            h.phone.state.value.match!!
                .state.current!!
                .points.a,
        )
    }

    @Test
    fun workoutWishFollowsTheMatchMeta() {
        h.prefs.workoutEnabled = false
        startOnWatch()
        val watchMatch = h.watch.state.value.match!!
        assertFalse("Uhr-Match trägt die Uhr-Einstellung", watchMatch.meta.watchWorkout)
        h.settle()
        assertFalse(
            "Handy sieht denselben Wunsch",
            h.phone.state.value.match!!
                .meta.watchWorkout,
        )
        assertFalse(WorkoutCoordinator.enabled(watchMatch.meta, watchSetting = true))
        c.confirmEnd()
        c.close()
        h.settle() // F22: erst wenn das Handy das Ende kennt, ist die Uhr frei für ein neues Director-Match
        // Vom Handy gestartet: dessen Wahl gilt, unabhängig von der Uhr-Einstellung
        h.phone.create(
            Presets.TENNIS_SINGLES_BO3.format(),
            Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me")),
            meta = MatchMeta(watchWorkout = true),
        )
        h.settle()
        assertTrue(
            WorkoutCoordinator.enabled(
                h.watch.state.value.match!!
                    .meta,
                watchSetting = false,
            ),
        )
        assertTrue("ohne Match gilt die Uhr-Einstellung", WorkoutCoordinator.enabled(null, watchSetting = true))
    }

    @Test
    fun homeShowsThePhoneConnection() {
        assertTrue((c.state.value.screen as WatchScreen.Home).phoneConnected)
        h.offline("watch")
        h.settle()
        assertFalse((c.state.value.screen as WatchScreen.Home).phoneConnected)
    }
}
