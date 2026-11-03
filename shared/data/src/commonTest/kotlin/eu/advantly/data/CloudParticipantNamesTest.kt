package eu.advantly.data

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.db.AdvantlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Staging-Befund 03.11.2026: Der Server lehnt Teilnehmer mit leerem Namen ab (`match_participants_display_name_check`)
 * und rollt damit das ganze Match zurück. Kein nicht-redigierter Platz darf deshalb ohne Namen hochgeladen werden.
 */
class CloudParticipantNamesTest {
    private val driver = createTestDriver()
    private val data = LocalData(driver)
    private val me = PlayerId("phone-self")

    private fun phone() {
        // Wie die App: der Self-Player entsteht ohne Namen (die UI zeigt „Du“).
        data.players.ensureSelf("", 0) { me }
        data.accountIdentities.initialize("phone", me.value, "PHONE")
        data.cloudUploads.activate("alice").also { data.cloudUploads.bindOwnedDevices(it, setOf("phone")) }
    }

    private fun createMatch(lineup: Lineup) {
        val format =
            if (lineup.a.size == 2) Presets.TENNIS_DOUBLES_MTB10.format() else Presets.TENNIS_SINGLES_BO3.format()
        data.events.append(
            ScoreEvent(
                EventId("genesis"),
                MatchId("match"),
                DeviceId("phone"),
                1,
                1,
                1,
                null,
                MatchCreated(format, lineup, createdBy = me),
            ).toStoredEvent(),
        )
        data.matches.insertIfAbsent(MatchId("match"), format, lineup, MatchMeta(), 1)
    }

    private fun names() =
        data.cloud
            .uploadParticipants("match", "alice")!!
            .participants
            .associate { it.slotId to it.displayName }

    @Test
    fun watchQuickMatchWithOnlyPlaceholdersHasNoBlankName() {
        phone()
        val opponent = Placeholders.id("b1")
        createMatch(Lineup(listOf(me), listOf(opponent), me))
        val names = names()
        // Self ohne Namen und Platzhalter: sprachneutraler Ersatz, den der Server ebenso einsetzt.
        assertEquals(mapOf(me.value to UNNAMED_PARTICIPANT, opponent.value to UNNAMED_PARTICIPANT), names)
    }

    @Test
    fun mixedSlotsKeepRealNamesAndNeverSendBlankOnes() {
        phone()
        val guest = PlayerId("guest")
        val unknown = PlayerId("unknown")
        val partner = Placeholders.id("a2")
        data.players.addGuest(guest, "Beatrice", 0)
        createMatch(Lineup(listOf(me, partner), listOf(guest, unknown), me))
        val names = names()
        assertEquals("Beatrice", names.getValue(guest.value))
        assertEquals(listOf(me, partner, unknown).associate { it.value to UNNAMED_PARTICIPANT }, names - guest.value)
    }

    @Test
    fun redactedSlotStaysBlankForServerReplacement() {
        phone()
        val guest = PlayerId("guest")
        data.players.addGuest(guest, "Beatrice", 0)
        createMatch(Lineup(listOf(me), listOf(guest), me))
        AdvantlyDatabase(driver).replicaLifecycleQueries.redactPlayer("another-account", "another-match", guest.value, 1)
        val names = names()
        // Absichtlich leer: der Server setzt dafür „Gelöschter Spieler“ bzw. seinen Platzhalter.
        assertEquals("", names.getValue(guest.value))
        assertEquals(UNNAMED_PARTICIPANT, names.getValue(me.value))
    }
}
