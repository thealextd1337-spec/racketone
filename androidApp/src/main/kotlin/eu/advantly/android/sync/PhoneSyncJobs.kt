package eu.advantly.android.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import eu.advantly.android.BuildConfig
import eu.advantly.android.auth.PhoneSyncRecord
import eu.advantly.android.auth.PhoneSyncScheduler
import eu.advantly.android.auth.runScheduledSync
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionListener
import eu.advantly.match.session.SessionState
import eu.advantly.presentation.friends.PendingVisitorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

internal const val SYNC_JOB_ID = 41_001
internal const val PERIODIC_SYNC_JOB_ID = 41_002
internal const val PROFILE_EXTRA = "profile"
internal const val PERIODIC_SYNC_MS = 6 * 60 * 60 * 1000L
internal const val MATCH_END_SYNC_DELAY_MS = 10_000L

/**
 * JobScheduler statt WorkManager: keine neue Abhängigkeit. Netzbedingung, Doze und App-Standby regelt das System,
 * persistente Aufträge überstehen Prozessende und Neustart. Ein neuer Auftrag mit derselben ID ersetzt den alten.
 */
internal class AndroidPhoneSyncScheduler(
    context: Context,
) : PhoneSyncScheduler {
    private val app = context.applicationContext
    private val jobs = app.getSystemService(JobScheduler::class.java)

    override fun schedule(
        profile: String,
        delayMs: Long,
    ) {
        jobs.schedule(job(SYNC_JOB_ID, profile).setMinimumLatency(delayMs).build())
    }

    override fun ensurePeriodic(profile: String) {
        // Unverändert lassen: Ein Neuplanen bei jedem App-Start verschöbe den Takt immer weiter.
        if (jobs.getPendingJob(PERIODIC_SYNC_JOB_ID)?.extras?.getString(PROFILE_EXTRA) == profile) return
        jobs.schedule(job(PERIODIC_SYNC_JOB_ID, profile).setPeriodic(PERIODIC_SYNC_MS).build())
    }

    /** Ein Matchende verkürzt keinen aktiven Backoff: Dessen späterer Termin bleibt, sonst Lauf nach 10 s. */
    fun scheduleAfterMatchEnd(
        profile: String,
        record: PhoneSyncRecord,
    ) {
        val pending = jobs.getPendingJob(SYNC_JOB_ID)?.extras?.getString(PROFILE_EXTRA) == profile
        if (pending && record.failures(profile) > 0) return
        schedule(profile, MATCH_END_SYNC_DELAY_MS)
    }

    override fun cancel() {
        jobs.cancel(SYNC_JOB_ID)
        jobs.cancel(PERIODIC_SYNC_JOB_ID)
    }

    private fun job(
        id: Int,
        profile: String,
    ) = JobInfo
        .Builder(id, ComponentName(app, PhoneSyncJobService::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPersisted(true)
        .setExtras(PersistableBundle().apply { putString(PROFILE_EXTRA, profile) })
}

/** Nicht im Backup (`backup_rules`): ein wiederhergestelltes „zuletzt gesichert“ beschriebe ein anderes Gerät. */
internal class AndroidPhoneSyncRecord(
    context: Context,
) : PhoneSyncRecord,
    PendingVisitorStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun lastBackupAtMs(profile: String): Long? = prefs.getLong("last_backup.$profile", -1).takeIf { it >= 0 }

    override fun failures(profile: String): Int = prefs.getInt("failures.$profile", 0)

    override fun save(
        profile: String,
        lastBackupAtMs: Long?,
        failures: Int,
    ) {
        prefs
            .edit()
            .apply {
                if (lastBackupAtMs == null) remove("last_backup.$profile") else putLong("last_backup.$profile", lastBackupAtMs)
            }.putInt("failures.$profile", failures)
            .apply()
    }

    override fun forget(profile: String) {
        prefs
            .edit()
            .remove("last_backup.$profile")
            .remove("failures.$profile")
            .remove("visitors.pending.$profile")
            .remove("visitors.watched.$profile")
            .apply()
    }

    // Zuschauer (F16): vorgemerkte Benennungen als „Match/Profil“, beides UUIDs ohne Schrägstrich.
    override fun pending(profileId: String): Map<String, Set<String>> =
        prefs
            .getStringSet("visitors.pending.$profileId", null)
            .orEmpty()
            .mapNotNull { entry -> entry.split('/').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    override fun setPending(
        profileId: String,
        pending: Map<String, Set<String>>,
    ) {
        val entries = pending.flatMap { (match, visitors) -> visitors.map { "$match/$it" } }.toSet()
        prefs.edit().putStringSet("visitors.pending.$profileId", entries).apply()
    }

    override fun watched(profileId: String): Set<String> = prefs.getStringSet("visitors.watched.$profileId", null).orEmpty().toSet()

    override fun setWatched(
        profileId: String,
        matchIds: Set<String>,
    ) {
        prefs.edit().putStringSet("visitors.watched.$profileId", matchIds).apply()
    }

    companion object {
        const val FILE = "advantly_sync"
    }
}

/**
 * Matchende auch ohne Activity (etwa ein empfangenes Uhr-Match über den Listener-Service): ein Abgleich, sobald Netz
 * da ist. Nur mit lokalem Konto; der Auftrag läuft ausschließlich für dieses Konto. Ebenso eine geänderte
 * Telemetrie-Wahl (H0, auch von der Uhr): der Lauf bringt sie ins Konto.
 */
internal fun scheduleSyncAfterMatchEnd(
    context: Context,
    session: MatchSession,
): () -> Unit {
    if (BuildConfig.SUPABASE_URL.isBlank()) return {}
    val scheduler = AndroidPhoneSyncScheduler(context)
    val record = AndroidPhoneSyncRecord(context)
    val schedule = {
        AndroidInstallationGuard
            .storage(context) {
                session.data.accountIdentities.profile
                    .orEmpty()
            }?.takeIf { it.isNotEmpty() }
            ?.let { scheduler.scheduleAfterMatchEnd(it, record) }
        Unit
    }
    val removeMatch = onMatchFinished(session, schedule)
    val removeTelemetry = onTelemetryChanged(session, schedule)
    return {
        removeMatch()
        removeTelemetry()
    }
}

/** Meldet jede Änderung der Telemetrie-Wahl (lokal, von der Uhr, aus dem Konto oder durch Kontowechsel). */
internal fun onTelemetryChanged(
    session: MatchSession,
    changed: () -> Unit,
): () -> Unit {
    var known = session.state.value.telemetry
    val listener =
        SessionListener { state ->
            if (state.telemetry != known) {
                known = state.telemetry
                changed()
            }
        }
    session.addListener(listener)
    return { session.removeListener(listener) }
}

/** Meldet jedes offene Match, das beendet wird; Schließen in der Anzeige oder Punkte zählen nicht. */
internal fun onMatchFinished(
    session: MatchSession,
    finished: () -> Unit,
): () -> Unit {
    fun finishedIds(state: SessionState) = state.matches.filter { it.state.status == MatchStatus.FINISHED }.mapTo(HashSet()) { it.id }
    var known = finishedIds(session.state.value)
    val listener =
        SessionListener { state ->
            val now = finishedIds(state)
            val ended = !known.containsAll(now)
            known = now
            if (ended) finished()
        }
    session.addListener(listener)
    return { session.removeListener(listener) }
}

/** Führt geplante Abgleiche auf Main über den prozessweiten Konto-Host aus; Gates und Kontobindung prüft der Koordinator. */
class PhoneSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = HashMap<Int, Job>()

    /** Testnaht; im Produkt immer [runScheduledSync]. */
    internal var work: suspend (Context, String?) -> Boolean = ::runScheduledSync

    override fun onStartJob(params: JobParameters): Boolean {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                var reschedule = false
                try {
                    reschedule = work(applicationContext, params.extras.getString(PROFILE_EXTRA))
                } finally {
                    // Ein ersetzter Lauf endet womöglich erst nach dem Start seines Nachfolgers: nur den eigenen Eintrag austragen.
                    if (running[params.jobId] === coroutineContext.job) {
                        running.remove(params.jobId)
                        jobFinished(params, reschedule)
                    }
                }
            }
        running[params.jobId] = job
        job.start()
        return true
    }

    /** Netz weg, Zeitlimit oder Doze: später erneut. Ein ersetzter Auftrag wird dabei nicht verdoppelt. */
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
