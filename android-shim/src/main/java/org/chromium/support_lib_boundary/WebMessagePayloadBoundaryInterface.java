package org.chromium.support_lib_boundary;

public interface WebMessagePayloadBoundaryInterface {
    int getType();

    String getAsString();

    byte[] getAsArrayBuffer();
}
