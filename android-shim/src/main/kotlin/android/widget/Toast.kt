package android.widget

import android.content.Context
import android.util.Log

/**
 * Accepts the toast an extension shows for user feedback and writes it to the log.
 *
 * There is no window to draw into, so nothing is displayed. The class exists because extensions
 * reference it from ordinary code paths (a login that fails, a preference that needs a restart),
 * and a missing class turns an otherwise reportable scenario into an uncaught
 * `NoClassDefFoundError` on whichever thread happened to reach it.
 */
class Toast private constructor() {

    var duration: Int = LENGTH_SHORT

    private var text: CharSequence? = null

    fun setText(text: CharSequence?) {
        this.text = text
    }

    fun show() {
        Log.i(TAG, text?.toString().orEmpty())
    }

    fun cancel() = Unit

    companion object {
        const val LENGTH_SHORT: Int = 0
        const val LENGTH_LONG: Int = 1

        private const val TAG = "Toast"

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast =
            Toast().apply {
                this.text = text
                this.duration = duration
            }

        /** The `R.string` overload; extensions only reach it through `context.getString`. */
        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): Toast =
            Toast().apply {
                this.text = context?.getString(resId) ?: "string-$resId"
                this.duration = duration
            }
    }
}
