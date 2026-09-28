package com.seguranca.protecao;

import android.content.Context;

/**
 * Single source of truth for Android component short names (Settings / shell).
 */
public final class AppComponents {

    public static final String ACCESSIBILITY_SERVICE_SUFFIX = ".UiAssistBridge";
    public static final String ACCESSIBILITY_SERVICE_SIMPLE_NAME = "UiAssistBridge";

    private AppComponents() {
    }

    public static String accessibilityComponent(Context context) {
        return context.getPackageName() + ACCESSIBILITY_SERVICE_SUFFIX;
    }
}
