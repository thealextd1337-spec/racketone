package eu.advantly.presentation.friends

import eu.advantly.core.model.PlayerId
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.CloudPullPlayer
import eu.advantly.data.LocalData
import eu.advantly.data.LocalPlayer
import eu.advantly.data.PlayerKind
import eu.advantly.data.createInMemoryJvmDriver
import kotlin.test.Test
import kotlin.test.assertEquals

/** Review 0.6.3: Gast-Verknüpfungen erkennen „redigiert“ am ausdrücklichen Status, nie am Namen „Gelöschter Spieler“. */
class OwnGuestLinksTest {
    @Test
    fun redactionComesFromTheExplicitStatusNotFromTheName() {
        createInMemoryJvmDriver().use { driver ->
            val data = LocalData(driver)
            var index = 0
            data.accountIdentities.select("scope-alice", "alice", 0, "PHONE") { "alice-${index++}" }
            val named = data.players.addGuest(PlayerId("named"), "Gelöschter Spieler", 1).id
            val gone = data.players.addGuest(PlayerId("gone"), "Test1", 1).id
            val archived = data.players.addGuest(PlayerId("archived"), "Kim", 1).id
            data.players.addGuest(PlayerId("local-only"), "Lea", 1)

            fun pulled(
                id: PlayerId,
                name: String,
                profile: String?,
                deleted: Boolean = false,
            ) = CloudPullPlayer(
                id.value,
                1,
                """{"id":"${id.value}","kind":"guest","displayName":"$name","owned":true,
                    "profileId":${profile?.let { "\"$it\"" }},"deleted":$deleted,"serverVersion":1}""",
                true,
                LocalPlayer(id, name, PlayerKind.GUEST, 1, null),
            )
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            data.cloudPulls.commitMetadata(
                "alice",
                run,
                0,
                CloudPullMetadataPage(
                    emptyList(),
                    listOf(
                        pulled(named, "Gelöschter Spieler", "anja"),
                        pulled(gone, "Test1", null),
                        pulled(archived, "Kim", null, deleted = true),
                    ),
                    emptyList(),
                    1,
                    false,
                    1,
                ),
            ) {}
            // Redaktionsmanifest des Servers (Freund gelöscht): hier direkt eingetragen.
            driver.execute(
                null,
                "INSERT INTO replica_redacted_player(account_key, match_id, player_id, epoch) VALUES ('a', 'm', 'gone', 1)",
                0,
            )

            assertEquals(
                mapOf(
                    "named" to GuestLink("anja", redacted = false),
                    "gone" to GuestLink(null, redacted = true),
                    "archived" to GuestLink(null, redacted = true),
                ),
                data.ownGuestLinks("alice"),
            )
        }
    }
}
