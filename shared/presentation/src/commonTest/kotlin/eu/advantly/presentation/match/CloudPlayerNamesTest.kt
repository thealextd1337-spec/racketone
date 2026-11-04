package eu.advantly.presentation.match

import kotlin.test.Test
import kotlin.test.assertEquals

class CloudPlayerNamesTest {
    @Test
    fun slotNumbersFollowTheFixedPlace() {
        assertEquals(listOf(1, 2), listOf(slotNumber("A", 1, false), slotNumber("B", 1, false)))
        assertEquals(
            listOf(1, 2, 3, 4),
            listOf(slotNumber("A", 1, true), slotNumber("A", 2, true), slotNumber("B", 1, true), slotNumber("B", 2, true)),
        )
    }

    @Test
    fun unnamedUnlinkedPlacesBecomePlayerN() {
        assertEquals(CloudPlayerName.Slot(2), cloudPlayerName("?", "B", 1, doubles = false))
        assertEquals(CloudPlayerName.Slot(4), cloudPlayerName(" ", "B", 2, doubles = true))
        assertEquals(CloudPlayerName.Slot(1), cloudPlayerName(null, "A", 1, doubles = true))
        assertEquals(CloudPlayerName.Named("Bea"), cloudPlayerName(" Bea ", "B", 1, doubles = false))
    }

    @Test
    fun linkedPlacesKeepTheirNameAndRedactionWins() {
        assertEquals(CloudPlayerName.Named("?"), cloudPlayerName("?", "A", 2, doubles = true, linked = true))
        assertEquals(CloudPlayerName.Deleted, cloudPlayerName("?", "A", 1, doubles = false, deleted = true))
        assertEquals(CloudPlayerName.Deleted, cloudPlayerName("Anja", "B", 1, doubles = false, linked = true, deleted = true))
        // visitor_view kennt kein Flag: der Server-Snapshot-Name genügt.
        assertEquals(CloudPlayerName.Deleted, cloudPlayerName(SERVER_DELETED_NAME, "B", 2, doubles = true))
    }
}
