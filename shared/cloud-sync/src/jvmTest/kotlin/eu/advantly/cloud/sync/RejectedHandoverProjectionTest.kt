package eu.advantly.cloud.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.data.LocalData
import eu.advantly.data.ScoreSummary
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.scoreEvents
import eu.advantly.data.toStoredEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * H3 (Review): Der Server lehnt die Abgabe (`ScorerChanged` an Jonas) endgültig ab, das Handy gibt zurück und zählt weiter.
 * Echter Store und echte Outbox (Auswahl, Mapping, Auswertung, Ack) gegen einen Server, der `apply_projection` nachbildet
 * (eventCount höchstens so groß wie seine Events): Die Projektion kommt trotz des abgelehnten Events an.
 */
class RejectedHandoverProjectionTest {
    private val driver = createInMemoryJvmDriver()
    private val data = LocalData(driver)
    private val match = MatchId("match")
    private val phone = DeviceId("phone")
    private var lamport = 0L

    /** Server: angenommene Events und angewandter Stand der Projektion. */
    private val serverEvents = mutableSetOf<String>()
    private var serverProjection: ProjectionDto? = null

    @AfterTest
    fun tearDown() = driver.close()

    private fun emit(payload: EventPayload) {
        lamport++
        val event = ScoreEvent(EventId("e$lamport"), match, phone, lamport, lamport, lamport, null, payload)
        data.events.append(event.toStoredEvent())
        val engine = ScoringEngine.replay(data.events.scoreEvents(match))
        val summary = ScoreSummary.of(engine.state).copy(lastEventAtMs = lamport, eventCount = engine.events.size + 1)
        data.matches.updateProgress(match, engine.state.status, summary, lamport)
    }

    private fun respond(batch: PushBatch): PushResponse {
        val events =
            batch.events.map { row ->
                if (row.type == "ScorerChanged" && "jonas" in row.payload.toString()) {
                    ItemResult(row.id, "rejected", "scorer_target_forbidden")
                } else {
                    serverEvents += row.id
                    ItemResult(row.id, "accepted")
                }
            }
        val matches =
            batch.matches.map { dto ->
                val projection =
                    dto.projection?.let {
                        if (it.eventCount > serverEvents.size) {
                            "ahead_of_events"
                        } else {
                            serverProjection = it
                            "applied"
                        }
                    }
                ItemResult(dto.id, "ok", projection = projection)
            }
        return PushResponse(players = batch.players.map { ItemResult(it.id, "ok") }, matches = matches, events = events)
    }

    /** Ein Upload-Lauf wie der Runner: auswählen, abbilden, senden, auswerten, quittieren – bis nichts mehr ansteht. */
    private fun sync() {
        val session = data.cloudUploads.activate("alice")
        repeat(5) {
            val snapshot = data.cloudUploads.capture(session, 500)
            val batch = snapshot.toPushBatch()
            if (batch.events.isEmpty() && batch.matches.isEmpty() && batch.players.isEmpty()) return
            val response = respond(batch)
            val update = OutboxPlanner.evaluate(batch, response)
            data.cloudUploads.acknowledge(snapshot, update.uploaded, update.rejected, snapshot.acknowledgedMetadata(batch, response))
        }
    }

    @Test
    fun projectionIsUploadedAfterARejectedHandoverAndTheHandBack() {
        val me = PlayerId("me")
        val guest = PlayerId("guest")
        data.players.ensureSelf("Alex", 0) { me }
        data.accountIdentities.initialize(phone.value, me.value, "PHONE")
        data.players.addGuest(guest, "Bea", 0)
        data.cloudUploads.bindOwnedDevices(data.cloudUploads.activate("alice"), setOf(phone.value))
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = Lineup(listOf(me), listOf(guest), me)
        data.matches.insertIfAbsent(match, format, lineup, MatchMeta(), 1)
        emit(MatchCreated(format, lineup, scorerDeviceId = phone, createdBy = me))
        emit(PointWon(Side.A))
        sync()
        assertEquals(2, serverProjection?.eventCount)

        emit(ScorerChanged(DeviceId("jonas-phone")))
        sync()
        assertEquals(mapOf("e3" to "scorer_target_forbidden"), data.cloud.rejectedEvents())

        // Rückgabe (handBackScoring) und weitere Punkte des Handys
        emit(ScorerChanged(phone))
        emit(PointWon(Side.A))
        emit(PointWon(Side.B))
        sync()

        val applied = assertNotNull(serverProjection)
        assertEquals(serverEvents.size, applied.eventCount, "Projektion zählt nur die Events des Servers")
        assertEquals(5, applied.eventCount)
        assertEquals("in_progress", applied.status)
        assertEquals("15", applied.liveState?.get("points")?.let { (it as kotlinx.serialization.json.JsonArray)[1].toString().trim('"') })
    }
}
