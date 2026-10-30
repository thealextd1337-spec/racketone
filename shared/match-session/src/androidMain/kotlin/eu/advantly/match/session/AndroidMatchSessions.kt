package eu.advantly.match.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.cash.sqldelight.db.SqlDriver
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.AndroidStorageFailures
import eu.advantly.data.LocalData
import eu.advantly.data.createAndroidDriver
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.DeviceLinkListener
import eu.advantly.device.link.LinkState
import eu.advantly.device.link.LinkStats
import eu.advantly.device.link.MainLooperScheduler
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.SendCallback
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.sync.protocol.PeerKind

/** Vorbereitete Hostregistrierungen werden erst nach erfolgreichem SQL-Commit ausgeführt. */
class AndroidSessionBootstrap internal constructor() {
    private val publications = mutableListOf<() -> Unit>()
    private val cleanups = mutableListOf<() -> Unit>()
    private var discarded = false

    fun afterCommit(action: () -> Unit) {
        publications += action
    }

    fun onDiscard(action: () -> Unit) {
        cleanups += action
    }

    internal fun publish(requireCurrent: () -> Unit) {
        val ready = publications.toList()
        publications.clear()
        ready.forEach {
            requireCurrent()
            it()
            requireCurrent()
        }
    }

    internal fun discard(error: Exception?) {
        if (discarded) return
        discarded = true
        publications.clear()
        cleanups.asReversed().forEach {
            try {
                it()
            } catch (cleanup: Exception) {
                if (error != null) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                } else {
                    throw cleanup
                }
            }
        }
        cleanups.clear()
    }
}

/** Android-only Singleton. Kein Link und keine Hostveröffentlichung vor Storage-Commit. Nur Main-Thread. */
object AndroidMatchSessions {
    @Volatile private var instance: MatchSession? = null

    @Volatile private var resources: Resources? = null

    fun current(): MatchSession? = instance

    fun get(
        context: Context,
        kind: PeerKind,
    ): MatchSession = instance ?: prepare(context, kind) { _, _ -> }

    /** Einschließlich später Hostlesepfade (Wear resume/Glance); Fehler werfen für die Host-Storagegrenze. */
    fun prepare(
        context: Context,
        kind: PeerKind,
        prepare: (MatchSession, AndroidSessionBootstrap) -> Unit,
    ): MatchSession {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Android session bootstrap requires main thread" }
        val app = context.applicationContext
        val installation = AndroidInstallationGuard.requireReady(app)
        val existing = instance
        if (existing == null) resources?.finish(null)
        val runtime = if (existing == null) Resources(app, installation.privateTransportAllowed) else checkNotNull(resources)
        resources = runtime
        AndroidInstallationGuard.beginBootstrap(app)
        instance = null
        runtime.work.hold()
        runtime.link?.hold()
        try {
            runtime.requireCurrent()
            if (runtime.driver == null) runtime.driver = createAndroidDriver(app, installation.activeDatabaseName)
            runtime.requireCurrent()
            val driver = checkNotNull(runtime.driver)
            val session =
                AdvantlyDatabase(driver).transactionWithResult {
                    val data = runtime.data ?: LocalData(driver).also { runtime.data = it }
                    runtime.requireCurrent()
                    if (data.settings.get(AndroidInstallationGuard.RESTORED_KEY) == installation.cacheNamespace) {
                        // Übernommene Kopie: vor jedem Sessionzugriff neue Identität; nur das Handy gibt kontolose Daten lokal frei.
                        rekeyRestoredInstallation(data, kind, installation.cacheNamespace, releaseUnbound = kind == PeerKind.PHONE)
                        data.settings.remove(AndroidInstallationGuard.RESTORED_KEY)
                    } else if (!installation.privateTransportAllowed && !data.accountIdentities.managed) {
                        val scope = "installation:${installation.cacheNamespace}"
                        val ids = RandomIdGenerator()
                        val nowMs = System.currentTimeMillis()
                        data.accountIdentities.select(scope, null, nowMs, kind.name) { ids.newUuid7(nowMs).value }
                        // Die Uhr bleibt ohne Autorität: sonst lehnte sie nach dem Hold jedes Handy dauerhaft ab.
                        if (kind == PeerKind.PHONE) {
                            data.settings.put("identity.authority", installation.cacheNamespace)
                            data.settings.put("identity.epoch", "1")
                            data.settings.put("identity.wireScope", scope)
                        }
                    }
                    val candidate =
                        existing ?: MatchSessions.create(
                            data,
                            runtime.link,
                            runtime.work,
                            kind,
                            appBuild = androidAppBuild(app),
                            threadGuard = { Looper.myLooper() == Looper.getMainLooper() },
                        )
                    runtime.session = candidate
                    runtime.requireCurrent()
                    candidate.start()
                    runtime.requireCurrent()
                    prepare(candidate, runtime.bootstrap)
                    runtime.requireCurrent()
                    candidate
                }
            // transactionWithResult kehrt erst nach erfolgreichem Commit zurück.
            runtime.requireCurrent()
            runtime.bootstrap.publish(runtime::requireCurrent)
            runtime.requireCurrent()
            AndroidInstallationGuard.bootstrapCommitted(app)
            runtime.requireCurrent()
            runtime.link?.activate()
            runtime.requireCurrent()
            instance = session
            runtime.requireCurrent()
            runtime.work.release()
            runtime.requireCurrent()
            return session
        } catch (error: Exception) {
            if (AndroidStorageFailures.isExpected(error)) AndroidInstallationGuard.storageFailed(app, error)
            runtime.invalidate(error)
            runtime.finish(error)
            throw error
        }
    }

