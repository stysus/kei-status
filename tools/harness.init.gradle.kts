// Records where an extension module's compiled classes live, so the JVM harness can load them
// without touching the extension build logic.
//
// Usage:
//   ./gradlew -I ../tools/harness.init.gradle.kts -PharnessModule=:src:en:aurora \
//       :src:en:aurora:dumpHarnessClasspath
//
// Project dependencies (:core, :lib:*, :lib-multisrc:*) are not listed: they are compiled by the
// module's own compile task, and the harness picks up every compiled shared module it can find.
// Resolving AGP configurations from an init script trips variant ambiguity, so this stays simple.

if (gradle.parent == null) {
    val harnessTarget = providers.gradleProperty("harnessModule").orNull
        ?: error("Pass -PharnessModule=<path> (e.g. :src:en:aurora)")

    gradle.afterProject {
        if (path != harnessTarget) return@afterProject

        tasks.register("dumpHarnessClasspath") {
            group = "harness"
            description = "Writes the compiled classes directory of $harnessTarget as JSON."

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
                      "module": "$harnessTarget",
                      "classesDir": "${classesDir.get().asFile.absolutePath}",
                      "moduleDir": "$moduleDir"
                    }
                    """.trimIndent() + "\n",
                )
                logger.lifecycle("harness layout: $out")
            }
        }
    }
}
