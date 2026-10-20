package org.chromium.support_lib_boundary;

import java.lang.reflect.InvocationHandler;

public interface WebkitToCompatConverterBoundaryInterface {
    InvocationHandler convertSettings(android.webkit.WebSettings webSettings);
}
