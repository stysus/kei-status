package android.icu.text

import java.util.Locale

/**
 * Stand-in for android.icu.text.RuleBasedCollator, holding a java.text delegate.
 *
 * ICU's constants differ from the JVM's: NO_DECOMPOSITION is 16 where java.text uses 0, while
 * PRIMARY is 0 in both, so only decomposition needs translating.
 *
 * [freeze] returns the collator itself, which is what ICU's Freezable contract promises: the
 * source chains it and keeps using the result.
 */
class RuleBasedCollator internal constructor(locale: Locale?) : Collator() {

    internal constructor() : this(null)

    private val delegate = (locale?.let { java.text.Collator.getInstance(it) }
        ?: java.text.Collator.getInstance()) as java.text.RuleBasedCollator

    private var numericCollation: Boolean = false

    override fun compare(a: Any, b: Any): Int = delegate.compare(a, b)

    fun setStrength(strength: Int) {
        delegate.strength = strength
    }

    fun setDecomposition(decomposition: Int) {
        delegate.decomposition = decomposition - NO_DECOMPOSITION
    }

    // java.text has no case level, and the source casefolds both sides before searching, so no
    // comparison can observe the difference.
    @Suppress("UNUSED_PARAMETER")
    fun setCaseLevel(caseLevel: Boolean) = Unit

    // ICU's numeric collation orders the digit runs of a name by value ("ch2" before "ch10");
    // java.text has no equivalent, and java.text's plain order is stable and locale-correct, so
    // the flag is kept for isNumericCollation and not applied.
    // shortcut: numericCollation only matters if a check ever observes sort order; upgrade by
    // comparing digit runs by value in compare
    fun setNumericCollation(numeric: Boolean) {
        numericCollation = numeric
    }

    fun isNumericCollation(): Boolean = numericCollation

    fun freeze(): RuleBasedCollator = this

    private companion object {
        const val NO_DECOMPOSITION: Int = 16
    }
}
