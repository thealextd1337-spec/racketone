package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.LinkStats
import eu.advantly.device.link.SendCallback
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.DecodeResult
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncCodec
import eu.advantly.sync.protocol.SyncMessage
import kotlin.random.Random

/**
 * Link ohne Netz (Vorlage: Reproducer aus Review-Runde 1): Gesendetes wird nur protokolliert, Empfang wird von Hand
 * mit [deliver] in beliebiger Reihenfolge eingespeist. So lassen sich Zustellreihenfolgen exakt durchspielen.
 */
class WireLink : DeviceLink {
    var sink: DeviceLinkListener? = null
        private set
    val sent = mutableListOf<SyncMessage>()
    val callbacks = mutableListOf<SendCallback>()
    override val state = LinkState.CONNECTED
    override val peers = listOf("lab")
    override val stats = LinkStats()

    override fun start() = Unit

    override fun stop() = Unit

    override fun setListener(listener: DeviceLinkListener?) {
        sink = listener
    }

    override fun send(
        peer: String,
        payload: ByteArray,
        callback: SendCallback?,
    ): String {
        sent += (SyncCodec.decode(payload) as DecodeResult.Ok).message
        callback?.let { callbacks += it }
        return "message-${sent.size}"
    }

    fun deliver(message: SyncMessage) {
        checkNotNull(sink).onMessage("lab", "incoming", SyncCodec.encode(message))
    }
}

/** Ein Gerät mit [WireLink], eigener Uhr und deterministischen IDs. */
class WireNode(
    name: String,
    seed: Int,
    kind: PeerKind = PeerKind.PHONE,
    val scheduler: ManualScheduler = ManualScheduler(),
    guard: ThreadGuard = ThreadGuard.NONE,
    identifyPeer: Boolean = kind == PeerKind.PHONE,
) {
    val data = testLocalData()
    val link = WireLink()
    var time = 10_000L
    val session =
        MatchSession(data, link, scheduler, SessionConfig(DeviceId(name), kind), WallClock { time }, RandomIdGenerator(Random(seed)), guard)

    init {
        session.start()
        // Handy: die Gegenstelle „lab“ stellt sich als weiteres Handy vor – erst identifizierte Gegenstellen bekommen
        // Matchdaten (Routing, Review W3-M R2 #3)
        if (identifyPeer) {
            link.deliver(Hello(DeviceId("lab"), PeerKind.PHONE))
            link.sent.clear()
        }
    }

    val match: ActiveMatch get() = checkNotNull(session.state.value.match) { "kein offenes Match" }

    fun events(id: MatchId = match.id): List<ScoreEvent> = data.events.scoreEvents(id)

    fun points(
        side: Side,
        count: Int,
    ) = repeat(count) {
        session.recordPoint(side)
        time += 1_000
    }
}
