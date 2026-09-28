package com.seguranca.protecao;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Translucent activity used to host the DeviceAdminAdd prompt.
 * This prevents the "Cannot start ADD_DEVICE_ADMIN as a new task" error.
 */
public class AdminPromptActivity extends Activity {

    private static final String TAG = "AdminPromptActivity";
    private static final int REQUEST_CODE_ADD_ADMIN = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Starting AdminPromptActivity");

        try {
            ComponentName adminComponent = new ComponentName(this, MyAdminReceiver.class);
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Necessário para proteção e formatação do dispositivo.");
            
            // Start without FLAG_ACTIVITY_NEW_TASK because we are calling from an Activity context
            startActivityForResult(intent, REQUEST_CODE_ADD_ADMIN);
            Log.d(TAG, "onCreate: DeviceAdminAdd intent started successfully");
        } catch (Exception e) {
            Log.e(TAG, "onCreate: Failed to start DeviceAdminAdd intent: " + e.getMessage());
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);
        if (requestCode == REQUEST_CODE_ADD_ADMIN) {
            finish();
        }
    }
}
