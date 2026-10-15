package eu.advantly.wear.match

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSessions
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.PlayerIds
import eu.advantly.wear.testLocalData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WatchDirectoryTest {
    private val data = testLocalData()
    private var now = 1_760_000_000_000L
    private val directory = WatchDirectory(data, { now }, PlayerIds()::next)

    @Test
    fun selfIsStableAndUnselectedPlacesNeverCreateGuests() {
        val self = directory.self()
        val opponent = directory.placeholder(PlayerSlot.OPPONENT_1)
        assertEquals(self, directory.self())
        assertNotEquals(opponent, directory.placeholder(PlayerSlot.OPPONENT_1))
        assertNotEquals(opponent, directory.placeholder(PlayerSlot.OPPONENT_2))
        assertNull("eigener Name leer: Anzeige als Du", directory.displayName(self))
        assertNull(directory.displayName(opponent))
        assertEquals(1, data.players.all().size)
        assertEquals(emptyMap<String, String>(), data.settings.all())
    }

    @Test
    fun lastSetupIsTheLatestOriginalMatchNotAFollowUp() {
        val session = MatchSessions.create(data, link = null, scheduler = ManualScheduler(), kind = PeerKind.WATCH, clock = { now })
        session.start()
        assertNull(directory.lastSetup())
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = SetupCatalog.lineup(Discipline.SINGLES, directory.lineupPlayers(), Side.B)
        session.create(format, lineup)
        now += 1_000
        // 6:0 6:0 (2 Sätze × 6 Spiele × 4 Punkte), dann Folge-Match: „Wie letztes Mal“ bleibt beim Best-of-Format
        repeat(2 * 6 * 4) { session.recordPoint(Side.A) }
        now += 1_000
        assertNotNull(session.createFollowUp())
        val last = directory.lastSetup()
        assertEquals(format, last?.format)
        assertEquals(lineup, last?.lineup)
        assertEquals(1, directory.recentResults().size)
        assertNotNull("Folge-Match läuft", directory.resumable())
    }
}
