package android.content

/**
 * Minimal stand-in for android.content.ComponentName.
 *
 * Only used to name the activity an [Intent] targets, which the harness never launches.
 */
class ComponentName(private val packageName: String?, private val className: String) {

    constructor(context: Context, className: String) : this(context.getPackageName(), className)

    fun getPackageName(): String? = packageName

    fun getClassName(): String = className

    override fun toString(): String = "$packageName/$className"

    override fun equals(other: Any?): Boolean =
        other is ComponentName && other.packageName == packageName && other.className == className

    override fun hashCode(): Int = (packageName?.hashCode() ?: 0) * 31 + className.hashCode()
}