    private class Resources(
        val context: Context,
        transportAllowed: Boolean,
    ) {
        var driver: SqlDriver? = null
        var data: LocalData? = null
        var session: MatchSession? = null
        val bootstrap = AndroidSessionBootstrap()
        private var closed = false
        private val handler = Handler(Looper.getMainLooper())
        val work =
            AndroidSessionWork(
                MainLooperScheduler(),
                storage = { block ->
                    AndroidInstallationGuard.storage(context) {
                        block()
                        true
                    } == true
                },
                available = { AndroidInstallationGuard.check(context) is AndroidInstallationAvailability.Ready },
                failed = ::invalidate,
            )
        val link =
            if (transportAllowed) {
                AndroidScopedLink(
                    identity = { androidSessionIdentity(checkNotNull(data)) },
                    validate = { query -> AndroidInstallationGuard.storage(context, query) == true },
                ) { currentIdentity ->
                    ReliableDeviceLink(
                        AndroidProtectedTransport(
                            WearDataLayerTransport(context, allowed = {
                                AndroidInstallationGuard.privateTransportAllowed(context) && currentIdentity()
                            }),
                            work,
                        ) { AndroidInstallationGuard.privateTransportAllowed(context) && currentIdentity() },
                        work,
                    )
                }
            } else {
                null
            }
        private val unregister = AndroidInstallationGuard.onUnavailable(context, ::invalidate)

        fun requireCurrent() {
            // Innere Storagegrenzen koennen den Fehler bereits behandelt haben. Nie durch Checkfailed ersetzen.
            work.throwIfFailed()
            AndroidInstallationGuard.requireReady(context)
            check(resources === this && !closed) { "Android bootstrap resource generation changed" }
            check(instance == null || instance === session) { "Android bootstrap session generation changed" }
        }

        fun invalidate(error: Exception) {
            // Capability und Aufgaben zuerst entziehen, auch wenn SQLite den Fehler auf einem anderen Thread meldet.
            if (instance === session) instance = null
            if (resources === this) {
                resources = null
            }
            work.discard(error)
            link?.hold()
            if (Looper.myLooper() == Looper.getMainLooper()) {
                stopRegistrations(error)
                // Der Corruptioncallback bleibt queryfrei; Handle nach dem fehlgeschlagenen Stack schließen.
                handler.post { finish(error) }
            } else {
                handler.post {
                    stopRegistrations(error)
                    finish(error)
                }
            }
        }

        private fun stopRegistrations(error: Exception) {
            try {
                link?.stop()
            } catch (cleanup: Exception) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            bootstrap.discard(error)
        }

        fun finish(error: Exception?) {
            if (closed) return
            closed = true
            work.discard(error)
            try {
                link?.stop()
            } catch (cleanup: Exception) {
                if (error != null) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                } else {
                    throw cleanup
                }
            }
            bootstrap.discard(error)
            unregister()
            try {
                driver?.close()
            } catch (cleanup: Exception) {
                if (error != null) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                } else {
                    throw cleanup
                }
            }
        }
    }
}

