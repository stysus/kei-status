package androidx.preference

import android.content.Context

/**
 * Minimal stand-in for androidx.preference.Preference.
 *
 * Extensions build their settings screen imperatively, so only the setters they call exist here.
 * Nothing is rendered: the harness never shows a UI.
 */
open class Preference(private val context: Context) {

    var key: String? = null
    var title: CharSequence? = null
    var summary: CharSequence? = null
    var isEnabled: Boolean = true
    var isVisible: Boolean = true

    fun getContext(): Context = context

    fun setDefaultValue(defaultValue: Any?) = Unit

    fun setOnPreferenceChangeListener(
        listener: Preference.OnPreferenceChangeListener?,
    ) = Unit

    fun setOnPreferenceClickListener(listener: Preference.OnPreferenceClickListener?) = Unit

    fun interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean
    }

    fun interface OnPreferenceClickListener {
        fun onPreferenceClick(preference: Preference): Boolean
    }
}
