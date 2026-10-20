package kei.status.engine

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Loads an extension module's compiled classes.
 *
 * The parent loader owns the host shim and third-party jars, so both the harness and the extension
 * see one copy of OkHttp, coroutines, and the model interfaces. Only the extension's own classes,
 * the shared modules it links against, and their `assets/` trees are added per module.
 */
class SourceLoader(
    private val sourceRoot: Path,
    private val layout: ModuleLayout,
) : AutoCloseable {

    private val loader: URLClassLoader = run {
        val modules = listOf(Path.of(layout.moduleDir)) + ModuleLayouts.sharedModuleDirs(sourceRoot)
        val dirs = buildList {
            add(Path.of(layout.classesDir))
            addAll(ModuleLayouts.classDirsOf(modules))
            addAll(ModuleLayouts.resourceRootsOf(modules))
        }.distinct()

        val missing = dirs.filterNot { it.exists() }
        require(missing.isEmpty()) {
            "Missing compiled classes: ${missing.joinToString()}. Compile the module first " +
                "(./gradlew ${layout.module}:compileDebugKotlin)."
        }

        URLClassLoader(
            dirs.map { it.toUri().toURL() }.toTypedArray(),
            SourceLoader::class.java.classLoader,
        )
    }

    /**
     * The KSP entry point. It is either a subclass of the annotated `@Source` class (single
     * source) or a `SourceFactory` (multiple `source {}` blocks).
     */
    fun createSources(): List<CatalogueSource> {
        val generated = loader.loadClass(GENERATED_CLASS)
        val instance = generated.getDeclaredConstructor().newInstance()

        val sources = when (instance) {
            is SourceFactory -> instance.createSources()
            is Source -> listOf(instance)
            else -> error("$GENERATED_CLASS is neither a Source nor a SourceFactory")
        }

        return sources.map { source ->
            source as? CatalogueSource
                ?: error("${source.name} is not a CatalogueSource; only online sources are supported")
        }
    }

    override fun close() = loader.close()

    private companion object {
        const val GENERATED_CLASS = "keiyoushi.source.Generated"
    }
}
