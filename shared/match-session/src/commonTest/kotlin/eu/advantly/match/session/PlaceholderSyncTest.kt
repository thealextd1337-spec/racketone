package eu.advantly.match.session

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.data.assignments
import eu.advantly.data.recentPeople
import eu.advantly.data.scoreEvents
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaceholderSyncTest {
    @Test
    fun displayOwnerAssignsFinishedWatchMatchOfflineAndRetryIsIdempotent() {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = c["phone"]
        val watch = c["watch"]
        val me = PlayerId("me")
        phone.data.players.ensureSelf("Alex", 1) { me }
        watch.data.players.ensureSelf("Alex", 1) { me }
        val slot = Placeholders.id("b1")
        val id = watch.session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(slot), me))
        repeat(15) { watch.session.recordPoint(Side.A) }
        c.settle()
        val before = watch.score
        val bea = phone.data.players.addGuest(PlayerId("bea"), "Bea", 1)
        assertFalse(phone.match.isScorer)
        c.offline("phone")
        phone.session.closeMatch()
        val count = phone.data.events.count(id.value)
        assertTrue(phone.session.assignPlayer(id, slot, bea.id))
        assertTrue(phone.session.assignPlayer(id, slot, bea.id))
        assertEquals(count + 1, phone.data.events.count(id.value))
        assertEquals(bea.id, phone.data.assignments(id)[slot]?.id)
        c.online("phone")
        c.settle()
        assertEquals(before, watch.score)
        assertEquals(
            phone.data.events
                .scoreEvents(id)
                .toSet(),
            watch.data.events
                .scoreEvents(id)
                .toSet(),
        )
        assertEquals(bea.id, watch.data.assignments(id)[slot]?.id)
        assertTrue(phone.data.recentPeople().any { it.id == bea.id })
        val stranger = PlayerId("stranger")
        phone.data.players.adoptSelf(
            eu.advantly.core.model
                .Player(stranger, "Stranger"),
            2,
        )
        assertFalse(phone.session.canAssignPlayer(id))
    }

    @Test
    fun phoneReceivesNamesAndMigrationRegistryWithoutAnyActiveMatch() {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = c["phone"]
        val watch = c["watch"]
        val legacy = PlayerId("old-opponent")
        watch.data.players.addGuest(legacy, "Gegner 1", 1)
        watch.data.settings.put("wear.guest.OPPONENT_1", legacy.value)
        phone.data.players.addGuest(legacy, "Gegner 1", 1)
        watch.data.migrateWatchPlaceholders()
        c.offline("watch")
        c.settle()
        c.online("watch")
        c.settle()
        assertTrue(phone.data.isPlaceholder(legacy))
        assertTrue(
            phone.data.players
                .all()
                .none { it.id == legacy },
        )
        val me =
            phone.data.players
                .ensureSelf("Alex", 1) { PlayerId("me") }
                .id
        val bea =
            phone.data.players
                .addGuest(PlayerId("bea"), "Bea", 1)
                .id
        val matchId = phone.session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(bea), me))
        phone.session.closeMatch()
        phone.session.syncNow()
        c.settle()
        assertEquals("Bea", assertNotNull(watch.data.players.get(bea)).displayName)
        assertTrue(
            watch.data.settings
                .get("players.recentRemote")
                .orEmpty()
                .contains("bea"),
        )
        phone.session.deleteMatch(matchId)
        phone.session.syncNow()
        c.settle()
        assertEquals("", watch.data.settings.get("players.recentRemote"))
    }

    @Test
    fun migratedOldWatchMatchKeepsItsLogAndScoreAndCanBeAssignedOnPhone() {
        val c = Cluster("phone" to PeerKind.PHONE, "watch" to PeerKind.WATCH)
        val phone = c["phone"]
        val watch = c["watch"]
        val me = PlayerId("owner")
        phone.data.players.ensureSelf("Alex", 1) { me }
        watch.data.players.ensureSelf("Alex", 1) { me }
        val slot = PlayerId("legacy")
        watch.data.players.addGuest(slot, "Opponent 1", 1)
        watch.data.settings.put("wear.guest.OPPONENT_1", slot.value)
        val id = watch.session.create(Presets.BADMINTON_1GAME_15.format(), Lineup(listOf(me), listOf(slot), me))
        watch.session.recordPoint(Side.B)
        c.settle()
        val originalLog = watch.data.events.scoreEvents(id)
        val originalScore = watch.score
        assertEquals(
            "Opponent 1",
            phone.data.players
                .get(slot)
                ?.displayName,
        )
        watch.data.migrateWatchPlaceholders()
        watch.session.syncNow()
        c.settle()
        assertTrue(phone.data.isPlaceholder(slot))
        assertEquals(originalLog, phone.data.events.scoreEvents(id))
        assertEquals(originalScore, phone.score)
        val real = phone.data.players.addGuest(PlayerId("bea"), "Bea", 1)
        assertTrue(phone.session.assignPlayer(id, slot, real.id))
        c.settle()
        assertEquals(real.id, watch.data.assignments(id)[slot]?.id)
        assertEquals(originalScore, watch.score)
        assertEquals(
            originalLog,
            watch.data.events
                .scoreEvents(id)
                .filterNot { it.payload is eu.advantly.core.scoring.PlayerAssigned },
        )
    }
}
