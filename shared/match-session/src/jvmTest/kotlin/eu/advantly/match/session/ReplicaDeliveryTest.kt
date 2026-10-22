package eu.advantly.match.session

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.LocalData
import eu.advantly.data.MatchRecord
import eu.advantly.data.RedactionCursor
import eu.advantly.data.RedactionPage
import eu.advantly.data.ReplicaAccess
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.ReplicaRedactionEvent
import eu.advantly.data.StoredEvent
import eu.advantly.sync.protocol.AccessAck
import eu.advantly.sync.protocol.AccessState
import eu.advantly.sync.protocol.DecodeResult
import eu.advantly.sync.protocol.MatchAccessLevel
import eu.advantly.sync.protocol.RedactedChunk
import eu.advantly.sync.protocol.RedactedChunkAck
import eu.advantly.sync.protocol.RedactionAck
import eu.advantly.sync.protocol.ReplicaControlMessage
import eu.advantly.sync.protocol.Summary
import eu.advantly.sync.protocol.SyncCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Access- und Redaktionszustellung Handy → Uhr über Protokoll 4 (Vertrag W3, E2/E3/E7–E9). Das Handy bekommt Rechte und
 * Redaktion über den echten Pull-Pfad (Katalog, Redaktionsseiten), die Uhr ausschließlich über die Zustellung.
 */
class ReplicaDeliveryTest {
    private val pair = LifecyclePair(V4)

    @AfterTest
    fun tearDown() = pair.close()

    private val guest = PlayerId("guest-secret")
    private val records = HashMap<MatchId, MatchRecord>()

