package eu.advantly.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OwnedSourceCatalogTest {
    @Test
    fun catalogRequiresBothProvenanceAndExpectedOwnerAndIncludesRemoteWatchWithoutWrites() {
        val driver = createInMemoryJvmDriver()
        try {
            val data = LocalData(driver)
            val identities = data.accountIdentities
            identities.initialize("phone", "self", "PHONE")
            identities.recordSource("watch", "watch-self", "WATCH", "other-scope", false)
            identities.recordSource("unbound", "unbound-self", "WATCH", "legacy:phone", false)
            identities.recordSource("foreign", "foreign-self", "WATCH", "legacy:phone", false)
            val alice = data.cloudUploads.activate("alice")
            data.cloudUploads.bindOwnedDevices(alice, setOf("phone", "watch", "owner-without-provenance"))
            val bob = data.cloudUploads.activate("bob")
            data.cloudUploads.bindOwnedDevices(bob, setOf("foreign"))
            data.events.append(StoredEvent("received", "match", "unbound", 1, 1, "payload"))
            val before = dump(driver)
            repeat(3) {
                assertEquals(
                    listOf(OwnedSourceIdentity("phone", "PHONE"), OwnedSourceIdentity("watch", "WATCH")),
                    identities.ownedSources("alice"),
                )
                assertEquals(listOf(OwnedSourceIdentity("foreign", "WATCH")), identities.ownedSources("bob"))
                assertEquals(emptyList(), identities.ownedSources("unknown"))
            }
            assertEquals(before, dump(driver))
            assertFailsWith<IllegalArgumentException> { identities.ownedSources("") }
        } finally {
            driver.close()
        }
    }

    private fun dump(driver: app.cash.sqldelight.db.SqlDriver): List<String?> =
        driver
            .executeQuery(
                null,
                "SELECT group_concat(device_id || ':' || profile_id) FROM cloud_device_owner UNION ALL " +
                    "SELECT group_concat(device_id || ':' || self_id || ':' || kind || ':' || scope_id) FROM local_source UNION ALL " +
                    "SELECT group_concat(id || ':' || payload || ':' || confirmed || ':' || cloud_state) FROM match_event",
                { cursor ->
                    app.cash.sqldelight.db.QueryResult
                        .Value(buildList { while (cursor.next().value) add(cursor.getString(0)) })
                },
                0,
            ).value
}
