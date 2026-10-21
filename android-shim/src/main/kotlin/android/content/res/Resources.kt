package android.content.res

import android.util.DisplayMetrics

/**
 * Stand-in for android.content.res.Resources.
 *
 * `getSystem().displayMetrics` is what `runWebView` sizes the view with; a plausible phone-sized
 * viewport matters because some sites render their desktop layout above ~1024px.
 */
open class Resources {

    val displayMetrics: DisplayMetrics = DisplayMetrics()

    val configuration: Configuration = Configuration()

    open fun getDimension(resId: Int): Float = 0f

    open fun getString(resId: Int): String = "string-$resId"

    companion object {
        private val system = Resources()

        @JvmStatic
        fun getSystem(): Resources = system
    }
}
