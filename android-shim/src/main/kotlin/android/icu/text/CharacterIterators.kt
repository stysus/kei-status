package android.icu.text

import java.text.CharacterIterator

/** java.text's iterators do not render their text through toString; walk a clone to read it. */
internal fun CharacterIterator.readText(): String {
    val copy = clone() as CharacterIterator
    return buildString {
        var char = copy.first()
        while (char != CharacterIterator.DONE) {
            append(char)
            char = copy.next()
        }
    }
}