package com.seguranca.protecao;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class CloneLaunchActivity extends Activity {
    private static final String TAG = "CloneLaunchActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Intent intent = getIntent();
        String targetPkg = intent != null ? intent.getStringExtra("target_package") : null;
        int userId = intent != null ? intent.getIntExtra("target_user_id", 10) : 10;
        
        Log.d(TAG, "🚀 Iniciando app no perfil isolado (User " + userId + "): " + targetPkg);
        
        if (targetPkg != null && !targetPkg.trim().isEmpty()) {
            try {
                // Executa am start no perfil secundário (User 10 - Pasta Segura)
                String cmd = "am start --user " + userId + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " + targetPkg;
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                p.waitFor();
                Log.d(TAG, "✅ App " + targetPkg + " disparado no User " + userId);
            } catch (Exception e) {
                Log.e(TAG, "❌ Erro ao disparar app no User " + userId + ": " + e.getMessage(), e);
            }
        }
        
        finish();
    }
}
