package eu.kanade.tachiyomi.source

import androidx.preference.PreferenceScreen

@Suppress("UNUSED")
interface ConfigurableSource {
    fun setupPreferenceScreen(screen: PreferenceScreen)
}
