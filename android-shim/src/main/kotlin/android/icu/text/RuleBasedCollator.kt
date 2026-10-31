package android.icu.text

/**
 * Stand-in for android.icu.text.RuleBasedCollator, holding a java.text delegate.
 *
 * ICU's constants differ from the JVM's: NO_DECOMPOSITION is 16 where java.text uses 0, while
 * PRIMARY is 0 in both, so only decomposition needs translating.
 */
class RuleBasedCollator internal constructor() : Collator() {

    private val delegate = java.text.Collator.getInstance() as java.text.RuleBasedCollator

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

    private companion object {
        const val NO_DECOMPOSITION: Int = 16
    }
}