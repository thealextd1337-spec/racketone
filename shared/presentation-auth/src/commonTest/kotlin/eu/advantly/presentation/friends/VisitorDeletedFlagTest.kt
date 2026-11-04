package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.VisitorTeamMember
import eu.advantly.cloud.sync.VisitorView
import kotlin.test.Test
import kotlin.test.assertEquals

/** Review 0.6.3: Zuschauer- und Live-Sicht reichen `deletedProfile` aus visitor_view durch; ältere Server liefern es nicht. */
class VisitorDeletedFlagTest {
    private fun watched(vararg teams: VisitorTeamMember) =
        VisitorView("m", "tennis", "singles", "in_progress", channel = "", teams = teams.toList()).toWatched().match

    @Test
    fun theServerFlagReachesTheSlots() {
        val match =
            watched(
                VisitorTeamMember("A", 1, "Gelöschter Spieler", deletedProfile = false),
                VisitorTeamMember("B", 1, "Anja", deletedProfile = true),
            )
        assertEquals(listOf(false), match.teamA.map { it.deleted })
        assertEquals(listOf(true), match.teamB.map { it.deleted })
        // Ohne Flag (Server vor 20261104000300) bleibt es offen: die Anzeige fällt auf den Namen zurück.
        assertEquals(listOf(null), watched(VisitorTeamMember("A", 1, "Bob")).teamA.map { it.deleted })
    }
}
