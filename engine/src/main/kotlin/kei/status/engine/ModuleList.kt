package kei.status.engine

import java.nio.file.Files
import java.nio.file.Path

/**
 * The extension modules a run should check.
 *
 * Modules are discovered from the source tree rather than listed by hand: `extensions-source`
 * registers every `src/<lang>/<name>` directory as a Gradle module (`loadAllIndividualExtensions()`
 * in its `settings.gradle.kts`), and every real extension there carries a `build.gradle.kts`. That
 * file is what tells an extension from a stray directory, and it means a hand-written list would go
 * stale the moment an extension is added.
 *
 * A file may still narrow the run, for a one-off or a debugging session. An entry is
 * `<Gradle module> [extra flags]`, where the flags belong to the checker.
 */
object ModuleList {

    data class Entry(val module: String, val args: List<String> = emptyList())

    /**
     * Every module Gradle would register, sorted so a shard is reproducible.
     *
     * A directory is a module when it holds a `build.gradle.kts`, which is what makes the Gradle
     * include work; anything else in the tree (assets, scratch dirs) is not an extension.
     */
    fun discover(sourceRoot: Path): List<Entry> {
        val src = sourceRoot.resolve("src")
        if (!Files.isDirectory(src)) return emptyList()

        return Files.list(src).use { langs ->
            langs.filter { Files.isDirectory(it) && !it.isHiddenDir() }
                .sorted()
                .toList()
        }.flatMap { lang ->
            Files.list(lang).use { modules ->
                modules.filter { Files.isDirectory(it) && !it.isHiddenDir() }
                    .filter { Files.isRegularFile(it.resolve("build.gradle.kts")) }
                    .sorted()
                    .toList()
            }.map { module ->
                Entry(":src:${lang.fileName}:${module.fileName}")
            }
        }
    }

    fun read(file: Path): List<Entry> {
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.readAllLines(file)
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                // The flags are passed through as written; the checker's own parser validates them,
                // so there is no second grammar to keep in sync here.
                Entry(parts.first(), parts.drop(1))
            }
    }

    fun modules(file: Path): Set<String> = read(file).map { it.module }.toSet()

    /**
     * Splits [entries] into [count] contiguous, evenly sized shards and returns shard [index]
     * (1-based), so several jobs can cover the whole list without overlapping.
     */
    fun shard(entries: List<Entry>, index: Int, count: Int): List<Entry> {
        require(count > 0) { "shard count must be positive" }
        require(index in 1..count) { "shard index must be between 1 and $count" }

        val size = (entries.size + count - 1) / count
        val from = (index - 1) * size
        if (from >= entries.size) return emptyList()
        return entries.subList(from, minOf(from + size, entries.size))
    }

    private fun Path.isHiddenDir(): Boolean = fileName.toString().startsWith(".")
}
