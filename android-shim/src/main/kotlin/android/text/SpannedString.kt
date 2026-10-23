package android.text

/** Stand-in for android.text.SpannedString: a [Spanned] over text with no spans attached. */
open class SpannedString(private val text: CharSequence) : Spanned {

    override val length: Int get() = text.length

    override fun get(index: Int): Char = text[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        SpannedString(text.subSequence(startIndex, endIndex))

    override fun toString(): String = text.toString()

    override fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<Any> = emptyArray()

    override fun getSpanStart(tag: Any): Int = -1

    override fun getSpanEnd(tag: Any): Int = -1

    override fun getSpanFlags(tag: Any): Int = 0

    override fun <T> nextSpanTransition(start: Int, limit: Int, type: Class<T>?): Int = limit
}
