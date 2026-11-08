package org.chromium.support_lib_boundary;

import java.util.Set;

public interface ProfileBoundaryInterface {

    void addOriginMatchedHeader(String headerName, String headerValue, Set<String> originRules);

    void clearOriginMatchedHeader(String headerName, String headerValue);
}
