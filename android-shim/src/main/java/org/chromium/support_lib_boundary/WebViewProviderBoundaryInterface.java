package org.chromium.support_lib_boundary;

import java.lang.reflect.InvocationHandler;

public interface WebViewProviderBoundaryInterface {
    void setAudioMuted(boolean mute);

    InvocationHandler addDocumentStartJavaScript(String script, String[] allowedOriginRules);

    int getJavaScriptWorld(String worldName);

    InvocationHandler addJavaScriptOnEvent(
            String script, String[] allowedOriginRules, int injectionEvent, String worldName);

    void addWebMessageListener(
            String jsObjectName, String[] allowedOriginRules, InvocationHandler listener);
}
