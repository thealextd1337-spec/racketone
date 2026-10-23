package eu.advantly.android.sync

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** B4: geplante Abgleiche über JobScheduler – Netzbedingung, Neustartfestigkeit und Kontobindung jedes Auftrags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
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
}
