package org.chromium.support_lib_boundary;

import java.lang.reflect.InvocationHandler;

public interface JsReplyProxyBoundaryInterface {
    void postMessage(String message);

    void executeJavaScript(String script, InvocationHandler callback);
}
