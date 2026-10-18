package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.PlayerAssigned
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReplicaLifecycleCodecTest {
    private val local = LocalMatchStamp(DeviceId("phone"), EventId("g"), "scope")
    private val cloud = CloudMatchStamp(7, 3)
    private val identity = IdentityEnvelope(OwnerContext("phone", 2, "account-scope"), "sender", "recipient")
    private val rawPayload = "  {\"type\":\"PlayerAssigned\", \"unknown\":null, \"extra\":{\"future\":true}}\n"

    private fun row(seq: Long = 1): RedactionEvent =
        RedactionEvent(EventId("e$seq"), MATCH, DeviceId("phone"), seq, seq, 42, null, "PlayerAssigned", rawPayload, seq)

    private fun chunk(): RedactedChunk =
        RedactedChunk(
            "transfer",
            MATCH,
            7,
            3,
            10,
            0,
            RedactionCursor(),
            RedactionCursor(PlayerId("b"), 2),
            listOf(PlayerId("a"), PlayerId("b")),
            listOf(row(), row(2)),
            true,
            true,
        )

    private fun directRoundTrip(message: SyncMessage): String {
        val wire = SyncCodec.json.encodeToString(SyncMessage.serializer(), message)
        assertEquals(message, SyncCodec.json.decodeFromString(SyncMessage.serializer(), wire))
        return wire
    }

    @Test
    fun allControlTypesRoundTripWithStableNamesAndVersion() {
        val controls =
            MatchAccessLevel.entries.flatMap {
                listOf("AccessState" to AccessState(MATCH, 7, it), "AccessAck" to AccessAck(MATCH, 7, it))
            } +
                listOf(
                    "RedactedChunk" to chunk(),
                    "RedactedChunkAck" to RedactedChunkAck("transfer", MATCH, 7, 3, 0),
                    "RedactionAck" to RedactionAck("transfer", MATCH, 7, 3),
                )
        controls.forEach { (name, control) ->
            val wire = directRoundTrip(control)
            assertTrue(wire.startsWith("{\"type\":\"$name\""))
            assertTrue("\"v\":4" in wire)
            assertEquals(DecodeResult.Unsupported(4, name), SyncCodec.decode(wire))
            assertEquals(DecodeResult.Unsupported(4, name), SyncCodec.decode(SyncCodec.encode(control, identity)))
            assertIs<DecodeResult.Malformed>(SyncCodec.decode(wire.replace("\"v\":4", "\"v\":3")))
            val error = assertFailsWith<IllegalArgumentException> { SyncCodec.encodeToString(control) }
            assertEquals("ReplicaControlMessage requires IdentityEnvelope", error.message)
            assertFailsWith<IllegalArgumentException> { SyncCodec.encode(control) }
        }
        assertFailsWith<IllegalArgumentException> { AccessState(MATCH, 0, MatchAccessLevel.MEMBER, 3) }
        assertFailsWith<IllegalArgumentException> { AccessAck(MATCH, 0, MatchAccessLevel.MEMBER, 5) }
        assertFailsWith<IllegalArgumentException> { chunk().copy(v = 3) }
        assertFailsWith<IllegalArgumentException> { RedactedChunkAck("t", MATCH, 0, 0, 0, 3) }
        assertFailsWith<IllegalArgumentException> { RedactionAck("t", MATCH, 0, 0, 3) }
    }

    @Test
    fun stampsAndLifecycleRoundTripWithoutNamesOrMatchingVectors() {
        for (stamp in listOf(local, cloud)) {
            MatchAccessLevel.entries.forEach { access ->
                val summary =
                    Summary(
                        mapOf("m1" to VersionVector.EMPTY),
                        true,
                        4,
                        mapOf("removed" to LifecycleSummary(7, access, 3)),
                        mapOf("m1" to stamp),
                    )
                directRoundTrip(summary)
                directRoundTrip(Events(MATCH, listOf(genesis()), true, 4, stamp))
                directRoundTrip(MatchMetaMessage(match = MatchHeader(MATCH, FORMAT, LINEUP, createdAtMs = 0), v = 4, stamp = stamp))
                directRoundTrip(Deleted(listOf(DeletedMatch(MATCH, 42)), 4, mapOf("m1" to stamp)))
                assertEquals(DecodeResult.Unsupported(4, "Summary"), SyncCodec.decode(SyncCodec.encode(summary)))
            }
        }
        directRoundTrip(Summary(emptyMap(), v = 4, lifecycle = mapOf("gone" to LifecycleSummary(0, MatchAccessLevel.ABSENT, 0))))
        directRoundTrip(Events(MATCH, emptyList(), v = 4))
        directRoundTrip(MatchMetaMessage(v = 4))
        directRoundTrip(Deleted(emptyList(), 4))
        val assignment =
            ev(
                "assignment",
                "phone",
                2,
                2,
                "g",
                PlayerAssigned.reference(PlayerId("slot"), PlayerId("person"), PlayerId("creator")),
            )
        val wire = directRoundTrip(Events(MATCH, listOf(assignment), v = 4, stamp = cloud))
        assertFalse("displayName" in wire || "\"name\"" in wire || "\"player\"" in wire)
    }

    @Test
    fun legacyGoldenBytesAndPositionalConstructorsRemainUnchanged() {
        val cases =
            listOf(
                Hello(DeviceId("watch"), PeerKind.WATCH, emptyList(), 3, 3) to
                    """{"type":"Hello","deviceId":"watch","kind":"WATCH","minV":3,"v":3}""",
                Summary(mapOf("m1" to VersionVector.EMPTY), true, 3) to
                    """{"type":"Summary","matches":{"m1":{}},"reply":true,"v":3}""",
                Events(MATCH, emptyList(), true, 3) to
                    """{"type":"Events","matchId":"m1","events":[],"more":true,"v":3}""",
                MatchMetaMessage(
                    null,
                    emptyList(),
                    emptyList(),
                    null,
                    emptyList(),
                    emptyList(),
                    null,
                    emptyList(),
                    null,
                    null,
                    3,
                ) to """{"type":"MatchMeta","v":3}""",
                Deleted(listOf(DeletedMatch(MATCH, 42)), 3) to
                    """{"type":"Deleted","matches":[{"matchId":"m1","deletedAtMs":42}],"v":3}""",
            )
        cases.forEach { (message, wire) ->
            assertEquals(wire, SyncCodec.encodeToString(message))
            assertTrue(wire.encodeToByteArray().contentEquals(SyncCodec.encode(message)))
            assertEquals(DecodeResult.Ok(message), SyncCodec.decode(wire))
            assertEquals(DecodeResult.Ok(message, identity), SyncCodec.decode(SyncCodec.encode(message, identity)))
        }
    }

    @Test
    fun protocolThreeCannotSilentlyCarryLifecycleOrStampFields() {
        assertFailsWith<IllegalArgumentException> {
            Summary(
                emptyMap(),
                lifecycle =
                    mapOf("m" to LifecycleSummary(0, MatchAccessLevel.FEED, 0)),
            )
        }
        assertFailsWith<IllegalArgumentException> { Summary(emptyMap(), stamps = mapOf("m" to local)) }
        assertFailsWith<IllegalArgumentException> { Events(MATCH, emptyList(), stamp = local) }
        assertFailsWith<IllegalArgumentException> { MatchMetaMessage(stamp = cloud) }
        assertFailsWith<IllegalArgumentException> { Deleted(emptyList(), stamps = mapOf("m" to cloud)) }
        val messages =
            listOf(
                Summary(emptyMap(), v = 4, lifecycle = mapOf("m" to LifecycleSummary(0, MatchAccessLevel.ABSENT, 0))),
                Summary(mapOf("m" to VersionVector.EMPTY), v = 4, stamps = mapOf("m" to local)),
                Events(MATCH, listOf(genesis()), v = 4, stamp = local),
                MatchMetaMessage(v = 4, stamp = cloud),
                Deleted(listOf(DeletedMatch(MATCH, 0)), 4, mapOf("m1" to cloud)),
            )
        messages.forEach {
            assertIs<DecodeResult.Malformed>(SyncCodec.decode(directRoundTrip(it).replace("\"v\":4", "\"v\":3")))
        }
    }

    @Test
    fun dataVersionFourRequiresStampsAndMatchingEventRows() {
        assertFailsWith<IllegalArgumentException> { Summary(mapOf("m1" to VersionVector.EMPTY), v = 4) }
        assertFailsWith<IllegalArgumentException> { Events(MATCH, listOf(genesis()), v = 4) }
        assertFailsWith<IllegalArgumentException> {
            Events(
                MATCH,
                listOf(genesis().copy(matchId = MatchId("other"))),
                v = 4,
                stamp = cloud,
            )
        }
        assertFailsWith<IllegalArgumentException> { MatchMetaMessage(match = MatchHeader(MATCH, FORMAT, LINEUP, createdAtMs = 0), v = 4) }
        assertFailsWith<IllegalArgumentException> { Deleted(listOf(DeletedMatch(MATCH, 0)), 4) }
        val valid = Events(MATCH, listOf(genesis()), v = 4, stamp = cloud)
        val tree = SyncCodec.json.parseToJsonElement(directRoundTrip(valid)) as JsonObject
        assertFailsWith<IllegalArgumentException> {
            SyncCodec.json.decodeFromJsonElement(SyncMessage.serializer(), JsonObject(tree - "stamp"))
        }
    }

    @Test
    fun serializersRejectMixedStampClassesAndInvalidControlFields() {
        val invalid =
            listOf(
                """{"type":"Events","v":4,"matchId":"m1","events":[],"stamp":{"type":"AccessState","generation":0}}""",
                """{"type":"Events","v":4,"matchId":"m1","events":[],"stamp":{"type":"CloudMatchStamp","generation":-1,"redactionEpoch":0}}""",
                """{"type":"Events","v":4,"matchId":"m1","events":[],"stamp":{"type":"LocalMatchStamp","sourceId":"d","genesisId":"g","scope":""}}""",
                """{"type":"AccessState","v":4,"matchId":"m1","generation":0,"access":"UNKNOWN"}""",
                """{"type":"AccessState","v":-1,"matchId":"m1","generation":0,"access":"MEMBER"}""",
                """{"type":"RedactedChunkAck","v":4,"transferId":"t","matchId":"m1","generation":0,"epoch":0,"index":-1}""",
            )
        invalid.forEach {
            assertFailsWith<IllegalArgumentException> { SyncCodec.json.decodeFromString(SyncMessage.serializer(), it) }
        }
        listOf(
            Events(MATCH, listOf(genesis()), v = 4, stamp = local),
            MatchMetaMessage(v = 4),
            Deleted(emptyList(), 4),
        ).forEach {
            assertEquals(
                DecodeResult.Unsupported(
                    4,
                    if (it is MatchMetaMessage) {
                        "MatchMeta"
                    } else if (it is Events) {
                        "Events"
                    } else {
                        "Deleted"
                    },
                ),
                SyncCodec.decode(SyncCodec.encode(it)),
            )
        }
    }

    @Test
    fun rawEventPayloadIsPreservedExactlyWithoutDecodingItsType() {
        val page = chunk()
        val encoded = directRoundTrip(page)
        val decoded = SyncCodec.json.decodeFromString(SyncMessage.serializer(), encoded) as RedactedChunk
        assertEquals(rawPayload, decoded.events.first().payload)
        directRoundTrip(
            page.copy(
                events = listOf(row().copy(type = "UnknownFutureType", payload = "not parsed as JSON")),
                next = RedactionCursor(PlayerId("b"), 1),
            ),
        )
        directRoundTrip(page.copy(from = RedactionCursor(PlayerId("0"), 0)))
        directRoundTrip(page.copy(playerIds = emptyList(), next = RedactionCursor(null, 2)))
        directRoundTrip(page.copy(events = emptyList(), next = RedactionCursor(PlayerId("b"), 0)))
        directRoundTrip(page.copy(playerIds = emptyList(), events = emptyList(), next = page.from))
    }

    @Test
    fun chunkRejectsCursorGapsRegressionsSortingAndBusyLoops() {
        val page = chunk()
        val invalid: List<() -> Any> =
            listOf(
                { page.copy(index = -1) },
                { page.copy(highWatermark = -1) },
                { page.copy(from = RedactionCursor(PlayerId("b"), 0)) },
                { page.copy(from = RedactionCursor(afterServerSeq = 1)) },
                { page.copy(next = RedactionCursor(PlayerId("a"), 2)) },
                { page.copy(next = RedactionCursor(PlayerId("b"), 3)) },
                { page.copy(playerIds = listOf(PlayerId("b"), PlayerId("a"))) },
                { page.copy(playerIds = listOf(PlayerId("b"), PlayerId("b"))) },
                { page.copy(events = listOf(row(2), row())) },
                { page.copy(events = listOf(row(), row())) },
                { page.copy(events = listOf(row(), row(2).copy(id = row().id))) },
                { page.copy(events = listOf(row().copy(matchId = MatchId("other")), row(2))) },
                { page.copy(highWatermark = 1) },
                { page.copy(playerIds = emptyList(), events = emptyList(), next = page.from, idsDone = false) },
                { page.copy(playerIds = emptyList(), events = emptyList(), next = page.from, eventsDone = false) },
                {
                    page.copy(
                        playerIds = emptyList(),
                        events = emptyList(),
                        from = RedactionCursor(afterServerSeq = 11),
                        next = RedactionCursor(afterServerSeq = 11),
                    )
                },
            )
        invalid.forEachIndexed { index, create -> assertFailsWith<IllegalArgumentException>("case $index") { create() } }
        directRoundTrip(page.copy(eventsDone = false, idsDone = false))
    }

    @Test
    fun allScalarBoundsRejectInvalidValuesAndAcceptEndpoints() {
        for (id in listOf("", " ", "x".repeat(129))) {
            val invalid: List<() -> Any> =
                listOf(
                    { AccessState(MatchId(id), 0, MatchAccessLevel.MEMBER) },
                    { AccessAck(MatchId(id), 0, MatchAccessLevel.FEED) },
                    { local.copy(sourceId = DeviceId(id)) },
                    { local.copy(genesisId = EventId(id)) },
                    { local.copy(scope = id) },
                    { RedactionCursor(PlayerId(id)) },
                    { row().copy(id = EventId(id)) },
                    { row().copy(matchId = MatchId(id)) },
                    { row().copy(deviceId = DeviceId(id)) },
                    { row().copy(parentId = EventId(id)) },
                    { row().copy(type = id) },
                    { chunk().copy(transferId = id) },
                    { chunk().copy(playerIds = listOf(PlayerId(id))) },
                    { RedactedChunkAck(id, MATCH, 0, 0, 0) },
                    { RedactionAck(id, MATCH, 0, 0) },
                    { Summary(emptyMap(), v = 4, lifecycle = mapOf(id to LifecycleSummary(0, MatchAccessLevel.ABSENT, 0))) },
                )
            invalid.forEach { assertFailsWith<IllegalArgumentException> { it() } }
        }
        val invalid: List<() -> Any> =
            listOf(
                { AccessState(MATCH, -1, MatchAccessLevel.MEMBER) },
                { AccessAck(MATCH, -1, MatchAccessLevel.ABSENT) },
                { cloud.copy(generation = -1) },
                { cloud.copy(redactionEpoch = -1) },
                { LifecycleSummary(-1, MatchAccessLevel.FEED, 0) },
                { LifecycleSummary(0, MatchAccessLevel.FEED, -1) },
                { RedactionCursor(afterServerSeq = -1) },
                { row().copy(deviceSeq = 0) },
                { row().copy(serverSeq = 0) },
                { row().copy(lamport = -1) },
                { chunk().copy(generation = -1) },
                { chunk().copy(epoch = -1) },
                { RedactedChunkAck("t", MATCH, -1, 0, 0) },
                { RedactedChunkAck("t", MATCH, 0, -1, 0) },
                { RedactedChunkAck("t", MATCH, 0, 0, -1) },
                { RedactionAck("t", MATCH, -1, 0) },
                { RedactionAck("t", MATCH, 0, -1) },
            )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it() } }
        directRoundTrip(AccessState(MatchId("x".repeat(128)), Long.MAX_VALUE, MatchAccessLevel.ABSENT))
        assertEquals(Long.MAX_VALUE, CloudMatchStamp(Long.MAX_VALUE, Long.MAX_VALUE).generation)
        assertEquals(128, local.copy(scope = "x".repeat(128)).scope.length)
        assertEquals(128, row().copy(type = "x".repeat(128)).type.length)
    }

    @Test
    fun pageAndLifecycleCountLimitsAreInclusive() {
        val ids = (1..200).map { PlayerId(it.toString().padStart(3, '0')) }
        val rows = (1L..500L).map(::row)
        val page = chunk().copy(highWatermark = 500, playerIds = ids, events = rows, next = RedactionCursor(ids.last(), 500))
        directRoundTrip(page)
        assertFailsWith<IllegalArgumentException> {
            page.copy(
                playerIds = ids + PlayerId("999"),
                next = RedactionCursor(PlayerId("999"), 500),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            page.copy(events = rows + row(501), highWatermark = 501, next = RedactionCursor(ids.last(), 501))
        }
        val lifecycle = (1..200).associate { "m$it" to LifecycleSummary(0, MatchAccessLevel.ABSENT, 0) }
        directRoundTrip(Summary(emptyMap(), v = 4, lifecycle = lifecycle))
        assertFailsWith<IllegalArgumentException> {
            Summary(emptyMap(), v = 4, lifecycle = lifecycle + ("overflow" to LifecycleSummary(0, MatchAccessLevel.ABSENT, 0)))
        }
    }

    @Test
    fun wireBudgetCountsUtf8EscapesEnvelopeAndActualInputBytes() {
        val base = Hello(DeviceId("phone"), PeerKind.PHONE, v = 4, minV = 4, session = "")
        val baseSize = SyncCodec.encode(base).size
        val exact = base.copy(session = "x".repeat(65536 - baseSize))
        assertEquals(65536, SyncCodec.encode(exact).size)
        assertIs<DecodeResult.Ok>(SyncCodec.decode(SyncCodec.encode(exact)))
        assertFailsWith<IllegalArgumentException> { SyncCodec.encodeToString(exact.copy(session = exact.session + "x")) }
        assertFailsWith<IllegalArgumentException> { SyncCodec.encode(exact, identity) }
        val overhead = SyncCodec.encode(base, identity).size - baseSize
        val wrapped = base.copy(session = "x".repeat(65536 - baseSize - overhead))
        assertEquals(65536, SyncCodec.encode(wrapped, identity).size)
        assertEquals(DecodeResult.Ok(wrapped, identity), SyncCodec.decode(SyncCodec.encode(wrapped, identity)))
        assertFailsWith<IllegalArgumentException> { SyncCodec.encode(wrapped.copy(session = wrapped.session + "x"), identity) }
        for (value in listOf("é", "🎾", "\n", "\"", "\\")) {
            assertFailsWith<IllegalArgumentException> { SyncCodec.encode(base.copy(session = value.repeat(40000))) }
        }
        val exactWire = SyncCodec.encodeToString(exact)
        assertIs<DecodeResult.Malformed>(SyncCodec.decode(exactWire + " "))
        assertIs<DecodeResult.Malformed>(SyncCodec.decode((exactWire + " ").encodeToByteArray()))
        // Die escaped Darstellung ist größer als das dekodierte Feld, zählt aber vollständig zum Budget.
        val escaped = SyncCodec.encodeToString(base.copy(session = "é".repeat(12000))).replace("é", "\\u00e9")
        assertIs<DecodeResult.Malformed>(SyncCodec.decode(escaped))
        val control = chunk().copy(events = listOf(row().copy(payload = "é".repeat(33000))), next = RedactionCursor(PlayerId("b"), 1))
        assertFailsWith<IllegalArgumentException> { SyncCodec.encode(control, identity) }
        assertIs<DecodeResult.Malformed>(SyncCodec.decode(SyncCodec.json.encodeToString(SyncMessage.serializer(), control)))
        val legacy = base.copy(v = 3, minV = 3, session = "x".repeat(70000))
        assertEquals(DecodeResult.Ok(legacy), SyncCodec.decode(SyncCodec.encode(legacy)))
        assertEquals(DecodeResult.Ok(legacy, identity), SyncCodec.decode(SyncCodec.encode(legacy, identity)))
    }

    @Test
    fun helloNegotiatesUpdateWithoutActivatingVersionFourAndErrorsHideBodies() {
        assertEquals(3, ProtocolVersion.CURRENT)
        assertEquals(3, ProtocolVersion.MIN_SUPPORTED)
        val hello = assertIs<DecodeResult.Ok>(SyncCodec.decode(SyncCodec.encode(Hello(DeviceId("watch"), PeerKind.WATCH, minV = 4, v = 4))))
        assertEquals(CompatibilityStatus.PEER_TOO_NEW, ProtocolVersion.negotiate(hello.message.v, 4).status)
        assertEquals(CompatibilityStatus.PEER_TOO_OLD, ProtocolVersion.negotiate(3, 3, 4, 4).status)
        val secret = "PRIVATE_RAW_PAYLOAD"
        val malformed =
            listOf(
                "{\"$secret",
                """{"type":"$secret","v":3}""",
                """{"type":"Events","matchId":"m1","events":[{"payload":"$secret"}],"v":3}""",
            )
        malformed.forEach {
            assertFalse(assertIs<DecodeResult.Malformed>(SyncCodec.decode(it)).reason.contains(secret))
        }
        val bad = SyncCodec.json.parseToJsonElement(directRoundTrip(AccessState(MATCH, 0, MatchAccessLevel.MEMBER))) as JsonObject
        assertIs<DecodeResult.Malformed>(
            SyncCodec.decode(
                JsonObject(
                    bad + ("v" to JsonPrimitive(3)) +
                        ("access" to JsonPrimitive("UNKNOWN")),
                ).toString(),
            ),
        )
    }
}
