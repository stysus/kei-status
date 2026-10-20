package android.app

import android.content.Context

/**
 * Minimal stand-in for android.app.Application. `keiyoushi.utils.applicationContext` resolves this
 * type from Injekt, so it must be registered there by the harness before any source is touched.
 */
open class Application : Context()
