package eu.advantly.wear.live

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.PeerInfo
import eu.advantly.match.session.SyncIndicator
import eu.advantly.match.session.SyncStatus
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.testLocalData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveModelTest {
    private val now = 1_760_000_100_000L

    private fun sync(
        pending: Int = 0,
        peers: Boolean = true,
        scorerConnected: Boolean = true,
        lastEvent: Long? = null,
        lastSync: Long? = null,
    ) = SyncStatus(
        link = if (peers) LinkState.CONNECTED else LinkState.DISCONNECTED,
        peers = if (peers) listOf(PeerInfo("node", DeviceId("phone"), PeerKind.PHONE, CompatibilityStatus.COMPATIBLE)) else emptyList(),
        indicator = if (peers) SyncIndicator.CONNECTED else SyncIndicator.LOCAL_ONLY,
        scorerConnected = scorerConnected,
        pendingToPeers = pending,
        lastEventReceivedAtMs = lastEvent,
        lastSyncAtMs = lastSync,
    )

    @Test
    fun scorerShowsSyncedPendingOrLocalOnly() {
        assertEquals(SyncUi.Synced, LiveModel.syncUi(LiveRole.SCORER, sync(), now))
        assertEquals(SyncUi.Pending(4), LiveModel.syncUi(LiveRole.SCORER, sync(pending = 4, peers = false), now))
        assertEquals(SyncUi.LocalOnly, LiveModel.syncUi(LiveRole.SCORER, sync(peers = false), now))
    }

    @Test
    fun displayShowsLiveOrSecondsSinceLastContact() {
        assertEquals(SyncUi.Live, LiveModel.syncUi(LiveRole.DISPLAY, sync(), now))
        val stale = sync(scorerConnected = false, lastEvent = now - 42_500, lastSync = now - 50_000)
        assertEquals(SyncUi.Stale(42), LiveModel.syncUi(LiveRole.DISPLAY, stale, now))
        assertEquals(SyncUi.Stale(null), LiveModel.syncUi(LiveRole.DISPLAY, sync(scorerConnected = false), now))
    }

    @Test
    fun takeOverIsOfferedOnlyToDisplaysAfterThirtySeconds() {
        val session =
            MatchSessions.create(
                testLocalData(),
                link = null,
                scheduler = ManualScheduler(),
                kind = PeerKind.WATCH,
                clock = { now },
            )
        session.start()
        val lineup = Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a"))
        session.create(
            Presets.TENNIS_SINGLES_BO3.format(),
            lineup,
            scorer =
                eu.advantly.match.session.ScorerChoice
                    .Device(DeviceId("phone")),
        )
        val match = session.state.value.match!!
        val disconnected = sync(scorerConnected = false)

        fun offered(since: Long?) = LiveModel.build(match, disconnected, { null }, now, since).takeOverOffered
        assertFalse(offered(null))
        assertFalse(offered(now - 29_999))
        assertTrue(offered(now - 30_000))
        assertFalse("verbunden", LiveModel.build(match, sync(), { null }, now, now - 60_000).takeOverOffered)
        assertEquals(LiveRole.DISPLAY, LiveModel.build(match, disconnected, { null }, now, null).role)
    }

    @Test
    fun scoreTextsForToastAndOngoingActivity() {
        val session =
            MatchSessions.create(
                testLocalData(),
                link = null,
                scheduler = ManualScheduler(),
                kind = PeerKind.WATCH,
                clock = { now },
            )
        session.start()
        val lineup = Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a"))
        session.create(Presets.TENNIS_SINGLES_BO3.format(), lineup)
        repeat(4) { session.recordPoint(Side.A) } // 1:0 Spiele
        repeat(3) { session.recordPoint(Side.A) }
        repeat(4) { session.recordPoint(Side.B) } // AD für B
        val state =
            session.state.value.match!!
                .state
        assertEquals("40:AD", LiveModel.scoreText(state))
        assertEquals("1:0 · 40:AD", LiveModel.ongoingText(session.state.value.match))
        assertEquals("", LiveModel.ongoingText(null))
    }
}
