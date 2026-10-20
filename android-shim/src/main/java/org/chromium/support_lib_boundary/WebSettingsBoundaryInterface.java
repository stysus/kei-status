package org.chromium.support_lib_boundary;

import java.util.Map;

public interface WebSettingsBoundaryInterface {
    void setUserAgentMetadataFromMap(Map<String, Object> metadata);

    Map<String, Object> getUserAgentMetadataMap();

    void setIncludeCookiesOnIntercept(boolean includeCookies);
}
