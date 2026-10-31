package android.icu.text

import java.text.CharacterIterator

/**
 * Stand-in for android.icu.text.StringSearch, reduced to a literal substring search.
 *
 * shortcut: matches are literal, not collation-based (ICU's PRIMARY pairs "cafe" with "café");
 * upgrade when a verified source's search results depend on accent-insensitive matching. The
 * pattern and target reach this class already casefolded by [Normalizer2], so case is handled.
 */
class StringSearch(
    pattern: String,
    target: CharacterIterator,
    @Suppress("UNUSED_PARAMETER") collator: RuleBasedCollator,
) {

    private var text: String = target.readText()
    private var index: Int = DONE

    var pattern: String = pattern
        set(value) {
            field = value
            index = DONE
        }

    var target: CharacterIterator = target
        set(value) {
            field = value
            text = value.readText()
            index = DONE
        }

    var isOverlapping: Boolean = false

    val matchedText: String
        get() = if (index == DONE) "" else text.substring(index, index + pattern.length)

    fun first(): Int = moveTo(0)

    fun next(): Int = if (index == DONE) DONE else moveTo(index + if (isOverlapping) 1 else pattern.length)

    private fun moveTo(from: Int): Int {
        index = if (pattern.isEmpty()) DONE else text.indexOf(pattern, from)
        return index
    }

    companion object {
        const val DONE: Int = -1
    }
}