package android.content

/**
 * Stand-in for android.content.ContextThemeWrapper, the platform parent of `Activity`.
 *
 * Only the hierarchy matters here: a source that hands an `Activity` to core code which then
 * resolves it to `ContextWrapper` needs the link to exist.
 */
open class ContextThemeWrapper(base: Context? = null) : ContextWrapper(base)
