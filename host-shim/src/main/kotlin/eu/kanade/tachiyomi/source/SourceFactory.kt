package eu.kanade.tachiyomi.source

/** A factory for creating sources at runtime. */
@Suppress("UNUSED")
interface SourceFactory {
    fun createSources(): List<Source>
}
