package android.content

import android.content.res.Resources

/**
 * Stand-in for android.content.ContextWrapper.
 *
 * Nothing in the harness wraps a context, but the class has to exist: Kotlin resolves the least
 * upper bound of `Activity` and `Application` against the real framework hierarchy, and `Activity`'s
 * parent chain runs through here. Core code that merges the two - `foregroundActivity() ?:
 * applicationContext` in `TurnstileHelper` - is compiled to `checkcast ContextWrapper` followed by
 * `ContextWrapper.getResources()`, so without this class the JVM cannot link the call and the
 * source never runs at all.
 *
 * The base is nullable because the harness has no real context to wrap; with none, the resources
 * fall back to the system set, which is what a headless run has.
 */
open class ContextWrapper(private val base: Context? = null) : Context() {

    open fun getBaseContext(): Context? = base

    override fun getResources(): Resources = base?.getResources() ?: super.getResources()

    override fun getApplicationContext(): Context = base?.getApplicationContext() ?: this
}
