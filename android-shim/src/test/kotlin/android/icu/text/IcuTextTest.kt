package android.icu.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.text.CharacterIterator
import java.text.StringCharacterIterator

class IcuTextTest {

    /**
     * Extensions are compiled against android.jar, so the static factories and descriptors are part
     * of the link contract; a Kotlin companion member without @JvmStatic links as NoSuchMethodError.
     */
    @Test
    fun `the ICU surface ProjectSuki links against is present and static`() {
        assertTrue(Modifier.isStatic(BreakIterator::class.java.getMethod("getWordInstance").modifiers))
        assertTrue(Modifier.isStatic(BreakIterator::class.java.getMethod("getCharacterInstance").modifiers))
        assertTrue(Modifier.isStatic(Collator::class.java.getMethod("getInstance").modifiers))
        assertTrue(Modifier.isStatic(Normalizer2::class.java.getMethod("getNFKCCasefoldInstance").modifiers))

        BreakIterator::class.java.getMethod("setText", CharacterIterator::class.java)
        BreakIterator::class.java.getMethod("getRuleStatus")
        Normalizer2::class.java.getMethod("normalize", CharSequence::class.java)
        RuleBasedCollator::class.java.getMethod("setCaseLevel", Boolean::class.javaPrimitiveType)
        RuleBasedCollator::class.java.getMethod("setStrength", Int::class.javaPrimitiveType)
        RuleBasedCollator::class.java.getMethod("setDecomposition", Int::class.javaPrimitiveType)
        StringSearch::class.java.getConstructor(
            String::class.java,
            CharacterIterator::class.java,
            RuleBasedCollator::class.java,
        )
        StringSearch::class.java.getMethod("setTarget", CharacterIterator::class.java)
        StringSearch::class.java.getMethod("getMatchedText")
    }

    @Test
    fun `word break marks letters and digits, not punctuation`() {
        val text = "Hello, world! 42"
        val iterator = BreakIterator.getWordInstance()
        iterator.setText(StringCharacterIterator(text))

        val words = mutableListOf<String>()
        var left = iterator.first()
        var right = iterator.next()
        while (right != BreakIterator.DONE) {
            if (iterator.getRuleStatus() != BreakIterator.WORD_NONE) words += text.substring(left, right)
            left = right
            right = iterator.next()
        }

        assertEquals(listOf("Hello", "world", "42"), words)
    }

    @Test
    fun `character break keeps a surrogate pair together`() {
        val text = "A😀é"
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(StringCharacterIterator(text))

        val characters = mutableListOf<String>()
        var left = iterator.first()
        var right = iterator.next()
        while (right != BreakIterator.DONE) {
            characters += text.substring(left, right)
            left = right
            right = iterator.next()
        }

        assertEquals(listOf("A", "😀", "é"), characters)
    }

    @Test
    fun `NFKC casefold normalizes compatibility forms and case`() {
        val normalizer = Normalizer2.getNFKCCasefoldInstance()
        assertEquals("hello 1 straße", normalizer.normalize("HELLO ① Straße"))
        // The descriptor takes CharSequence, which is what the extension's bytecode links against.
        assertEquals("hello", normalizer.normalize(StringBuilder("HELLO")))
    }

    @Test
    fun `string search honours overlapping and plain stepping`() {
        val search = StringSearch("abc", StringCharacterIterator("abcabc"), Collator.getInstance() as RuleBasedCollator)
        search.isOverlapping = true
        assertEquals(0, search.first())
        assertEquals("abc", search.matchedText)
        assertEquals(3, search.next())
        assertEquals(StringSearch.DONE, search.next())
        assertEquals(StringSearch.DONE, search.next())

        search.target = StringCharacterIterator("aaaa")
        search.pattern = "aa"
        assertEquals(0, search.first())
        assertEquals(1, search.next())
        assertEquals(2, search.next())
        assertEquals(StringSearch.DONE, search.next())

        search.isOverlapping = false
        search.target = StringCharacterIterator("aaaa")
        assertEquals(0, search.first())
        assertEquals(2, search.next())
        assertEquals(StringSearch.DONE, search.next())
    }
}