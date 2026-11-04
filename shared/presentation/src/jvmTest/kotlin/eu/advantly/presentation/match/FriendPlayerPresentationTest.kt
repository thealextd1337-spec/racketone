package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.data.PlayerKind
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.PlayerPlace
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2-E: Freund im Assistenten – lokale Spieler-ID, die Profil-ID bleibt in der Kontozuordnung. */
class FriendPlayerPresentationTest {
    @Test
    fun wizardSeatsFriendUnderALocalIdThatIsReusedAndNeverCarriesTheProfileId() {
        val place = PlayerPlace(Side.B, 0)
        // Ohne ausgewähltes Konto gibt es keine Freunde.
        val offline = createInMemoryJvmLocalData().also { it.players.ensureSelf("Alex", 1) { PlayerId("me") } }
        val offlineStore =
            SessionMatchStore(MatchSession(offline, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 }))
        assertNull(MatchWizard(offlineStore).apply { selectSport(Sport.BADMINTON) }.assignFriend(place, "bob-profile", "Bob"))
        offlineStore.close()

        val data = createInMemoryJvmLocalData()
        var index = 0
        data.accountIdentities.select("scope-alice", "alice", 0, "PHONE") { "alice-${index++}" }
        data.players.ensureSelf("Alex", 1) { PlayerId("me") }
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE), { 10 })
        val store = SessionMatchStore(session)

        val wizard = MatchWizard(store).apply { selectSport(Sport.BADMINTON) }
        assertNull(wizard.assignFriend(place, "alice", "Alex"))
        assertNull(wizard.assignFriend(place, "bob-profile", " "))
        val bob = wizard.assignFriend(place, "bob-profile", " Bob ")!!
        assertNotEquals("bob-profile", bob.id.value)
        assertEquals("Bob", bob.displayName)
        assertTrue(wizard.isFriendSeated("bob-profile"))
        assertNull(wizard.assignFriend(place, "carol-profile", "Carol"))
        assertTrue(store.guests.value.isEmpty())
        assertTrue(wizard.playerSuggestions().isEmpty())
        assertFalse(store.canRename(bob.id))
        assertEquals(PlayerKind.FRIEND, data.players.get(bob.id)?.kind)
        assertEquals("bob-profile", data.players.friendProfile("alice", bob.id))

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
        // Lineup, Header und Event-Payloads (Grundlage von Uhr-Nachrichten und Export) kennen nur die lokale ID.
        assertFalse(
            data.matches
                .get(id)
                .toString()
                .contains("bob-profile"),
        )
        assertFalse(
            data.events
                .scoreEvents(id)
                .toString()
                .contains("bob-profile"),
        )

        // Zweites Match: derselbe Freund behält seine lokale ID (Verlauf/Statistik wie bei Gästen).
        val again = MatchWizard(store).apply { selectSport(Sport.BADMINTON) }
        assertEquals(bob.id, again.assignFriend(place, "bob-profile", "Bobby")?.id)
        assertTrue(store.guests.value.isEmpty())

        // Revanche/Favorit: der Freund sitzt schon über seine lokale ID – nicht erneut anbieten, Alias nicht anfassen.
        val rematch = MatchWizard(store).apply { selectSport(Sport.BADMINTON) }
        rematch.assignPlayer(
            place,
            store
                .record(id)!!
                .summary.setup
                .player(bob.id)!!,
        )
        assertTrue(rematch.isFriendSeated("bob-profile"))
        assertFalse(rematch.isFriendSeated("carol-profile"))
        assertNull(rematch.assignFriend(PlayerPlace(Side.A, 0), "bob-profile", "Renamed"))
        assertEquals("Bobby", data.players.get(bob.id)?.displayName)
        store.close()
    }

    /** plan-0.6.3 §3.4: Platzhalter nachträglich an „Du“ (ohne Namen) und an einen Freund; der Upload verknüpft den Freund. */
    @Test
    fun placeholderTakesTheUnnamedSelfOrAFriendWhoseProfileGoesUp() {
        val data = createInMemoryJvmLocalData()
        var index = 0
        data.accountIdentities.select("scope-alice", "alice", 0, "PHONE") { "alice-${index++}" }
        val me = data.players.ensureSelf("", 1) { PlayerId("me") }.id
        // Eigene Quelle des Kontos: nur deren Matches gehen hoch
        val source = data.accountIdentities.currentSources().single()
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId(source), PeerKind.PHONE), { 10 })
        val store = SessionMatchStore(session)
        val bea = store.addGuest("Bea")
        val slot = Placeholders.id("opponent")
        val id = session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(bea.id), listOf(slot), bea.id))

        assertNull(store.assignmentFailure(id, slot, me))
        assertTrue(store.assignPlayer(id, slot, me))
        val bob = store.addFriend("bob-profile", "Bob")!!
        assertTrue(store.assignPlayer(id, slot, bob.id))
        assertEquals(
            "Bob",
            store
                .record(id)
                ?.summary
                ?.setup
                ?.player(slot)
                ?.displayName,
        )

        val upload = data.cloudUploads.activate("alice").also { data.cloudUploads.bindOwnedDevices(it, setOf(source)) }
        val participant =
            data.cloudUploads
                .capture(upload, 100)
                .participants
                .single()
                .value.participants
                .single { it.team == "B" }
        assertEquals("bob-profile", participant.profileId)
        assertEquals("Bob", participant.displayName)
        store.close()
    }
}
