package android.text

/**
 * Stand-in for android.text.Spanned.
 *
 * `Html.fromHtml` is declared to return this type, so it is what the caller's bytecode names. No
 * span is ever attached here: the harness renders text as plain glyphs, and the only consumer -
 * the text-interceptor lib - immediately calls `toString()` on the result.
 */
interface Spanned : CharSequence {

    fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<Any>

    fun getSpanStart(tag: Any): Int

    fun getSpanEnd(tag: Any): Int

    fun getSpanFlags(tag: Any): Int

    fun <T> nextSpanTransition(start: Int, limit: Int, type: Class<T>?): Int

    companion object {
        const val SPAN_MARK_MARK = 0x11
        const val SPAN_MARK_POINT = 0x12
        const val SPAN_POINT_MARK = 0x21
        const val SPAN_POINT_POINT = 0x22
        const val SPAN_PARAGRAPH = 0x33
        const val SPAN_INCLUSIVE_EXCLUSIVE = 0x11
        const val SPAN_INCLUSIVE_INCLUSIVE = 0x12
        const val SPAN_EXCLUSIVE_EXCLUSIVE = 0x21
        const val SPAN_EXCLUSIVE_INCLUSIVE = 0x22
        const val SPAN_COMPOSING = 0x0100
        const val SPAN_USER_SHIFT = 24
        const val SPAN_PRIORITY_SHIFT = 16
    }
}
