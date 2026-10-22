package android.content

/**
 * Thrown by [Context.startActivity] because the harness has no foreground UI.
 *
 * This is the same failure a device without the target activity produces, which is the case
 * extensions already handle: a source that wants the user to clear a captcha in a WebView catches
 * this and reports its own "solve it in the WebView" message instead of appearing broken.
 */
class ActivityNotFoundException(message: String? = null) : RuntimeException(message)
