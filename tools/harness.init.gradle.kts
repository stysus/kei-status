// Records where an extension module's compiled classes and run-time jars live, so the JVM harness
// can load them without touching the extension build logic.
//
// Usage:
//   ./gradlew -I ../tools/harness.init.gradle.kts -PharnessModule=:src:en:aurora \
//       :src:en:aurora:dumpHarnessClasspath
//
//   # many modules in one invocation: compile in parallel, write one JSON per module
//   ./gradlew -I ../tools/harness.init.gradle.kts -PharnessModuleList=shard.txt \
//       dumpAllHarnessClasspaths
//
// Project dependencies (:core, :lib:*, :lib-multisrc:*) keep their classes directory only: the
// module's compile task builds them, and the harness picks up every compiled shared module it can
// find. Their third-party dependencies are a different matter. `:lib:e4p` declares
// `implementation("org.kotlincrypto.hash:blake2")`, which reaches the APK by being bundled into the
// extension jar; the harness loads a classes directory instead, so the jars have to be recorded or
// the extension dies with NoClassDefFoundError the first time it hashes anything.

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import java.io.File

if (gradle.parent == null) {
    // A single module keeps the original flag; a list lets one Gradle invocation compile a whole
    // shard in parallel instead of paying configuration cost once per module.
    val single = providers.gradleProperty("harnessModule").orNull
    val listFile = providers.gradleProperty("harnessModuleList").orNull

    val targets: Set<String> = when {
        listFile != null -> file(listFile).readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            // A line may carry checker flags after the module path; the build only needs the path.
            .map { it.split(Regex("\\s+")).first() }
            .toSet()

        single != null -> setOf(single)

        else -> error("Pass -PharnessModule=<path> or -PharnessModuleList=<file>")
    }

    if (targets.isEmpty()) {
        error("The harness module list is empty")
    }

    gradle.afterProject {
        if (path !in targets) return@afterProject

        // `path` here is the project path; inside the task block it would be the task path.
        val projectPath = path

        // Only module components are taken. Project dependencies are already covered by the shared
        // module scan in ModuleLayouts, and asking for their artifacts here is the variant ambiguity
        // this init script deliberately avoids. The configuration itself does not exist yet - AGP
        // creates its variant configurations after this project has been evaluated - so the lookup
        // is deferred to the provider, which is realised when the task graph is built.
        //
        // `android-classes-jar` is the artifact type AGP transforms an `.aar` into: an AAR is a zip
        // of zips and loads nothing when handed to a URLClassLoader, while this view yields the
        // class jar inside it. Plain JVM jars resolve unchanged under the same attribute.
        val runtimeJars = objects.fileCollection()
        runtimeJars.from(
            provider<List<File>> {
                configurations.findByName("debugRuntimeClasspath")
                    ?.incoming
                    ?.artifactView {
                        attributes.attribute(
                            ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                            "android-classes-jar",
                        )
                        componentFilter { id: ComponentIdentifier -> id is ModuleComponentIdentifier }
                    }
                    ?.files
                    ?.toList()
                    ?: emptyList()
            },
        )

        tasks.register("dumpHarnessClasspath") {
            group = "harness"
            description = "Writes the compiled classes directory and runtime jars of $projectPath as JSON."

            // The harness loads these classes, so make sure they exist before recording the path.
            dependsOn("compileDebugKotlin")

            val classesDir = layout.buildDirectory
                .dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
            val outputFile = layout.buildDirectory.file("harness-classpath.json")
            val moduleDir = projectDir.absolutePath

            inputs.files(runtimeJars)

            doLast {
                val out = outputFile.get().asFile
                out.parentFile.mkdirs()

                // Local, so the task does not capture a script-level function: the configuration
                // cache cannot serialise Gradle script objects.
                val quote = { value: String ->
                    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                }
                val jars = runtimeJars.files
                    .map { quote(it.absolutePath) }
                    .sorted()
                    .joinToString(",\n    ")

                out.writeText(
                    buildString {
                        appendLine("{")
                        appendLine("  \"module\": ${quote(projectPath)},")
                        appendLine("  \"classesDir\": ${quote(classesDir.get().asFile.absolutePath)},")
                        appendLine("  \"moduleDir\": ${quote(moduleDir)},")
                        if (jars.isEmpty()) {
                            appendLine("  \"runtimeJars\": []")
                        } else {
                            appendLine("  \"runtimeJars\": [")
                            appendLine("    $jars")
                            appendLine("  ]")
                        }
                        appendLine("}")
                    },
                )
                logger.lifecycle("harness layout: $out")
            }
        }
    }

    // Registered on the root project with string paths so it can be defined before the subprojects
    // that own the tasks have been configured.
    if (listFile != null) {
        gradle.rootProject {
            tasks.register("dumpAllHarnessClasspaths") {
                group = "harness"
                description = "Writes the compiled classes directory and runtime jars of every module in the list."

                dependsOn(targets.map { "$it:dumpHarnessClasspath" })
            }
        }
    }
}
