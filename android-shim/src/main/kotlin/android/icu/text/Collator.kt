package android.icu.text

/**
 * Stand-in for android.icu.text.Collator.
 *
 * ProjectSuki only asks for the default instance to construct a [StringSearch], which matches the
 * text its own [Normalizer2] already folded, so nothing here compares strings.
 */
open class Collator protected constructor() {
    companion object {
        @JvmStatic
        fun getInstance(): Collator = RuleBasedCollator()
    }
}