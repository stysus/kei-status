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

    private val WHITESPACE = Regex("\\s+")

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
                val parts = line.split(WHITESPACE).filter { it.isNotEmpty() }
                // The flags are passed through as written; the checker's own parser validates them,
                // so there is no second grammar to keep in sync here.
                Entry(parts.first(), parts.drop(1))
            }
    }

    fun modules(file: Path): Set<String> = read(file).map { it.module }.toSet()

    /**
     * Writes the shard plan a sweep runs, and returns the shards it wrote.
     *
     * This is the only implementation of both rules: which directories are modules (above), and how
     * the list is split ([split]). `ci/discover.sh` calls it rather than repeating them in `find`
     * and `awk`, so the local `--shard` and the CI matrix cannot drift apart.
     *
     * Every shard index in `1..count` gets a file, empty when the list is shorter than the shard
     * count: the workflow tells "this shard has nothing to do" from "this shard never reported in"
     * by the file existing at all.
     */
    fun plan(
        entries: List<Entry>,
        count: Int,
        outDir: Path,
        costs: Map<String, Int> = emptyMap(),
    ): List<List<Entry>> {
        require(count > 0) { "shard count must be positive" }

        // Sorted by the line as written, in code-unit order rather than the locale's collation. The
        // shell `sort` this replaced depended on the runner's locale, so the same tree could produce
        // a different shard assignment on a different machine and silently invalidate its cache.
        val ordered = entries.sortedBy { it.line() }
        Files.createDirectories(outDir)
        writeAtomically(outDir.resolve("all.txt"), ordered.joinToString("") { it.line() + "\n" })

        val shards = split(ordered, count, costs)
        shards.forEachIndexed { offset, shard ->
            val body = shard.joinToString("") { it.line() + "\n" }
            writeAtomically(outDir.resolve("shard-${offset + 1}.txt"), body)
        }
        return shards
    }

    /** `:src:all:mangadex --lang en` — a module and the flags that narrow it. */
    private fun Entry.line(): String =
        if (args.isEmpty()) module else (listOf(module) + args).joinToString(" ")

    /**
     * Splits [entries] into [count] shards and returns shard [index] (1-based), so several jobs can
     * cover the whole list without overlapping.
     */
    fun shard(
        entries: List<Entry>,
        index: Int,
        count: Int,
        costs: Map<String, Int> = emptyMap(),
    ): List<Entry> {
        require(count > 0) { "shard count must be positive" }
        require(index in 1..count) { "shard index must be between 1 and $count" }

        return split(entries, count, costs).getOrElse(index - 1) { emptyList() }
    }

    /**
     * Distributes [entries] over [count] shards by cost, not by count.
     *
     * A module is not a unit of work. `:src:all:xcomic` declares 109 sources against one site and
     * `:src:en:aurora` declares one, yet both are one module - and every `:src:all:*` module sorts
     * to the front of the list, so splitting the list into equal slices handed all of them to the
     * first shards. Measured on the 2026-10-23 sweep, shards 1 and 2 held 10% of the modules and 46%
     * of the scenarios, and took 58 and 55 minutes against 15-24 for the other eighteen.
     *
     * [costs] is scenarios per module, which the caller reads from the store. A module it does not
     * know is assumed to be typical rather than free, so a brand-new extension does not land on
     * whichever shard happens to be emptiest and then turn out to be a hundred-source aggregate.
     *
     * Greedy longest-first packing: each module, heaviest first, goes to the lightest shard. It is
     * deterministic - equal costs are ordered by line, and an equal load goes to the lower shard -
     * and lands within a few per cent of optimal for this shape of input, without the cost of an
     * exact partition.
     */
    fun split(
        entries: List<Entry>,
        count: Int,
        costs: Map<String, Int> = emptyMap(),
    ): List<List<Entry>> {
        require(count > 0) { "shard count must be positive" }
        if (entries.isEmpty()) return List(count) { emptyList() }

        val known = costs.values.sorted()
        val fallback = if (known.isEmpty()) 1 else maxOf(1, known[known.size / 2])
        fun cost(entry: Entry) = (costs[entry.module] ?: fallback) + MODULE_OVERHEAD_MS

        val queue = entries.sortedWith(compareByDescending<Entry> { cost(it) }.thenBy { it.line() })
        val bins = List(count) { mutableListOf<Entry>() }
        val loads = IntArray(count)
        for (entry in queue) {
            var target = 0
            for (i in 1 until count) if (loads[i] < loads[target]) target = i
            bins[target] += entry
            loads[target] += cost(entry)
        }

        return bins.map { bin -> bin.sortedBy { it.line() } }
    }

    private fun Path.isHiddenDir(): Boolean = fileName.toString().startsWith(".")
}

/**
 * Fixed overhead added to every module's cost before the greedy split compares them.
 *
 * When the caller provides wall-clock times (which include compilation, class loading, and JVM
 * warm-up), the overhead is already in the number and this should be zero.  The store-based
 * fallback slightly under-counts overhead, but that is a cold-start approximation that improves
 * after one run writes a costs file; distorting the cost ratios with a hardcoded constant is worse.
 */
private const val MODULE_OVERHEAD_MS = 0
