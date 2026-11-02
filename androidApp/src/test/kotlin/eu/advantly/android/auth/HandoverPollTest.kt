package eu.advantly.android.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class HandoverPollTest {
    private var now = 0L
    private val scheduled = mutableListOf<Long>()
    private val poll = HandoverPoll({ now }, { scheduled += it })

    @Test fun pollsEveryFiveSecondsWhileAHandoverIsOpenForAtMostTenMinutes() {
        poll.afterSync(open = false)
        assertEquals(emptyList<Long>(), scheduled)
        poll.afterSync(open = true)
        now += 5_000
        poll.afterSync(open = true)
        assertEquals(listOf(5_000L, 5_000L), scheduled)

        now += HandoverPoll.LIMIT_MS
        poll.afterSync(open = true)
        assertEquals(2, scheduled.size)

        // neue Aktion: Frist beginnt neu
        poll.restart()
        poll.afterSync(open = true)
        assertEquals(3, scheduled.size)
        // Wechsel gesehen: keine Abfrage mehr, nächste Übergabe beginnt frisch
        poll.afterSync(open = false)
        now += HandoverPoll.LIMIT_MS
        poll.afterSync(open = true)
        assertEquals(4, scheduled.size)
    }
}
