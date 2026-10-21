package android.app

import android.content.ContextWrapper
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal stand-in for android.app.Application. `keiyoushi.utils.applicationContext` resolves this
 * type from Injekt, so it must be registered there by the harness before any source is touched.
 *
 * It extends [ContextWrapper] because the platform one does. Core code that merges an `Activity`
 * with the application context resolves to `ContextWrapper` at compile time and casts to it at
 * runtime, so the application has to be one too.
 *
 * Lifecycle callbacks are accepted and stored but never delivered: the harness has no Activity to
 * drive them. Registering is enough for core's ActivityTracker to settle on "no resumed activity"
 * instead of retrying a failed registration on every lookup.
 */
open class Application : ContextWrapper() {

    interface ActivityLifecycleCallbacks {
        fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?)

        fun onActivityStarted(activity: Activity)

        fun onActivityResumed(activity: Activity)

        fun onActivityPaused(activity: Activity)

        fun onActivityStopped(activity: Activity)

        fun onActivitySaveInstanceState(activity: Activity, outState: Bundle)

        fun onActivityDestroyed(activity: Activity)
    }

    private val lifecycleCallbacks = CopyOnWriteArrayList<ActivityLifecycleCallbacks>()

    open fun registerActivityLifecycleCallbacks(callbacks: ActivityLifecycleCallbacks) {
        lifecycleCallbacks += callbacks
    }

    open fun unregisterActivityLifecycleCallbacks(callbacks: ActivityLifecycleCallbacks) {
        lifecycleCallbacks -= callbacks
    }
}
