package android.net

/**
 * Minimal stand-in for android.net.Uri.
 *
 * Only the string-preserving surface is provided; Android-specific behaviours (normalization,
 * content:// resolution, authority parsing) are intentionally absent.
 */
class Uri private constructor(private val value: String) {

    fun toStringValue(): String = value

    override fun toString(): String = value

    fun isAbsolute(): Boolean = value.contains("://")

    fun getScheme(): String? = value.substringBefore("://", "").takeIf { it.isNotEmpty() && value.contains("://") }

    fun getAuthority(): String? = value.substringAfter("://", "").substringBefore('/').takeIf { it.isNotEmpty() }

    fun getEncodedPath(): String? = getPath()

    fun getEncodedQuery(): String? = value.substringAfter('?', "").takeIf { value.contains('?') }

    fun getQuery(): String? = getEncodedQuery()

    fun getHost(): String? = value.substringAfter("://", "").substringBefore('/').substringBefore(':')
        .takeIf { it.isNotEmpty() }

    fun getPath(): String? = value.substringAfter("://", value).let {
        val slash = it.indexOf('/')
        if (slash < 0) null else it.substring(slash)
    }

    override fun equals(other: Any?): Boolean = other is Uri && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)
    }
}
