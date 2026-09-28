package com.seguranca.protecao.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import org.lsposed.lsparanoid.Obfuscate;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Gerenciador de plugins DEX carregados em runtime.
 * Usa DexClassLoader para carregar módulos .dex do armazenamento interno,
 * verificação SHA-256 para integridade, e timeout de execução.
 */
@Obfuscate
public class PluginManager {

    private static final String TAG = "PM";
    private static final String PREFS_NAME = "plugin_registry";
    public static final int HOST_VERSION = 1;

    private static volatile PluginManager sInstance;
    private Context appContext;
    private File pluginDir;
    private File optimizedDir;
    private SharedPreferences prefs;
    private final ExecutorService pluginExecutor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, LoadedPlugin> loadedPlugins = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    /**
     * Estrutura interna de um plugin carregado.
     */
    public static class LoadedPlugin {
        public PluginInterface instance;
        public String pluginId;
        public String version;
        public String className;
        public File dexFile;
        public DexClassLoader classLoader;
        public long loadedAt;
        public String sha256;

        public Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("pluginId", pluginId);
            map.put("version", version);
            map.put("className", className);
            map.put("sha256", sha256);
            map.put("loadedAt", String.valueOf(loadedAt));
            map.put("dexPath", dexFile != null ? dexFile.getAbsolutePath() : "");
            return map;
        }
    }

    private PluginManager() {}

    public static PluginManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PluginManager.class) {
                if (sInstance == null) {
                    sInstance = new PluginManager();
                    sInstance.init(context);
                }
            }
        }
        return sInstance;
    }

    private void init(Context context) {
        this.appContext = context.getApplicationContext();
        this.pluginDir = new File(context.getFilesDir(), "plugins");
        this.optimizedDir = new File(pluginDir, "opt");
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (!pluginDir.exists()) pluginDir.mkdirs();
        if (!optimizedDir.exists()) optimizedDir.mkdirs();

        // Recarrega plugins persistidos
        loadPersistedPlugins();
    }

    /**
     * Carrega um plugin DEX com verificação de integridade.
     * @param dexFile Arquivo .dex a carregar
     * @param className Nome completo da classe que implementa PluginInterface
     * @param expectedSha256 Hash SHA-256 esperado (hex string)
     * @return true se carregou com sucesso
     */
    public boolean loadPlugin(File dexFile, String className, String expectedSha256) {
        if (dexFile == null || !dexFile.exists()) {
            Log.e(TAG, "DEX não encontrado: " + dexFile);
            return false;
        }

        try {
            // Verificação de integridade SHA-256
            String actualHash = calculateSha256(dexFile);
            if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                if (!actualHash.equalsIgnoreCase(expectedSha256)) {
                    Log.e(TAG, "SHA-256 mismatch! Esperado: " + expectedSha256 + " Obtido: " + actualHash);
                    return false;
                }
                Log.d(TAG, "SHA-256 verificado: " + actualHash);
            }

            // Cria ClassLoader isolado
            DexClassLoader classLoader = new DexClassLoader(
                dexFile.getAbsolutePath(),
                optimizedDir.getAbsolutePath(),
                null,
                appContext.getClassLoader()
            );

            // Carrega a classe do plugin
            Class<?> pluginClass = classLoader.loadClass(className);
            Object instance = pluginClass.newInstance();

            if (!(instance instanceof PluginInterface)) {
                Log.e(TAG, "Classe não implementa PluginInterface: " + className);
                return false;
            }

            PluginInterface plugin = (PluginInterface) instance;

            // Verifica compatibilidade
            if (!plugin.isCompatible(HOST_VERSION)) {
                Log.e(TAG, "Plugin incompatível com host v" + HOST_VERSION);
                return false;
            }

            // Descarrega versão anterior se existir
            String pluginId = plugin.getPluginId();
            if (loadedPlugins.containsKey(pluginId)) {
                unloadPlugin(pluginId);
            }

            // Inicializa o plugin
            plugin.onLoad(appContext, new Bundle());

            // Registra
            LoadedPlugin loaded = new LoadedPlugin();
            loaded.instance = plugin;
            loaded.pluginId = pluginId;
            loaded.version = plugin.getPluginVersion();
            loaded.className = className;
            loaded.dexFile = dexFile;
            loaded.classLoader = classLoader;
            loaded.loadedAt = System.currentTimeMillis();
            loaded.sha256 = actualHash;

            loadedPlugins.put(pluginId, loaded);
            persistPluginMetadata();

            Log.d(TAG, "Plugin carregado: " + pluginId + " v" + loaded.version);
            return true;

        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Classe não encontrada: " + className + " - " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Erro carregando plugin: " + e.getMessage());
        }
        return false;
    }

    /**
     * Descarrega um plugin.
     */
    public void unloadPlugin(String pluginId) {
        try {
            LoadedPlugin loaded = loadedPlugins.remove(pluginId);
            if (loaded != null && loaded.instance != null) {
                loaded.instance.onUnload();
                loaded.instance = null;
                loaded.classLoader = null;
                Log.d(TAG, "Plugin descarregado: " + pluginId);
            }
            persistPluginMetadata();
        } catch (Exception e) {
            Log.e(TAG, "Erro descarregando plugin " + pluginId + ": " + e.getMessage());
        }
    }

    /**
     * Executa ação em um plugin com timeout de 30 segundos.
     */
    public Object executePlugin(String pluginId, String action, Bundle params) {
        LoadedPlugin loaded = loadedPlugins.get(pluginId);
        if (loaded == null || loaded.instance == null) {
            Log.e(TAG, "Plugin não encontrado: " + pluginId);
            return null;
        }

        try {
            Future<Object> future = pluginExecutor.submit(() -> {
                return loaded.instance.execute(action, params);
            });

            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            Log.e(TAG, "Plugin timeout (30s): " + pluginId + "." + action);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Erro executando " + pluginId + "." + action + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Lista todos os plugins carregados.
     */
    public List<Map<String, String>> listPlugins() {
        List<Map<String, String>> list = new ArrayList<>();
        for (LoadedPlugin p : loadedPlugins.values()) {
            list.add(p.toMap());
        }
        return list;
    }

    public boolean isPluginLoaded(String pluginId) {
        return loadedPlugins.containsKey(pluginId);
    }

    public PluginInterface getPlugin(String pluginId) {
        LoadedPlugin loaded = loadedPlugins.get(pluginId);
        return loaded != null ? loaded.instance : null;
    }

    /**
     * Recarrega um plugin a partir do mesmo arquivo DEX.
     */
    public boolean reloadPlugin(String pluginId) {
        LoadedPlugin loaded = loadedPlugins.get(pluginId);
        if (loaded == null) return false;

        File dexFile = loaded.dexFile;
        String className = loaded.className;
        String sha256 = loaded.sha256;

        unloadPlugin(pluginId);
        return loadPlugin(dexFile, className, sha256);
    }

    /**
     * Calcula hash SHA-256 de um arquivo.
     * @return Hex string do hash
     */
    public String calculateSha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }

        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Persiste metadados dos plugins carregados em SharedPreferences.
     */
    private void persistPluginMetadata() {
        try {
            List<Map<String, String>> metadata = new ArrayList<>();
            for (LoadedPlugin p : loadedPlugins.values()) {
                Map<String, String> m = p.toMap();
                metadata.add(m);
            }
            String json = gson.toJson(metadata);
            prefs.edit().putString("loaded_plugins", json).apply();
        } catch (Exception e) {
            Log.e(TAG, "Persist erro: " + e.getMessage());
        }
    }

    /**
     * Recarrega plugins que estavam carregados na sessão anterior.
     */
    private void loadPersistedPlugins() {
        try {
            String json = prefs.getString("loaded_plugins", null);
            if (json == null || json.isEmpty()) return;

            Type listType = new TypeToken<List<Map<String, String>>>(){}.getType();
            List<Map<String, String>> metadata = gson.fromJson(json, listType);

            for (Map<String, String> m : metadata) {
                String dexPath = m.get("dexPath");
                String className = m.get("className");
                String sha256 = m.get("sha256");

                if (dexPath != null && className != null) {
                    File dex = new File(dexPath);
                    if (dex.exists()) {
                        loadPlugin(dex, className, sha256);
                    } else {
                        Log.w(TAG, "DEX persistido não encontrado: " + dexPath);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "LoadPersisted erro: " + e.getMessage());
        }
    }

    /**
     * Remove arquivos .dex órfãos que não estão no registry.
     */
    public void cleanup() {
        try {
            File[] files = pluginDir.listFiles();
            if (files == null) return;

            java.util.Set<String> activePaths = new java.util.HashSet<>();
            for (LoadedPlugin p : loadedPlugins.values()) {
                if (p.dexFile != null) activePaths.add(p.dexFile.getAbsolutePath());
            }

            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".dex")) {
                    if (!activePaths.contains(f.getAbsolutePath())) {
                        f.delete();
                        Log.d(TAG, "Órfão removido: " + f.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Cleanup erro: " + e.getMessage());
        }
    }
}
