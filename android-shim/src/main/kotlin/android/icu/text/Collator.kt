package android.icu.text

import java.util.Comparator
import java.util.Locale

/**
 * Stand-in for android.icu.text.Collator.
 *
 * ProjectSuki only asks for the default instance to construct a [StringSearch], which matches the
 * text its own [Normalizer2] already folded. AquellosQueTraducen goes further: it asks for a
 * locale instance, configures it through the [RuleBasedCollator] setters, and then sorts with it
 * directly, so the class is a [Comparator] and `compare` really orders strings through the
 * java.text delegate rather than throwing at the call site.
 */
abstract class Collator protected constructor() : Comparator<Any> {

    abstract override fun compare(a: Any, b: Any): Int

    companion object {
        @JvmStatic
        fun getInstance(): Collator = RuleBasedCollator()

        @JvmStatic
        fun getInstance(locale: Locale): Collator = RuleBasedCollator(locale)
    }
}
