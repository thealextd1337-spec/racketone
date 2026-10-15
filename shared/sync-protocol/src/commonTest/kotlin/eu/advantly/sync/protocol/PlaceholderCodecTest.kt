package eu.advantly.sync.protocol

import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.PlayerAssigned
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderCodecTest {
    @Test
    fun assignmentsDirectoryAndMigrationRegistryRoundTrip() {
        val slot = Placeholders.id("opponent")
        val person = Player(PlayerId("bea"), "Bea")
        val messages =
            listOf(
                Events(MATCH, listOf(ev("assignment", "phone", 2, 3, "g", PlayerAssigned(slot, person, PlayerId("owner"))))),
                Events(
                    MATCH,
                    listOf(
                        ev(
                            "release",
                            "phone",
                            3,
                            4,
                            "assignment",
                            PlayerAssigned(
                                PlayerId("legacy"),
                                null,
                                PlayerId("owner"),
                                legacySlot = true,
                                creatorAlias = PlayerId("old-self"),
                            ),
                        ),
                    ),
                ),
                MatchMetaMessage(placeholderIds = listOf(PlayerId("legacy")), recentPlayers = listOf(person)),
            )
        messages.forEach { assertEquals(DecodeResult.Ok(it), SyncCodec.decode(SyncCodec.encode(it))) }
        assertEquals(CompatibilityStatus.PEER_TOO_OLD, ProtocolVersion.negotiate(2, 2).status)
        assertEquals(CompatibilityStatus.PEER_TOO_NEW, ProtocolVersion.negotiate(3, 3, 2, 2).status)
    }
}
