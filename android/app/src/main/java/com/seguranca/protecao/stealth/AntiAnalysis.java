package com.seguranca.protecao.stealth;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.Log;
import org.lsposed.lsparanoid.Obfuscate;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;

/**
 * Módulo de contra-inteligência.
 * Detecta emuladores, debuggers, frameworks de hooking e ambientes de análise.
 */
@Obfuscate
public class AntiAnalysis {

    private static final String TAG = "AA";
    private final Context context;

    // Paths conhecidos de binários de root
    private static final String[] ROOT_PATHS = {
        "/system/xbin/su", "/system/bin/su", "/sbin/su",
        "/system/su", "/system/bin/.ext/.su",
        "/system/usr/we-need-root/su-backup",
        "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
        "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
        "/system/xbin/busybox", "/system/bin/busybox",
        "/system/xbin/magisk", "/sbin/magisk"
    };

    // Packages de frameworks de hooking
    private static final String[] HOOKING_PACKAGES = {
        "de.robv.android.xposed.installer",
        "org.meowcat.edxposed.manager",
        "org.lsposed.manager",
        "io.va.exposed",
        "com.saurik.substrate",
        "com.topjohnwu.magisk"
    };

    // Packages de emuladores
    private static final String[] EMULATOR_PACKAGES = {
        "com.google.android.launcher.layouts.genymotion",
        "com.bluestacks.settings",
        "com.bignox.appcenter",
        "com.vphone.launcher",
        "me.haima.androidassist"
    };

    public AntiAnalysis(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Detecta se está rodando em emulador.
     */
    public boolean isEmulator() {
        int score = 0;

        try {
            // Build properties
            String fingerprint = Build.FINGERPRINT;
            if (fingerprint != null) {
                String fp = fingerprint.toLowerCase();
                if (fp.contains("generic")) score += 15;
                if (fp.contains("unknown")) score += 10;
                if (fp.contains("sdk")) score += 15;
                if (fp.contains("vbox")) score += 20;
                if (fp.contains("test-keys")) score += 10;
            }

            String model = Build.MODEL != null ? Build.MODEL.toLowerCase() : "";
            if (model.contains("google_sdk") || model.contains("emulator") ||
                model.contains("android sdk") || model.contains("droid4x")) {
                score += 20;
            }

            String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase() : "";
            if (manufacturer.contains("genymotion") || manufacturer.contains("unknown")) {
                score += 20;
            }

            String hardware = Build.HARDWARE != null ? Build.HARDWARE.toLowerCase() : "";
            if (hardware.contains("goldfish") || hardware.contains("ranchu") || hardware.contains("vbox")) {
                score += 25;
            }

            String product = Build.PRODUCT != null ? Build.PRODUCT.toLowerCase() : "";
            if (product.contains("sdk") || product.contains("vbox") || product.contains("nox")) {
                score += 15;
            }

            String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";
            if (brand.startsWith("generic") || brand.equals("android")) {
                score += 10;
            }

            String device = Build.DEVICE != null ? Build.DEVICE.toLowerCase() : "";
            if (device.startsWith("generic") || device.contains("vbox")) {
                score += 15;
            }

            // Arquivos de emulador
            String[] emuFiles = {
                "/dev/qemu_pipe", "/dev/socket/qemud",
                "/system/lib/libc_malloc_debug_qemu.so",
                "/sys/qemu_trace", "/system/bin/qemu-props"
            };
            for (String path : emuFiles) {
                if (new File(path).exists()) {
                    score += 15;
                    break;
                }
            }

            // Sensores (emuladores geralmente não têm sensores físicos reais)
            SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sm != null) {
                Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                Sensor gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                if (accel == null && gyro == null) {
                    score += 15; // Nenhum sensor de movimento
                }
            }

            // Packages de emulador
            PackageManager pm = context.getPackageManager();
            for (String pkg : EMULATOR_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    score += 20;
                    break;
                } catch (PackageManager.NameNotFoundException ignored) {}
            }

        } catch (Exception e) {
            Log.e(TAG, "Emulator check erro: " + e.getMessage());
        }

