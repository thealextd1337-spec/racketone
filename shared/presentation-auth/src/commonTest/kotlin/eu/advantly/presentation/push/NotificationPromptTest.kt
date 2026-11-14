package eu.advantly.presentation.push

import eu.advantly.presentation.friends.FriendEntry
import eu.advantly.presentation.friends.FriendsState
import eu.advantly.presentation.friends.liveMatch
import eu.advantly.presentation.friends.toLiveEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPromptTest {
    private class Store(
        override var stage: PromptStage = PromptStage.NEW,
    ) : NotificationPromptStore {
        override var laterAtMs = 0L
        override var hintHiddenAtMs = 0L
    }

    private var clock = 1_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun momentNeedsFriendsRequestsOrLive() {
        assertFalse(notificationMoment(FriendsState()))
        assertFalse(notificationMoment(FriendsState(live = emptyList())))
        val someone = listOf(FriendEntry("p1", "Alex"))
        assertTrue(notificationMoment(FriendsState(friends = someone)))
        assertTrue(notificationMoment(FriendsState(incoming = someone)))
        assertTrue(notificationMoment(FriendsState(outgoing = someone)))
        assertTrue(notificationMoment(FriendsState(live = listOfNotNull(liveMatch("m1").toLiveEntry()))))
    }

    @Test fun firstMomentOpensThePromptOnce() {
        val store = Store()
        val prompt = NotificationPrompt(store) { clock }
        prompt.onMoment(displayOn = true)
        assertFalse(prompt.prompt.value)
        prompt.onMoment(displayOn = false)
        assertTrue(prompt.prompt.value)
        prompt.decided()
        assertFalse(prompt.prompt.value)
        prompt.onMoment(displayOn = false)
        assertFalse(prompt.prompt.value)
        assertEquals(PromptStage.DONE, store.stage)
    }

    @Test fun laterReturnsOnceAfterFourteenDays() {
        val store = Store()
        val prompt = NotificationPrompt(store) { clock }
        prompt.onMoment(false)
        prompt.later()
        assertEquals(PromptStage.LATER, store.stage)
        clock += 14 * day - 1
        prompt.onMoment(false)
        assertFalse(prompt.prompt.value)
        clock += 1
        prompt.onMoment(false)
        assertTrue(prompt.prompt.value)
        prompt.later()
        assertEquals(PromptStage.DONE, store.stage)
        clock += 100 * day
        prompt.onMoment(false)
        assertFalse(prompt.prompt.value)
    }

    @Test fun deniedNeverReturnsAutomatically() {
        val store = Store()
        val prompt = NotificationPrompt(store) { clock }
        prompt.onMoment(false)
        prompt.denied()
        prompt.decided()
        assertEquals(PromptStage.DENIED, store.stage)
        clock += 365 * day
        prompt.onMoment(false)
        assertFalse(prompt.prompt.value)
    }

    @Test fun hintHidesForThirtyDaysAndOnlyWhileOff() {
        val prompt = NotificationPrompt(Store(PromptStage.DONE)) { clock }
        assertTrue(prompt.hintVisible(displayOn = false))
        assertFalse(prompt.hintVisible(displayOn = true))
        prompt.hideHint()
        clock += 30 * day - 1
        assertFalse(prompt.hintVisible(false))
        clock += 1
        assertTrue(prompt.hintVisible(false))
    }
}
