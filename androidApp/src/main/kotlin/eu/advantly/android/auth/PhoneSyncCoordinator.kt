package eu.advantly.android.auth

import android.os.Looper
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.match.session.MatchSession
import eu.advantly.presentation.auth.PhonePullBlock
import eu.advantly.presentation.auth.PhonePullResult
import eu.advantly.presentation.auth.PhonePullState
import eu.advantly.presentation.auth.PhoneSyncBlock
import eu.advantly.presentation.auth.PhoneSyncPort
import eu.advantly.presentation.auth.PhoneSyncState
import eu.advantly.presentation.auth.PhoneSyncStatus
import eu.advantly.presentation.auth.PhoneUploadError
import eu.advantly.presentation.auth.PhoneUploadResult
import eu.advantly.presentation.auth.PhoneUploadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Geplante Arbeit (Android: JobScheduler). Jeder Auftrag trägt das Konto, für das er geplant wurde. */
internal interface PhoneSyncScheduler {
    /** Einmaliger Lauf mit Netzbedingung frühestens nach [delayMs]; ersetzt einen noch geplanten. */
    fun schedule(
        profile: String,
        delayMs: Long,
    )

    /** Periodischer Lauf mit Netzbedingung, falls für [profile] noch keiner geplant ist. */
    fun ensurePeriodic(profile: String)

    /** Verwirft alle geplanten Läufe. */
    fun cancel()
}

/** Persistenter Stand je Konto: letzter vollständiger Upload und Fehlerserie des Backoffs. */
internal interface PhoneSyncRecord {
    fun lastBackupAtMs(profile: String): Long?

    fun failures(profile: String): Int

    fun save(
        profile: String,
        lastBackupAtMs: Long?,
        failures: Int,
    )

    /** Logout und Löschantrag: Für das Konto bleibt kein Stand liegen. */
    fun forget(profile: String)
}

/**
 * Host-Vertrag B4: koordinierter Abgleich je Konto, erst Pull, dann Upload; beide Hosts serialisieren über die
 * prozessweite Kontosperre. Ein Lauf hat höchstens [MAX_PASSES] begrenzte Runden. Danach, nach Fehlern (Backoff) und
 * nach einem Prozessende setzt geplante Arbeit fort. BLOCKED plant nichts; erst der nächste Auslöser versucht es erneut.
 * Logout, Kontowechsel, Löschantrag und Einwilligungsentzug beenden laufende und geplante Arbeit sofort.
 */
