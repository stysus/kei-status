// Records where an extension module's compiled classes live, so the JVM harness can load them
// without touching the extension build logic.
//
// Usage:
//   ./gradlew -I ../tools/harness.init.gradle.kts -PharnessModule=:src:en:aurora \
//       :src:en:aurora:dumpHarnessClasspath
//
//   # many modules in one invocation: compile in parallel, write one JSON per module
//   ./gradlew -I ../tools/harness.init.gradle.kts -PharnessModuleList=shard.txt \
//       dumpAllHarnessClasspaths
//
// Project dependencies (:core, :lib:*, :lib-multisrc:*) are not listed: they are compiled by the
// module's own compile task, and the harness picks up every compiled shared module it can find.
// Resolving AGP configurations from an init script trips variant ambiguity, so this stays simple.

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

        tasks.register("dumpHarnessClasspath") {
            group = "harness"
            description = "Writes the compiled classes directory of $projectPath as JSON."

            // The harness loads these classes, so make sure they exist before recording the path.
            dependsOn("compileDebugKotlin")

            val classesDir = layout.buildDirectory
                .dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
            val outputFile = layout.buildDirectory.file("harness-classpath.json")
            val moduleDir = projectDir.absolutePath

            doLast {
                val out = outputFile.get().asFile
                out.parentFile.mkdirs()
                out.writeText(
                    """
                    {
                      "module": "$projectPath",
                      "classesDir": "${classesDir.get().asFile.absolutePath}",
                      "moduleDir": "$moduleDir"
                    }
                    """.trimIndent() + "\n",
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
                description = "Writes the compiled classes directory of every module in the list."

                dependsOn(targets.map { "$it:dumpHarnessClasspath" })
            }
        }
    }
}
