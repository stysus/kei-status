package org.chromium.support_lib_glue;

import java.lang.reflect.InvocationHandler;

/**
 * Stand-in for the Chromium support-library glue that ships inside the WebView APK.
 *
 * core's {@code WebViewGlueBridge} reflects on this class to reach the WebView provider factory.
 * On a device it returns the real glue; here it returns a factory that supports nothing, which is
 * what the boundary interfaces already promise. Extension code then takes its documented fallback
 * instead of the whole bridge failing with {@link ClassNotFoundException} on every feature probe.
 */
public final class SupportLibReflectionUtil {

    private SupportLibReflectionUtil() {
    }

    public static InvocationHandler createWebViewProviderFactory() {
        return (proxy, method, args) -> {
            switch (method.getName()) {
                case "getSupportedFeatures":
                    return new String[0];
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "WebViewProviderFactory(no supported features)";
                default:
                    return null;
            }
        };
    }
}
