package androidx.preference

import android.content.Context

open class PreferenceScreen(context: Context) : Preference(context) {

    private val preferences = mutableListOf<Preference>()

    fun addPreference(preference: Preference): Boolean {
        preferences += preference
        return true
    }

    fun getPreferenceCount(): Int = preferences.size

    fun getPreference(index: Int): Preference = preferences[index]
}
