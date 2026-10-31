package android.icu.text

import java.text.Normalizer
import java.util.Locale

/**
 * Stand-in for android.icu.text.Normalizer2, reduced to the NFKC casefold instance.
 *
 * shortcut: lowercase after NFKC is not full case folding (ß stays ß, default-ignorables stay),
 * upgrade when a source's search depends on the exact fold.
 */
object Normalizer2 {

    @JvmStatic
    fun getNFKCCasefoldInstance(): Normalizer2 = this

    fun normalize(input: CharSequence): String =
        Normalizer.normalize(input, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}