package kei.status.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * What the Gradle init script writes into an extension module's build directory.
 */
@Serializable
data class ModuleLayout(
    val module: String,
    val classesDir: String,
    val moduleDir: String,
)

object ModuleLayouts {

    private val json = Json { ignoreUnknownKeys = true }

    fun read(sourceRoot: Path, module: String): ModuleLayout {
        val modulePath = module.removePrefix(":").replace(':', '/')
        val file = sourceRoot.resolve(modulePath).resolve("build/harness-classpath.json")
        require(file.exists()) {
            "Missing $file. Run:\n" +
                "  ./gradlew -I ../tools/harness.init.gradle.kts -PharnessModule=$module " +
                "$module:dumpHarnessClasspath"
        }
        return try {
            json.decodeFromString(file.readText())
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IllegalStateException(
                "$file is out of date or malformed. Regenerate it:\n" +
                    "  ./gradlew -I ../tools/harness.init.gradle.kts -PharnessModule=$module " +
                    "$module:dumpHarnessClasspath",
                e,
            )
        }
    }

    /**
     * Every shared module (core, lib, lib-multisrc) the extension may link against.
     *
     * The module's own compile task already built the ones it needs, so collecting all that exist
     * is enough - unused entries cost nothing because classes load on demand.
     */
    fun sharedModuleDirs(sourceRoot: Path): List<Path> {
        val containers = listOf(
            sourceRoot.resolve("lib"),
            sourceRoot.resolve("lib-multisrc"),
        )

        val modules = buildList {
            sourceRoot.resolve("core").takeIf { it.exists() }?.let { add(it) }
            containers.forEach { container ->
                if (!container.exists()) return@forEach
                Files.list(container).use { children ->
                    addAll(children.toList().filter { Files.isDirectory(it) })
                }
            }
        }

        return modules
    }

    fun classDirsOf(modules: List<Path>): List<Path> = modules
        .map { it.resolve(CLASSES_SUBDIR) }
        .filter { it.exists() }

    /**
     * Module roots that carry an `assets/` tree. Themes ship their i18n message files there and
     * look them up as `assets/i18n/...` through the class loader, so the *module root* goes on the
     * classpath - exactly how the app merges assets.
     */
    fun resourceRootsOf(modules: List<Path>): List<Path> = modules
        .filter { it.resolve("assets").exists() }

    private const val CLASSES_SUBDIR =
        "build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
}
