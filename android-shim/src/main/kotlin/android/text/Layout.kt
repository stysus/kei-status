package android.text

import android.graphics.Canvas

/**
 * Stand-in for android.text.Layout: the height and the drawing extent of a laid-out text block.
 *
 * Extensions only reach this class through `Layout.Alignment`, which is a *nested* type - bytecode
 * names `android.text.Layout$Alignment` - so the nesting is part of the contract, not a style
 * choice.
 */
open class Layout(private val lineWidth: Int, val alignment: Alignment) {

    open fun getHeight(): Int = 0

    open fun getWidth(): Int = lineWidth

    open fun getLineCount(): Int = 0

    open fun draw(canvas: Canvas) = Unit

    enum class Alignment { ALIGN_NORMAL, ALIGN_CENTER, ALIGN_OPPOSITE }

    companion object {
        /**
         * The hyphenation frequencies `StaticLayout.Builder.setHyphenationFrequency` accepts.
         * Values are the framework's; the shim's greedy wrapper does not hyphenate, so the
         * setting is recorded only for the calls to link.
         */
        const val HYPHENATION_FREQUENCY_NONE = 0
        const val HYPHENATION_FREQUENCY_FULL = 1
    }
}
