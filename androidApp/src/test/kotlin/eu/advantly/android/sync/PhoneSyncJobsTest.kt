package eu.advantly.android.sync

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import eu.advantly.android.UnsupportedDirectorySyncLinux
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** B4: geplante Abgleiche über JobScheduler – Netzbedingung, Neustartfestigkeit und Kontobindung jedes Auftrags. */
@RunWith(RobolectricTestRunner::class)
// Gleicher Linux-Shadow wie die Restore-Guard-Tests: SharedPreferences-Schreibvorgänge binden sonst das Standard-ShadowLinux
// an das statische Linux-Objekt der geteilten Sandbox, und läuft diese Klasse zuerst, scheitern jene am Cast.
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class PhoneSyncJobsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val jobs = app.getSystemService(JobScheduler::class.java)

    private fun assertBackgroundJob(
        job: JobInfo,
        profile: String,
    ) {
        assertEquals(ComponentName(app, PhoneSyncJobService::class.java), job.service)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertTrue(job.isPersisted)
        assertEquals(profile, job.extras.getString(PROFILE_EXTRA))
    }

    @Test fun retryAndPeriodicJobsWaitForNetworkSurviveRebootAndCarryTheirAccount() {
        val scheduler = AndroidPhoneSyncScheduler(app)
        scheduler.schedule("alice", 30_000)
        assertBackgroundJob(jobs.getPendingJob(SYNC_JOB_ID)!!, "alice")
        assertEquals(30_000, jobs.getPendingJob(SYNC_JOB_ID)!!.minLatencyMillis)
        // Eine ID: Der neue Auftrag ersetzt den alten, es stapelt sich nichts.
        scheduler.schedule("alice", 60_000)
        assertEquals(60_000, jobs.getPendingJob(SYNC_JOB_ID)!!.minLatencyMillis)

        scheduler.ensurePeriodic("alice")
        val periodic = jobs.getPendingJob(PERIODIC_SYNC_JOB_ID)!!
        assertBackgroundJob(periodic, "alice")
        assertTrue(periodic.isPeriodic)
        assertEquals(PERIODIC_SYNC_MS, periodic.intervalMillis)
        // Jeder App-Start fragt erneut: Für dasselbe Konto bleibt der Takt unverändert, ein anderes plant neu.
        scheduler.ensurePeriodic("alice")
        assertSame(periodic, jobs.getPendingJob(PERIODIC_SYNC_JOB_ID))
        scheduler.ensurePeriodic("bob")
        assertBackgroundJob(jobs.getPendingJob(PERIODIC_SYNC_JOB_ID)!!, "bob")
        assertEquals(2, jobs.allPendingJobs.size)

        scheduler.cancel()
        assertTrue(jobs.allPendingJobs.isEmpty())
    }

    @Test fun recordKeepsLastBackupAndFailuresPerAccount() {
        val record = AndroidPhoneSyncRecord(app)
        assertNull(record.lastBackupAtMs("alice"))
        assertEquals(0, record.failures("alice"))
        record.save("alice", 1_000, 3)
        record.save("bob", null, 1)
        val reopened = AndroidPhoneSyncRecord(app)
        assertEquals(1_000L, reopened.lastBackupAtMs("alice"))
        assertEquals(3, reopened.failures("alice"))
        assertNull(reopened.lastBackupAtMs("bob"))
        assertEquals(1, reopened.failures("bob"))
        record.save("alice", null, 0)
        assertNull(reopened.lastBackupAtMs("alice"))
    }

    /** P3-7: Logout/Löschantrag entfernen beide Schlüssel des Kontos, andere Konten bleiben. */
    @Test fun forgetRemovesOnlyTheKeysOfTheAccount() {
        val record = AndroidPhoneSyncRecord(app)
        record.save("alice", 1_000, 2)
        record.save("bob", 2_000, 1)
        record.forget("alice")
        val prefs = app.getSharedPreferences(AndroidPhoneSyncRecord.FILE, Context.MODE_PRIVATE)
        assertEquals(setOf("last_backup.bob", "failures.bob"), prefs.all.keys)
    }

    /** Zuschauer: vorgemerkte Benennungen und beobachtete Matches je Konto, Logout entfernt sie. */
    @Test fun visitorQueueSurvivesReopenAndIsForgottenWithTheAccount() {
        val record = AndroidPhoneSyncRecord(app)
        record.setPending("alice", mapOf("m1" to setOf("vera", "carol"), "m2" to setOf("dora")))
        record.setWatched("alice", setOf("m3"))
        record.setPending("bob", mapOf("m9" to setOf("eve")))
        val reopened = AndroidPhoneSyncRecord(app)
        assertEquals(mapOf("m1" to setOf("vera", "carol"), "m2" to setOf("dora")), reopened.pending("alice"))
        assertEquals(setOf("m3"), reopened.watched("alice"))
        record.forget("alice")
        assertEquals(emptyMap<String, Set<String>>(), reopened.pending("alice"))
        assertEquals(emptySet<String>(), reopened.watched("alice"))
        assertEquals(mapOf("m9" to setOf("eve")), reopened.pending("bob"))
    }

    /** P3-3: Ein Matchende plant nach 10 s, verkürzt aber keinen aktiven Backoff. */
    @Test fun matchEndKeepsAnActiveBackoffButReplacesOtherPendingWork() {
        val scheduler = AndroidPhoneSyncScheduler(app)
        val record = AndroidPhoneSyncRecord(app)
        scheduler.scheduleAfterMatchEnd("alice", record)
        assertEquals(MATCH_END_SYNC_DELAY_MS, jobs.getPendingJob(SYNC_JOB_ID)!!.minLatencyMillis)
        // Fortsetzung ohne Fehlerserie ist kein Backoff: Das Matchende darf früher abgleichen.
        scheduler.schedule("alice", 60_000)
        scheduler.scheduleAfterMatchEnd("alice", record)
        assertEquals(MATCH_END_SYNC_DELAY_MS, jobs.getPendingJob(SYNC_JOB_ID)!!.minLatencyMillis)

        record.save("alice", null, 4)
        scheduler.schedule("alice", 240_000)
        scheduler.scheduleAfterMatchEnd("alice", record)
        assertEquals(240_000, jobs.getPendingJob(SYNC_JOB_ID)!!.minLatencyMillis)
        // Ohne geplanten Auftrag (etwa schon gestartet) plant das Matchende trotz Fehlerserie.
        scheduler.cancel()
        scheduler.scheduleAfterMatchEnd("alice", record)
        assertEquals(MATCH_END_SYNC_DELAY_MS, jobs.getPendingJob(SYNC_JOB_ID)!!.minLatencyMillis)
    }

    /** P3-1: Endet ein ersetzter Lauf erst nach dem Start seines Nachfolgers, meldet er nicht dessen Ende. */
    @Test fun aReplacedRunNeverFinishesItsSuccessor() {
        val service = Robolectric.buildService(PhoneSyncJobService::class.java).create().get()
        val release = CompletableDeferred<Unit>()
        val successor = CompletableDeferred<Boolean>()
        var runs = 0
        service.work = { _, _ ->
            if (runs++ == 0) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { release.await() }
                }
            } else {
                successor.await()
            }
        }
        // Main.immediate auf dem Test-Main-Thread setzt sofort fort; kein Looper-idle() nötig.
        val shadow = shadowOf(service)
        val first = params(SYNC_JOB_ID)
        assertTrue(service.onStartJob(first))
        assertTrue(service.onStopJob(first))
        assertTrue(service.onStartJob(params(SYNC_JOB_ID)))
        release.complete(Unit)
        assertFalse("der ersetzte Lauf trägt den Nachfolger aus", shadow.isJobFinished)
        successor.complete(true)
        assertTrue(shadow.isJobFinished)
        assertTrue(shadow.isRescheduleNeeded)
        service.onDestroy()
    }

    /** JobParameters hat keinen öffentlichen Konstruktor; der erste Int ist die jobId. */
    private fun params(id: Int): JobParameters {
        val constructor = JobParameters::class.java.declaredConstructors.maxBy { it.parameterCount }
        var idSet = false
        val args =
            constructor.parameterTypes.map {
                when (it) {
                    Int::class.javaPrimitiveType -> if (idSet) 0 else id.also { idSet = true }
                    Boolean::class.javaPrimitiveType -> false
                    PersistableBundle::class.java -> PersistableBundle().apply { putString(PROFILE_EXTRA, "alice") }
                    else -> null
                }
            }
        constructor.isAccessible = true
        return constructor.newInstance(*args.toTypedArray()) as JobParameters
    }
}
