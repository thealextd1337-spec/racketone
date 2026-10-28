package eu.advantly.match.session

import app.cash.sqldelight.db.SqlDriver
import eu.advantly.core.model.PlayerId
import eu.advantly.data.CloudUploadSession
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkTransport
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.SendCallback
import eu.advantly.device.link.testing.InMemoryLinkNetwork
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.DecodeResult
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.ProtocolVersion
import eu.advantly.sync.protocol.SyncCodec
import eu.advantly.sync.protocol.SyncMessage
import kotlin.random.Random

/**
 * Link, der jedes gesendete Paket aufzeichnet und Pakete von Hand einspeisen kann (verspätete Envelopes, Replays).
 * [lose] verwirft ausgehende Pakete auf App-Ebene (verlorene Acks/Seiten, ohne Wiederholung durch den Link).
 */
class RecordingLink(
    private val delegate: DeviceLink,
) : DeviceLink by delegate {
    val sent = mutableListOf<ByteArray>()
    var lose: (SyncMessage) -> Boolean = { false }
    private var receiver: DeviceLinkListener? = null

    override fun setListener(listener: DeviceLinkListener?) {
        receiver = listener
        delegate.setListener(listener)
    }

    override fun send(
        peer: String,
        payload: ByteArray,
        callback: SendCallback?,
    ): String {
        sent += payload
        val message = (SyncCodec.decode(payload, 3..ProtocolVersion.CURRENT) as? DecodeResult.Ok)?.message
        if (message != null && lose(message)) return "lost-${sent.size}"
        return delegate.send(peer, payload, callback)
    }

    fun replay(
        peer: String,
        payload: ByteArray,
    ) = checkNotNull(receiver).onMessage(peer, "replay", payload)

    fun decoded(): List<DecodeResult.Ok> = sent.mapNotNull { SyncCodec.decode(it, 3..ProtocolVersion.CURRENT) as? DecodeResult.Ok }
}

/**
 * Gekoppeltes Handy und Uhr (vertraute Links, Wear-Puffer bei Trennung) mit je einem dauerhaften Treiber: Neustarts
 * bauen `LocalData` und Session auf derselben Datenbank neu auf.
 */
class LifecyclePair(
    private val phoneVersions: IntRange = ProtocolVersion.MIN_SUPPORTED..ProtocolVersion.CURRENT,
    private val watchVersions: IntRange = phoneVersions,
) {
    val scheduler = ManualScheduler()
    val network = InMemoryLinkNetwork(scheduler, queueWhenDisconnected = true)
    private val phoneDriver: SqlDriver = createInMemoryJvmDriver()
    private val watchDriver: SqlDriver = createInMemoryJvmDriver()
    var phoneData = LocalData(phoneDriver)
        private set
    var watchData = LocalData(watchDriver)
        private set
    private var seed = 0

    private fun link(name: String): RecordingLink {
        val transport = network.transport(name)
        val trusted =
            object : LinkTransport by transport {
                override fun isTrustedCounterpart(peer: String): Boolean = true
            }
        return RecordingLink(ReliableDeviceLink(trusted, scheduler))
    }

    val phoneLink = link("phone")
    val watchLink = link("watch")

    fun session(
        data: LocalData,
        link: DeviceLink?,
        kind: PeerKind,
    ): MatchSession =
        MatchSession(
            data,
            link,
            scheduler,
            SessionConfig(
                MatchSessions.deviceId(data),
                kind,
                protocolVersions = if (kind == PeerKind.PHONE) phoneVersions else watchVersions,
            ),
            WallClock { 1_760_000_000_000 + scheduler.nowMs() },
            RandomIdGenerator(Random(++seed)),
        )

    var phone: MatchSession
        private set
    var watch: MatchSession
        private set

    init {
        phoneData.players.ensureSelf("Alex", 0) { PlayerId("phone-self") }
        watchData.players.ensureSelf("Alex", 0) { PlayerId("watch-self") }
        phone = session(phoneData, phoneLink, PeerKind.PHONE)
        watch = session(watchData, watchLink, PeerKind.WATCH)
        phone.start()
        watch.start()
        settle()
    }

    fun settle() = scheduler.advanceBy(2_000)

    /** Upload-Sitzung des angemeldeten Kontos (Auth-Lease des Handys). */
    var token: CloudUploadSession? = null
        private set

    /** Konto am Handy anmelden und dessen Replikat aktivieren (Integrator-Schritt, hier für die Tests). */
    fun activate(profile: String) {
        token = phoneData.cloudUploads.activate(profile)
        phone.activateAccount(profile, token!!)
        settle()
        phoneData.replicas.activate(phoneData.replicas.captureContext()) {}
        settle()
    }

    /** Neustart auf derselben Datenbank; der Host bindet die Kontositzung wie beim App-Start erneut ([bindAccount]). */
    fun restartPhone(bindAccount: Boolean = true) {
        phone.close()
        phoneData = LocalData(phoneDriver)
        phone = session(phoneData, phoneLink, PeerKind.PHONE)
        phone.start()
        if (bindAccount) bindAccount()
        settle()
    }

    /** App-Start-Schritt des Hosts: Kontositzung (Auth-Lease) des gespeicherten Kontos wieder binden. */
    fun bindAccount() {
        phoneData.accountIdentities.profile?.let { profile ->
            token = phoneData.cloudUploads.activate(profile)
            phone.activateAccount(profile, token!!)
        }
    }

    fun restartWatch() {
        watch.close()
        watchData = LocalData(watchDriver)
        watch = session(watchData, watchLink, PeerKind.WATCH)
        watch.start()
        settle()
    }

    fun close() {
        phone.close()
        watch.close()
        phoneDriver.close()
        watchDriver.close()
    }
}
