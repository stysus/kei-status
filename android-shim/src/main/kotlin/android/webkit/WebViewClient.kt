package android.webkit

import android.graphics.Bitmap

/** Stand-in for android.webkit.WebViewClient. Overrides are called on the main thread. */
open class WebViewClient {

    open fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

    open fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

    open fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit

    open fun onPageFinished(view: WebView?, url: String?) = Unit

    open fun onLoadResource(view: WebView?, url: String?) = Unit

    open fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? = null

    open fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) = Unit

    open fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) = Unit

    open fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = false
}

/** Stand-in for android.webkit.WebChromeClient. */
open class WebChromeClient {

    open fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = false

    open fun onProgressChanged(view: WebView?, newProgress: Int) = Unit

    open fun onReceivedTitle(view: WebView?, title: String?) = Unit
}
