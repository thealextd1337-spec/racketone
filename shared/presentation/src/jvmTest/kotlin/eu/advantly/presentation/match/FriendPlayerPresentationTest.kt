package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.data.PlayerKind
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.PlayerPlace
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2-E: Freund im Assistenten – Platz mit Profil-ID, kein Gast, Name bleibt im Verlauf erhalten. */
class FriendPlayerPresentationTest {
    @Test
    fun wizardSeatsFriendByProfileIdWithoutCreatingAGuestAndHistoryKeepsTheName() {
        val data = createInMemoryJvmLocalData()
        val me = data.players.ensureSelf("Alex", 1) { PlayerId("me") }.id
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })
        val store = SessionMatchStore(session)
        val wizard = MatchWizard(store).apply { selectSport(Sport.BADMINTON) }
        val place = PlayerPlace(Side.B, 0)
        assertNull(wizard.assignFriend(place, "me", "Alex"))
        assertNull(wizard.assignFriend(place, "bob-profile", " "))
        val bob = Player(PlayerId("bob-profile"), "Bob")
        assertEquals(bob, wizard.assignFriend(place, "bob-profile", " Bob "))
        assertEquals(bob, wizard.state.value.input.teamB[0])
        // Belegter Platz bzw. schon gesetzter Freund: keine zweite Besetzung.
        assertNull(wizard.assignFriend(place, "carol-profile", "Carol"))
        assertTrue(store.guests.value.isEmpty())
        assertTrue(wizard.playerSuggestions().isEmpty())
        assertFalse(store.canRename(bob.id))
        assertEquals(PlayerKind.FRIEND, data.players.get(bob.id)?.kind)

        val setup = wizard.finish()!!
        val id = session.create(setup.format, setup.lineup)
        assertEquals(
            "Bob",
            store
                .record(id)
                ?.summary
                ?.setup
                ?.player(bob.id)
                ?.displayName,
        )
        assertTrue(store.guests.value.isEmpty())
        store.close()
    }
}
