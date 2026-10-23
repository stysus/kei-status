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
 * the shared modules it links against, their `assets/` trees, and the jars those modules declare as
 * `implementation` are added per module.
 *
 * That last part is not optional. An extension is packaged with its own dependencies inside the
 * jar, so the app never has to know about them; here the classes come from a directory, and without
 * the jars `:lib:e4p`'s `org.kotlincrypto.hash:blake2` is a `NoClassDefFoundError` at the first
 * hash. The parent is asked first, so a jar the host also provides (OkHttp, coroutines) still
 * resolves to the host's copy and the extension sees one version of it.
 *
 * The loader is deliberately never closed. Extensions start fire-and-forget work on `GlobalScope`
 * (`KeiSource.triggerBackgroundFilterFetch`), which outlives the module and still resolves classes
 * while it runs; closing the loader turns that work into a `NoClassDefFoundError` on the next
 * module. Leaving it alone holds nothing open - the class path is made of directories and jars the
 * Gradle cache owns, so there is no jar handle this loader opened to release - and the loader,
 * together with its classes, becomes collectable once that background work finishes.
 */
class SourceLoader(
    private val sourceRoot: Path,
    private val layout: ModuleLayout,
) {

    private val loader: URLClassLoader = run {
        val classpath = classpathOf(sourceRoot, layout)

        val missing = classpath.filterNot { it.exists() }
        require(missing.isEmpty()) {
            "Missing compiled classes or runtime jars: ${missing.joinToString()}. Rebuild the " +
                "module and rewrite its classpath file:\n" +
                "  ./gradlew -I ../tools/harness.init.gradle.kts " +
                "-PharnessModule=${layout.module} ${layout.module}:dumpHarnessClasspath"
        }

        URLClassLoader(
            classpath.map { it.toUri().toURL() }.toTypedArray(),
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

    internal companion object {
        private const val GENERATED_CLASS = "keiyoushi.source.Generated"

        /**
         * The module's own classes first, then the shared modules it links against, then the jars
         * those modules declare, then the `assets/` roots.
         *
         * The module's classes have to come before anything else so its own copy of a class wins,
         * and the jars come after the directories because a jar is a dependency, never the module
         * itself.
         */
        internal fun classpathOf(sourceRoot: Path, layout: ModuleLayout): List<Path> {
            val modules = listOf(Path.of(layout.moduleDir)) + ModuleLayouts.sharedModuleDirs(sourceRoot)
            return buildList {
                add(Path.of(layout.classesDir))
                addAll(ModuleLayouts.classDirsOf(modules))
                addAll(layout.runtimeJars.map { Path.of(it) })
                addAll(ModuleLayouts.resourceRootsOf(modules))
            }.distinct()
        }
    }
}
