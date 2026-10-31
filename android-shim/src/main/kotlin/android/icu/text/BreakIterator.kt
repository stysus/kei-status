package android.icu.text

import java.text.CharacterIterator

/**
 * Stand-in for android.icu.text.BreakIterator on top of java.text.
 *
 * Kotlin code compiled against android.jar calls `getRuleStatus()`, which java.text.BreakIterator
 * does not expose, so the category is derived from the segment that ended at the current boundary:
 * one holding a letter or digit is a word. That is all ProjectSuki's title tokenizer asks for.
 */
class BreakIterator private constructor(private val delegate: java.text.BreakIterator) {

    private var text = ""
    private var segmentStart = DONE

    fun setText(text: CharacterIterator) {
        delegate.setText(text)
        this.text = text.readText()
        segmentStart = DONE
    }

    fun first(): Int = delegate.first().also { segmentStart = DONE }

    fun next(): Int {
        val current = delegate.current()
        val next = delegate.next()
        segmentStart = current
        return next
    }

    fun getRuleStatus(): Int {
        val right = delegate.current()
        if (right == DONE || segmentStart == DONE) return WORD_NONE

        // ICU reports a positive category for a word (WORD_NUMBER = 100, WORD_LETTER = 200) and
        // zero otherwise; every caller here only tells zero from non-zero.
        return if (text.substring(segmentStart, right).any { it.isLetterOrDigit() }) WORD_LETTER else WORD_NONE
    }

    companion object {
        const val DONE: Int = -1
        const val WORD_NONE: Int = 0

        private const val WORD_LETTER: Int = 200

        @JvmStatic
        fun getCharacterInstance(): BreakIterator = BreakIterator(java.text.BreakIterator.getCharacterInstance())

        @JvmStatic
        fun getWordInstance(): BreakIterator = BreakIterator(java.text.BreakIterator.getWordInstance())
    }
}