internal class PhoneSyncCoordinator(
    private val auth: CloudAuthPort,
    private val session: MatchSession,
    private val pull: PhonePullHost?,
    private val upload: PhoneUploadHost,
    private val scheduler: PhoneSyncScheduler,
    private val record: PhoneSyncRecord,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val threadGuard: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val hostAccessAllowed: () -> Boolean = { true },
    private val storageAction: (() -> Unit) -> Boolean = {
        it()
        true
    },
) : PhoneSyncPort {
    private val lifecycle = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + lifecycle)
    private val mutable = MutableStateFlow(PhoneSyncState())
    override val state = mutable.asStateFlow()
    private var context: PhoneCloudContext? = null
    private var cycle: Job? = null
    private var lastEndMs: Long? = null
    private var closed = false

    init {
        confined()
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.session.collect { synchronize() }
        }
    }

    private fun confined() = check(threadGuard()) { "Phone-Sync nur auf dem Main-Host-Kontext verwenden" }

    private fun current(): PhoneCloudContext? {
        var result: PhoneCloudContext? = null
        return if (storageAction { result = readPhoneCloudContext(auth, session, hostAccessAllowed) }) result else null
    }

    private fun synchronize(): PhoneCloudContext? {
        confined()
        if (closed) return null
        val next = current()
        if (next != context) {
            val previous = context
            context = next
            cycle?.cancel()
            cycle = null
            // Geplante Arbeit des alten Kontos endet sofort; ein Auftrag prüft beim Start zusätzlich sein Konto.
            if (previous != null) scheduler.cancel()
            mutable.value = PhoneSyncState(available = next != null, lastBackupAtMs = next?.let { record.lastBackupAtMs(it.profile) })
            if (next != null) {
                scheduler.ensurePeriodic(next.profile)
                // App-Start, Anmeldung, Kontowechsel oder wieder gültige Einwilligung: erster Abgleich dieses Kontos.
                launch(next)
            }
        }
        // K5: Ein gesperrtes Konto hat keinen Cloud-Kontext und plant nichts; der Grund bleibt sichtbar.
        val suspended = next == null && hostAccessAllowed() && auth.session.value?.suspended == true
        if (next == null && (mutable.value.blocked == PhoneSyncBlock.SUSPENDED) != suspended) {
            mutable.value =
                if (suspended) PhoneSyncState(status = PhoneSyncStatus.BLOCKED, blocked = PhoneSyncBlock.SUSPENDED) else PhoneSyncState()
        }
        return next
    }

    override fun syncNow() {
        trigger(null)
    }

    override fun onForeground() {
        confined()
        val expected = synchronize() ?: return
        if (cycle != null || lastEndMs?.let { nowMs() - it < FOREGROUND_INTERVAL_MS } == true) return
        launch(expected)
    }

    /** Netz wieder da: nur ein Verbindungsfehler wird sofort statt erst nach dem Backoff wiederholt. */
    fun onNetworkAvailable() {
        confined()
        val expected = synchronize() ?: return
        if (cycle == null && mutable.value.status == PhoneSyncStatus.CONNECTION_FAILED) launch(expected)
    }

    /** Geplanter Auftrag; mit [profile] nur für das Konto, für das er geplant wurde. `null`: kein Lauf. */
    fun request(profile: String?): Job? = trigger(profile)

    /** Ein laufender Lauf deckt den Auslöser ab; was er nicht mehr sah, holt sein Folgelauf nach. */
    private fun trigger(profile: String?): Job? {
        confined()
        val expected = synchronize() ?: return null
        if (profile != null && profile != expected.profile) return null
        return launch(expected)
    }

    /**
     * Änderten sich lokale Daten während des Uploads (etwa das Matchende), folgt genau ein weiterer Lauf. Ein Folgelauf
     * erzeugt keinen eigenen, sonst liefe der Abgleich während eines Live-Matches dauerhaft.
     */
    private fun launch(
        expected: PhoneCloudContext,
        followUp: Boolean = false,
    ): Job {
        cycle?.let { return it }
        lateinit var job: Job
        job =
            work.launch(start = CoroutineStart.LAZY) {
                var again = false
                try {
                    again = run(expected) && !followUp
                } finally {
                    if (cycle === job) {
                        cycle = null
                        lastEndMs = nowMs()
                        mutable.value = mutable.value.copy(running = false)
                        if (again && !closed) launch(expected, followUp = true)
                    }
                }
            }
        cycle = job
        mutable.value = mutable.value.copy(running = true)
        job.start()
        return job
    }

    /** `true`: fehlerfrei und vollständig beendet, aber lokale Daten änderten sich während des Uploads. Abbruch plant nichts. */
    private suspend fun run(expected: PhoneCloudContext): Boolean {
        var passes = 0
        while (true) {
            val pulled =
                if (pull == null) {
                    null
                } else {
                    pass(expected, pull::start) { pull.state.value.takeIf { it.result != null } } ?: return false
                }
            if (pulled?.result == PhonePullResult.CANCELLED) return false
            val uploaded = pass(expected, upload::start) { upload.state.value.takeIf { it.result != null } } ?: return false
            if (uploaded.result == PhoneUploadResult.CANCELLED) return false
            passes++
            val more = pulled?.result == PhonePullResult.LIMIT_REACHED || uploaded.result in CONTINUE
            val failed =
                pulled?.result in setOf(PhonePullResult.FAILED, PhonePullResult.CONNECTION_FAILED) ||
                    (uploaded.result == PhoneUploadResult.FAILED && uploaded.error != PhoneUploadError.PERMISSION)
            if (!failed && more && passes < MAX_PASSES) continue
            finish(expected, pulled, uploaded, more, failed)
            // Nach ausgeschöpften Runden sieht die geplante Fortsetzung die Änderung; kein Folgelauf vor ihrer Pause.
            return !failed && !more && uploaded.stale
        }
    }

    /**
     * Eine schon laufende Fremdaktion (Statusabfrage, abgebrochener Altlauf) zählt nicht: danach einmal selbst starten.
     * Vor jedem Start und jedem Ergebnis gilt noch derselbe Kontext; sonst weder Ergebnis noch Start fürs neue Konto.
     */
    private suspend fun <S : Any> pass(
        expected: PhoneCloudContext,
        start: () -> Job?,
        result: () -> S?,
    ): S? {
        repeat(2) {
            if (synchronize() != expected) return null
            (start() ?: return null).join()
            if (synchronize() != expected) return null
            result()?.let { return it }
        }
        return null
    }

    private fun finish(
        expected: PhoneCloudContext,
        pulled: PhonePullState?,
        uploaded: PhoneUploadState,
        more: Boolean,
        failed: Boolean,
    ) {
        val profile = expected.profile
        val now = nowMs()
        val last = if (uploaded.result == PhoneUploadResult.DRAINED) now else record.lastBackupAtMs(profile)
        val failures = if (failed) record.failures(profile) + 1 else 0
        record.save(profile, last, failures)
        val blocked = if (failed) null else blockOf(pulled, uploaded)
        val delay =
            when {
                failed -> backoffMs(failures)
                more -> CONTINUE_DELAY_MS
                else -> null
            }
        delay?.let { scheduler.schedule(profile, it) }
        val offline = pulled?.result == PhonePullResult.CONNECTION_FAILED || uploaded.error == PhoneUploadError.CONNECTION
        mutable.value =
            PhoneSyncState(
                available = true,
                running = true,
                status =
                    when {
                        failed && offline -> {
                            PhoneSyncStatus.CONNECTION_FAILED
                        }

                        failed -> {
                            PhoneSyncStatus.FAILED
                        }

                        // Der Download setzt fort; ein Grund (etwa wartender Upload) bleibt zur Anzeige erhalten.
                        more -> {
                            PhoneSyncStatus.PENDING
                        }

                        blocked != null -> {
                            PhoneSyncStatus.BLOCKED
                        }

                        else -> {
                            PhoneSyncStatus.BACKED_UP
                        }
                    },
                blocked = blocked,
                lastBackupAtMs = last,
                nextAttemptAtMs = delay?.let { now + it },
            )
    }

    /** Der Download-Grund zuerst: er erklärt auch einen wartenden Upload. */
    private fun blockOf(
        pulled: PhonePullState?,
        uploaded: PhoneUploadState,
    ): PhoneSyncBlock? =
        when {
            pulled?.result == PhonePullResult.BLOCKED -> {
                when (pulled.blocked) {
                    PhonePullBlock.PERMISSION -> PhoneSyncBlock.PERMISSION
                    PhonePullBlock.REMOVALS -> PhoneSyncBlock.REMOVALS
                    PhonePullBlock.REDACTIONS -> PhoneSyncBlock.REDACTIONS
                    PhonePullBlock.MATERIALIZATION -> PhoneSyncBlock.MATERIALIZATION
                    PhonePullBlock.READINESS, null -> PhoneSyncBlock.READINESS
                }
            }

            uploaded.error == PhoneUploadError.PERMISSION -> {
                PhoneSyncBlock.PERMISSION
            }

            uploaded.result == PhoneUploadResult.BLOCKED -> {
                // Nur noch dauerhaft abgewiesene Events: kein weiterer Lauf hilft. Sonst wartet der Rest auf den Download.
                if (uploaded.pendingEvents == 0L && uploaded.pendingMetadata == 0L && (uploaded.rejectedEvents ?: 0) > 0) {
                    PhoneSyncBlock.UPLOAD_REJECTED
                } else {
                    PhoneSyncBlock.UPLOAD_WAITING
                }
            }

            else -> {
                null
            }
        }

    fun close() {
        confined()
        if (closed) return
        closed = true
        cycle = null
        lifecycle.cancel()
        mutable.value = PhoneSyncState()
    }

    companion object {
        const val MAX_PASSES = 4
        const val CONTINUE_DELAY_MS = 60_000L
        const val FOREGROUND_INTERVAL_MS = 60_000L
        const val BACKOFF_BASE_MS = 30_000L
        const val BACKOFF_CAP_MS = 3_600_000L
        private val CONTINUE = setOf(PhoneUploadResult.LIMIT_REACHED, PhoneUploadResult.PARTIAL)

        /** 30 s, 60 s, 2 min … höchstens 1 h; [failures] zählt ab 1. */
        fun backoffMs(failures: Int): Long = minOf(BACKOFF_BASE_MS shl (failures - 1).coerceIn(0, 20), BACKOFF_CAP_MS)
    }
}
