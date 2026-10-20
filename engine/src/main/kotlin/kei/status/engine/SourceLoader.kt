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
 *
 * The loader is deliberately never closed. Extensions start fire-and-forget work on `GlobalScope`
 * (`KeiSource.triggerBackgroundFilterFetch`), which outlives the module and still resolves classes
 * while it runs; closing the loader turns that work into a `NoClassDefFoundError` on the next
 * module. Leaving it alone holds nothing open - the class path is made of directories, so there is
 * no jar handle to release - and the loader, together with its classes, becomes collectable once
 * that background work finishes.
 */
class SourceLoader(
    private val sourceRoot: Path,
    private val layout: ModuleLayout,
) {

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

    private companion object {
        const val GENERATED_CLASS = "keiyoushi.source.Generated"
    }
}