/** Persistenter Identitätskontext; historische Event-/Spieler-IDs werden weder geändert noch als Namen abgeglichen. */
internal fun androidSessionIdentity(data: LocalData): List<Any?> =
    listOf(
        data.accountIdentities.managed,
        data.accountIdentities.scope.takeIf { data.accountIdentities.managed },
        data.settings.get("session.deviceId"),
        data.settings.get("identity.authority"),
        data.settings.get("identity.epoch"),
    )

/** Vor Commit unsichtbarer Link; weder Konstruktor noch start() aktivieren einen privaten Eingang. */
internal class AndroidScopedLink(
    private val identity: () -> Any,
    private val validate: (() -> Boolean) -> Boolean = { it() },
    private val factory: (() -> Boolean) -> DeviceLink,
) : DeviceLink {
    private var delegate: DeviceLink? = null
    private var listener: DeviceLinkListener? = null
    private var started = false
    private var binding: Any? = null

    @Volatile private var generation = 0L

    @Volatile private var active = false

    // Auch Lesen bindet nach Identitätswechsel neu: Ein alter Transport verwirft Eingänge still und sucht keine Peers mehr.
    // Ohne bekannten Peer sendete die Session nie, der Link bliebe bis zum Prozessneustart taub.
    override val state: LinkState get() = if (active) currentDelegate().state else LinkState.UNAVAILABLE
    override val peers: List<String> get() = if (active) currentDelegate().peers else emptyList()
    override val stats: LinkStats get() = delegate?.stats ?: LinkStats()

    override fun isTrustedCounterpart(peer: String): Boolean = active && currentDelegate().isTrustedCounterpart(peer)

    override fun setListener(listener: DeviceLinkListener?) {
        this.listener = listener
        if (active) delegate?.setListener(listener)
    }

    override fun start() {
        started = true
    }

    fun hold() {
        active = false
    }

    fun activate() {
        if (!started) return
        active = true
        currentDelegate()
    }

    private fun currentDelegate(): DeviceLink {
        val scope = identity()
        if (delegate == null || binding != scope) {
            generation++
            val old = delegate
            delegate = null
            old?.setListener(null)
            old?.stop()
            binding = scope
            val epoch = generation
            val link =
                factory {
                    // Raw-Poll/GMS-Callbacks laufen auch ausserhalb Work. Storagefehler sperren hier statt im Handler zu entweichen.
                    active && generation == epoch && validate { identity() == scope } && active && generation == epoch
                }
            delegate = link
            link.setListener(listener)
            link.start()
        }
        return checkNotNull(delegate)
    }

    override fun stop() {
        active = false
        generation++
        started = false
        delegate?.setListener(null)
        delegate?.stop()
        listener = null
    }

    override fun send(
        peer: String,
        payload: ByteArray,
        callback: SendCallback?,
    ): String {
        // Widerruf kann zwischen allowed()/targets() und send() eintreten: wie ein inerter Transport still verwerfen.
        if (!active) return ""
        return currentDelegate().send(peer, payload, callback)
    }
}
