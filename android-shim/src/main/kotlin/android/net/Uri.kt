package android.net

/**
 * Minimal stand-in for android.net.Uri.
 *
 * Only the string-preserving surface is provided; Android-specific behaviours (normalization,
 * content:// resolution, authority parsing) are intentionally absent.
 *
 * Two members are modelled for real rather than stubbed, because they decide the request an
 * extension sends: `encode`, which every source that builds a URL out of a title passes through
 * (the shared text-interceptor lib included), and `buildUpon`, which four sources assemble query
 * strings with. Their escaping and joining rules match the framework's, so the URLs stay identical.
 */
class Uri private constructor(private val value: String) {

    override fun toString(): String = value

    fun isAbsolute(): Boolean = value.contains("://")

    fun getScheme(): String? = value.substringBefore("://", "").takeIf { it.isNotEmpty() && value.contains("://") }

    fun getAuthority(): String? = value.substringAfter("://", "")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .takeIf { it.isNotEmpty() }

    fun getEncodedPath(): String? = getPath()

    fun getEncodedQuery(): String? {
        val withoutFragment = value.substringBefore('#')
        if (!withoutFragment.contains('?')) return null
        return withoutFragment.substringAfter('?')
    }

    fun getQuery(): String? = getEncodedQuery()

    fun getHost(): String? = value.substringAfter("://", "")
        .substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        .takeIf { it.isNotEmpty() }

    fun getPath(): String? = value.substringAfter("://", value).let {
        val slash = it.indexOf('/')
        if (slash < 0) null else it.substring(slash).substringBefore('?').substringBefore('#')
    }

    fun buildUpon(): Builder {
        val builder = Builder()
        builder.partScheme = schemeOf(value)
        builder.partAuthority = authorityOf(value)
        builder.partPath = pathOf(value)
        builder.partQuery = queryOf(value)
        builder.partFragment = fragmentOf(value)
        return builder
    }

    override fun equals(other: Any?): Boolean = other is Uri && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        private const val UNRESERVED = "_-!.~'()*"
        private val HEX = "0123456789ABCDEF".toCharArray()

        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)

        /**
         * Percent-encodes everything outside RFC 3986's unreserved set, as UTF-8 octets.
         *
         * Android leaves only letters, digits and `_-!.~'()*` alone, and writes a space as `%20`
         * rather than `+`. Both details are load-bearing: the text-interceptor lib packs a whole
         * synopsis into one path segment, so a character that survived here would change where the
         * request goes.
         */
        @JvmStatic
        fun encode(s: String): String {
            val encoded = StringBuilder()
            var index = 0
            while (index < s.length) {
                val codePoint = s.codePointAt(index)
                when {
                    isUnreserved(codePoint) -> encoded.appendCodePoint(codePoint)
                    codePoint == ' '.code -> encoded.append("%20")
                    else -> {
                        for (byte in String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)) {
                            encoded.append('%')
                                .append(HEX[(byte.toInt() shr 4) and 0xF])
                                .append(HEX[byte.toInt() and 0xF])
                        }
                    }
                }
                index += Character.charCount(codePoint)
            }
            return encoded.toString()
        }

        private fun isUnreserved(codePoint: Int): Boolean =
            (codePoint in 'A'.code..'Z'.code) ||
                (codePoint in 'a'.code..'z'.code) ||
                (codePoint in '0'.code..'9'.code) ||
                (codePoint < 0x80 && UNRESERVED.indexOf(codePoint.toChar()) != -1)

        private fun schemeOf(value: String): String? =
            value.substringBefore("://", "").takeIf { it.isNotEmpty() && value.contains("://") }

        private fun authorityOf(value: String): String? =
            value.substringAfter("://", "").takeIf { value.contains("://") }
                ?.substringBefore('/')?.substringBefore('?')?.substringBefore('#')

        private fun pathOf(value: String): String? {
            // A relative reference has no authority to stop at, so its path is everything up to the
            // query or fragment - dropping to the first slash would turn "api/v1" into "/v1".
            val withoutScheme = if (value.contains("://")) value.substringAfter("://") else value
            val withoutQuery = withoutScheme.substringBefore('?').substringBefore('#')
            val slash = withoutQuery.indexOf('/')
            return when {
                value.contains("://") -> if (slash < 0) null else withoutQuery.substring(slash)
                else -> withoutQuery.takeIf { it.isNotEmpty() }
            }
        }

        private fun queryOf(value: String): String? {
            val withoutFragment = value.substringBefore('#')
            if (!withoutFragment.contains('?')) return null
            return withoutFragment.substringAfter('?')
        }

        private fun fragmentOf(value: String): String? =
            if (value.contains('#')) value.substringAfter('#') else null
    }

    /**
     * Stand-in for android.net.Uri.Builder, limited to what sources call: `appendPath`,
     * `appendQueryParameter` and `build`.
     *
     * Parts are kept encoded, as on Android: `appendPath` escapes its argument, so an appended
     * segment cannot introduce a separator. Decoded setters (`path`, `authority`, `query`,
     * `fragment`) are deliberately absent rather than guessed at - each escapes against a
     * different allow-set, and a wrong rule would silently rewrite a request instead of failing
     * where the harness can report it.
     */
    class Builder {

        internal var partScheme: String? = null
        internal var partAuthority: String? = null
        internal var partPath: String? = null
        internal var partQuery: String? = null
        internal var partFragment: String? = null

        fun appendPath(newSegment: String): Builder = apply {
            val encoded = Uri.encode(newSegment)
            partPath = when (val existing = partPath) {
                null -> "/$encoded"
                else -> if (existing.endsWith("/")) existing + encoded else "$existing/$encoded"
            }
        }

        fun appendQueryParameter(key: String, value: String?): Builder = apply {
            // Android encodes both halves and joins with '=', so a value containing '&' stays one
            // parameter; a null value becomes the literal text "null", as it does on Android.
            val parameter = "${Uri.encode(key)}=${Uri.encode(value ?: "null")}"
            partQuery = when (val existing = partQuery) {
                null, "" -> parameter
                else -> "$existing&$parameter"
            }
        }

        fun build(): Uri = Uri(toString())

        override fun toString(): String = buildString {
            partScheme?.let { append(it).append(':') }
            partAuthority?.let { append("//").append(it) }
            partPath?.let { append(it) }
            partQuery?.let { append('?').append(it) }
            partFragment?.let { append('#').append(it) }
        }
    }
}
