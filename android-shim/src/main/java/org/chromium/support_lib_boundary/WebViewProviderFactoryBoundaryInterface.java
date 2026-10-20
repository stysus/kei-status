package org.chromium.support_lib_boundary;

import java.lang.reflect.InvocationHandler;

/**
 * Compile-time contract of androidx.webkit's boundary layer.
 *
 * core's {@code WebViewGlueBridge} references these types, so they must exist on the harness
 * classpath or that class fails to link. On a real device the glue implementation lives in the
 * WebView APK; here nothing implements them, which is exactly what makes every optional WebView
 * feature report itself as unsupported and fall back to its documented path.
 */
public interface WebViewProviderFactoryBoundaryInterface {
    InvocationHandler getWebkitToCompatConverter();

    String[] getSupportedFeatures();

    InvocationHandler createWebView(android.webkit.WebView webview);
}
