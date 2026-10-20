package android.webkit;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Stand-in for android.webkit.WebResourceResponse.
 *
 * Written in Java so both Android constructors exist with their exact erased signatures; sources
 * construct it directly when they replace a request in {@code interceptRequest}.
 */
public class WebResourceResponse {

    private String mimeType;
    private String encoding;
    private int statusCode;
    private String reasonPhrase;
    private Map<String, String> responseHeaders;
    private InputStream data;

    public WebResourceResponse(String mimeType, String encoding, InputStream data) {
        this(mimeType, encoding, 200, null, new HashMap<>(), data);
    }

    public WebResourceResponse(
            String mimeType,
            String encoding,
            int statusCode,
            String reasonPhrase,
            Map<String, String> responseHeaders,
            InputStream data) {
        this.mimeType = mimeType;
        this.encoding = encoding;
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.responseHeaders = responseHeaders == null ? new HashMap<>() : responseHeaders;
        this.data = data;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(Map<String, String> responseHeaders) {
        this.responseHeaders = responseHeaders == null ? new HashMap<>() : responseHeaders;
    }

    public InputStream getData() {
        return data;
    }

    public void setData(InputStream data) {
        this.data = data;
    }
}
