package android.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Stand-in for android.text.Html, reduced to plain text.
 *
 * The harness has no span model: `fromHtml` is used by the text-interceptor lib only to turn a
 * source's HTML synopsis into glyphs it can draw, and it calls `toString()` immediately. Block
 * elements therefore become blank-line separators and `<br>` a newline, so the rendered page keeps
 * the source's paragraph structure without pretending to carry styling.
 */
object Html {

    const val FROM_HTML_MODE_LEGACY = 0
    const val FROM_HTML_MODE_COMPACT = 63

    const val FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH = 0x1
    const val FROM_HTML_SEPARATOR_LINE_BREAK_HEADING = 0x2
    const val FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM = 0x4
    const val FROM_HTML_SEPARATOR_LINE_BREAK_LIST = 0x8
    const val FROM_HTML_SEPARATOR_LINE_BREAK_DIV = 0x10
    const val FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE = 0x20
    const val FROM_HTML_OPTION_USE_CSS_COLORS = 0x100

    const val TO_HTML_PARAGRAPH_LINES_CONSECUTIVE = 0
    const val TO_HTML_PARAGRAPH_LINES_INDIVIDUAL = 1

    private val WHITESPACE = Regex("\\s+")

    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "table", "tr", "blockquote", "pre",
        "section", "article", "header", "footer",
    )

    @JvmStatic
    fun fromHtml(source: String): Spanned = fromHtml(source, FROM_HTML_MODE_LEGACY)

    @JvmStatic
    fun fromHtml(source: String, flags: Int): Spanned = SpannedString(render(source))

    @JvmStatic
    fun toHtml(text: Spanned): String = text.toString()

    @JvmStatic
    fun escapeHtml(text: CharSequence?): String? = text?.toString()?.let {
        it.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun render(source: String): String {
        val out = StringBuilder()
        appendChildren(Jsoup.parse(source).body(), out)
        return out.trimEnd('\n').toString()
    }

    private fun appendChildren(node: Node, out: StringBuilder) {
        for (child in node.childNodes()) appendNode(child, out)
    }

    private fun appendNode(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> appendText(node.text(), out)
            is Element -> when (node.tagName().lowercase()) {
                "br" -> out.append('\n')
                "hr" -> breakBlock(out)
                in BLOCK_TAGS -> {
                    breakBlock(out)
                    appendChildren(node, out)
                    breakBlock(out)
                }
                else -> appendChildren(node, out)
            }
            // Comments and data nodes carry nothing a reader sees.
            else -> appendChildren(node, out)
        }
    }

    private fun appendText(text: String, out: StringBuilder) {
        val collapsed = text.replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return
        out.append(if (out.isEmpty() || out.last() == '\n') collapsed.trimStart() else collapsed)
    }

    /** Ends the current block: one blank line, never a ragged run of them. */
    private fun breakBlock(out: StringBuilder) {
        while (out.isNotEmpty() && (out.last() == '\n' || out.last() == ' ')) {
            out.deleteCharAt(out.length - 1)
        }
        if (out.isEmpty()) return
        out.append("\n\n")
    }
}