    /** Eigenes Match am Handy mit Gast, der Uhr zugewiesen; Upload bestätigt. */
    private fun sharedMatch(): MatchId {
        pair.activate("alice")
        pair.phoneData.players.addGuest(guest, "Bea Secret", 0)
        val self =
            pair.phoneData.players
                .self()!!
                .id
        val match = pair.phone.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(guest), self))
        pair.settle()
        val upload = pair.phoneData.cloudUploads.capture(pair.token!!, 100)
        pair.phoneData.cloudUploads.acknowledge(upload, upload.events.mapTo(mutableSetOf()) { it.id }, metadata = upload.metadataRevisions)
        return match
    }

    /** Katalogseite des Pulls am Handy: Match bei G mit Stufe und Redaktionsepoche. */
    private fun catalog(
        match: MatchId,
        version: Long,
        generation: Long = 1,
        access: String = "member",
        epoch: Long = 0,
    ): Long {
        val data = pair.phoneData
        val profile = data.accountIdentities.profile!!
        val record = records.getOrPut(match) { checkNotNull(data.matches.get(match)) }
        val run = data.cloudPulls.beginRun(profile) {}.runSerial
        val entry = CloudPullMatch(match.value, version, """{"version":$version}""", access, generation, epoch, false, record)
        val page = CloudPullMetadataPage(listOf(entry), emptyList(), emptyList(), version, false, generation)
        if (!data.cloudPulls.commitMetadata(profile, run, data.cloudPulls.progress(profile).cursor, page) {}) {
            assertTrue(data.cloudPulls.commitMetadata(profile, run, data.cloudPulls.progress(profile).cursor, page) {})
        }
        return run
    }

    /** Redaktion (G, E) am Handy über den Pull anwenden: diese Spieler-IDs gelten als gelöscht. */
    private fun redact(
        run: Long,
        playerIds: List<String>,
    ) {
        val data = pair.phoneData
        val work = checkNotNull(data.cloudPulls.nextEventWork(data.accountIdentities.profile!!, run))
        val request = checkNotNull(data.cloudPulls.redactionWork(work, run))
        assertTrue(data.cloudPulls.stageRedaction(work, run, request, 0, playerIds, emptyList(), true, true) {})
    }

    private fun LocalData.accessOf(match: MatchId): ReplicaAccess? = replicas.access(replicas.captureContext(), match.value)

    private val phonePending: Int get() = pair.phone.state.value.sync.lifecyclePendingToPeers

    @Test
    fun offlineWatchDuringDeletionLosesTheNameOnReconnect() {
        val match = sharedMatch()
        assertEquals(
            "Bea Secret",
            pair.watchData.players
                .get(guest)
                ?.displayName,
            "Vorbedingung: Name liegt auf der Uhr",
        )
        assertEquals(null, pair.watchData.settings.get("replica.activated"), "Uhr vor der ersten Zustellung nicht aktiviert")
        catalog(match, 1)
        pair.settle()
        assertEquals("true", pair.watchData.settings.get("replica.activated"), "E2: aktiviert mit der ersten Access-Zustellung")
        assertEquals(0, phonePending, "Access bei G 1 zugestellt und bestätigt")
        assertEquals(ReplicaAccess(match.value, 1, ReplicaAccessLevel.MEMBER, true, 0), pair.watchData.accessOf(match))

        pair.network.setConnected("watch", false)
        redact(catalog(match, 2, epoch = 1), listOf(guest.value))
        pair.settle()
        assertEquals(
            "",
            pair.phoneData.players
                .get(guest)
                ?.displayName,
        )
        assertEquals(1, phonePending, "offline Uhr: Redaktion ausstehend")
        assertTrue(!pair.phone.state.value.sync.settled)

        pair.network.setConnected("watch", true)
        pair.settle()
        assertEquals(
            "",
            pair.watchData.players
                .get(guest)
                ?.displayName,
            "kein Klarname auf der Uhr",
        )
        assertEquals(0, phonePending)
        assertEquals(ReplicaAccess(match.value, 1, ReplicaAccessLevel.MEMBER, true, 1), pair.watchData.accessOf(match))
        val wire = pair.phoneLink.decoded().map { it.message }
        assertTrue(wire.any { it is AccessState } && wire.any { it is RedactedChunk }, "über Protokoll 4 zugestellt")
        assertTrue(
            pair.watchData.replicas
                .redactionProgress(pair.watchData.replicas.captureContext(), match.value)!!
                .completed,
        )
    }

    /** Mehrseitige Redaktion: 450 lange Spieler-IDs in drei Pull-Seiten (≤ 200 IDs je Data-Seite). */
    private fun redactMany(
        run: Long,
        count: Int = 450,
    ) {
        val data = pair.phoneData
        val work = checkNotNull(data.cloudPulls.nextEventWork(data.accountIdentities.profile!!, run))
        val ids = (0 until count).map { "p-${it.toString().padStart(3, '0')}-" + "x".repeat(100) }
        ids.chunked(200).forEachIndexed { i, page ->
            val request = checkNotNull(data.cloudPulls.redactionWork(work, run))
            val last = i == (count - 1) / 200
            assertEquals(last, data.cloudPulls.stageRedaction(work, run, request, 0, page, emptyList(), last, true) {})
        }
    }

    private fun tick() = pair.scheduler.advanceBy(31_000)

    private fun watchTransferCompleted(match: MatchId): Boolean? =
        pair.watchData.replicas
            .redactionProgress(pair.watchData.replicas.captureContext(), match.value)
            ?.completed

    @Test
    fun multiPageRedactionSurvivesALostPageAckAndLostCompletionAck() {
        val match = sharedMatch()
        catalog(match, 1)
        pair.settle()
        pair.phoneLink.lose = { it is RedactedChunk && it.index == 2L }
        pair.watchLink.lose = { (it is RedactedChunkAck && it.index == 1L) || it is RedactionAck }
        redactMany(catalog(match, 2, epoch = 1))
        pair.settle()
        val chunks = pair.phoneLink.decoded().mapNotNull { it.message as? RedactedChunk }
        assertEquals(listOf(0L, 1L, 2L), chunks.map { it.index }, "drei Wire-Seiten")
        assertEquals(false, watchTransferCompleted(match), "Seite 2 verloren: Transfer offen")
        assertEquals(1, phonePending)

        pair.phoneLink.lose = { false }
        pair.phoneLink.sent.clear()
        tick()
        val resent = pair.phoneLink.decoded().mapNotNull { (it.message as? RedactedChunk)?.index }
        assertEquals(listOf(1L, 2L), resent, "Wiederanlauf ab der ersten unbestätigten Seite")
        assertEquals(true, watchTransferCompleted(match))
        assertTrue(pair.watchLink.decoded().any { it.message is RedactionAck }, "Abschluss-Ack wurde gesendet (und verloren)")

        pair.watchLink.lose = { false }
        tick()
        tick()
        assertEquals(0, phonePending, "Abschluss über RedactionAck oder Summary.lifecycle (E8)")
        assertEquals(ReplicaAccess(match.value, 1, ReplicaAccessLevel.MEMBER, true, 1), pair.watchData.accessOf(match))
        assertEquals(0, pair.phone.state.value.sync.pendingToPeers, "Event-Acks unabhängig von der Redaktion")
    }

    @Test
    fun phoneRestartMidTransferResendsFromTheFirstPage() {
        val match = sharedMatch()
        catalog(match, 1)
        pair.settle()
        pair.phoneLink.lose = { it is RedactedChunk && it.index == 2L }
        redactMany(catalog(match, 2, epoch = 1))
        pair.settle()
        assertEquals(false, watchTransferCompleted(match))
        pair.phoneLink.lose = { false }
        pair.phoneLink.sent.clear()
        pair.restartPhone()
        val resent = pair.phoneLink.decoded().mapNotNull { (it.message as? RedactedChunk)?.index }
        assertEquals(listOf(0L, 1L, 2L), resent, "E3: nach Neustart ab Index 0, die Uhr nimmt Wiederholungen idempotent")
        assertEquals(true, watchTransferCompleted(match))
        assertEquals(0, phonePending)
    }

    @Test
    fun watchRestartMidTransferContinuesWithTheMissingPage() {
        val match = sharedMatch()
        catalog(match, 1)
        pair.settle()
        pair.phoneLink.lose = { it is RedactedChunk && it.index == 2L }
        redactMany(catalog(match, 2, epoch = 1))
        pair.settle()
        pair.phoneLink.lose = { false }
        pair.phoneLink.sent.clear()
        pair.restartWatch()
        val resent = pair.phoneLink.decoded().mapNotNull { (it.message as? RedactedChunk)?.index }
        assertEquals(listOf(2L), resent, "Wiederanlauf nach Reconnect ab der ersten unbestätigten Seite")
        assertEquals(true, watchTransferCompleted(match))
        assertEquals(0, phonePending)
    }

    @Test
    fun revocationAndRegrantFollowTheGeneration() {
        val match = sharedMatch()
        catalog(match, 1)
        pair.settle()
        assertEquals(
            match,
            pair.watch.state.value.match
                ?.id,
        )
        catalog(match, 2, generation = 2, access = "feed")
        pair.settle()
        assertEquals(ReplicaAccessLevel.FEED, pair.watchData.accessOf(match)?.access)
        assertEquals(null, pair.watch.state.value.match, "Entzug schließt das offene Match der Uhr")
        assertEquals(0, phonePending)
        catalog(match, 3, generation = 3, access = "member")
        pair.settle()
        assertEquals(ReplicaAccess(match.value, 3, ReplicaAccessLevel.MEMBER, true, 0), pair.watchData.accessOf(match))
        assertEquals(0, phonePending)
    }

    @Test
    fun lateEnvelopesAfterAnAccountSwitchChangeNothing() {
        val match = sharedMatch()
        pair.phoneLink.lose = { it is AccessState }
        catalog(match, 1)
        pair.settle()
        val late = pair.phoneLink.sent.filter { (SyncCodec.decode(it, V4) as? DecodeResult.Ok)?.message is AccessState }
        assertTrue(late.isNotEmpty())
        assertEquals(null, pair.watchData.accessOf(match))
        pair.phoneLink.lose = { false }

        pair.activate("bob")
        late.forEach { pair.watchLink.replay("phone", it) }
        pair.settle()
        assertEquals(null, pair.watchData.accessOf(match), "Konto B übernimmt keine Rechte aus Paketen von A")

        pair.activate("alice")
        late.forEach { pair.watchLink.replay("phone", it) }
        assertEquals(ReplicaAccess(match.value, 1, ReplicaAccessLevel.MEMBER, true, 0), pair.watchData.accessOf(match))
    }

    @Test
    fun controlPacketsWithoutAValidFenceChangeNothing() {
        val match = sharedMatch()
        catalog(match, 1)
        pair.settle()
        val bare = """{"type":"AccessState","v":4,"matchId":"${match.value}","generation":9,"access":"FEED"}"""
        pair.watchLink.replay("phone", bare.encodeToByteArray())
        val envelope =
            pair.watchLink
                .decoded()
                .last { it.identity != null }
                .identity!!
        // Envelope der Uhr selbst (Absender/Empfänger vertauscht) ist kein Beleg des Handys
        pair.watchLink.replay("phone", SyncCodec.encode(AccessState(match, 9, MatchAccessLevel.FEED), envelope))
        assertEquals(ReplicaAccess(match.value, 1, ReplicaAccessLevel.MEMBER, true, 0), pair.watchData.accessOf(match))
    }

    /** P2-2: Ohne Kontositzung (Neustart ohne `activateAccount`) stellt das Handy nichts zu und bestätigt nichts. */
    @Test
    fun aPhoneRestartWithoutItsAccountSessionDeliversAndAcknowledgesNothing() {
        val match = sharedMatch()
        pair.watchLink.lose = { it is AccessAck || (it is Summary && it.lifecycle.isNotEmpty()) }
        catalog(match, 1)
        pair.settle()
        assertEquals(ReplicaAccessLevel.MEMBER, pair.watchData.accessOf(match)?.access, "Vorbedingung: Uhr hat angewandt")
        assertEquals(1, phonePending, "Vorbedingung: Bestätigung der Uhr verloren")
        pair.watchLink.lose = { false }
        pair.phoneLink.sent.clear()
        pair.watchLink.sent.clear()

        pair.restartPhone(bindAccount = false)
        tick()
        tick()
        assertTrue(
            pair.watchLink.decoded().any { (it.message as? Summary)?.lifecycle?.containsKey(match.value) == true },
            "Vorbedingung: Stand der Uhr kam an",
        )
        assertTrue(pair.phoneLink.decoded().none { it.message is ReplicaControlMessage }, "ohne Kontositzung keine Zustellung")
        assertEquals(1, phonePending, "ohne Kontositzung bestätigt der Stand der Uhr nichts")

        pair.bindAccount()
        tick()
        assertEquals(0, phonePending, "mit Kontositzung wieder bestätigt")
    }

    /** P2-2: Ein Pairing-, Konto- oder Sitzungswechsel zwischen Empfang und Mutation lässt die Mutation scheitern. */
    @Test
    fun aSwitchBetweenReceiptAndMutationFailsTheFence() {
        val match = sharedMatch()
        catalog(match, 1)
        pair.settle()

        fun lastEnvelope(link: RecordingLink) = link.decoded().last { it.identity != null }.identity!!
        val phoneIn = pair.phone.incomingFence("watch", lastEnvelope(pair.watchLink))
        val phoneOut = pair.phone.outgoingFence("watch")
        val watchIn = pair.watch.incomingFence("phone", lastEnvelope(pair.phoneLink))
        phoneIn()
        phoneOut()
        watchIn()

        pair.phone.detachAccountSession()
        assertFailsWith<IllegalStateException>("Auth-Lease weg") { phoneIn() }
        assertFailsWith<IllegalStateException>("Auth-Lease weg") { phoneOut() }
        pair.bindAccount()
        phoneIn()
        phoneOut()

        pair.activate("bob")
        assertFailsWith<IllegalStateException>("Kontowechsel") { phoneIn() }
        assertFailsWith<IllegalStateException>("Kontowechsel") { phoneOut() }
        assertFailsWith<IllegalStateException>("neues Pairing der Uhr") { watchIn() }
    }

    /** Review P3-2 (Probe B): Das E9-Summary geht verloren, der Access ist bestätigt, das Match beendet. */
    @Test
    fun aLostLifecycleReportIsRepeatedByTheTickForAFinishedMatch() {
        val match = sharedMatch()
        pair.phone.endMatch(EndReason.ABANDONED)
        pair.settle()
        pair.phoneLink.lose = { it is Summary && it.lifecycle.isNotEmpty() }
        catalog(match, 1)
        pair.settle()
        assertEquals(ReplicaAccess(match.value, 1, ReplicaAccessLevel.MEMBER, false, 0), pair.watchData.accessOf(match), "Meldung verloren")
        assertEquals(0, phonePending, "Zustellung selbst bestätigt")

        pair.phoneLink.lose = { false }
        tick()
        assertEquals(ReplicaAccess(match.value, 1, ReplicaAccessLevel.MEMBER, true, 0), pair.watchData.accessOf(match), "ohne neues Hello")
        pair.phoneLink.sent.clear()
        tick()
        assertTrue(pair.phoneLink.decoded().none { (it.message as? Summary)?.lifecycle?.isNotEmpty() == true }, "nach dem Ack Ruhe im Takt")
    }

    /** Review P3-5: Ein Redaktionselement, das keine Wire-Seite fasst, geht nie auf den Draht und ist sichtbar blockiert. */
    @Test
    fun anOversizedRedactionItemIsBlockedAndNeverSent() {
        val match = sharedMatch()
        catalog(match, 1)
        pair.settle()
        val data = pair.phoneData
        val run = catalog(match, 2, epoch = 1)
        val work = checkNotNull(data.cloudPulls.nextEventWork(data.accountIdentities.profile!!, run))
        val request = checkNotNull(data.cloudPulls.redactionWork(work, run))
        assertTrue(data.cloudPulls.stageRedaction(work, run, request, 1, emptyList(), listOf(oversizedRedaction(match)), true, true) {})
        pair.settle()
        tick()
        val sync = pair.phone.state.value.sync
        assertTrue(sync.lifecycleBlocked && !sync.settled, "blockiert statt still ausstehend")
        assertTrue(pair.phoneLink.decoded().none { it.message is RedactedChunk })
    }

    /** Größtes Redaktionsevent, dessen Data-Seite (≤ 64 KiB ohne Hülle) Data noch annimmt; samt Envelope zu groß. */
    private fun oversizedRedaction(match: MatchId): ReplicaRedactionEvent {
        val assigned = PlayerAssigned(PlayerId("slot"), Player(PlayerId("guest-x"), "Gelöschter Spieler"), PlayerId("me"))
        val payload = SyncCodec.json.encodeToString(EventPayload.serializer(), assigned).dropLast(1)

        fun event(pad: Int) =
            ReplicaRedactionEvent(
                StoredEvent("huge", match.value, "phone-x", 1, 1, "$payload,\"pad\":\"${"x".repeat(pad)}\"}", 1, null, "PlayerAssigned"),
                1,
            )

        fun pageBytes(pad: Int) =
            SyncCodec.json
                .encodeToString(
                    RedactionPage.serializer(),
                    RedactionPage(0, RedactionCursor(null, 0), RedactionCursor(null, 1), emptyList(), listOf(event(pad)), true, true),
                ).encodeToByteArray()
                .size
        return event(60_000 + 64 * 1024 - 32 - pageBytes(60_000))
    }

    /** 90e2746: Wire-Seiten je lokalem Transfer; Konto B bekommt für gleiches Match, G und E seine eigenen Seiten ab 0. */
    @Test
    fun anotherAccountGetsItsOwnPagesForTheSameMatchGenerationAndEpoch() {
        val match = sharedMatch()
        catalog(match, 1)
        pair.settle()
        redactMany(catalog(match, 2, epoch = 1))
        pair.settle()
        assertEquals(true, watchTransferCompleted(match), "Konto A vollständig")

        pair.activate("bob")
        pair.phoneLink.sent.clear()
        redactMany(catalog(match, 1, epoch = 1), count = 5)
        pair.settle()
        val chunks = pair.phoneLink.decoded().mapNotNull { it.message as? RedactedChunk }
        assertEquals(listOf(0L), chunks.map { it.index }, "eigener Transfer ab Index 0")
        assertEquals(5, chunks.single().playerIds.size, "Seiten von B, nicht aus dem Cache von A")
        assertEquals(true, watchTransferCompleted(match))
    }

    @Test
    fun anOldWatchStateNeverReuploadsAndCatchesUpAfterTheRedaction() {
        val match = sharedMatch()
        catalog(match, 1)
        pair.settle()
        assertTrue(pair.watch.takeOverScoring())
        pair.settle()
        pair.network.setConnected("watch", false)
        assertTrue(pair.watch.recordPoint(Side.A))
        val offlinePoint =
            pair.watchData.events
                .eventsFor(match.value)
                .last()
        redact(catalog(match, 2, epoch = 1), listOf(guest.value))
        pair.phoneLink.lose = { it is RedactedChunk }
        pair.network.setConnected("watch", true)
        pair.settle()
        assertTrue(
            pair.phoneData.events
                .eventsFor(match.value)
                .none { it.id == offlinePoint.id },
            "Stand E 0 wird nicht angenommen",
        )
        assertTrue(pair.watch.state.value.sync.pendingToPeers > 0)

        pair.phoneLink.lose = { false }
        tick()
        tick()
        assertTrue(
            pair.phoneData.events
                .eventsFor(match.value)
                .any { it.id == offlinePoint.id },
            "nach der Redaktion angenommen",
        )
        assertEquals(0, phonePending)
    }

    private companion object {
        val V4 = 3..4
    }
}