        return score >= 40;
    }

    /**
     * Detecta se debugger está conectado.
     */
    public boolean isDebuggerAttached() {
        try {
            // Check direto
            if (android.os.Debug.isDebuggerConnected()) {
                return true;
            }

            // TracerPid em /proc/self/status
            BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    String pidStr = line.substring("TracerPid:".length()).trim();
                    int tracerPid = Integer.parseInt(pidStr);
                    reader.close();
                    if (tracerPid != 0) return true;
                    break;
                }
            }
            reader.close();

            // Timing-based detection (código instrumentado roda mais lento)
            long start = System.nanoTime();
            for (int i = 0; i < 1000000; i++) {
                // operação trivial
                int x = i * i;
            }
            long elapsed = System.nanoTime() - start;

            // Se demorou mais de 50ms para 1M operações triviais, suspeito
            if (elapsed > 50_000_000L) {
                return true;
            }

        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Detecta presença de Frida (framework de instrumentação dinâmica).
     */
    public boolean isFridaPresent() {
        try {
            // Scan de portas do Frida (27042-27049)
            for (int port = 27042; port <= 27049; port++) {
                try {
                    ServerSocket socket = new ServerSocket(port);
                    socket.close();
                    // Se conseguiu abrir, porta está livre = sem Frida nessa porta
                } catch (Exception e) {
                    // Porta em uso — possível Frida
                    return true;
                }
            }

            // Verifica /proc/self/maps para frida-agent
            BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("frida") || lower.contains("gadget")) {
                    reader.close();
                    return true;
                }
            }
            reader.close();

            // Verifica strings na memória via /proc/self/mem (não praticável sem root)
            // Fallback: verifica processos
            try {
                Process process = Runtime.getRuntime().exec("ps");
                BufferedReader procReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String procLine;
                while ((procLine = procReader.readLine()) != null) {
                    if (procLine.toLowerCase().contains("frida")) {
                        procReader.close();
                        return true;
                    }
                }
                procReader.close();
            } catch (Exception ignored) {}

        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Detecta se o dispositivo está rooteado.
     */
    public boolean isRooted() {
        try {
            // Verifica binários de root
            for (String path : ROOT_PATHS) {
                if (new File(path).exists()) {
                    return true;
                }
            }

            // Verifica Build.TAGS
            if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
                return true;
            }

            // Tenta executar su
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"which", "su"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String result = reader.readLine();
                reader.close();
                if (result != null && !result.isEmpty()) {
                    return true;
                }
            } catch (Exception ignored) {}

            // Verifica mount points RW
            try {
                Process process = Runtime.getRuntime().exec("mount");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("/system") && line.contains("rw,")) {
                        reader.close();
                        return true;
                    }
                }
                reader.close();
            } catch (Exception ignored) {}

        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Detecta frameworks de hooking (Xposed, EdXposed, LSPosed, Substrate).
     */
    public boolean isHookingFrameworkPresent() {
        try {
            PackageManager pm = context.getPackageManager();
            for (String pkg : HOOKING_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    return true;
                } catch (PackageManager.NameNotFoundException ignored) {}
            }

            // Verifica stack trace para Xposed
            try {
                throw new Exception("Stack check");
            } catch (Exception e) {
                for (StackTraceElement element : e.getStackTrace()) {
                    String cls = element.getClassName();
                    if (cls != null && (cls.contains("xposed") || cls.contains("substrate"))) {
                        return true;
                    }
                }
            }

        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Calcula nível de ameaça geral (0-100).
     * Combina todos os checks com pesos.
     */
    public int getThreatLevel() {
        int threat = 0;

        try {
            if (isEmulator()) threat += 40;
            if (isDebuggerAttached()) threat += 30;
            if (isFridaPresent()) threat += 35;
            if (isRooted()) threat += 10; // Root sozinho não é ameaça forte
            if (isHookingFrameworkPresent()) threat += 25;
        } catch (Exception e) {
            Log.e(TAG, "ThreatLevel erro: " + e.getMessage());
        }

        return Math.min(100, threat);
    }

    /**
     * Retorna resposta recomendada baseada no nível de ameaça.
     * @return StealthProtocol.MODE_*
     */
    public int getResponse(int threatLevel) {
        if (threatLevel >= 90) return StealthProtocol.MODE_ABORT;
        if (threatLevel >= 60) return StealthProtocol.MODE_PARANOID;
        if (threatLevel >= 30) return StealthProtocol.MODE_STEALTH;
        return StealthProtocol.MODE_NORMAL;
    }
}
