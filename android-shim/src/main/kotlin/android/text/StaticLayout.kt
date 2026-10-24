package android.text

import android.graphics.Canvas
import android.graphics.text.LineBreaker
import java.awt.FontMetrics
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Stand-in for android.text.StaticLayout: greedy word wrap over a [TextPaint], drawn line by line.
 *
 * The text-interceptor lib sizes its bitmap from `getHeight()` and then draws the same block, so
 * measuring and drawing have to agree exactly. Both go through the paint's `awtFont()` and the
 * metrics below, which is why the wrapping here is done up front rather than at draw time.
 */
class StaticLayout(
    text: CharSequence,
    private val paint: TextPaint,
    width: Int,
    alignment: Layout.Alignment,
    spacingMult: Float,
    spacingAdd: Float,
    @Suppress("UNUSED_PARAMETER") includePad: Boolean,
) : Layout(width, alignment) {

    private val layoutWidth: Int = width

    private val metrics: FontMetrics = scratchMetrics(paint.awtFont())

    private val lineHeight: Int = (metrics.height * spacingMult + spacingAdd)
        .roundToInt()
        .coerceAtLeast(1)

    private val lines: List<String> = wrapAll(text.toString())

    override fun getHeight(): Int = lines.size * lineHeight

    override fun getLineCount(): Int = lines.size

    override fun draw(canvas: Canvas) {
        var baseline = -metrics.ascent.toFloat()
        for (line in lines) {
            if (line.isNotEmpty()) {
                canvas.drawText(line, lineOffset(line), baseline, paint)
            }
            baseline += lineHeight
        }
    }

    private fun lineOffset(line: String): Float = when (alignment) {
        Layout.Alignment.ALIGN_CENTER -> (layoutWidth - metrics.stringWidth(line)) / 2f
        Layout.Alignment.ALIGN_OPPOSITE -> (layoutWidth - metrics.stringWidth(line)).toFloat()
        Layout.Alignment.ALIGN_NORMAL -> 0f
    }

    private fun wrapAll(text: String): List<String> {
        val wrapped = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                wrapped.add("")
            } else {
                wrapped.addAll(wrap(paragraph))
            }
        }
        return wrapped
    }

    /**
     * The builder the framework added in M, which is what extensions reach for now.
     *
     * Nested rather than top-level on purpose: bytecode names `android.text.StaticLayout$Builder`,
     * so the nesting is part of the link contract. `setBreakStrategy` and
     * `setHyphenationFrequency` are accepted and recorded - the greedy wrapper above does not
     * hyphenate or balance, and no source observed depends on the difference - while alignment
     * and includePad do what the framework does with them.
     */
    class Builder private constructor(
        private val text: CharSequence,
        private val paint: TextPaint,
        private val width: Int,
    ) {
        private var alignmentValue = Layout.Alignment.ALIGN_NORMAL
        private var breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
        private var hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_FULL
        private var includePad = true

        companion object {
            @JvmStatic
            fun obtain(source: CharSequence, start: Int, end: Int, paint: TextPaint, sourceWidth: Int): Builder {
                val sliced = if (start == 0 && end == source.length) source else source.subSequence(start, end)
                return Builder(sliced, paint, sourceWidth)
            }
        }

        fun setAlignment(alignment: Layout.Alignment): Builder = apply { alignmentValue = alignment }

        fun setBreakStrategy(strategy: Int): Builder = apply { breakStrategy = strategy }

        fun setHyphenationFrequency(frequency: Int): Builder = apply { hyphenationFrequency = frequency }

        fun setIncludePad(include: Boolean): Builder = apply { includePad = include }

        fun build(): StaticLayout = StaticLayout(text, paint, width, alignmentValue, 1f, 0f, includePad)
    }

    /** Greedy wrap on spaces, which is what Android does for a run of un-styled text. */
    private fun wrap(paragraph: String): List<String> {
        val wrapped = mutableListOf<String>()
        val current = StringBuilder()

        for (word in paragraph.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && metrics.stringWidth(candidate) > layoutWidth) {
                wrapped.add(current.toString())
                current.setLength(0)
                current.append(word)
            } else {
                current.setLength(0)
                current.append(candidate)
            }
        }

        wrapped.add(current.toString())
        return wrapped
    }

    /**
     * [FontMetrics] needs a graphics context to measure with, but only for the moment it measures.
     *
     * The context is created per layout and dropped immediately: `stringWidth` keeps the font and
     * the render context, not the graphics object, and a shared one would be a race for the
     * background work sources start.
     */
    private fun scratchMetrics(font: java.awt.Font): FontMetrics {
        val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        return try {
            graphics.getFontMetrics(font)
        } finally {
            graphics.dispose()
        }
    }
}
