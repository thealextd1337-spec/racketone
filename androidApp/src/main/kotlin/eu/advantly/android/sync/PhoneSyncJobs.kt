package eu.advantly.android.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
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
import eu.advantly.presentation.friends.Handover
import eu.advantly.presentation.friends.LiveScoreUpload
import eu.advantly.presentation.friends.PendingScorerStore
import eu.advantly.presentation.friends.PendingVisitorStore
import eu.advantly.presentation.friends.ScorerInvite
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

    /**
     * Sofortiger Live-Upload als Expedited Job (API 31+): läuft auch in Doze, wenn die Uhr zählt und das Handy in der
     * Tasche liegt. Ohne Quote führt das System ihn als normalen Auftrag aus; lehnt es ihn ab, gilt der normale Weg.
     */
    private fun scheduleNow(profile: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val result = runCatching { jobs.schedule(job(SYNC_JOB_ID, profile).setExpedited(true).build()) }.getOrNull()
            if (result == JobScheduler.RESULT_SUCCESS) return
        }
        schedule(profile, 0)
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
    ) = scheduleUnlessBackingOff(profile, record, MATCH_END_SYNC_DELAY_MS)

    /**
     * Lauf nach [delayMs], außer ein Backoff ist geplant (dann bleibt dessen Termin: offline oder gestört, kein Hämmern).
     * Live-Stand (plan-live-mitspieler §3.1) und Matchende; Netzbedingung und Doze regelt der JobScheduler.
     */
    fun scheduleUnlessBackingOff(
        profile: String,
        record: PhoneSyncRecord,
        delayMs: Long,
    ) {
        val pending = jobs.getPendingJob(SYNC_JOB_ID)?.extras?.getString(PROFILE_EXTRA) == profile
        if (pending && record.failures(profile) > 0) return
        if (delayMs <= 0) scheduleNow(profile) else schedule(profile, delayMs)
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
    PendingVisitorStore,
    PendingScorerStore {
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
            .remove("scorers.pending.$profile")
            // Altlast bis 0.5.x (Live-Upload nur mit Zuschauern); wird nicht mehr geschrieben.
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

    // Zählen weitergeben (§3.4): „Match/Profil/0|1“ (1 = am Server), nach der Abgabe zusätzlich „/Event/von/an“ (wartet
    // auf die Antwort des Servers); UUIDs ohne Schrägstrich.
    override fun scorerInvites(profileId: String): Map<String, ScorerInvite> =
        prefs
            .getStringSet("scorers.pending.$profileId", null)
            .orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split('/')
                val handover = if (parts.size == 6) Handover(parts[3], parts[4], parts[5]) else null
                if (parts.size != 3 && handover == null) return@mapNotNull null
                parts[0] to ScorerInvite(parts[1], parts[2] == "1", handover)
            }.toMap()

    override fun setScorerInvites(
        profileId: String,
        invites: Map<String, ScorerInvite>,
    ) {
        val entries =
            invites
                .map { (match, invite) ->
                    "$match/${invite.scorerId}/${if (invite.sent) 1 else 0}" +
                        invite.handover?.let { "/${it.eventId}/${it.from}/${it.to}" }.orEmpty()
                }.toSet()
        prefs.edit().putStringSet("scorers.pending.$profileId", entries).apply()
    }

    companion object {
        const val FILE = "advantly_sync"
    }
}

/**
 * Matchende auch ohne Activity (etwa ein empfangenes Uhr-Match über den Listener-Service): ein Abgleich, sobald Netz
 * da ist. Nur mit lokalem Konto; der Auftrag läuft ausschließlich für dieses Konto. Ebenso eine geänderte
 * Telemetrie-Wahl (H0, auch von der Uhr): der Lauf bringt sie ins Konto. Und der Live-Stand ([onLiveScore]): Punkte
 * der Uhr kommen auch ohne Activity und ohne Konto-Host über den Listener-Service herein.
 */
internal fun scheduleSyncAfterMatchEnd(
    context: Context,
    session: MatchSession,
): () -> Unit {
    if (BuildConfig.SUPABASE_URL.isBlank()) return {}
    val scheduler = AndroidPhoneSyncScheduler(context)
    val record = AndroidPhoneSyncRecord(context)
    val profile = {
        AndroidInstallationGuard
            .storage(context) {
                session.data.accountIdentities.profile
                    .orEmpty()
            }?.takeIf { it.isNotEmpty() }
    }
    val schedule = {
        profile()?.let { scheduler.scheduleAfterMatchEnd(it, record) }
        Unit
    }
    val removeMatch = onMatchFinished(session, schedule)
    val removeTelemetry = onTelemetryChanged(session, schedule)
    val live =
        LiveScoreUpload(System::currentTimeMillis, { account, delayMs -> scheduler.scheduleUnlessBackingOff(account, record, delayMs) })
    val removeLive = onLiveScore(session, live, profile)
    return {
        removeMatch()
        removeTelemetry()
        removeLive()
    }
}

/**
 * Live-Stand für Mitspieler und Zuschauer (plan-live-mitspieler §3.1): jedes laufende Match, das dieses Handy oder seine
 * Uhr zählt (auch „Meine Uhr“, bevor sie sich gemeldet hat), sofort beim Start, danach höchstens alle 10 s bei
 * Änderung ([LiveScoreUpload]); nach einer Übergabe an ein anderes Konto nicht mehr.
 */
internal fun onLiveScore(
    session: MatchSession,
    upload: LiveScoreUpload,
    profile: () -> String?,
): () -> Unit {
    val listener =
        SessionListener { state ->
            val running = state.matches.filter { it.state.status != MatchStatus.FINISHED }
            if (running.isEmpty()) return@SessionListener
            val account = profile()
            running.forEach {
                val scoring = it.scorerDeviceId == MatchSession.PENDING_WATCH || session.scoresHere(it.id)
                upload.onScore(account, it.id.value, it.eventCount, scoring)
            }
        }
    session.addListener(listener)
    return { session.removeListener(listener) }
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
