package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Sport
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AccountIdentityPresentationTest {
    @Test
    fun existingStoreAndWizardFollowAccountSelfAndDiscardThePreviousDraft() {
        val data = createInMemoryJvmLocalData()
        val session = MatchSession(data, null, ManualScheduler(), SessionConfig(DeviceId("phone"), PeerKind.PHONE))
        val store = SessionMatchStore(session)
        val wizard = MatchWizard(store)
        val legacy = store.self.value.id

        fun fill() {
            wizard.selectSport(Sport.TENNIS)
            wizard.selectSurface(null)
            wizard.addGuest("Opponent")
        }
        fill()
        assertNotNull(wizard.finish())
        session.activateAccount("alice", data.cloudUploads.activate("alice"))
        val alice = store.self.value.id
        assertNotEquals(legacy, alice)
        assertNull(wizard.finish())
        fill()
        val first = store.start(assertNotNull(wizard.finish()), StartOptions())
        assertEquals(
            alice,
            data.matches
                .get(first)
                ?.lineup
                ?.a
                ?.first(),
        )
        store.saveFavorite(assertNotNull(wizard.finish()))
        assertEquals(
            alice,
            store.favorites.value
                .single()
                .setup.lineup.a
                .first(),
        )
        val historical = data.events.eventsFor(first.value)
        session.activateAccount("bob", data.cloudUploads.activate("bob"))
        val bob = store.self.value.id
        assertNotEquals(alice, bob)
        assertEquals(emptyList(), store.favorites.value)
        assertNull(store.lastSetup.value)
        assertNull(wizard.finish())
        fill()
        val second = store.start(assertNotNull(wizard.finish()), StartOptions())
        assertEquals(
            bob,
            data.matches
                .get(second)
                ?.lineup
                ?.a
                ?.first(),
        )
        session.activateAccount("alice", data.cloudUploads.activate("alice"))
        assertEquals(alice, store.self.value.id)
        assertEquals(
            alice,
            store.favorites.value
                .single()
                .setup.lineup.a
                .first(),
        )
        assertNull(wizard.finish())
        fill()
        val third = store.start(assertNotNull(wizard.finish()), StartOptions())
        assertEquals(
            alice,
            data.matches
                .get(third)
                ?.lineup
                ?.a
                ?.first(),
        )
        assertEquals(historical, data.events.eventsFor(first.value))
        wizard.close()
        store.close()
    }
}
