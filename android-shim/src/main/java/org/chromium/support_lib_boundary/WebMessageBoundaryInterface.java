package org.chromium.support_lib_boundary;

import java.lang.reflect.InvocationHandler;

public interface WebMessageBoundaryInterface {
    String getData();

    InvocationHandler getMessagePayload();
}
